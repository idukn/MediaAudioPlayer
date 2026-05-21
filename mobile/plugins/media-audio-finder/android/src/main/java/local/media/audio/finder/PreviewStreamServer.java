package local.media.audio.finder;

import android.content.Context;

import fi.iki.elonen.NanoHTTPD;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

final class PreviewStreamServer extends NanoHTTPD {

    interface LibraryRootProvider {
        File getLibraryRoot();
    }

    private static final class TranscodeProfile {
        final String id;
        final String contentType;
        final List<String> ffmpegArgs;
        final EncoderCheck check;

        TranscodeProfile(String id, String contentType, List<String> ffmpegArgs, EncoderCheck check) {
            this.id = id;
            this.contentType = contentType;
            this.ffmpegArgs = ffmpegArgs;
            this.check = check;
        }
    }

    @FunctionalInterface
    private interface EncoderCheck {
        boolean isAvailable(String encoderText);
    }

    private static final List<TranscodeProfile> PROFILES = Arrays.asList(
        new TranscodeProfile(
            "mp3",
            "audio/mpeg",
            Arrays.asList("-c:a", "libmp3lame", "-q:a", "4", "-id3v2_version", "0", "-write_xing", "0", "-f", "mp3"),
            text -> text.contains("libmp3lame")
        ),
        new TranscodeProfile(
            "aac-mp4",
            "audio/mp4",
            Arrays.asList("-c:a", "aac", "-b:a", "128k", "-movflags", "frag_keyframe+empty_moov+default_base_moof", "-f", "mp4"),
            text -> text.contains(" aac ") || text.contains("aac_at")
        ),
        new TranscodeProfile(
            "opus-webm",
            "audio/webm",
            Arrays.asList("-c:a", "libopus", "-b:a", "96k", "-f", "webm"),
            text -> text.contains("libopus")
        ),
        new TranscodeProfile(
            "wav",
            "audio/wav",
            Arrays.asList("-c:a", "pcm_s16le", "-f", "wav"),
            text -> true
        )
    );

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

    private final Context context;
    private final LibraryRootProvider libraryRootProvider;
    private final Map<String, TranscodeProfile> profileCache = new HashMap<>();

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
        String uri = session.getUri();
        Map<String, List<String>> params = session.getParameters();

        if ("/stream".equals(uri)) {
            return handleStream(params);
        }
        if ("/audio".equals(uri)) {
            return handleAudio(params);
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found");
    }

    private Response handleAudio(Map<String, List<String>> params) {
        String pathParam = firstParam(params, "path");
        if (pathParam == null || pathParam.isEmpty()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing path");
        }

        try {
            File libraryRoot = libraryRootProvider.getLibraryRoot().getCanonicalFile();
            File target = new File(pathParam).getCanonicalFile();
            if (!target.getPath().startsWith(libraryRoot.getPath())) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Forbidden");
            }
            if (!target.exists() || !target.isFile()) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found");
            }

            String ext = "";
            int dot = target.getName().lastIndexOf('.');
            if (dot >= 0) {
                ext = target.getName().substring(dot).toLowerCase(Locale.ROOT);
            }
            String contentType = MIME_BY_EXT.getOrDefault(ext, "audio/mpeg");
            FileInputStream input = new FileInputStream(target);
            Response response = newFixedLengthResponse(
                Response.Status.OK,
                contentType,
                input,
                target.length()
            );
            response.addHeader("Accept-Ranges", "bytes");
            response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            return response;
        } catch (IOException e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.getMessage());
        }
    }

    private Response handleStream(Map<String, List<String>> params) {
        String url = firstParam(params, "url");
        if (url == null || url.isEmpty()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url");
        }

        String ytdlp = ExecutableResolver.resolve(context, "yt-dlp");
        String ffmpeg = ExecutableResolver.resolve(context, "ffmpeg");
        File ytdlpFile = new File(ytdlp);
        File ffmpegFile = new File(ffmpeg);
        if (!ytdlpFile.exists() || !ffmpegFile.exists()) {
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "yt-dlp または ffmpeg が見つかりません（Termux 等でインストールしてください）"
            );
        }

        try {
            TranscodeProfile profile = resolveTranscodeProfile(ffmpeg);
            return newChunkedResponse(
                Response.Status.OK,
                profile.contentType,
                new StreamPipeInputStream(ytdlp, ffmpeg, url, profile)
            );
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.getMessage());
        }
    }

    private TranscodeProfile resolveTranscodeProfile(String ffmpegPath) throws Exception {
        if (profileCache.containsKey(ffmpegPath)) {
            return profileCache.get(ffmpegPath);
        }

        ProcessBuilder builder = new ProcessBuilder(ffmpegPath, "-hide_banner", "-encoders");
        builder.redirectErrorStream(true);
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

        TranscodeProfile chosen = PROFILES.get(PROFILES.size() - 1);
        for (TranscodeProfile candidate : PROFILES) {
            if (candidate.check.isAvailable(encoderText)) {
                chosen = candidate;
                break;
            }
        }
        profileCache.put(ffmpegPath, chosen);
        return chosen;
    }

    private static String firstParam(Map<String, List<String>> params, String key) {
        List<String> values = params.get(key);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(0);
    }

    private static final class StreamPipeInputStream extends InputStream {

        private final String ytdlp;
        private final String ffmpeg;
        private final String url;
        private final TranscodeProfile profile;

        private Process ytProcess;
        private Process ffProcess;
        private InputStream ffStdout;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private volatile boolean started = false;
        private volatile Exception startError;

        StreamPipeInputStream(String ytdlp, String ffmpeg, String url, TranscodeProfile profile) {
            this.ytdlp = ytdlp;
            this.ffmpeg = ffmpeg;
            this.url = url;
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
                        url
                    );
                    ytBuilder.redirectErrorStream(true);
                    ytProcess = ytBuilder.start();

                    List<String> ffArgs = new java.util.ArrayList<>();
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
                    ffProcess = ffBuilder.start();

                    Thread pipeThread = new Thread(() -> {
                        try (InputStream ytOut = ytProcess.getInputStream();
                             OutputStream ffIn = ffProcess.getOutputStream()) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = ytOut.read(buf)) != -1) {
                                ffIn.write(buf, 0, n);
                                ffIn.flush();
                            }
                        } catch (IOException ignored) {
                            /* client disconnected */
                        } finally {
                            try {
                                ffProcess.getOutputStream().close();
                            } catch (IOException ignored) {
                            }
                        }
                    }, "preview-yt-to-ff");
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
