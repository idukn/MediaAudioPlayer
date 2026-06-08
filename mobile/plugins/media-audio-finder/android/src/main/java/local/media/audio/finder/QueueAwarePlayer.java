package local.media.audio.finder;

import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.Player;
import androidx.media3.common.Player.Commands;
import androidx.media3.exoplayer.ExoPlayer;

/** Exposes queue navigation to MediaSession / notification transport controls. */
final class QueueAwarePlayer extends ForwardingPlayer {
    interface NavigationHandler {
        boolean hasPreviousTrack();

        boolean hasNextTrack();

        void onSkipPrevious();

        void onSkipNext();
    }

    private NavigationHandler navigationHandler;

    QueueAwarePlayer(ExoPlayer player) {
        super(player);
    }

    void setNavigationHandler(NavigationHandler navigationHandler) {
        this.navigationHandler = navigationHandler;
    }

    @Override
    public boolean hasPreviousMediaItem() {
        return navigationHandler != null && navigationHandler.hasPreviousTrack();
    }

    @Override
    public boolean hasNextMediaItem() {
        return navigationHandler != null && navigationHandler.hasNextTrack();
    }

    @Override
    public void seekToPreviousMediaItem() {
        if (navigationHandler != null) {
            navigationHandler.onSkipPrevious();
        }
    }

    @Override
    public void seekToPrevious() {
        seekToPreviousMediaItem();
    }

    @Override
    public void seekToNextMediaItem() {
        if (navigationHandler != null) {
            navigationHandler.onSkipNext();
        }
    }

    @Override
    public void seekToNext() {
        seekToNextMediaItem();
    }

    @Override
    public Commands getAvailableCommands() {
        return super.getAvailableCommands()
            .buildUpon()
            .add(COMMAND_SEEK_TO_NEXT)
            .add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(COMMAND_SEEK_TO_PREVIOUS)
            .add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .add(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .build();
    }
}
