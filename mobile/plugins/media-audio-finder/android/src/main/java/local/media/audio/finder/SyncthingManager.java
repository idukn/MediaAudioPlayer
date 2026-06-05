package local.media.audio.finder;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SyncthingManager {

    static final String FOLDER_ID = "yt-audio-app-sync";
    private static final Pattern DEVICE_ID_GROUPED_RE =
        Pattern.compile("^[A-Z2-7]{7}(-[A-Z2-7]{7}){6,7}$");

    private final Context context;
    private final File configDir;

    private Process process;
    private String apiKey;
    private static final String DEFAULT_API_HOST = "127.0.0.1";
    private static final String DEFAULT_API_PORT = "8384";
    private String baseUrl = "http://" + DEFAULT_API_HOST + ":" + DEFAULT_API_PORT;
    private boolean shouldRun;
    private boolean starting;
    private String lastStartError = "";
    private final Object readyLock = new Object();
    private boolean readyInProgress;

    SyncthingManager(Context context) {
        this.context = context.getApplicationContext();
        File base = context.getFilesDir();
        this.configDir = new File(base, "syncthing_config");
    }

    private File getBinaryPath() {
        ApplicationInfo info = context.getApplicationInfo();
        if (info.nativeLibraryDir != null) {
            File so = new File(info.nativeLibraryDir, "libsyncthing.so");
            if (so.isFile()) {
                return so;
            }
        }
        return null;
    }

    static String sanitizeDeviceIDInput(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim()
            .replaceAll("[\\u2010-\\u2015\\u2212\\uFE58\\uFE63\\uFF0D]", "-")
            .replaceAll("\\s+", "")
            .toUpperCase(Locale.ROOT);
    }

    static String normalizeDeviceID(String raw) {
        String trimmed = sanitizeDeviceIDInput(raw);
        if (trimmed.isEmpty()) {
            return null;
        }
        if (DEVICE_ID_GROUPED_RE.matcher(trimmed).matches()) {
            return trimmed;
        }
        String compact = trimmed.replace("-", "");
        if (!compact.matches("^[A-Z2-7]+$")) {
            return null;
        }
        if (compact.length() != 52 && compact.length() != 56) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < compact.length(); i += 7) {
            if (sb.length() > 0) {
                sb.append('-');
            }
            sb.append(compact, i, Math.min(i + 7, compact.length()));
        }
        return sb.toString();
    }

    private String resolveDeviceID(String rawDeviceID) throws Exception {
        String prepped = sanitizeDeviceIDInput(rawDeviceID);
        if (prepped.isEmpty()) {
            return null;
        }
        String local = normalizeDeviceID(prepped);
        try {
            String encoded = java.net.URLEncoder.encode(prepped, "UTF-8");
            JSONObject res = apiRequest("GET", "/rest/svc/deviceid?id=" + encoded, null);
            if (!res.has("error") && res.has("id")) {
                String id = res.optString("id", "").trim();
                if (!id.isEmpty()) {
                    return id;
                }
            }
        } catch (Exception e) {
            android.util.Log.w("Syncthing", "deviceid API fallback: " + e.getMessage());
        }
        return local;
    }

    synchronized void startBackground() {
        new Thread(() -> {
            try {
                start();
            } catch (Exception e) {
                android.util.Log.e("Syncthing", "Background start failed: " + e.getMessage());
            }
        }, "syncthing-start").start();
    }

    private boolean attachToExistingDaemon() {
        if (!configDir.exists()) {
            return false;
        }
        readConfig();
        if (apiKey == null) {
            return false;
        }
        try {
            apiRequest("GET", "/rest/system/status", null);
            android.util.Log.i("Syncthing", "Attached to running daemon at " + baseUrl);
            shouldRun = true;
            return true;
        } catch (Exception e) {
            android.util.Log.w("Syncthing", "Existing daemon not reachable: " + e.getMessage());
            return false;
        }
    }

    private void ensureReady() throws Exception {
        synchronized (readyLock) {
            if (readyInProgress) {
                while (readyInProgress) {
                    readyLock.wait(500);
                }
                if (apiKey != null) {
                    return;
                }
            }
            readyInProgress = true;
        }
        try {
            if (attachToExistingDaemon()) {
                return;
            }
            start();
            waitForApi(120);
        } finally {
            synchronized (readyLock) {
                readyInProgress = false;
                readyLock.notifyAll();
            }
        }
    }

    synchronized void start() throws Exception {
        if (attachToExistingDaemon()) {
            return;
        }
        if (process != null || starting) {
            if (apiKey != null) {
                return;
            }
            long waitedMs = 0;
            while (starting && waitedMs < 60000) {
                Thread.sleep(250);
                waitedMs += 250;
                if (attachToExistingDaemon()) {
                    return;
                }
            }
            if (apiKey != null) {
                return;
            }
        }
        shouldRun = true;
        starting = true;
        try {
            ensureBinary();
            if (!configDir.exists() && !configDir.mkdirs()) {
                throw new Exception("Syncthing config dir を作成できませんでした");
            }
            ensureConfigExists();
            if (attachToExistingDaemon()) {
                return;
            }

            File binary = getBinaryPath();
            if (binary == null) {
                throw new Exception("Syncthing バイナリ (libsyncthing.so) が見つかりません");
            }

            List<String> command = syncthingCommand(
                "--no-browser",
                "--no-restart",
                "--home=" + configDir.getAbsolutePath()
            );
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            builder.directory(configDir);
            applySyncthingEnvironment(builder);
            process = builder.start();

            Thread.sleep(1500);
            if (!process.isAlive()) {
                int exitCode = process.exitValue();
                throw new Exception(
                    "Syncthing が起動できませんでした (exit=" + exitCode + "). "
                        + "scripts/build-syncthing-android.sh を実行して APK を再ビルドしてください。"
                        + (lastStartError.isEmpty() ? "" : " " + lastStartError)
                );
            }

            Thread logThread = new Thread(() -> drainProcessOutput(process), "syncthing-log");
            logThread.setDaemon(true);
            logThread.start();

            new Thread(() -> {
                try {
                    int code = process.waitFor();
                    android.util.Log.i("Syncthing", "Daemon exited: " + code);
                } catch (InterruptedException ignored) {
                }
                process = null;
                if (shouldRun) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignored) {
                    }
                    try {
                        start();
                    } catch (Exception e) {
                        android.util.Log.e("Syncthing", "Restart failed: " + e.getMessage());
                    }
                }
            }, "syncthing-wait").start();

            waitForApi();
        } finally {
            starting = false;
        }
    }

    synchronized void stop() {
        shouldRun = false;
        if (process != null) {
            process.destroy();
            process = null;
        }
    }

    void bootstrap(String saveDir, SyncListener listener) {
        new Thread(() -> {
            try {
                if (listener != null) {
                    listener.onSyncEvent(event("dir", "syncing", true));
                }
                ensureReady();
                if (listener != null) {
                    JSONObject readyPayload = event("dir", "idle", true);
                    readyPayload.put("myID", readLocalDeviceIdFromConfig());
                    listener.onSyncEvent(readyPayload);
                }
                ensureFolder(saveDir);
                waitForApi(120);
                JSONObject result = runStartupSync();
                if (listener != null) {
                    JSONObject payload = event("dir", "idle", true);
                    payload.put("completed", result.optBoolean("completed", false));
                    payload.put("state", result.optString("state", "unknown"));
                    payload.put("needBytes", result.optLong("needBytes", 0));
                    listener.onSyncEvent(payload);
                }
            } catch (Exception e) {
                android.util.Log.e("Syncthing", "Bootstrap failed: " + e.getMessage());
                if (listener != null) {
                    try {
                        JSONObject payload = event("dir", "error", true);
                        payload.put("error", e.getMessage());
                        listener.onSyncEvent(payload);
                    } catch (Exception ignored) {
                    }
                }
            }
        }, "syncthing-bootstrap").start();
    }

    JSONObject getInfo(String libraryPath) throws Exception {
        String fallbackId = readLocalDeviceIdFromConfig();

        try {
            ensureBinary();
        } catch (Exception e) {
            return buildErrorInfo(libraryPath, e.getMessage(), fallbackId, false);
        }

        try {
            ensureReady();
        } catch (Exception e) {
            if (!fallbackId.isEmpty()) {
                return buildPartialInfo(libraryPath, fallbackId, e.getMessage(), true);
            }
            return buildErrorInfo(libraryPath, e.getMessage(), fallbackId, starting || readyInProgress);
        }

        try {
            return buildInfoFromApi(libraryPath, fallbackId);
        } catch (Exception e) {
            if (!fallbackId.isEmpty()) {
                return buildPartialInfo(libraryPath, fallbackId, e.getMessage(), false);
            }
            throw e;
        }
    }

    private JSONObject buildErrorInfo(String libraryPath, String error, String myID, boolean startingFlag) throws Exception {
        JSONObject err = new JSONObject();
        err.put("ok", false);
        err.put("error", error);
        err.put("starting", startingFlag);
        err.put("libraryPath", libraryPath);
        if (!myID.isEmpty()) {
            err.put("myID", myID);
        }
        return err;
    }

    private JSONObject buildPartialInfo(String libraryPath, String myID, String warning, boolean startingFlag) throws Exception {
        JSONObject info = new JSONObject();
        info.put("ok", true);
        info.put("myID", myID);
        info.put("starting", startingFlag);
        info.put("folderId", FOLDER_ID);
        info.put("folderPath", libraryPath);
        info.put("folderState", startingFlag ? "starting" : "idle");
        info.put("globalBytes", 0);
        info.put("needBytes", 0);
        info.put("devices", new JSONArray());
        if (warning != null && !warning.isEmpty()) {
            info.put("warning", warning);
        }
        return info;
    }

    private JSONObject buildInfoFromApi(String libraryPath, String fallbackId) throws Exception {
        JSONObject status = apiRequest("GET", "/rest/system/status", null);
        JSONObject config = apiRequest("GET", "/rest/system/config", null);

        JSONObject connections = new JSONObject();
        try {
            connections = apiRequest("GET", "/rest/system/connections", null);
        } catch (Exception e) {
            android.util.Log.w("Syncthing", "connections API skipped: " + e.getMessage());
        }

        JSONObject folderStatus = null;
        try {
            folderStatus = apiRequest(
                "GET",
                "/rest/db/status?folder=" + java.net.URLEncoder.encode(FOLDER_ID, "UTF-8"),
                null
            );
        } catch (Exception ignored) {
        }

        String myID = status.optString("myID", "");
        if (myID.isEmpty()) {
            myID = status.optString("deviceID", "");
        }
        if (myID.isEmpty()) {
            myID = fallbackId;
        }
        if (myID.isEmpty()) {
            myID = readLocalDeviceIdFromConfig();
        }
        String formattedMyId = normalizeDeviceID(myID);
        if (formattedMyId != null) {
            myID = formattedMyId;
        }

        JSONArray folders = config.optJSONArray("folders");
        JSONObject folder = null;
        if (folders != null) {
            for (int i = 0; i < folders.length(); i++) {
                JSONObject entry = folders.getJSONObject(i);
                if (FOLDER_ID.equals(entry.optString("id"))) {
                    folder = entry;
                    break;
                }
            }
        }

        JSONArray devices = config.optJSONArray("devices");
        JSONArray remoteDevices = new JSONArray();
        JSONObject connMap = connections.optJSONObject("connections");
        if (devices != null) {
            for (int i = 0; i < devices.length(); i++) {
                JSONObject device = devices.getJSONObject(i);
                String deviceID = device.optString("deviceID", device.optString("id", ""));
                if (deviceID.isEmpty() || deviceID.equals(myID)) {
                    continue;
                }
                JSONObject item = new JSONObject();
                item.put("deviceID", deviceID);
                String name = device.optString("name", "");
                if (name.isEmpty() && deviceID.length() >= 7) {
                    name = deviceID.substring(0, 7);
                }
                item.put("name", name);
                boolean connected = false;
                if (connMap != null && connMap.has(deviceID)) {
                    connected = connMap.optJSONObject(deviceID).optBoolean("connected", false);
                }
                item.put("connected", connected);
                remoteDevices.put(item);
            }
        }

        JSONObject info = new JSONObject();
        info.put("ok", true);
        info.put("myID", myID);
        info.put("folderId", FOLDER_ID);
        info.put("folderPath", folder != null ? folder.optString("path", libraryPath) : libraryPath);
        info.put(
            "folderState",
            folderStatus != null
                ? folderStatus.optString("state", folder != null ? "idle" : "missing")
                : (folder != null ? "idle" : "missing")
        );
        info.put("globalBytes", folderStatus != null ? folderStatus.optLong("globalBytes", 0) : 0);
        info.put("needBytes", folderStatus != null ? folderStatus.optLong("needBytes", 0) : 0);
        info.put("devices", remoteDevices);
        return info;
    }

    private String readLocalDeviceIdFromConfig() {
        File configPath = new File(configDir, "config.xml");
        if (!configPath.exists()) {
            return "";
        }
        try {
            String xml = readFile(configPath);
            Matcher matcher = Pattern.compile(
                "<device\\s+id=\"([^\"]+)\"",
                Pattern.CASE_INSENSITIVE
            ).matcher(xml);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        } catch (Exception e) {
            android.util.Log.w("Syncthing", "readLocalDeviceIdFromConfig failed: " + e.getMessage());
        }
        return "";
    }

    JSONObject addDevice(String rawDeviceID) throws Exception {
        ensureReady();
        String deviceID = resolveDeviceID(rawDeviceID);
        if (deviceID == null) {
            throw new Exception("Invalid device ID format");
        }

        JSONObject config = apiRequest("GET", "/rest/system/config", null);
        JSONArray devices = config.getJSONArray("devices");
        boolean deviceExists = false;
        for (int i = 0; i < devices.length(); i++) {
            if (deviceID.equals(devices.getJSONObject(i).optString("deviceID"))) {
                deviceExists = true;
                break;
            }
        }

        boolean needsRestart = false;
        if (!deviceExists) {
            JSONObject device = new JSONObject();
            device.put("deviceID", deviceID);
            device.put("name", "Remote Device (" + deviceID.substring(0, 7) + ")");
            device.put("addresses", new JSONArray().put("dynamic"));
            device.put("compression", "metadata");
            device.put("introducer", false);
            device.put("paused", false);
            devices.put(device);
            needsRestart = true;
        }

        JSONArray folders = config.getJSONArray("folders");
        for (int i = 0; i < folders.length(); i++) {
            JSONObject folder = folders.getJSONObject(i);
            if (!FOLDER_ID.equals(folder.optString("id"))) {
                continue;
            }
            JSONArray folderDevices = folder.optJSONArray("devices");
            if (folderDevices == null) {
                folderDevices = new JSONArray();
                folder.put("devices", folderDevices);
            }
            boolean shared = false;
            for (int j = 0; j < folderDevices.length(); j++) {
                if (deviceID.equals(folderDevices.getJSONObject(j).optString("deviceID"))) {
                    shared = true;
                    break;
                }
            }
            if (!shared) {
                JSONObject share = new JSONObject();
                share.put("deviceID", deviceID);
                folderDevices.put(share);
                needsRestart = true;
            }
            break;
        }

        if (needsRestart) {
            apiRequest("POST", "/rest/system/config", config);
            apiRequest("POST", "/rest/system/restart", null);
            waitForApi();
        }

        JSONObject result = new JSONObject();
        result.put("success", true);
        return result;
    }

    private JSONObject event(String type, String phase, boolean startup) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("type", type);
        payload.put("phase", phase);
        payload.put("startup", startup);
        return payload;
    }

    interface SyncListener {
        void onSyncEvent(JSONObject payload);
    }

    private void ensureBinary() throws Exception {
        File binary = getBinaryPath();
        if (binary == null || !binary.exists()) {
            throw new Exception(
                "APK に libsyncthing.so (arm64) がありません。"
                    + " ./scripts/build-syncthing-android.sh のあと ./build_and_install_android_app.sh で再インストールしてください。"
                    + " (x86 エミュレータは未対応)"
            );
        }
        if (binary.length() < 1_000_000) {
            throw new Exception("Syncthing バイナリが不完全です (size=" + binary.length() + ")");
        }
        if (!verifyBinaryRunnable(binary)) {
            android.util.Log.w(
                "Syncthing",
                "Version probe failed (will try start anyway): "
                    + (lastStartError.isEmpty() ? "unknown" : lastStartError)
            );
        }
        android.util.Log.i("Syncthing", "Using binary at " + binary.getAbsolutePath()
            + " (" + binary.length() + " bytes)");
    }

    private List<String> syncthingCommand(String... args) {
        File binary = getBinaryPath();
        List<String> command = new ArrayList<>();
        if (binary != null) {
            command.add(binary.getAbsolutePath());
        }
        if (args != null) {
            for (String arg : args) {
                command.add(arg);
            }
        }
        return command;
    }

    private void applySyncthingEnvironment(ProcessBuilder builder) {
        if (builder == null) {
            return;
        }
        String binary = getBinaryPath() != null ? getBinaryPath().getAbsolutePath() : "";
        MediaTools.applyProcessEnvironment(builder, context, binary);
        Map<String, String> env = builder.environment();
        env.put("HOME", configDir.getAbsolutePath());
        env.put("TMPDIR", context.getCacheDir().getAbsolutePath());
        env.put("STHOMEDIR", configDir.getAbsolutePath());
        env.put("STGUIADDRESS", DEFAULT_API_HOST + ":" + DEFAULT_API_PORT);
        env.put("STNOUPGRADE", "true");
    }

    private void ensureConfigExists() throws Exception {
        File configPath = new File(configDir, "config.xml");
        if (configPath.isFile() && configPath.length() > 0) {
            return;
        }
        if (!configDir.exists() && !configDir.mkdirs()) {
            throw new Exception("Syncthing config dir を作成できませんでした");
        }
        android.util.Log.i("Syncthing", "Generating initial config.xml ...");
        List<String> command = syncthingCommand(
            "--home=" + configDir.getAbsolutePath(),
            "generate",
            "--no-default-folder"
        );
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.directory(configDir);
        applySyncthingEnvironment(builder);
        Process generate = builder.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(generate.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        boolean finished = generate.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            generate.destroyForcibly();
            throw new Exception("syncthing generate timed out");
        }
        if (generate.exitValue() != 0) {
            throw new Exception("syncthing generate failed: " + output.toString().trim());
        }
        if (!configPath.isFile()) {
            throw new Exception("syncthing generate did not create config.xml");
        }
        readConfig();
    }

    private String normalizeGuiBaseUrl(String address) {
        String port = DEFAULT_API_PORT;
        if (address != null && !address.isEmpty() && !"dynamic".equals(address)) {
            int colon = address.lastIndexOf(':');
            if (colon > 0 && colon < address.length() - 1) {
                port = address.substring(colon + 1);
            }
        }
        return "http://" + DEFAULT_API_HOST + ":" + port;
    }

    private boolean verifyBinaryRunnable(File binary) {
        for (String versionFlag : new String[]{"-version", "--version"}) {
            if (probeSyncthingVersion(binary, versionFlag)) {
                return true;
            }
        }
        return false;
    }

    private boolean probeSyncthingVersion(File binary, String versionFlag) {
        Process probe = null;
        try {
            List<String> command = syncthingCommand(versionFlag);
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            applySyncthingEnvironment(builder);
            probe = builder.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(probe.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                    if (output.length() > 4096) {
                        break;
                    }
                }
            }
            boolean finished = probe.waitFor(12, TimeUnit.SECONDS);
            if (!finished) {
                probe.destroyForcibly();
                lastStartError = "syncthing " + versionFlag + " timed out";
                return false;
            }
            int exit = probe.exitValue();
            String text = output.toString().trim();
            String lower = text.toLowerCase(Locale.ROOT);
            if (lower.contains("cannot link executable") || lower.contains("unsupported verneed")) {
                lastStartError = text;
                return false;
            }
            boolean looksValid = lower.contains("syncthing") || lower.contains("version");
            if (!looksValid) {
                lastStartError = "syncthing " + versionFlag + " exit=" + exit + " out=" + text;
                return false;
            }
            if (exit != 0) {
                android.util.Log.w("Syncthing", "version probe exit=" + exit + " but output ok: " + text);
            }
            return true;
        } catch (Exception e) {
            lastStartError = e.getMessage();
            return false;
        } finally {
            if (probe != null) {
                probe.destroy();
            }
        }
    }

    private void waitForApi() throws Exception {
        waitForApi(40);
    }

    private void waitForApi(int maxAttempts) throws Exception {
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            readConfig();
            if (apiKey != null) {
                try {
                    apiRequest("GET", "/rest/system/status", null);
                    return;
                } catch (Exception ignored) {
                }
            }
            if (!readLocalDeviceIdFromConfig().isEmpty() && attempt > 10) {
                android.util.Log.d("Syncthing", "config.xml ready, waiting for API...");
            }
            Thread.sleep(500);
        }
        String deviceId = readLocalDeviceIdFromConfig();
        if (!deviceId.isEmpty()) {
            throw new Exception("Syncthing API did not become ready in time (device ID in config: " + deviceId + ")");
        }
        throw new Exception("Syncthing API did not become ready in time");
    }

    private void readConfig() {
        File configPath = new File(configDir, "config.xml");
        if (!configPath.exists()) {
            return;
        }
        try {
            String xml = readFile(configPath);
            Matcher guiBlock = Pattern.compile("<gui[\\s\\S]*?</gui>", Pattern.CASE_INSENSITIVE).matcher(xml);
            String source = guiBlock.find() ? guiBlock.group() : xml;
            Matcher apiKeyMatch = Pattern.compile("<apikey>([^<]+)</apikey>", Pattern.CASE_INSENSITIVE).matcher(source);
            Matcher addressMatch = Pattern.compile("<address>([^<]+)</address>", Pattern.CASE_INSENSITIVE).matcher(source);
            if (apiKeyMatch.find()) {
                apiKey = apiKeyMatch.group(1).trim();
            }
            String addr = addressMatch.find() ? addressMatch.group(1).trim() : "";
            baseUrl = normalizeGuiBaseUrl(addr);
            android.util.Log.d("Syncthing", "API base URL: " + baseUrl);
        } catch (Exception e) {
            android.util.Log.w("Syncthing", "readConfig failed: " + e.getMessage());
        }
    }

    private String readFile(File file) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private JSONObject apiRequest(String method, String endpoint, JSONObject body) throws Exception {
        if (apiKey == null) {
            throw new Exception("API Key not loaded");
        }
        Exception lastError = null;
        String[] candidates = new String[] {
            baseUrl,
            "http://" + DEFAULT_API_HOST + ":" + DEFAULT_API_PORT,
        };
        for (String candidate : candidates) {
            if (candidate == null || candidate.isEmpty()) {
                continue;
            }
            try {
                return apiRequestOnBase(candidate, method, endpoint, body);
            } catch (Exception e) {
                lastError = e;
                android.util.Log.w("Syncthing", "API via " + candidate + " failed: " + e.getMessage());
            }
        }
        if (lastError != null) {
            throw new Exception(
                "Syncthing API に接続できません ("
                    + DEFAULT_API_HOST + ":" + DEFAULT_API_PORT
                    + "). デーモンが起動しているか確認してください。 "
                    + lastError.getMessage()
            );
        }
        throw new Exception("Syncthing API に接続できません");
    }

    private JSONObject apiRequestOnBase(String apiBase, String method, String endpoint, JSONObject body)
        throws Exception {
        URL url = new URL(apiBase + endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("X-API-Key", apiKey);
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(bytes);
            }
        }
        int code = conn.getResponseCode();
        InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String responseText = "";
        if (stream != null) {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            responseText = sb.toString();
        }
        conn.disconnect();
        if (code >= 400) {
            throw new Exception("Syncthing API " + method + " " + endpoint + " failed: " + responseText);
        }
        if (responseText.isEmpty()) {
            return new JSONObject();
        }
        return new JSONObject(responseText);
    }

    private void ensureFolder(String saveDir) throws Exception {
        JSONObject config = apiRequest("GET", "/rest/system/config", null);
        JSONArray folders = config.getJSONArray("folders");
        boolean folderExists = false;
        JSONObject folder = null;
        for (int i = 0; i < folders.length(); i++) {
            JSONObject entry = folders.getJSONObject(i);
            if (FOLDER_ID.equals(entry.optString("id"))) {
                folderExists = true;
                folder = entry;
                break;
            }
        }

        JSONArray devices = config.getJSONArray("devices");
        if (!folderExists) {
            JSONObject newFolder = new JSONObject();
            newFolder.put("id", FOLDER_ID);
            newFolder.put("label", "YT Audio Sync");
            newFolder.put("path", saveDir);
            newFolder.put("type", "sendreceive");
            JSONArray folderDevices = new JSONArray();
            for (int i = 0; i < devices.length(); i++) {
                JSONObject dev = new JSONObject();
                dev.put("deviceID", devices.getJSONObject(i).optString("deviceID"));
                folderDevices.put(dev);
            }
            newFolder.put("devices", folderDevices);
            newFolder.put("rescanIntervalS", 3600);
            newFolder.put("fsWatcherEnabled", true);
            newFolder.put("fsWatcherDelayS", 10);
            folders.put(newFolder);
            apiRequest("POST", "/rest/system/config", config);
            apiRequest("POST", "/rest/system/restart", null);
            waitForApi();
            return;
        }

        if (folder != null && !saveDir.equals(folder.optString("path"))) {
            folder.put("path", saveDir);
            apiRequest("POST", "/rest/system/config", config);
            apiRequest("POST", "/rest/system/restart", null);
            waitForApi();
        }
    }

    private JSONObject runStartupSync() throws Exception {
        String encodedFolder = java.net.URLEncoder.encode(FOLDER_ID, "UTF-8");
        apiRequest("POST", "/rest/db/scan?folder=" + encodedFolder, null);

        long startedAt = System.currentTimeMillis();
        long timeoutMs = 120000;
        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            JSONObject folderStatus;
            try {
                folderStatus = apiRequest("GET", "/rest/db/status?folder=" + encodedFolder, null);
            } catch (Exception e) {
                Thread.sleep(1500);
                continue;
            }
            String state = folderStatus.optString("state", "unknown");
            long needBytes = folderStatus.optLong("needBytes", 0);
            if ("error".equals(state)) {
                throw new Exception("Syncthing reported a folder sync error");
            }
            if ("idle".equals(state) && needBytes == 0) {
                JSONObject result = new JSONObject();
                result.put("completed", true);
                result.put("state", state);
                result.put("needBytes", needBytes);
                return result;
            }
            Thread.sleep(1500);
        }

        JSONObject result = new JSONObject();
        result.put("completed", false);
        result.put("state", "timeout");
        result.put("needBytes", 0);
        return result;
    }

    private void drainProcessOutput(Process proc) {
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty()) {
                    lastStartError = line;
                }
                android.util.Log.d("Syncthing", line);
            }
        } catch (Exception ignored) {
        }
    }
}
