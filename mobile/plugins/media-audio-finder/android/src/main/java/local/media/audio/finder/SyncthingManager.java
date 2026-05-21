package local.media.audio.finder;

import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

final class SyncthingManager {

    static final String FOLDER_ID = "yt-audio-app-sync";
    private static final String VERSION = "v1.27.6";
    private static final Pattern DEVICE_ID_RE =
        Pattern.compile("^[A-Z0-9]{7}(-[A-Z0-9]{7}){6}$");

    private final Context context;
    private final File binDir;
    private final File configDir;
    private final File binaryPath;

    private Process process;
    private String apiKey;
    private String baseUrl = "http://127.0.0.1:8384";
    private boolean shouldRun;
    private boolean starting;

    SyncthingManager(Context context) {
        this.context = context.getApplicationContext();
        File base = context.getFilesDir();
        this.binDir = new File(base, "syncthing_bin");
        this.configDir = new File(base, "syncthing_config");
        this.binaryPath = new File(binDir, "syncthing");
    }

    static String normalizeDeviceID(String raw) {
        String trimmed = String.valueOf(raw == null ? "" : raw).trim().toUpperCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            return null;
        }
        if (DEVICE_ID_RE.matcher(trimmed).matches()) {
            return trimmed;
        }
        String compact = trimmed.replace("-", "");
        if (compact.length() != 49 || !compact.matches("^[A-Z2-7]+$")) {
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

    synchronized void startBackground() {
        new Thread(() -> {
            try {
                start();
            } catch (Exception e) {
                android.util.Log.e("Syncthing", "Background start failed: " + e.getMessage());
            }
        }, "syncthing-start").start();
    }

    synchronized void start() throws Exception {
        if (process != null || starting) {
            return;
        }
        shouldRun = true;
        starting = true;
        try {
            ensureBinary();
            if (!configDir.exists() && !configDir.mkdirs()) {
                throw new Exception("Syncthing config dir を作成できませんでした");
            }

            ProcessBuilder builder = new ProcessBuilder(
                binaryPath.getAbsolutePath(),
                "--no-browser",
                "--no-restart",
                "--home=" + configDir.getAbsolutePath()
            );
            builder.redirectErrorStream(true);
            builder.directory(configDir);
            process = builder.start();

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
                start();
                ensureFolder(saveDir);
                waitForApi();
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

    JSONObject getInfo() throws Exception {
        if (!binaryPath.exists()) {
            JSONObject err = new JSONObject();
            err.put("ok", false);
            err.put("error", "Syncthing を準備中です。しばらく待ってから再試行してください。");
            return err;
        }
        start();
        waitForApi();

        JSONObject status = apiRequest("GET", "/rest/system/status", null);
        JSONObject config = apiRequest("GET", "/rest/system/config", null);
        JSONObject connections = apiRequest("GET", "/rest/system/connections", null);

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
                String deviceID = device.optString("deviceID", "");
                if (deviceID.equals(myID)) {
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
        info.put("folderPath", folder != null ? folder.optString("path", "") : "");
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

    JSONObject addDevice(String rawDeviceID) throws Exception {
        String deviceID = normalizeDeviceID(rawDeviceID);
        if (deviceID == null) {
            throw new Exception("Invalid device ID format");
        }

        start();
        waitForApi();

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
        if (binaryPath.exists() && binaryPath.canExecute()) {
            return;
        }
        if (!binDir.exists() && !binDir.mkdirs()) {
            throw new Exception("Syncthing bin dir を作成できませんでした");
        }

        boolean useArm64 = Build.SUPPORTED_64_BIT_ABIS != null && Build.SUPPORTED_64_BIT_ABIS.length > 0;
        String arch = useArm64 ? "arm64" : "arm";
        String folderName = "syncthing-linux-" + arch + "-" + VERSION;
        String fileName = folderName + ".tar.gz";
        String downloadUrl =
            "https://github.com/syncthing/syncthing/releases/download/" + VERSION + "/" + fileName;

        File archivePath = new File(binDir, fileName);
        android.util.Log.i("Syncthing", "Downloading " + downloadUrl);
        downloadFile(downloadUrl, archivePath);
        extractSyncthingBinary(archivePath, folderName);
        if (!binaryPath.setExecutable(true, false)) {
            throw new Exception("Syncthing バイナリを実行可能にできませんでした");
        }
        //noinspection ResultOfMethodCallIgnored
        archivePath.delete();
    }

    private void downloadFile(String urlString, File dest) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(120000);
        conn.connect();
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new Exception("Download failed HTTP " + code);
        }
        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } finally {
            conn.disconnect();
        }
    }

    private void extractSyncthingBinary(File archivePath, String folderName) throws Exception {
        File extractedBinary = new File(binDir, folderName + "/syncthing");
        try (FileInputStream fis = new FileInputStream(archivePath);
             GZIPInputStream gis = new GZIPInputStream(fis);
             TarArchiveInputStream tar = new TarArchiveInputStream(gis)) {
            ArchiveEntry archiveEntry;
            while ((archiveEntry = tar.getNextEntry()) != null) {
                if (!(archiveEntry instanceof TarArchiveEntry) || archiveEntry.isDirectory()) {
                    continue;
                }
                TarArchiveEntry entry = (TarArchiveEntry) archiveEntry;
                File outFile = new File(binDir, entry.getName());
                File parent = outFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                }
                try (OutputStream out = new FileOutputStream(outFile)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = tar.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
            }
        }
        if (!extractedBinary.exists()) {
            throw new Exception("Syncthing バイナリの展開に失敗しました");
        }
        copyFile(extractedBinary, binaryPath);
        deleteRecursive(new File(binDir, folderName));
    }

    private void copyFile(File src, File dest) throws Exception {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }

    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private void waitForApi() throws Exception {
        for (int attempt = 0; attempt < 40; attempt++) {
            readConfig();
            if (apiKey != null) {
                try {
                    apiRequest("GET", "/rest/system/status", null);
                    return;
                } catch (Exception ignored) {
                }
            }
            Thread.sleep(500);
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
            Matcher apiKeyMatch = Pattern.compile("<apikey>([^<]+)</apikey>").matcher(xml);
            Matcher addressMatch = Pattern.compile("<address>([^<]+)</address>").matcher(xml);
            if (apiKeyMatch.find()) {
                apiKey = apiKeyMatch.group(1);
            }
            if (addressMatch.find()) {
                String addr = addressMatch.group(1).replace("127.0.0.1", "localhost");
                baseUrl = "http://" + addr;
            }
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
        URL url = new URL(baseUrl + endpoint);
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
                android.util.Log.d("Syncthing", line);
            }
        } catch (Exception ignored) {
        }
    }
}
