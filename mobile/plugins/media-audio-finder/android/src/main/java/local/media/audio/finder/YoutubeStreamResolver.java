package local.media.audio.finder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class YoutubeStreamResolver {

    private static final String TAG = "YoutubeStreamResolver";

    private static final Pattern VIDEO_ID_RE = Pattern.compile(
        "(?:youtube\\.com/(?:[^/]+/.+/|(?:v|e(?:mbed)?|shorts)/|.*[?&]v=)|youtu\\.be/)([A-Za-z0-9_-]{11})"
    );

    /** 2026-05 時点で稼働確認済み。公式リスト取得失敗時の保険。 */
    private static final List<String> PIPED_INSTANCES_FALLBACK = Arrays.asList(
        "https://api.piped.private.coffee",
        "https://pipedapi.in.projectsegfau.lt",
        "https://api.piped.projectsegfau.lt"
    );

    private static final List<String> INVIDIOUS_INSTANCES_FALLBACK = Arrays.asList(
        "https://inv.thepixora.com",
        "https://invidious.fdn.fr",
        "https://yewtu.be"
    );

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 25000;
    private static final int INSTANCE_LIST_TIMEOUT_MS = 12000;

    private YoutubeStreamResolver() {
    }

    static String extractVideoId(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        Matcher matcher = VIDEO_ID_RE.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    static String resolveAudioUrl(String pageUrl) {
        String videoId = extractVideoId(pageUrl);
        if (videoId == null) {
            android.util.Log.w(TAG, "No video id in: " + pageUrl);
            return null;
        }

        List<String> pipedInstances = new ArrayList<>(PIPED_INSTANCES_FALLBACK);
        pipedInstances.addAll(fetchLivePipedInstances());
        List<String> invidiousInstances = new ArrayList<>(INVIDIOUS_INSTANCES_FALLBACK);
        invidiousInstances.addAll(fetchLiveInvidiousInstances());

        // Piped を先に試す（Invidious 公開 API は多くが 403/無効化されている）
        for (String instance : dedupe(pipedInstances)) {
            String audioUrl = fetchFromPiped(instance, videoId);
            if (audioUrl != null) {
                android.util.Log.i(TAG, "Resolved via Piped: " + instance);
                return audioUrl;
            }
        }
        for (String instance : dedupe(invidiousInstances)) {
            String audioUrl = fetchFromInvidious(instance, videoId);
            if (audioUrl != null) {
                android.util.Log.i(TAG, "Resolved via Invidious: " + instance);
                return audioUrl;
            }
        }
        android.util.Log.w(TAG, "All resolvers failed for videoId=" + videoId);
        return null;
    }

    private static List<String> dedupe(List<String> instances) {
        Set<String> seen = new LinkedHashSet<>();
        for (String instance : instances) {
            if (instance == null || instance.isEmpty()) {
                continue;
            }
            seen.add(instance.replaceAll("/$", ""));
        }
        return new ArrayList<>(seen);
    }

    private static List<String> fetchLivePipedInstances() {
        List<String> out = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("https://piped-instances.kavin.rocks/").openConnection();
            conn.setConnectTimeout(INSTANCE_LIST_TIMEOUT_MS);
            conn.setReadTimeout(INSTANCE_LIST_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "MediaAudioFinder/1.0");
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return out;
            }
            String body = readBody(conn);
            JSONArray arr = new JSONArray(body);
            List<JSONObject> ranked = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                ranked.add(item);
            }
            ranked.sort((a, b) -> Double.compare(
                b.optDouble("uptime_24h", 0),
                a.optDouble("uptime_24h", 0)
            ));
            for (JSONObject item : ranked) {
                String api = item.optString("api_url", item.optString("api", ""));
                if (!api.isEmpty()) {
                    out.add(api);
                }
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "fetchLivePipedInstances: " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return out;
    }

    private static List<String> fetchLiveInvidiousInstances() {
        List<String> out = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("https://api.invidious.io/instances.json?sort_by=health").openConnection();
            conn.setConnectTimeout(INSTANCE_LIST_TIMEOUT_MS);
            conn.setReadTimeout(INSTANCE_LIST_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "MediaAudioFinder/1.0");
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return out;
            }
            String body = readBody(conn);
            JSONArray arr = new JSONArray(body);
            for (int i = 0; i < arr.length(); i++) {
                JSONArray row = arr.optJSONArray(i);
                if (row == null || row.length() < 2) {
                    continue;
                }
                JSONObject meta = row.optJSONObject(1);
                if (meta == null) {
                    continue;
                }
                JSONObject monitor = meta.optJSONObject("monitor");
                double uptime = monitor != null ? monitor.optDouble("uptime", 0) : 0;
                boolean apiFlag = meta.optBoolean("api", false);
                // api:false でも試す（一覧の api フラグは古い/誤っていることが多い）
                if (!apiFlag && uptime < 95.0) {
                    continue;
                }
                String uri = meta.optString("uri", "");
                if (uri.isEmpty()) {
                    String host = row.optString(0, "");
                    if (host.isEmpty()) {
                        continue;
                    }
                    uri = host.startsWith("http") ? host : "https://" + host;
                }
                out.add(uri);
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "fetchLiveInvidiousInstances: " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return out;
    }

    private static String readBody(HttpURLConnection conn) throws Exception {
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
        }
        return body.toString();
    }

    private static String fetchFromPiped(String instance, String videoId) {
        HttpURLConnection conn = null;
        try {
            String endpoint = instance.replaceAll("/$", "") + "/streams/" + videoId;
            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36");
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                android.util.Log.w(TAG, "Piped HTTP " + code + " @ " + instance);
                return null;
            }
            JSONObject payload = new JSONObject(readBody(conn));
            return pickBestFromPiped(payload.optJSONArray("audioStreams"));
        } catch (Exception e) {
            android.util.Log.w(TAG, "Piped " + instance + ": " + e.getMessage());
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String pickBestFromPiped(JSONArray audioStreams) {
        if (audioStreams == null) {
            return null;
        }
        String bestUrl = null;
        long bestBitrate = -1;
        for (int i = 0; i < audioStreams.length(); i++) {
            JSONObject stream = audioStreams.optJSONObject(i);
            if (stream == null) {
                continue;
            }
            String url = stream.optString("url", "");
            if (url.isEmpty()) {
                continue;
            }
            long bitrate = stream.optLong("bitrate", 0);
            if (bitrate <= 0) {
                bitrate = stream.optLong("bitrateKbps", 0) * 1000L;
            }
            if (bitrate > bestBitrate) {
                bestBitrate = bitrate;
                bestUrl = url;
            }
        }
        return bestUrl;
    }

    private static String fetchFromInvidious(String instance, String videoId) {
        HttpURLConnection conn = null;
        try {
            String endpoint = instance.replaceAll("/$", "") + "/api/v1/videos/" + videoId;
            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36");
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                android.util.Log.w(TAG, "Invidious HTTP " + code + " @ " + instance);
                return null;
            }
            return pickBestAudioUrl(new JSONObject(readBody(conn)));
        } catch (Exception e) {
            android.util.Log.w(TAG, "Invidious " + instance + ": " + e.getMessage());
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String pickBestAudioUrl(JSONObject payload) {
        String bestUrl = null;
        long bestBitrate = -1;

        JSONArray sources = new JSONArray();
        appendFormats(sources, payload.optJSONArray("adaptiveFormats"));
        appendFormats(sources, payload.optJSONArray("formatStreams"));

        for (int i = 0; i < sources.length(); i++) {
            JSONObject format = sources.optJSONObject(i);
            if (format == null) {
                continue;
            }
            String type = format.optString("type", "").toLowerCase(Locale.ROOT);
            if (!type.startsWith("audio/")) {
                continue;
            }
            String url = format.optString("url", "");
            if (url.isEmpty()) {
                continue;
            }
            long bitrate = format.optLong("bitrate", 0);
            if (bitrate <= 0) {
                bitrate = format.optLong("bitrateKbps", 0) * 1000L;
            }
            if (bitrate > bestBitrate) {
                bestBitrate = bitrate;
                bestUrl = url;
            }
        }
        return bestUrl;
    }

    private static void appendFormats(JSONArray target, JSONArray source) {
        if (source == null) {
            return;
        }
        for (int i = 0; i < source.length(); i++) {
            target.put(source.optJSONObject(i));
        }
    }
}
