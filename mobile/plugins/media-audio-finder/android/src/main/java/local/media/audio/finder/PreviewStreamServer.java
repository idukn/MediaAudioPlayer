package local.media.audio.finder;

import android.content.Context;
import android.util.Log;

import fi.iki.elonen.NanoHTTPD;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.json.JSONObject;

/**
 * Mac 版と同様に yt-dlp → ffmpeg のパイプでリアルタイム試聴する。
 * WebView 向けに CORS ヘッダーを付与（Capacitor は https://localhost から 127.0.0.1 へアクセスするため）。
 */
final class PreviewStreamServer extends NanoHTTPD {

    private static final String TAG = "PreviewStream";

    interface LibraryRootProvider {
        File getLibraryRoot();
    }

    private static final class TranscodeProfile {
        final String contentType;
        final List<String> ffmpegArgs;
        final EncoderCheck check;

        TranscodeProfile(String contentType, List<String> ffmpegArgs, EncoderCheck check) {
            this.contentType = contentType;
            this.ffmpegArgs = ffmpegArgs;
            this.check = check;
        }
    }

    @FunctionalInterface
    private interface EncoderCheck {
        boolean isAvailable(String encoderText);
    }

    /**
     * WebView はチャンク fMP4 / webm を嫌うことが多いため MP3 → WAV のみ（Mac 版の WAV フォールバック相当）。
     */
    private static final List<TranscodeProfile> STREAM_PROFILES = Arrays.asList(
        new TranscodeProfile(
            "audio/mpeg",
            Arrays.asList("-c:a", "libmp3lame", "-q:a", "4", "-id3v2_version", "0", "-write_xing", "0", "-f", "mp3"),
            text -> text.contains("libmp3lame")
        ),
        new TranscodeProfile(
            "audio/wav",
            Arrays.asList("-c:a", "pcm_s16le", "-f", "wav"),
            text -> true
        )
    );

    private static final TranscodeProfile DEFAULT_STREAM_PROFILE = STREAM_PROFILES.get(0);

    private static final int PREFETCH_MIN_BYTES = 4096;
    private static final long PREFETCH_TIMEOUT_MS = 25000;

    /** WebView がネイティブ再生しづらい形式のみ ffmpeg でキャッシュ変換して配信。 */
    private static final Set<String> TRANSCODE_EXTS = new HashSet<>(Arrays.asList(
        ".flac", ".wma", ".ogg", ".opus", ".webm"
    ));

    private static final Map<String, String> MIME_BY_EXT = new HashMap<>();

    static {
        MIME_BY_EXT.put(".mp3", "audio/mpeg");
        MIME_BY_EXT.put(".m4a", "audio/mp4");
        MIME_BY_EXT.put(".aac", "audio/aac");
        MIME_BY_EXT.put(".wav", "audio/wav");
        MIME_BY_EXT.put(".ogg", "audio/ogg");
        MIME_BY_EXT.put(".opus", "audio/opus");
        MIME_BY_EXT.put(".webm", "audio/webm");
        MIME_BY_EXT.put(".wma", "audio/x-ms-wma");
        MIME_BY_EXT.put(".flac", "audio/flac");
    }

    private static final long STREAM_PREVIEW_CACHE_MAX_AGE_MS = 6L * 60 * 60 * 1000;
    private static final long STREAM_PREVIEW_BUILD_TIMEOUT_SEC = 600;

    private final Context context;
    private final LibraryRootProvider libraryRootProvider;
    private final Map<String, TranscodeProfile> profileCache = new HashMap<>();
    private final Object streamCacheLock = new Object();

    PreviewStreamServer(Context context, LibraryRootProvider libraryRootProvider) throws IOException {
        super(0);
        this.context = context.getApplicationContext();
        this.libraryRootProvider = libraryRootProvider;
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
    }

    int getBoundPort() {
        return getListeningPort();
    }

    @Override
    public Response serve(IHTTPSession session) {
        if (Method.OPTIONS.equals(session.getMethod())) {
            return withCors(newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, ""));
        }

        String uri = session.getUri();
        Map<String, List<String>> params = session.getParameters();

        if ("/stream/prepare".equals(uri)) {
            return withCors(handleStreamPrepare(session));
        }
        if ("/stream".equals(uri)) {
            return withCors(handleStream(session));
        }
        if ("/audio".equals(uri)) {
            return withCors(handleAudio(session));
        }
        return withCors(newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found"));
    }

