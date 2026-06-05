package local.media.audio.finder;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@CapacitorPlugin(
    name = "MediaAudioFinder",
    permissions = {
        @Permission(
            alias = "termuxRun",
            strings = { "com.termux.permission.RUN_COMMAND" }
        )
    }
)
public class MediaAudioFinderPlugin extends Plugin {

    private static final String LIBRARY_DIR_NAME = "library";
    private static final String PLAYLISTS_FILE = "playlists.json";
    private static final Set<String> AUDIO_EXTS = new HashSet<>(Arrays.asList(
        ".mp3", ".m4a", ".aac", ".webm", ".wav", ".ogg", ".flac", ".opus", ".wma"
    ));

    private PreviewStreamServer previewStreamServer;
    private SyncthingManager syncthingManager;
    private FileObserver libraryObserver;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable syncNotifyRunnable;

    private File getLibraryRoot() {
        File base = getContext().getExternalFilesDir(null);
        if (base == null) {
            base = getContext().getFilesDir();
        }
        File library = new File(base, LIBRARY_DIR_NAME);
        if (!library.exists()) {
            //noinspection ResultOfMethodCallIgnored
            library.mkdirs();
        }
        return library;
    }

    private File getPlaylistsFile() {
        return new File(getLibraryRoot(), PLAYLISTS_FILE);
    }

    @Override
    public void load() {
        super.load();
        try {
            previewStreamServer = new PreviewStreamServer(getContext(), this::getLibraryRoot);
        } catch (Exception e) {
            android.util.Log.e("MediaAudioFinder", "Preview server failed: " + e.getMessage());
        }

        new Thread(() -> {
            boolean ok = MediaTools.hasWorkingFfmpeg(getContext());
            android.util.Log.i("MediaAudioFinder", "ffmpeg warmup: " + (ok ? "ok" : "failed"));
        }, "ffmpeg-warmup").start();

        syncthingManager = new SyncthingManager(getContext());
        syncthingManager.bootstrap(getLibraryRoot().getAbsolutePath(), this::emitSyncUpdated);
        setupLibraryWatcher();
    }

    @Override
    protected void handleOnDestroy() {
        if (libraryObserver != null) {
            libraryObserver.stopWatching();
            libraryObserver = null;
        }
        if (syncthingManager != null) {
            syncthingManager.stop();
        }
        if (previewStreamServer != null) {
            previewStreamServer.stop();
            previewStreamServer = null;
        }
        super.handleOnDestroy();
    }

    private void setupLibraryWatcher() {
        String path = getLibraryRoot().getAbsolutePath();
        libraryObserver = new FileObserver(path, FileObserver.ALL_EVENTS) {
            @Override
            public void onEvent(int event, String relativePath) {
                if (event == FileObserver.CLOSE_WRITE
                    || event == FileObserver.CREATE
                    || event == FileObserver.DELETE
                    || event == FileObserver.MOVED_TO
                    || event == FileObserver.MOVED_FROM) {
                    scheduleSyncNotify();
                }
            }
        };
        libraryObserver.startWatching();
    }

    private void scheduleSyncNotify() {
        if (syncNotifyRunnable != null) {
            mainHandler.removeCallbacks(syncNotifyRunnable);
        }
        syncNotifyRunnable = () -> {
            try {
                JSObject payload = new JSObject();
                payload.put("type", "audio");
                emitSyncUpdated(payload);
            } catch (Exception ignored) {
            }
        };
        mainHandler.postDelayed(syncNotifyRunnable, 800);
    }

    private void emitSyncUpdated(JSObject payload) {
        notifyListeners("syncUpdated", payload);
    }

    private void emitSyncUpdated(JSONObject payload) {
        try {
            emitSyncUpdated(jsonToJSObject(payload));
        } catch (Exception e) {
            android.util.Log.e("MediaAudioFinder", "emitSyncUpdated failed: " + e.getMessage());
        }
    }

    private JSObject jsonToJSObject(JSONObject json) throws Exception {
        JSObject obj = new JSObject();
        JSONArray names = json.names();
        if (names == null) {
            return obj;
        }
        for (int i = 0; i < names.length(); i++) {
            String key = names.getString(i);
            Object value = json.get(key);
            if (value instanceof JSONArray) {
                obj.put(key, jsonArrayToJSArray((JSONArray) value));
            } else if (value instanceof JSONObject) {
                obj.put(key, jsonToJSObject((JSONObject) value));
            } else {
                obj.put(key, value);
            }
        }
        return obj;
    }

