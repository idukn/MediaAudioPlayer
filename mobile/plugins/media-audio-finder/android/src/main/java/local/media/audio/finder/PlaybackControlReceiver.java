package local.media.audio.finder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class PlaybackControlReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        NativePlaybackManager manager = NativePlaybackManager.getInstance(context);
        manager.startForegroundService();
        switch (intent.getAction()) {
            case NativePlaybackManager.ACTION_PREVIOUS:
                manager.skipPrevious();
                break;
            case NativePlaybackManager.ACTION_NEXT:
                manager.skipNext();
                break;
            case NativePlaybackManager.ACTION_PLAY_PAUSE:
                manager.playPause();
                break;
            case NativePlaybackManager.ACTION_CYCLE_LOOP:
                manager.cycleLoopMode();
                break;
            default:
                break;
        }
    }
}
