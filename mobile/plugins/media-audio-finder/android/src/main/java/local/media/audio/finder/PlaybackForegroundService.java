package local.media.audio.finder;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;

/** Keeps media playback alive while the screen is off. */
public class PlaybackForegroundService extends Service {
    @Override
    public void onCreate() {
        super.onCreate();
        PlaybackLog.info("foreground service onCreate", PlaybackLog.fields());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NativePlaybackManager manager = NativePlaybackManager.getInstance(this);
        manager.bindForegroundService(this);
        String action = intent != null ? intent.getAction() : null;
        if (NativePlaybackManager.isControlAction(action)) {
            PlaybackLog.info("notification control", PlaybackLog.with("action", action));
            manager.handleControlAction(action);
            return START_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        NativePlaybackManager manager = NativePlaybackManager.getInstance(this);
        if (manager.isPlaybackSessionActive()) {
            manager.onNotificationDismissed();
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        NativePlaybackManager.getInstance(this).detachForegroundService(this);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