    private JSArray jsonArrayToJSArray(JSONArray array) throws Exception {
        JSArray jsArray = new JSArray();
        for (int i = 0; i < array.length(); i++) {
            Object value = array.get(i);
            if (value instanceof JSONObject) {
                jsArray.put(jsonToJSObject((JSONObject) value));
            } else if (value instanceof JSONArray) {
                jsArray.put(jsonArrayToJSArray((JSONArray) value));
            } else {
                jsArray.put(value);
            }
        }
        return jsArray;
    }

    private String readTextFile(File file) throws Exception {
        if (!file.exists()) {
            return "[]";
        }
        FileInputStream in = new FileInputStream(file);
        byte[] data = new byte[(int) file.length()];
        int read = in.read(data);
        in.close();
        return new String(data, 0, Math.max(read, 0), StandardCharsets.UTF_8);
    }

    private void writeTextFile(File file, String text) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        FileOutputStream out = new FileOutputStream(file, false);
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.close();
    }

    private JSONArray readPlaylistsArray() throws Exception {
        String raw = readTextFile(getPlaylistsFile());
        if (raw.trim().isEmpty()) {
            return new JSONArray();
        }
        return new JSONArray(raw);
    }

    private void writePlaylistsArray(JSONArray playlists) throws Exception {
        writeTextFile(getPlaylistsFile(), playlists.toString(2));
    }

    private boolean isPathWithinLibrary(File file) throws IOException {
        File libraryRoot = getLibraryRoot().getCanonicalFile();
        String libPath = libraryRoot.getPath();
        String targetPath = file.getCanonicalPath();
        return targetPath.equals(libPath) || targetPath.startsWith(libPath + File.separator);
    }

    private String extractLibraryRelativePath(String rawPath) {
        String normalized = rawPath.replace('\\', '/').trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        String[] markers = new String[] {
            "/library/",
            ".media-audio-finder/library/",
            "media-audio-finder/library/",
            "/application support/media-audio-finder/library/",
            "/reference/",
            "/yt_audio_app/",
        };
        String best = "";
        for (String marker : markers) {
            int idx = lower.lastIndexOf(marker);
            if (idx >= 0) {
                String rel = normalized.substring(idx + marker.length());
                if (rel.length() > best.length()) {
                    best = rel;
                }
            }
        }
        return best;
    }

    /** パス末尾のセグメントから library 内の実ファイルを探す（Mac 同期パス・相対パス用）。 */
    private String resolveByPathSuffixes(File libraryRoot, String rawPath) {
        String normalized = rawPath.replace('\\', '/').trim();
        String[] parts = normalized.split("/");
        java.util.ArrayList<String> segments = new java.util.ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.isEmpty() && !".".equals(part)) {
                segments.add(part);
            }
        }
        for (int start = 0; start < segments.size(); start++) {
            StringBuilder rel = new StringBuilder();
            for (int i = start; i < segments.size(); i++) {
                if (rel.length() > 0) {
                    rel.append('/');
                }
                rel.append(segments.get(i));
            }
            File candidate = new File(libraryRoot, rel.toString());
            if (candidate.isFile()) {
                return candidate.getAbsolutePath();
            }
        }
        return null;
    }

    private boolean isAbsolutePath(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        return path.startsWith("/") || path.matches("^[A-Za-z]:[/\\\\].*");
    }

    private String findAudioFileByName(File dir, String name) {
        if (dir == null || !dir.isDirectory() || name == null || name.isEmpty()) {
            return null;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return null;
        }
        for (File child : children) {
            if (child.isFile() && child.getName().equalsIgnoreCase(name)) {
                String lower = child.getName().toLowerCase(Locale.ROOT);
                for (String ext : AUDIO_EXTS) {
                    if (lower.endsWith(ext)) {
                        return child.getAbsolutePath();
                    }
                }
            }
            if (child.isDirectory()) {
                String found = findAudioFileByName(child, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Mac 同期パスを Android ライブラリ内の実ファイルへ解決する。 */
    private String resolveLibraryAudioPath(String rawPath) {
        if (rawPath == null || rawPath.trim().isEmpty()) {
            return null;
        }
        try {
            File libraryRoot = getLibraryRoot().getCanonicalFile();
            String trimmed = rawPath.trim();
            if (trimmed.startsWith("file://")) {
                trimmed = trimmed.substring(7);
            }
            File direct = new File(trimmed);
            if (direct.isFile() && isPathWithinLibrary(direct)) {
                return direct.getAbsolutePath();
            }

            if (!isAbsolutePath(trimmed)) {
                File relative = new File(libraryRoot, trimmed);
                if (relative.isFile()) {
                    return relative.getAbsolutePath();
                }
            }

            String rel = extractLibraryRelativePath(trimmed);
            if (!rel.isEmpty()) {
                File relFile = new File(libraryRoot, rel);
                if (relFile.isFile()) {
                    return relFile.getAbsolutePath();
                }
            }

            String suffixResolved = resolveByPathSuffixes(libraryRoot, trimmed);
            if (suffixResolved != null) {
                return suffixResolved;
            }

            String baseName = new File(trimmed.replace('\\', '/')).getName();
            if (!baseName.isEmpty()) {
                return findAudioFileByName(libraryRoot, baseName);
            }
        } catch (IOException e) {
            android.util.Log.w("MediaAudioFinder", "resolveLibraryAudioPath failed: " + e.getMessage());
        }
        return null;
    }

    /** ライブラリ内の相対パス（playlists.json 保存用。Mac/Android で共通）。 */
    private String toStorageLibraryPath(String rawPath) throws IOException {
        if (rawPath == null || rawPath.trim().isEmpty()) {
            return "";
        }
        String trimmed = rawPath.trim();
        if (trimmed.startsWith("file://")) {
            trimmed = trimmed.substring(7);
        }
        trimmed = trimmed.replace('\\', '/');

        if (!isAbsolutePath(trimmed)) {
            return trimmed.replaceAll("^/+", "");
        }

        File libraryRoot = getLibraryRoot().getCanonicalFile();
        File direct = new File(trimmed);
        if (direct.isFile() && isPathWithinLibrary(direct)) {
            return relativizeUnderLibrary(libraryRoot, direct);
        }

        String resolved = resolveLibraryAudioPath(trimmed);
        if (resolved != null) {
            return relativizeUnderLibrary(libraryRoot, new File(resolved));
        }

        String rel = extractLibraryRelativePath(trimmed);
        if (!rel.isEmpty()) {
            return rel.replace('\\', '/').replaceAll("^/+", "");
        }

        return new File(trimmed).getName();
    }

    private String relativizeUnderLibrary(File libraryRoot, File file) throws IOException {
        String libPath = libraryRoot.getCanonicalPath();
        String targetPath = file.getCanonicalPath();
        if (targetPath.equals(libPath)) {
            return "";
        }
        if (targetPath.startsWith(libPath + File.separator)) {
            return targetPath.substring(libPath.length() + 1).replace('\\', '/');
        }
        return file.getName();
    }

    private JSONObject normalizePlaylistItemForStorage(JSONObject raw) throws Exception {
        if (raw == null) {
            return null;
        }
        String fullPath = raw.optString("fullPath", raw.optString("path", "")).trim();
        String webpageUrl = raw.optString("webpageUrl", raw.optString("url", "")).trim();
        JSONObject normalized = new JSONObject();
        if (!fullPath.isEmpty()) {
            fullPath = toStorageLibraryPath(fullPath);
            if (fullPath.isEmpty()) {
                return null;
            }
            normalized.put("type", "local");
            normalized.put("fullPath", fullPath);
            String title = raw.optString("title", "").trim();
            if (title.isEmpty()) {
                int slash = Math.max(fullPath.lastIndexOf('/'), fullPath.lastIndexOf('\\'));
                title = slash >= 0 ? fullPath.substring(slash + 1) : fullPath;
            }
            normalized.put("title", title);
            normalized.put("uploader", raw.optString("uploader", "Local File"));
            normalized.put("duration", raw.optString("duration", "-"));
            if (raw.has("durationSec") && !raw.isNull("durationSec")) {
                normalized.put("durationSec", raw.optDouble("durationSec"));
            }
            normalized.put("site", raw.optString("site", "Local"));
            if (raw.has("id")) {
                normalized.put("id", raw.optString("id"));
            }
            return normalized;
        }
        if (!webpageUrl.isEmpty()) {
            normalized.put("type", "url");
            normalized.put("webpageUrl", webpageUrl);
            normalized.put("title", raw.optString("title", "(No title)"));
            normalized.put("uploader", raw.optString("uploader", "-"));
            normalized.put("duration", raw.optString("duration", "-"));
            normalized.put("site", raw.optString("site", "unknown"));
            if (raw.has("id")) {
                normalized.put("id", raw.optString("id"));
            }
            return normalized;
        }
        return null;
    }

    private JSONObject expandPlaylistItemForClient(JSONObject stored) throws Exception {
        if (stored == null) {
            return null;
        }
        if (!"local".equals(stored.optString("type", ""))) {
            return stored;
        }
        String storagePath = stored.optString("fullPath", "").trim();
        if (storagePath.isEmpty()) {
            return stored;
        }
        JSONObject client = new JSONObject(stored.toString());
        String resolved = resolveLibraryAudioPath(storagePath);
        if (resolved != null) {
            client.put("fullPath", resolved);
        }
        return client;
    }

    private JSONArray normalizePlaylistItemsForStorage(JSONArray items) throws Exception {
        JSONArray normalized = new JSONArray();
        if (items == null) {
            return normalized;
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = normalizePlaylistItemForStorage(items.optJSONObject(i));
            if (item != null) {
                normalized.put(item);
            }
        }
        return normalized;
    }

    private JSONArray expandPlaylistItemsForClient(JSONArray storageItems) throws Exception {
        JSONArray expanded = new JSONArray();
        if (storageItems == null) {
            return expanded;
        }
        for (int i = 0; i < storageItems.length(); i++) {
            JSONObject item = expandPlaylistItemForClient(storageItems.optJSONObject(i));
            if (item != null) {
                expanded.put(item);
            }
        }
        return expanded;
    }

    private boolean playlistItemsNeedStorageMigration(JSONArray raw, JSONArray storage) throws Exception {
        if (raw == null && storage == null) {
            return false;
        }
        if (raw == null || storage == null || raw.length() != storage.length()) {
            return true;
        }
        for (int i = 0; i < raw.length(); i++) {
            JSONObject before = raw.optJSONObject(i);
            JSONObject after = storage.optJSONObject(i);
            if (before == null || after == null) {
                return true;
            }
            String pathBefore = before.optString("fullPath", before.optString("path", "")).trim();
            String pathAfter = after.optString("fullPath", "").trim();
            if (!pathBefore.equals(pathAfter)) {
                return true;
            }
        }
        return false;
    }

    private String runCommand(List<String> command, long timeoutSec) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        if (!command.isEmpty()) {
            MediaTools.applyProcessEnvironment(builder, getContext(), command.get(0));
        }
        Process process = builder.start();
        boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new Exception("Command timed out");
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder output = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            output.append(line).append('\n');
        }
        reader.close();
        if (process.exitValue() != 0) {
            throw new Exception(output.toString().trim());
        }
        return output.toString();
    }

    @PluginMethod
    public void getLibraryDir(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("path", getLibraryRoot().getAbsolutePath());
        call.resolve(ret);
    }

    @PluginMethod
    public void getAudioServerPort(PluginCall call) {
        JSObject ret = new JSObject();
        int port = previewStreamServer != null ? previewStreamServer.getBoundPort() : 0;
        ret.put("port", port);
        call.resolve(ret);
    }

    @PluginMethod
    public void getPlatformInfo(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("platform", "android");
        ret.put("platformLabel", "Android");
        ret.put("fileManagerLabel", "ファイルマネージャ");
        ret.put("libraryDir", getLibraryRoot().getAbsolutePath());
        call.resolve(ret);
    }

    @PluginMethod
    public void getMediaToolsDiagnostics(PluginCall call) {
        Context ctx = getContext();
        JSObject ret = new JSObject();
        ret.put("bundledFfmpeg", NativeBinaryResolver.resolveBundledFfmpeg(ctx) != null);
        ret.put("ffmpegWorks", MediaTools.hasWorkingFfmpeg(ctx));
        ret.put("ytdlpInTermux", MediaTools.isYtdlpInstalledInTermux());
        ret.put("ytdlpDirectExec", MediaTools.resolveYtdlp(ctx) != null);
        ret.put("termuxPackage", TermuxDetector.findInstalledPackage(ctx));
        ret.put("termuxVersion", TermuxDetector.getTermuxVersionName(ctx));
        ret.put("termuxVersionCode", TermuxDetector.getTermuxVersionCode(ctx));
        ret.put("termuxGooglePlayBuild", TermuxDetector.isGooglePlayBuild(ctx));
        ret.put("termuxVersionSupported", TermuxDetector.isTermuxVersionLikelySupported(ctx));
        ret.put("termuxInstalled", TermuxCommandRunner.isTermuxInstalled(ctx));
        ret.put("termuxRunCommandDefined", TermuxDetector.isRunCommandPermissionDefined(ctx));
        ret.put("termuxRunCommandPermission", TermuxCommandRunner.hasRunCommandPermission(ctx));
        ret.put("termuxPermissionState", getPermissionState("termuxRun").toString());
        ret.put("termuxSetupHint", TermuxCommandRunner.getSetupHint(ctx));
        if (TermuxDetector.isRunCommandPermissionDefined(ctx)) {
            ret.put("adbGrantCommand",
                "adb shell pm grant " + ctx.getPackageName() + " com.termux.permission.RUN_COMMAND");
        } else {
            ret.put("adbGrantCommand", "(この端末では Unknown permission — 公式 Termux/F-Droid が必要)");
        }
        call.resolve(ret);
    }

    @PluginMethod
    public void requestTermuxRunPermission(PluginCall call) {
        Context ctx = getContext();
        if (!TermuxCommandRunner.isTermuxInstalled(ctx)) {
            call.reject("Termux がインストールされていません");
            return;
        }
        if (TermuxCommandRunner.hasRunCommandPermission(ctx)) {
            JSObject ret = new JSObject();
            ret.put("granted", true);
            call.resolve(ret);
            return;
        }
        if (getPermissionState("termuxRun") == PermissionState.GRANTED) {
            JSObject ret = new JSObject();
            ret.put("granted", true);
            call.resolve(ret);
            return;
        }
        requestPermissionForAlias("termuxRun", call, "termuxRunPermsCallback");
    }

    @PermissionCallback
    private void termuxRunPermsCallback(PluginCall call) {
        boolean granted = TermuxCommandRunner.hasRunCommandPermission(getContext());
        JSObject ret = new JSObject();
        ret.put("granted", granted);
        ret.put("permissionState", getPermissionState("termuxRun").toString());
        if (!granted) {
            ret.put("hint", TermuxCommandRunner.getSetupHint(getContext()));
            ret.put("adbGrantCommand",
                "adb shell pm grant " + getContext().getPackageName() + " com.termux.permission.RUN_COMMAND");
        }
        call.resolve(ret);
    }

    @PluginMethod
    public void openAppPermissionSettings(PluginCall call) {
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getContext().getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void listAudio(PluginCall call) {
        try {
            String saveDir = call.getString("saveDir", getLibraryRoot().getAbsolutePath());
            File dir = new File(saveDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File[] entries = dir.listFiles();
            JSArray files = new JSArray();
            if (entries != null) {
                Arrays.sort(entries, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                for (File entry : entries) {
                    String name = entry.getName();
                    String lower = name.toLowerCase(Locale.ROOT);
                    boolean isDir = entry.isDirectory();
                    boolean isAudio = false;
                    for (String ext : AUDIO_EXTS) {
                        if (lower.endsWith(ext)) {
                            isAudio = true;
                            break;
                        }
                    }
                    if (!isDir && !isAudio) {
                        continue;
                    }
                    JSObject item = new JSObject();
                    item.put("name", name);
                    item.put("fullPath", entry.getAbsolutePath());
                    item.put("isDir", isDir);
                    item.put("isAudio", isAudio);
                    item.put("size", entry.length());
                    item.put("mtimeMs", entry.lastModified());
                    files.put(item);
                }
            }
            JSObject ret = new JSObject();
            ret.put("files", files);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void getPlaylists(PluginCall call) {
        try {
            JSONArray playlists = readPlaylistsArray();
            boolean dirty = false;
            JSArray arr = new JSArray();
            for (int i = 0; i < playlists.length(); i++) {
                JSONObject playlist = playlists.getJSONObject(i);
                JSONArray rawItems = playlist.optJSONArray("items");
                JSONArray storageItems = normalizePlaylistItemsForStorage(rawItems);
                if (playlistItemsNeedStorageMigration(rawItems, storageItems)) {
                    dirty = true;
                    playlist.put("items", storageItems);
                }
                JSONObject clientPlaylist = new JSONObject(playlist.toString());
                clientPlaylist.put("items", expandPlaylistItemsForClient(storageItems));
                arr.put(clientPlaylist);
            }
            if (dirty) {
                writePlaylistsArray(playlists);
                android.util.Log.i("MediaAudioFinder", "Migrated playlist paths to library-relative format");
            }
            JSObject ret = new JSObject();
            ret.put("playlists", arr);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void createPlaylist(PluginCall call) {
        try {
            String name = call.getString("name", "").trim();
            if (name.isEmpty()) {
                call.reject("プレイリスト名を入力してください");
                return;
            }
            JSONArray playlists = readPlaylistsArray();
            JSONObject created = new JSONObject();
            created.put("id", "pl_" + System.currentTimeMillis());
            created.put("name", name);
            created.put("createdAt", System.currentTimeMillis());
            created.put("items", new JSONArray());
            JSONArray next = new JSONArray();
            next.put(created);
            for (int i = 0; i < playlists.length(); i++) {
                next.put(playlists.get(i));
            }
            writePlaylistsArray(next);
            JSObject createdObj = new JSObject();
            createdObj.put("id", created.getString("id"));
            createdObj.put("name", created.getString("name"));
            createdObj.put("createdAt", created.getLong("createdAt"));
            createdObj.put("items", new JSArray());
            call.resolve(createdObj);
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void addItemsToPlaylist(PluginCall call) {
        try {
            String playlistId = call.getString("playlistId", "");
            JSArray items = call.getArray("items");
            JSONArray playlists = readPlaylistsArray();
            int index = -1;
            for (int i = 0; i < playlists.length(); i++) {
                JSONObject playlist = playlists.getJSONObject(i);
                if (playlistId.equals(playlist.optString("id"))) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                call.reject("指定されたプレイリストが見つかりません");
                return;
            }
            JSONObject playlist = playlists.getJSONObject(index);
            JSONArray existing = playlist.optJSONArray("items");
            if (existing == null) {
                existing = new JSONArray();
            }
            int added = 0;
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = normalizePlaylistItemForStorage(items.getJSONObject(i));
                if (item == null) {
                    continue;
                }
                existing.put(item);
                added++;
            }
            playlist.put("items", existing);
            playlists.put(index, playlist);
            writePlaylistsArray(playlists);
            JSObject ret = new JSObject();
            ret.put("ok", true);
            ret.put("addedCount", added);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void reorderPlaylistItems(PluginCall call) {
        try {
            String playlistId = call.getString("playlistId", "");
            int fromIndex = call.getInt("fromIndex", -1);
            int toIndex = call.getInt("toIndex", -1);
            JSONArray playlists = readPlaylistsArray();
            for (int i = 0; i < playlists.length(); i++) {
                JSONObject playlist = playlists.getJSONObject(i);
                if (!playlistId.equals(playlist.optString("id"))) {
                    continue;
                }
                JSONArray items = playlist.getJSONArray("items");
                if (fromIndex < 0 || toIndex < 0 || fromIndex >= items.length() || toIndex >= items.length()) {
                    call.reject("並べ替え位置が不正です");
                    return;
                }
                ArrayList<JSONObject> list = new ArrayList<>();
                for (int j = 0; j < items.length(); j++) {
                    list.add(items.getJSONObject(j));
                }
                JSONObject moved = list.remove(fromIndex);
                list.add(toIndex, moved);
                JSONArray reordered = new JSONArray();
                for (JSONObject entry : list) {
                    reordered.put(entry);
                }
                playlist.put("items", reordered);
                playlists.put(i, playlist);
                writePlaylistsArray(playlists);
                JSObject ret = new JSObject();
                ret.put("ok", true);
                call.resolve(ret);
                return;
            }
            call.reject("指定されたプレイリストが見つかりません");
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void removePlaylistItems(PluginCall call) {
        try {
            String playlistId = call.getString("playlistId", "");
            JSArray indexes = call.getArray("itemIndexes");
            Set<Integer> removeSet = new HashSet<>();
            for (int i = 0; i < indexes.length(); i++) {
                removeSet.add(indexes.getInt(i));
            }
            JSONArray playlists = readPlaylistsArray();
            for (int i = 0; i < playlists.length(); i++) {
                JSONObject playlist = playlists.getJSONObject(i);
                if (!playlistId.equals(playlist.optString("id"))) {
                    continue;
                }
                JSONArray items = playlist.getJSONArray("items");
                JSONArray next = new JSONArray();
                for (int j = 0; j < items.length(); j++) {
                    if (!removeSet.contains(j)) {
                        next.put(items.get(j));
                    }
                }
                playlist.put("items", next);
                playlists.put(i, playlist);
                writePlaylistsArray(playlists);
                JSObject ret = new JSObject();
                ret.put("ok", true);
                ret.put("removedCount", removeSet.size());
                call.resolve(ret);
                return;
            }
            call.reject("指定されたプレイリストが見つかりません");
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void createFolder(PluginCall call) {
        createFolderInternal(call, true);
    }

    @PluginMethod
    public void createFolderAt(PluginCall call) {
        createFolderInternal(call, false);
    }

    private void createFolderInternal(PluginCall call, boolean restrictToLibrary) {
        try {
            String parentDir = call.getString("parentDir", getLibraryRoot().getAbsolutePath());
            String name = call.getString("name", "").trim();
            if (name.isEmpty()) {
                call.reject("フォルダ名を入力してください");
                return;
            }
            File parent = new File(parentDir);
            if (restrictToLibrary && !parent.getCanonicalPath().startsWith(getLibraryRoot().getCanonicalPath())) {
                call.reject("ライブラリ外のパスは操作できません");
                return;
            }
            File target = new File(parent, name);
            if (target.exists()) {
                call.reject("同名のファイルまたはフォルダが既に存在します");
                return;
            }
            if (!target.mkdir()) {
                call.reject("フォルダを作成できませんでした");
                return;
            }
            JSObject ret = new JSObject();
            ret.put("ok", true);
            ret.put("fullPath", target.getAbsolutePath());
            ret.put("name", name);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void downloadAudio(PluginCall call) {
        try {
            String url = call.getString("url", "");
            String saveDir = call.getString("saveDir", getLibraryRoot().getAbsolutePath());
            String audioFormat = call.getString("audioFormat", "auto");
            if (url.isEmpty()) {
                call.reject("URLが不正です");
                return;
            }
            File dir = new File(saveDir);
            dir.mkdirs();
            String ytdlp = MediaTools.resolveYtdlp(getContext());
            if (ytdlp == null) {
                call.reject("yt-dlp が見つかりません。Termux で pkg install yt-dlp ffmpeg を実行してください");
                return;
            }
            List<String> command = new ArrayList<>();
            command.add(ytdlp);
            command.add("-x");
            command.add("--audio-format");
            command.add("auto".equals(audioFormat) ? "mp3" : audioFormat);
            command.add("-o");
            command.add(new File(dir, "%(title).100s.%(ext)s").getAbsolutePath());
            command.add("--no-playlist");
            command.add(url);
            runCommand(command, 900);
            JSObject ret = new JSObject();
            ret.put("ok", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("ダウンロードに失敗しました: " + e.getMessage());
        }
    }

    @PluginMethod
    public void searchVideos(PluginCall call) {
        try {
            String query = call.getString("query", "").trim();
            if (query.isEmpty()) {
                call.reject("検索キーワードを入力してください");
                return;
            }
            String ytdlp = MediaTools.resolveYtdlp(getContext());
            if (ytdlp == null) {
                call.reject("yt-dlp が見つかりません。Termux で pkg install yt-dlp ffmpeg を実行してください");
                return;
            }
            List<String> command = new ArrayList<>();
            command.add(ytdlp);
            command.add("--flat-playlist");
            command.add("-J");
            command.add("ytsearch10:" + query);
            String output = runCommand(command, 120);
            JSONObject parsed = new JSONObject(output);
            JSONArray entries = parsed.optJSONArray("entries");
            JSArray results = new JSArray();
            if (entries != null) {
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject entry = entries.getJSONObject(i);
                    JSObject item = new JSObject();
                    item.put("title", entry.optString("title", "(No title)"));
                    item.put("uploader", entry.optString("uploader", "-"));
                    item.put("duration", entry.optString("duration_string", "-"));
                    item.put("webpageUrl", entry.optString("webpage_url", entry.optString("url", "")));
                    item.put("site", "youtube");
                    results.put(item);
                }
            }
            JSObject ret = new JSObject();
            ret.put("results", results);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("検索に失敗しました: " + e.getMessage());
        }
    }

    @PluginMethod
    public void chooseDownloadDir(PluginCall call) {
        JSObject ret = new JSObject();
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        ret.put("path", downloads != null ? downloads.getAbsolutePath() : getLibraryRoot().getAbsolutePath());
        call.resolve(ret);
    }

    @PluginMethod
    public void openInFinder(PluginCall call) {
        if (getActivity() == null) {
            call.reject("アプリが前面にありません");
            return;
        }
        getActivity().runOnUiThread(() -> {
            try {
                String filePath = call.getString("filePath", "");
                File target;
                if (filePath.isEmpty()) {
                    target = getLibraryRoot();
                } else {
                    target = new File(filePath).getCanonicalFile();
                }
                File libraryRoot = getLibraryRoot().getCanonicalFile();
                String libPath = libraryRoot.getPath();
                String targetPath = target.getPath();
                if (!targetPath.equals(libPath) && !targetPath.startsWith(libPath + File.separator)) {
                    call.reject("ライブラリ外のパスは開けません");
                    return;
                }
                File folder = target.isDirectory() ? target : target.getParentFile();
                if (folder == null || !folder.exists()) {
                    call.reject("フォルダが見つかりません");
                    return;
                }

                Context ctx = getContext();
                if (FileManagerOpener.openFolder(ctx, folder)) {
                    call.resolve();
                    return;
                }
                FileManagerOpener.copyPathAndNotify(ctx, folder.getAbsolutePath());
                JSObject ret = new JSObject();
                ret.put("fallback", "clipboard");
                ret.put("path", folder.getAbsolutePath());
                call.resolve(ret);
            } catch (Exception e) {
                call.reject(e.getMessage() != null ? e.getMessage() : "フォルダを開けませんでした");
            }
        });
    }

    @PluginMethod
    public void openExternal(PluginCall call) {
        try {
            String url = call.getString("url", "");
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void writeClipboardText(PluginCall call) {
        android.content.ClipboardManager clipboard =
            (android.content.ClipboardManager) getContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("text", call.getString("text", "")));
        call.resolve();
    }

    @PluginMethod
    public void resolveLibraryPath(PluginCall call) {
        String path = call.getString("path", call.getString("fullPath", "")).trim();
        String resolved = resolveLibraryAudioPath(path);
        JSObject ret = new JSObject();
        ret.put("found", resolved != null && !resolved.isEmpty());
        ret.put("path", resolved != null ? resolved : "");
        call.resolve(ret);
    }

    @PluginMethod
    public void syncthingGetInfo(PluginCall call) {
        new Thread(() -> {
            try {
                JSONObject info = syncthingManager.getInfo(getLibraryRoot().getAbsolutePath());
                JSObject ret = jsonToJSObject(info);
                call.resolve(ret);
            } catch (Exception e) {
                JSObject ret = new JSObject();
                ret.put("ok", false);
                ret.put("error", e.getMessage());
                ret.put("libraryPath", getLibraryRoot().getAbsolutePath());
                ret.put("starting", false);
                call.resolve(ret);
            }
        }, "syncthing-get-info").start();
    }

    @PluginMethod
    public void syncthingAddDevice(PluginCall call) {
        String deviceID = call.getString("deviceID", "");
        new Thread(() -> {
            try {
                JSONObject res = syncthingManager.addDevice(deviceID);
                JSONObject info = syncthingManager.getInfo(getLibraryRoot().getAbsolutePath());
                JSObject ret = jsonToJSObject(info);
                ret.put("success", res.optBoolean("success", true));
                call.resolve(ret);
            } catch (Exception e) {
                call.reject(e.getMessage());
            }
        }, "syncthing-add-device").start();
    }
}
