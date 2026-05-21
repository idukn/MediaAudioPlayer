package local.media.audio.finder;

import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@CapacitorPlugin(name = "MediaAudioFinder")
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
        emitSyncUpdated(jsonToJSObject(payload));
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

    private String runCommand(List<String> command, long timeoutSec) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
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
        ret.put("fileManagerLabel", "ファイル");
        ret.put("libraryDir", getLibraryRoot().getAbsolutePath());
        call.resolve(ret);
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
            JSArray arr = new JSArray();
            for (int i = 0; i < playlists.length(); i++) {
                arr.put(playlists.getJSONObject(i));
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
                JSONObject item = items.getJSONObject(i);
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
            String ytdlp = ExecutableResolver.resolve(getContext(), "yt-dlp");
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
            String ytdlp = ExecutableResolver.resolve(getContext(), "yt-dlp");
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
        try {
            String filePath = call.getString("filePath", "");
            if (filePath.isEmpty()) {
                call.resolve();
                return;
            }
            File file = new File(filePath);
            Uri uri = Uri.parse("file://" + (file.isDirectory() ? file.getAbsolutePath() : file.getParent()));
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "*/*");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(Intent.createChooser(intent, "Open folder"));
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
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
    public void syncthingGetInfo(PluginCall call) {
        new Thread(() -> {
            try {
                JSONObject info = syncthingManager.getInfo();
                JSObject ret = jsonToJSObject(info);
                call.resolve(ret);
            } catch (Exception e) {
                JSObject ret = new JSObject();
                ret.put("ok", false);
                ret.put("error", e.getMessage());
                ret.put("libraryPath", getLibraryRoot().getAbsolutePath());
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
                JSONObject info = syncthingManager.getInfo();
                JSObject ret = jsonToJSObject(info);
                ret.put("success", res.optBoolean("success", true));
                call.resolve(ret);
            } catch (Exception e) {
                call.reject(e.getMessage());
            }
        }, "syncthing-add-device").start();
    }
}
