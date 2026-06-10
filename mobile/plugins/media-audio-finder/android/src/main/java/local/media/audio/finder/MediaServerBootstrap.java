package local.media.audio.finder;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;

import com.getcapacitor.JSObject;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

final class MediaServerBootstrap {

    private static final String TERMINAL_PACKAGE = "com.android.virtualization.terminal";
    private static final String TERMINAL_MAIN_ACTIVITY = TERMINAL_PACKAGE + ".MainActivity";
    private static final String TERMINAL_LAUNCHER_ACTIVITY = TERMINAL_PACKAGE + ".LauncherActivity";
    private static final String ACTION_VM_TERMINAL = "android.virtualization.VM_TERMINAL";
    private static final long TERMINAL_RELAUNCH_COOLDOWN_MS = 30_000L;

    private static MediaServerBootstrap instance;

    private final Context appContext;
    private Activity activity;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean backgroundStarted = new AtomicBoolean(false);
    private volatile long lastTerminalLaunchMs;

    private MediaServerBootstrap(Context context) {
        this.appContext = context.getApplicationContext();
    }

    static synchronized MediaServerBootstrap getInstance(Context context) {
        if (instance == null) {
            instance = new MediaServerBootstrap(context);
        }
        return instance;
    }

    void attachActivity(Activity activity) {
        this.activity = activity;
    }

    void detachActivity() {
        this.activity = null;
    }

    void startBackground() {
        if (!backgroundStarted.compareAndSet(false, true)) {
            return;
        }
        new Thread(() -> {
            PlaybackLog.info("media server bootstrap start", PlaybackLog.fields());
            ensureReady(60, 500);
        }, "media-server-bootstrap").start();
    }

    boolean probeHealth() {
        HttpURLConnection connection = null;
        try {
            String healthUrl = "http://127.0.0.1:" + MediaAudioFinderPlugin.MEDIA_SERVER_PORT + "/health";
            connection = (HttpURLConnection) new URL(healthUrl).openConnection();
            connection.setConnectTimeout(1500);
            connection.setReadTimeout(1500);
            connection.setRequestMethod("GET");
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                PlaybackLog.warn(
                    "media server health unexpected status",
                    PlaybackLog.with("code", code)
                );
                return false;
            }
            byte[] body = readAllBytes(connection);
            if (body.length == 0) {
                return false;
            }
            JSONObject json = new JSONObject(new String(body, StandardCharsets.UTF_8));
            return json.optBoolean("ok", false);
        } catch (Exception e) {
            PlaybackLog.warn(
                "media server health probe failed",
                PlaybackLog.put(
                    PlaybackLog.put(PlaybackLog.fields(), "error", e.getClass().getSimpleName()),
                    "message",
                    String.valueOf(e.getMessage())
                )
            );
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    JSObject probeHealthDetails() {
        HttpURLConnection connection = null;
        JSObject ret = new JSObject();
        ret.put("port", MediaAudioFinderPlugin.MEDIA_SERVER_PORT);
        ret.put("ok", false);
        try {
            String healthUrl = "http://127.0.0.1:" + MediaAudioFinderPlugin.MEDIA_SERVER_PORT + "/health";
            connection = (HttpURLConnection) new URL(healthUrl).openConnection();
            connection.setConnectTimeout(1500);
            connection.setReadTimeout(1500);
            connection.setRequestMethod("GET");
            int code = connection.getResponseCode();
            ret.put("statusCode", code);
            if (code != HttpURLConnection.HTTP_OK) {
                return ret;
            }
            byte[] body = readAllBytes(connection);
            if (body.length == 0) {
                return ret;
            }
            JSONObject json = new JSONObject(new String(body, StandardCharsets.UTF_8));
            ret.put("ok", json.optBoolean("ok", false));
            if (json.has("libraryRoot")) {
                ret.put("libraryRoot", json.optString("libraryRoot", ""));
            }
            if (json.has("ytdlp")) {
                ret.put("ytdlp", json.optString("ytdlp", ""));
            }
            if (json.has("ffmpeg")) {
                ret.put("ffmpeg", json.optString("ffmpeg", ""));
            }
            return ret;
        } catch (Exception e) {
            ret.put("error", e.getClass().getSimpleName());
            ret.put("message", String.valueOf(e.getMessage()));
            return ret;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    void launchTerminalIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastTerminalLaunchMs < TERMINAL_RELAUNCH_COOLDOWN_MS) {
            return;
        }
        Intent launchIntent = buildTerminalLaunchIntent();
        if (launchIntent == null) {
            PlaybackLog.warn("debian terminal unavailable", PlaybackLog.fields());
            return;
        }
        lastTerminalLaunchMs = now;
        mainHandler.post(() -> {
            try {
                Context launchContext = activity != null ? activity : appContext;
                if (launchContext == appContext) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                launchContext.startActivity(launchIntent);
                ComponentName component = launchIntent.getComponent();
                PlaybackLog.info(
                    "launched debian terminal vm",
                    PlaybackLog.put(
                        PlaybackLog.fields(),
                        "component",
                        component != null ? component.flattenToShortString() : ACTION_VM_TERMINAL
                    )
                );
            } catch (ActivityNotFoundException e) {
                PlaybackLog.warn(
                    "debian terminal launch failed",
                    PlaybackLog.put(PlaybackLog.fields(), "message", e.getMessage())
                );
            }
        });
    }

    boolean ensureReady(int maxAttempts, long delayMs) {
        for (int attempt = 0; attempt < maxAttempts; attempt += 1) {
            if (probeHealth()) {
                if (attempt > 0) {
                    PlaybackLog.info(
                        "media server ready",
                        PlaybackLog.with("attempt", attempt)
                    );
                }
                return true;
            }
            if (attempt == 0 || attempt == 3 || attempt == 10) {
                launchTerminalIfNeeded();
            }
            if (attempt < maxAttempts - 1) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        PlaybackLog.error("media server bootstrap timed out", PlaybackLog.fields());
        return false;
    }

    private Intent buildTerminalLaunchIntent() {
        PackageManager pm = appContext.getPackageManager();
        Intent vmIntent = new Intent(ACTION_VM_TERMINAL);
        vmIntent.setPackage(TERMINAL_PACKAGE);
        ResolveInfo vmResolve = pm.resolveActivity(vmIntent, PackageManager.MATCH_DEFAULT_ONLY);
        if (vmResolve != null && vmResolve.activityInfo != null) {
            Intent intent = new Intent(ACTION_VM_TERMINAL);
            intent.setComponent(new ComponentName(
                vmResolve.activityInfo.packageName,
                vmResolve.activityInfo.name
            ));
            return intent;
        }

        Intent mainIntent = new Intent(Intent.ACTION_MAIN);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        mainIntent.setComponent(new ComponentName(TERMINAL_PACKAGE, TERMINAL_LAUNCHER_ACTIVITY));
        ResolveInfo launcherResolve = pm.resolveActivity(mainIntent, PackageManager.MATCH_DEFAULT_ONLY);
        if (launcherResolve != null) {
            return mainIntent;
        }

        Intent explicitVmIntent = new Intent(ACTION_VM_TERMINAL);
        explicitVmIntent.setComponent(new ComponentName(TERMINAL_PACKAGE, TERMINAL_MAIN_ACTIVITY));
        if (pm.resolveActivity(explicitVmIntent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
            return explicitVmIntent;
        }
        return null;
    }

    private static byte[] readAllBytes(HttpURLConnection connection) throws Exception {
        try (java.io.InputStream in = connection.getInputStream()) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) >= 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }
    }
}