    private Response handleAudio(IHTTPSession session) {
        String pathParam = firstParam(session.getParameters(), "path");
        if (pathParam == null || pathParam.isEmpty()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing path");
        }

        try {
            pathParam = URLDecoder.decode(pathParam, StandardCharsets.UTF_8.name());
            File target = new File(pathParam).getCanonicalFile();
            if (!isAllowedPath(target)) {
                Log.w(TAG, "handleAudio forbidden: " + target.getPath());
                return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Forbidden");
            }
            if (!target.exists() || !target.isFile()) {
                Log.w(TAG, "handleAudio not found: " + target.getPath());
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found");
            }

            String ext = extensionOf(target.getName());
            File serveTarget = target;
            String contentType = MIME_BY_EXT.getOrDefault(ext, "audio/mpeg");

            if (TRANSCODE_EXTS.contains(ext)) {
                String ffmpeg = requireFfmpeg();
                TranscodeProfile profile = resolveStreamProfile(ffmpeg);
                serveTarget = ensureTranscodedCache(target, profile);
                contentType = profile.contentType;
            }

            return serveLocalFile(session, serveTarget, contentType);
        } catch (Exception e) {
            Log.e(TAG, "handleAudio failed: " + e.getMessage());
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.getMessage());
        }
    }

    private File ensureTranscodedCache(File source, TranscodeProfile profile) throws Exception {
        File cacheDir = new File(context.getCacheDir(), "audio_transcode");
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw new IOException("Cannot create transcode cache");
        }

        String outExt = profile.contentType.contains("wav") ? ".wav" : ".mp3";
        String cacheKey = source.getCanonicalPath() + "|" + source.lastModified() + "|" + profile.contentType;
        File cached = new File(cacheDir, Integer.toHexString(cacheKey.hashCode()) + outExt);
        if (cached.isFile() && cached.length() > 512 && cached.lastModified() >= source.lastModified()) {
            return cached;
        }

        File tmp = new File(cacheDir, cached.getName() + ".part");
        transcodeFileTo(source, tmp, profile);
        if (cached.exists() && !cached.delete()) {
            Log.w(TAG, "Could not replace stale transcode cache");
        }
        if (!tmp.renameTo(cached)) {
            throw new IOException("Transcode cache rename failed");
        }
        Log.i(TAG, "Transcoded cache ready: " + cached.getName() + " (" + cached.length() + " bytes)");
        return cached;
    }

    private void transcodeFileTo(File source, File dest, TranscodeProfile profile) throws Exception {
        String ffmpeg = requireFfmpeg();
        if (dest.exists() && !dest.delete()) {
            throw new IOException("Cannot clear transcode temp file");
        }

        List<String> args = new ArrayList<>();
        args.add(ffmpeg);
        args.add("-hide_banner");
        args.add("-loglevel");
        args.add("error");
        args.add("-y");
        args.add("-i");
        args.add(source.getAbsolutePath());
        args.add("-vn");
        args.addAll(profile.ffmpegArgs);
        args.add(dest.getAbsolutePath());

        ProcessBuilder builder = new ProcessBuilder(args);
        builder.redirectErrorStream(true);
        MediaTools.applyProcessEnvironment(builder, context, ffmpeg);
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        byte[] buffer = new byte[4096];
        int read;
        InputStream in = process.getInputStream();
        while ((read = in.read(buffer)) != -1) {
            output.append(new String(buffer, 0, read));
        }
        in.close();
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("ffmpeg transcode timed out");
        }
        if (process.exitValue() != 0 || !dest.isFile() || dest.length() < 512) {
            throw new IOException(
                "ffmpeg transcode failed: " + output.toString().trim()
            );
        }
    }

    private Response serveLocalFile(IHTTPSession session, File file, String contentType) throws IOException {
        long fileLen = file.length();
        boolean headOnly = Method.HEAD.equals(session.getMethod());

        String rangeHeader = session.getHeaders().get("range");
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            String[] parts = rangeHeader.substring(6).split("-", 2);
            long start = Long.parseLong(parts[0].trim());
            long end = parts.length > 1 && !parts[1].trim().isEmpty()
                ? Long.parseLong(parts[1].trim())
                : fileLen - 1;
            if (start < 0 || start >= fileLen) {
                return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "");
            }
            if (end >= fileLen) {
                end = fileLen - 1;
            }
            long len = end - start + 1;
            if (headOnly) {
                Response head = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, contentType, "");
                head.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + fileLen);
                head.addHeader("Accept-Ranges", "bytes");
                head.addHeader("Content-Length", String.valueOf(len));
                return head;
            }
            FileInputStream input = new FileInputStream(file);
            long skipped = input.skip(start);
            if (skipped < start) {
                input.close();
                throw new IOException("Range skip failed");
            }
            Response response = newFixedLengthResponse(
                Response.Status.PARTIAL_CONTENT,
                contentType,
                input,
                len
            );
            response.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + fileLen);
            response.addHeader("Accept-Ranges", "bytes");
            response.addHeader("Content-Length", String.valueOf(len));
            response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            return response;
        }

        if (headOnly) {
            Response head = newFixedLengthResponse(Response.Status.OK, contentType, "");
            head.addHeader("Accept-Ranges", "bytes");
            head.addHeader("Content-Length", String.valueOf(fileLen));
            return head;
        }

        FileInputStream input = new FileInputStream(file);
        Response response = newFixedLengthResponse(
            Response.Status.OK,
            contentType,
            input,
            fileLen
        );
        response.addHeader("Accept-Ranges", "bytes");
        response.addHeader("Content-Length", String.valueOf(fileLen));
        response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        return response;
    }

    private Response handleStreamPrepare(IHTTPSession session) {
        String pageUrl = extractPageUrlParam(session);
        if (pageUrl == null || pageUrl.isEmpty()) {
            return jsonResponse(false, "Missing url", Response.Status.BAD_REQUEST);
        }

        String videoId = YoutubeStreamResolver.extractVideoId(pageUrl);
        if (videoId == null) {
            Log.w(TAG, "Invalid YouTube URL: " + pageUrl + " query=" + session.getQueryParameterString());
            return jsonResponse(
                false,
                "YouTube URL が不正です（動画IDを取得できません）: " + pageUrl,
                Response.Status.BAD_REQUEST
            );
        }

        try {
            Log.i(TAG, "Stream prepare videoId=" + videoId + " url=" + pageUrl);
            File cached = ensureStreamPreviewCache(pageUrl);
            String playUrl = "http://127.0.0.1:" + getBoundPort()
                + "/audio?path=" + URLEncoder.encode(cached.getAbsolutePath(), StandardCharsets.UTF_8.name())
                + "&t=" + System.currentTimeMillis();
            JSONObject json = new JSONObject();
            json.put("ok", true);
            json.put("playUrl", playUrl);
            json.put("cachePath", cached.getAbsolutePath());
            json.put("bytes", cached.length());
            Log.i(TAG, "Stream prepare ok: " + cached.length() + " bytes");
            return jsonResponse(true, json, Response.Status.OK);
        } catch (Exception e) {
            Log.e(TAG, "handleStreamPrepare failed: " + e.getMessage());
            return jsonResponse(false, e.getMessage(), Response.Status.INTERNAL_ERROR);
        }
    }

    private Response handleStream(IHTTPSession session) {
        String pageUrl = extractPageUrlParam(session);
        if (pageUrl == null || pageUrl.isEmpty()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url");
        }

        try {
            File cached = ensureStreamPreviewCache(pageUrl);
            return serveLocalFile(session, cached, "audio/mpeg");
        } catch (Exception e) {
            Log.e(TAG, "handleStream failed: " + e.getMessage());
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.getMessage());
        }
    }

    private static Response jsonResponse(boolean ok, String error, Response.Status status) {
        try {
            JSONObject json = new JSONObject();
            json.put("ok", ok);
            if (!ok) {
                json.put("error", error != null ? error : "Unknown error");
            }
            return newFixedLengthResponse(status, "application/json", json.toString());
        } catch (Exception e) {
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                error != null ? error : "error"
            );
        }
    }

    private static Response jsonResponse(boolean ok, JSONObject body, Response.Status status) {
        try {
            body.put("ok", ok);
            return newFixedLengthResponse(status, "application/json", body.toString());
        } catch (Exception e) {
            return jsonResponse(false, e.getMessage(), Response.Status.INTERNAL_ERROR);
        }
    }

    private File ensureStreamPreviewCache(String pageUrl) throws Exception {
        File cacheDir = new File(context.getCacheDir(), "stream_preview");
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw new IOException("Cannot create stream preview cache");
        }

        String cacheKey = Integer.toHexString(pageUrl.hashCode());
        File cached = new File(cacheDir, cacheKey + ".mp3");
        long ageMs = System.currentTimeMillis() - cached.lastModified();
        if (cached.isFile() && cached.length() > 8192 && ageMs < STREAM_PREVIEW_CACHE_MAX_AGE_MS) {
            Log.i(TAG, "Stream preview cache hit: " + cached.getName());
            return cached;
        }

        synchronized (streamCacheLock) {
            ageMs = System.currentTimeMillis() - cached.lastModified();
            if (cached.isFile() && cached.length() > 8192 && ageMs < STREAM_PREVIEW_CACHE_MAX_AGE_MS) {
                return cached;
            }

            File tmp = new File(cacheDir, cacheKey + ".part");
            Log.i(TAG, "Building stream preview cache: " + pageUrl);
            buildStreamPreviewToFile(pageUrl, tmp);
            if (cached.exists() && !cached.delete()) {
                Log.w(TAG, "Could not replace stale stream preview cache");
            }
            if (!tmp.renameTo(cached)) {
                throw new IOException("Stream preview cache rename failed");
            }
            Log.i(TAG, "Stream preview cache ready: " + cached.length() + " bytes");
            return cached;
        }
    }

    private void buildStreamPreviewToFile(String pageUrl, File dest) throws Exception {
        if (dest.exists() && !dest.delete()) {
            throw new IOException("Cannot clear stream preview temp file");
        }

        String ffmpeg = requireFfmpeg();
        TranscodeProfile profile = resolveStreamProfile(ffmpeg);
        String ytdlpDirect = MediaTools.resolveYtdlp(context);
        Exception lastError = null;

        String audioUrl = YoutubeStreamResolver.resolveAudioUrl(pageUrl);
        if (audioUrl != null) {
            try {
                Log.i(TAG, "Stream cache via Piped/Invidious + ffmpeg");
                ffmpegUrlToFile(ffmpeg, audioUrl, dest, profile);
                return;
            } catch (Exception e) {
                lastError = e;
                Log.w(TAG, "resolver+ffmpeg cache failed: " + e.getMessage());
            }
        }

        if (ytdlpDirect != null) {
            try {
                Log.i(TAG, "Stream cache via yt-dlp pipe (direct)");
                ytdlpPageToFile(ytdlpDirect, ffmpeg, pageUrl, dest, profile);
                return;
            } catch (Exception e) {
                lastError = e;
                Log.w(TAG, "yt-dlp direct cache failed: " + e.getMessage());
            }
        } else if (TermuxCommandRunner.isYtdlpInstalledInTermux()
            && TermuxDetector.isRunCommandPermissionDefined(context)
            && TermuxCommandRunner.hasRunCommandPermission(context)) {
            try {
                Log.i(TAG, "Stream cache via Termux RUN_COMMAND");
                TermuxCommandRunner.extractYoutubeAudioToFile(
                    context,
                    pageUrl,
                    dest,
                    STREAM_PREVIEW_BUILD_TIMEOUT_SEC
                );
                return;
            } catch (Exception e) {
                lastError = e;
                Log.w(TAG, "Termux yt-dlp failed: " + e.getMessage());
            }
        }

        boolean termuxYtdlp = TermuxCommandRunner.isYtdlpInstalledInTermux();
        throw new IOException(
            buildStreamPreviewErrorMessage(context, pageUrl, ytdlpDirect != null, termuxYtdlp, audioUrl != null, lastError)
        );
    }

    private static String buildStreamPreviewErrorMessage(
        Context context,
        String pageUrl,
        boolean ytdlpDirect,
        boolean termuxYtdlpInstalled,
        boolean hadResolverUrl,
        Exception lastError
    ) {
        String detail = lastError != null && lastError.getMessage() != null
            ? lastError.getMessage().trim()
            : "";
        String videoId = YoutubeStreamResolver.extractVideoId(pageUrl);
        String idHint = videoId != null ? ("動画ID=" + videoId + " ") : "";

        String watchUrl = videoId != null ? ("https://www.youtube.com/watch?v=" + videoId) : pageUrl;

        if (!hadResolverUrl) {
            StringBuilder msg = new StringBuilder();
            msg.append("YouTube の試聴に失敗しました（").append(idHint).append("）。\n\n");
            msg.append("【原因】2026 年 5 月現在、公開 Piped / Invidious のほぼ全インスタンスが ");
            msg.append("YouTube のボット検出強化で停止しており、外部アプリからの音声取得が困難です。\n\n");
            msg.append("【対処】\n");
            if (termuxYtdlpInstalled) {
                msg.append("・F-Droid 版 Termux + RUN_COMMAND 許可で yt-dlp 経由再生を試す:\n  ");
                msg.append(TermuxCommandRunner.getSetupHint(context)).append("\n");
            } else {
                msg.append("・F-Droid 版 Termux を導入し pkg install yt-dlp ffmpeg 後、本アプリで Termux 連携許可を出す\n");
            }
            msg.append("・直接 YouTube アプリ/ブラウザで開く: ").append(watchUrl).append("\n");
            msg.append("・ローカルにダウンロード済みの曲をプレイリストに入れて再生する");
            if (!detail.isEmpty()) {
                msg.append("\n\n詳細: ").append(detail);
            }
            return msg.toString();
        }
        return "YouTube 音声 URL は取得できましたが変換に失敗しました（" + idHint + "）。"
            + " APK 同梱 ffmpeg の再ビルド (./scripts/build-media-tools-android.sh) を試してください。"
            + (detail.isEmpty() ? "" : "\n\n詳細: " + detail);
    }

    /**
     * NanoHTTPD は url=https://...watch?v=xxx の &v= を別パラメータに分割することがある。
     * encodeURIComponent 済みの生クエリを優先して復元する。
     */
    private static String extractPageUrlParam(IHTTPSession session) {
        String query = session.getQueryParameterString();
        if (query != null && !query.isEmpty()) {
            String fromRawQuery = extractUrlParamFromRawQuery(query);
            if (fromRawQuery != null && !fromRawQuery.isEmpty()) {
                return fromRawQuery;
            }
        }
        return reconstructPageUrlFromParams(session.getParameters());
    }

    private static String extractUrlParamFromRawQuery(String query) {
        String key = "url=";
        int start = query.indexOf(key);
        if (start < 0) {
            return null;
        }
        String value = query.substring(start + key.length());
        return safeUrlDecode(value);
    }

    private static String reconstructPageUrlFromParams(Map<String, List<String>> params) {
        String base = firstParam(params, "url");
        if (base == null || base.isEmpty()) {
            return null;
        }
        String pageUrl = safeUrlDecode(base);
        if (YoutubeStreamResolver.extractVideoId(pageUrl) != null) {
            return pageUrl;
        }

        String videoId = firstParam(params, "v");
        if (videoId != null && !videoId.isEmpty()) {
            String lower = pageUrl.toLowerCase(Locale.ROOT);
            if (lower.contains("youtube.com/watch") || lower.contains("youtube.com/live/")
                || lower.contains("youtu.be/")) {
                pageUrl = pageUrl + (pageUrl.contains("?") ? "&" : "?") + "v=" + videoId;
            }
        }
        return pageUrl;
    }

    private static String safeUrlDecode(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        try {
            if (value.indexOf('%') >= 0) {
                return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
            }
            return value;
        } catch (Exception e) {
            return value;
        }
    }

    private void ffmpegUrlToFile(String ffmpeg, String audioUrl, File dest, TranscodeProfile profile)
        throws Exception {
        List<String> args = new ArrayList<>();
        args.add(ffmpeg);
        args.add("-hide_banner");
        args.add("-loglevel");
        args.add("error");
        args.add("-probesize");
        args.add("32768");
        args.add("-analyzeduration");
        args.add("500000");
        args.add("-reconnect");
        args.add("1");
        args.add("-reconnect_streamed");
        args.add("1");
        args.add("-reconnect_delay_max");
        args.add("5");
        args.add("-user_agent");
        args.add("Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
        args.add("-referer");
        args.add("https://www.youtube.com/");
        args.add("-i");
        args.add(audioUrl);
        args.add("-vn");
        args.add("-map_metadata");
        args.add("-1");
        args.addAll(profile.ffmpegArgs);
        args.add("-y");
        args.add(dest.getAbsolutePath());
        runFfmpegCommand(args, dest, STREAM_PREVIEW_BUILD_TIMEOUT_SEC);
    }

    private void ytdlpPageToFile(
        String ytdlp,
        String ffmpeg,
        String pageUrl,
        File dest,
        TranscodeProfile profile
    ) throws Exception {
        ProcessBuilder ytBuilder = new ProcessBuilder(
            ytdlp,
            "-o", "-",
            "-f", "bestaudio/best",
            "--no-playlist",
            "--no-warnings",
            "-q",
            "--user-agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36",
            pageUrl
        );
        ytBuilder.redirectErrorStream(true);
        MediaTools.applyProcessEnvironment(ytBuilder, context, ytdlp);
        Process ytProcess = ytBuilder.start();

        List<String> ffArgs = new ArrayList<>();
        ffArgs.add(ffmpeg);
        ffArgs.add("-hide_banner");
        ffArgs.add("-loglevel");
        ffArgs.add("error");
        ffArgs.add("-i");
        ffArgs.add("pipe:0");
        ffArgs.add("-vn");
        ffArgs.add("-map_metadata");
        ffArgs.add("-1");
        ffArgs.addAll(profile.ffmpegArgs);
        ffArgs.add("-y");
        ffArgs.add(dest.getAbsolutePath());

        ProcessBuilder ffBuilder = new ProcessBuilder(ffArgs);
        ffBuilder.redirectErrorStream(true);
        MediaTools.applyProcessEnvironment(ffBuilder, context, ffmpeg);
        Process ffProcess = ffBuilder.start();

        Thread pipeThread = new Thread(() -> pipeYtToFf(ytProcess, ffProcess), "preview-yt-to-ff-file");
        pipeThread.start();

        boolean ffFinished = ffProcess.waitFor(STREAM_PREVIEW_BUILD_TIMEOUT_SEC, TimeUnit.SECONDS);
        if (!ffFinished) {
            ytProcess.destroyForcibly();
            ffProcess.destroyForcibly();
            throw new IOException("ffmpeg stream cache timed out");
        }
        pipeThread.join(5000);
        if (ytProcess.isAlive()) {
            ytProcess.destroyForcibly();
        }
        if (ffProcess.exitValue() != 0 || !dest.isFile() || dest.length() < 512) {
            throw new IOException("yt-dlp/ffmpeg stream cache failed (exit " + ffProcess.exitValue() + ")");
        }
    }

    private void runFfmpegCommand(List<String> args, File dest, long timeoutSec) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(args);
        builder.redirectErrorStream(true);
        if (!args.isEmpty()) {
            MediaTools.applyProcessEnvironment(builder, context, args.get(0));
        }
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        byte[] buffer = new byte[4096];
        int read;
        InputStream in = process.getInputStream();
        while ((read = in.read(buffer)) != -1) {
            output.append(new String(buffer, 0, read));
        }
        in.close();
        if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("ffmpeg timed out");
        }
        if (process.exitValue() != 0 || !dest.isFile() || dest.length() < 512) {
            throw new IOException("ffmpeg failed: " + output.toString().trim());
        }
    }

    private static void pipeYtToFf(Process ytProcess, Process ffProcess) {
        try (InputStream ytOut = ytProcess.getInputStream();
             OutputStream ffIn = ffProcess.getOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = ytOut.read(buf)) != -1) {
                ffIn.write(buf, 0, n);
                ffIn.flush();
            }
        } catch (IOException ignored) {
        } finally {
            try {
                ffProcess.getOutputStream().close();
            } catch (IOException ignored) {
            }
        }
    }

    private TranscodeProfile resolveStreamProfile(String ffmpegPath) throws Exception {
        if (profileCache.containsKey(ffmpegPath)) {
            return profileCache.get(ffmpegPath);
        }

        try {
            ProcessBuilder builder = new ProcessBuilder(ffmpegPath, "-hide_banner", "-encoders");
            builder.redirectErrorStream(true);
            MediaTools.applyProcessEnvironment(builder, context, ffmpegPath);
            Process process = builder.start();
            StringBuilder output = new StringBuilder();
            byte[] buffer = new byte[4096];
            int read;
            InputStream in = process.getInputStream();
            while ((read = in.read(buffer)) != -1) {
                output.append(new String(buffer, 0, read));
            }
            process.waitFor();
            String encoderText = output.toString();

            for (TranscodeProfile candidate : STREAM_PROFILES) {
                if (candidate.check.isAvailable(encoderText)) {
                    profileCache.put(ffmpegPath, candidate);
                    return candidate;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "encoder probe failed: " + e.getMessage());
        }

        profileCache.put(ffmpegPath, DEFAULT_STREAM_PROFILE);
        return DEFAULT_STREAM_PROFILE;
    }

    private String requireFfmpeg() throws IOException {
        if (!MediaTools.hasWorkingFfmpeg(context)) {
            throw new IOException(
                "ffmpeg が動作しません。./scripts/build-media-tools-android.sh 後に再インストール"
                    + "（Termux の ffmpeg はアプリから直接実行できない場合があります）"
            );
        }
        return MediaTools.resolveFfmpeg(context);
    }

    private boolean isAllowedPath(File target) throws IOException {
        File libraryRoot = libraryRootProvider.getLibraryRoot().getCanonicalFile();
        File streamCache = new File(context.getCacheDir(), "stream_preview").getCanonicalFile();
        String targetPath = target.getCanonicalPath();
        String libPath = libraryRoot.getPath();
        String cachePath = streamCache.getPath();
        return targetPath.equals(libPath)
            || targetPath.startsWith(libPath + File.separator)
            || targetPath.equals(cachePath)
            || targetPath.startsWith(cachePath + File.separator);
    }

    private static Response withCors(Response response) {
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS");
        response.addHeader("Access-Control-Allow-Headers", "Range, Content-Type");
        response.addHeader("Access-Control-Expose-Headers", "Content-Length, Content-Type, Accept-Ranges");
        response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        return response;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return "";
        }
        return name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static String firstParam(Map<String, List<String>> params, String key) {
        List<String> values = params.get(key);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(0);
    }

    private static boolean isLikelyPassthroughAudioUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("googlevideo.com")
            || lower.contains("mime=audio/mp4")
            || lower.contains("mime=audio%2fmp4")
            || lower.contains("itag=140")
            || lower.contains("acodec=mp4a");
    }

    private Response liveStreamResponse(String contentType, InputStream source) {
        try {
            InputStream prefetched = prefetchStreamStart(source, PREFETCH_MIN_BYTES, PREFETCH_TIMEOUT_MS);
            return withCors(newChunkedResponse(
                Response.Status.OK,
                contentType,
                prefetched
            ));
        } catch (IOException e) {
            Log.e(TAG, "liveStreamResponse: " + e.getMessage());
            closeQuietly(source);
            return withCors(newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                e.getMessage() != null ? e.getMessage() : "Preview stream failed"
            ));
        }
    }

    private Response liveStreamResponse(TranscodeProfile profile, InputStream source) {
        return liveStreamResponse(profile.contentType, source);
    }

    private static final class HttpUrlInputStream extends InputStream {

        private final HttpURLConnection connection;
        private final InputStream body;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        HttpUrlInputStream(String audioUrl) throws IOException {
            connection = (HttpURLConnection) new URL(audioUrl).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36");
            connection.setRequestProperty("Accept", "*/*");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                connection.disconnect();
                throw new IOException("HTTP " + code + " for audio URL");
            }
            body = new BufferedInputStream(connection.getInputStream());
        }

        @Override
        public int read() throws IOException {
            return body.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int len) throws IOException {
            return body.read(buffer, offset, len);
        }

        @Override
        public void close() throws IOException {
            if (closed.compareAndSet(false, true)) {
                body.close();
                connection.disconnect();
            }
            super.close();
        }
    }

    private static InputStream prefetchStreamStart(InputStream source, int minBytes, long timeoutMs)
        throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (head.size() < minBytes && System.currentTimeMillis() < deadline) {
            int n = source.read(buf);
            if (n < 0) {
                break;
            }
            if (n > 0) {
                head.write(buf, 0, n);
            }
        }
        if (head.size() < 512) {
            closeQuietly(source);
            throw new IOException(
                "試聴用の音声データを取得できませんでした。"
                    + " ネット接続・Termux(yt-dlp/ffmpeg)・再ビルドを確認してください。"
            );
        }
        Log.i(TAG, "Prefetched " + head.size() + " bytes for WebView");
        return new SequenceInputStream(new ByteArrayInputStream(head.toByteArray()), source);
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) {
            return;
        }
        try {
            in.close();
        } catch (IOException ignored) {
        }
    }

    private abstract static class FfmpegPipeInputStream extends InputStream {

        private final Context context;
        private final String ffmpeg;
        private final TranscodeProfile profile;

        private Process ffProcess;
        private InputStream ffStdout;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private volatile boolean started = false;
        private volatile Exception startError;

        FfmpegPipeInputStream(Context context, String ffmpeg, TranscodeProfile profile) {
            this.context = context.getApplicationContext();
            this.ffmpeg = ffmpeg;
            this.profile = profile;
        }

        protected abstract List<String> buildInputArgs();

        private void ensureStarted() throws IOException {
            if (started) {
                if (startError != null) {
                    throw new IOException(startError.getMessage());
                }
                return;
            }
            synchronized (this) {
                if (started) {
                    if (startError != null) {
                        throw new IOException(startError.getMessage());
                    }
                    return;
                }
                started = true;
                try {
                    List<String> ffArgs = new ArrayList<>();
                    ffArgs.add(ffmpeg);
                    ffArgs.add("-hide_banner");
                    ffArgs.add("-loglevel");
                    ffArgs.add("error");
                    ffArgs.add("-fflags");
                    ffArgs.add("+nobuffer");
                    ffArgs.addAll(buildInputArgs());
                    ffArgs.add("-vn");
                    ffArgs.add("-map_metadata");
                    ffArgs.add("-1");
                    ffArgs.addAll(profile.ffmpegArgs);
                    ffArgs.add("-y");
                    ffArgs.add("pipe:1");

                    ProcessBuilder ffBuilder = new ProcessBuilder(ffArgs);
                    ffBuilder.redirectErrorStream(true);
                    MediaTools.applyProcessEnvironment(ffBuilder, context, ffmpeg);
                    ffProcess = ffBuilder.start();
                    ffStdout = new BufferedInputStream(ffProcess.getInputStream());
                } catch (Exception e) {
                    startError = e;
                    cleanup();
                    throw new IOException(e.getMessage());
                }
            }
        }

        @Override
        public int read() throws IOException {
            ensureStarted();
            return ffStdout.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int len) throws IOException {
            ensureStarted();
            return ffStdout.read(buffer, offset, len);
        }

        @Override
        public void close() throws IOException {
            if (closed.compareAndSet(false, true)) {
                cleanup();
            }
            super.close();
        }

        private void cleanup() {
            if (ffProcess != null && ffProcess.isAlive()) {
                ffProcess.destroyForcibly();
            }
        }
    }

    private static final class FfmpegUrlInputStream extends FfmpegPipeInputStream {

        private final String audioUrl;

        FfmpegUrlInputStream(Context context, String ffmpeg, String audioUrl, TranscodeProfile profile) {
            super(context, ffmpeg, profile);
            this.audioUrl = audioUrl;
        }

        @Override
        protected List<String> buildInputArgs() {
            return Arrays.asList(
                "-probesize", "32768",
                "-analyzeduration", "500000",
                "-reconnect", "1",
                "-reconnect_streamed", "1",
                "-reconnect_delay_max", "5",
                "-i", audioUrl
            );
        }
    }

    private static final class FfmpegFileInputStream extends FfmpegPipeInputStream {

        private final String filePath;

        FfmpegFileInputStream(Context context, String ffmpeg, String filePath, TranscodeProfile profile) {
            super(context, ffmpeg, profile);
            this.filePath = filePath;
        }

        @Override
        protected List<String> buildInputArgs() {
            return Arrays.asList("-i", filePath);
        }
    }

    private static final class YtdlpStreamInputStream extends InputStream {

        private final Context context;
        private final String ytdlp;
        private final String ffmpeg;
        private final String pageUrl;
        private final TranscodeProfile profile;

        private Process ytProcess;
        private Process ffProcess;
        private InputStream ffStdout;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private volatile boolean started = false;
        private volatile Exception startError;

        YtdlpStreamInputStream(
            Context context,
            String ytdlp,
            String ffmpeg,
            String pageUrl,
            TranscodeProfile profile
        ) {
            this.context = context.getApplicationContext();
            this.ytdlp = ytdlp;
            this.ffmpeg = ffmpeg;
            this.pageUrl = pageUrl;
            this.profile = profile;
        }

        private void ensureStarted() throws IOException {
            if (started) {
                if (startError != null) {
                    throw new IOException(startError.getMessage());
                }
                return;
            }
            synchronized (this) {
                if (started) {
                    if (startError != null) {
                        throw new IOException(startError.getMessage());
                    }
                    return;
                }
                started = true;
                try {
                    ProcessBuilder ytBuilder = new ProcessBuilder(
                        ytdlp,
                        "-o", "-",
                        "-f", "bestaudio/best",
                        "--no-playlist",
                        "--no-warnings",
                        "-q",
                        "--user-agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36",
                        pageUrl
                    );
                    ytBuilder.redirectErrorStream(true);
                    MediaTools.applyProcessEnvironment(ytBuilder, context, ytdlp);
                    ytProcess = ytBuilder.start();

                    List<String> ffArgs = new ArrayList<>();
                    ffArgs.add(ffmpeg);
                    ffArgs.add("-hide_banner");
                    ffArgs.add("-loglevel");
                    ffArgs.add("error");
                    ffArgs.add("-fflags");
                    ffArgs.add("+nobuffer");
                    ffArgs.add("-i");
                    ffArgs.add("pipe:0");
                    ffArgs.add("-vn");
                    ffArgs.add("-map_metadata");
                    ffArgs.add("-1");
                    ffArgs.addAll(profile.ffmpegArgs);
                    ffArgs.add("-y");
                    ffArgs.add("pipe:1");
                    ProcessBuilder ffBuilder = new ProcessBuilder(ffArgs);
                    ffBuilder.redirectErrorStream(true);
                    MediaTools.applyProcessEnvironment(ffBuilder, context, ffmpeg);
                    ffProcess = ffBuilder.start();

                    final Process yt = ytProcess;
                    final Process ff = ffProcess;
                    Thread pipeThread = new Thread(() -> pipeYtToFf(yt, ff), "preview-yt-to-ff");
                    pipeThread.setDaemon(true);
                    pipeThread.start();

                    ffStdout = new BufferedInputStream(ffProcess.getInputStream());
                } catch (Exception e) {
                    startError = e;
                    cleanup();
                    throw new IOException(e.getMessage());
                }
            }
        }

        @Override
        public int read() throws IOException {
            ensureStarted();
            return ffStdout.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int len) throws IOException {
            ensureStarted();
            return ffStdout.read(buffer, offset, len);
        }

        @Override
        public void close() throws IOException {
            if (closed.compareAndSet(false, true)) {
                cleanup();
            }
            super.close();
        }

        private void cleanup() {
            if (ytProcess != null && ytProcess.isAlive()) {
                ytProcess.destroyForcibly();
            }
            if (ffProcess != null && ffProcess.isAlive()) {
                ffProcess.destroyForcibly();
            }
        }
    }
}
