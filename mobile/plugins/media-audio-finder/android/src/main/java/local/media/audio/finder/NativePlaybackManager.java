package local.media.audio.finder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.Manifest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.RemoteViews;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.CommandButton;
import androidx.media3.session.MediaSession;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionResult;

import com.getcapacitor.JSObject;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import local.media.audio.finder.plugin.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class NativePlaybackManager implements Player.Listener {
    static final String EVENT_TRACK_CHANGED = "nativePlaybackTrackChanged";
    static final String EVENT_STATE_CHANGED = "nativePlaybackStateChanged";
    static final String EVENT_LOOP_MODE_CHANGED = "nativePlaybackLoopModeChanged";
    static final String ACTION_CYCLE_LOOP = "local.media.audio.finder.CYCLE_LOOP";
    static final String ACTION_PREVIOUS = "local.media.audio.finder.PREV";
    static final String ACTION_NEXT = "local.media.audio.finder.NEXT";
    static final String ACTION_PLAY_PAUSE = "local.media.audio.finder.PLAY_PAUSE";
    static final String ACTION_NOTIFICATION_DISMISSED = "local.media.audio.finder.NOTIFICATION_DISMISSED";

    private static final int NOTIFICATION_ID = 87651;
    private static final String CHANNEL_ID = "media_playback_controls";

    private static NativePlaybackManager instance;

    interface LibraryPathResolver {
        String resolveLibraryAudioPath(String rawPath);
        File getLibraryRoot();
    }

    static synchronized NativePlaybackManager getInstance(Context context) {
        if (instance == null) {
            instance = new NativePlaybackManager(context.getApplicationContext());
        }
        return instance;
    }

    private final Context appContext;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final List<PlaybackQueueItem> queue = new ArrayList<>();

    private ExoPlayer player;
    private QueueAwarePlayer queuePlayer;
    private MediaSession mediaSession;
    private PlaybackForegroundService foregroundService;
    private Notification lastPostedNotification;
    private Notification pendingForegroundNotification;
    private MediaAudioFinderPlugin plugin;
    private LibraryPathResolver libraryPathResolver;

    private int currentIndex = -1;
    private String loopMode = "off";
    private String streamQuality = "medium";
    private long lastStreamQualityChangeMs = 0;
    private int stableHighBufferChecks = 0;
    private static final long STREAM_QUALITY_CHANGE_COOLDOWN_MS = 8000;
    private static final int STABLE_CHECKS_FOR_UPGRADE = 2;
    private static final long BUFFER_WATCHDOG_STARTUP_GRACE_MS = 15000;
    private static final long STREAM_SEEK_DEBOUNCE_MS = 400;
    private static final long STREAM_SEEK_RETRY_MS = 250;
    private static final int MAX_PLAY_SERVER_WAIT = 30;
    private static final long PLAY_SERVER_WAIT_MS = 1000;
    private boolean playbackSessionActive = false;
    private boolean foregroundActive = false;
    private Runnable bufferWatchdog;
    private Runnable progressTicker;
    private Runnable foregroundNotificationRetry;
    private int foregroundNotificationRetryCount = 0;
    private boolean foregroundServiceStartPending = false;
    private long trackStartedAtMs = 0;
    private long trackTransitionUntilMs = 0;
    private int lowBufferStrikes = 0;
    private boolean trackEndHandling = false;
    private long streamTimeOffsetMs = 0;
    private boolean preferHintDuration = false;
    private int cachedPlayerDurationIndex = -1;
    private long cachedPlayerDurationMs = -1;
    private final Map<String, Long> resolvedStreamDurationSecByUrl = new HashMap<>();
    private long pendingSeekMs = 0;
    private long lastKnownPositionMs = 0;
    private long pendingSeekTargetMs = -1;
    private boolean playbackError = false;
    private String lastPlaybackErrorMessage = "";
    private boolean seekInFlight = false;
    private long coalescedStreamSeekMs = -1;
    private long appliedStreamSeekMs = -1;
    private Runnable streamUrlSeekRunnable;
    private int streamSeekServerWaitAttempts = 0;
    private static final int MAX_STREAM_SEEK_SERVER_WAIT = 10;
    private static final long STREAM_SEEK_SERVER_WAIT_MS = 1000;
    private int playServerWaitAttempts = 0;
    private int pendingPlayIndex = -1;
    private long pendingPlaySeekMs = 0;
    private boolean pendingPlayEmitTrackChange = true;
    private Runnable pendingPlayRunnable;

    private NativePlaybackManager(Context appContext) {
        this.appContext = appContext;
    }

    void attachPlugin(MediaAudioFinderPlugin plugin, LibraryPathResolver resolver) {
        this.plugin = plugin;
        this.libraryPathResolver = resolver;
    }

    void configureQueue(JSONArray items, int index, String loopMode) {
        queue.clear();
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject raw = items.optJSONObject(i);
                PlaybackQueueItem item = PlaybackQueueItem.fromJson(raw);
                if (item != null) {
                    queue.add(item);
                    seedStreamDurationCache(item);
                }
            }
        }
        this.loopMode = normalizeLoopMode(loopMode);
        if (queue.isEmpty()) {
            currentIndex = -1;
            return;
        }
        currentIndex = Math.max(0, Math.min(index, queue.size() - 1));
        cachedPlayerDurationIndex = -1;
        cachedPlayerDurationMs = -1;
        preferHintDuration = true;
        handler.post(this::refreshNotification);
    }

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            handler.post(action);
        }
    }

    void setLoopMode(String loopMode) {
        runOnMain(() -> {
            this.loopMode = normalizeLoopMode(loopMode);
            emitLoopModeChanged();
            updateMediaSessionLayout();
            refreshNotification();
        });
    }

    void cycleLoopMode() {
        runOnMain(this::cycleLoopModeInternal);
    }

    static boolean isControlAction(@Nullable String action) {
        return ACTION_PREVIOUS.equals(action)
            || ACTION_NEXT.equals(action)
            || ACTION_PLAY_PAUSE.equals(action)
            || ACTION_CYCLE_LOOP.equals(action)
            || ACTION_NOTIFICATION_DISMISSED.equals(action);
    }

    void handleControlAction(String action) {
        runOnMain(() -> {
            switch (action) {
                case ACTION_PREVIOUS:
                    skipPreviousInternal();
                    break;
                case ACTION_NEXT:
                    skipNextInternal();
                    break;
                case ACTION_PLAY_PAUSE:
                    playPauseInternal();
                    break;
                case ACTION_CYCLE_LOOP:
                    cycleLoopModeInternal();
                    break;
                case ACTION_NOTIFICATION_DISMISSED:
                    onNotificationDismissed();
                    break;
                default:
                    break;
            }
        });
    }

    void playAtCurrentIndex() {
        runOnMain(() -> {
            if (currentIndex < 0 || currentIndex >= queue.size()) {
                return;
            }
            playAtIndexInternal(currentIndex, 0);
        });
    }

    void playAtIndex(int index) {
        playAtIndex(index, 0);
    }

    void playAtIndex(int index, long seekMs) {
        runOnMain(() -> playAtIndexInternal(index, seekMs, true));
    }

    private void playAtIndexInternal(int index, long seekMs) {
        playAtIndexInternal(index, seekMs, true);
    }

    private void playAtIndexInternal(int index, long seekMs, boolean emitTrackChangeEvent) {
        if (index < 0 || index >= queue.size()) {
            return;
        }
        ensurePlayer();
        if (index == currentIndex && seekMs <= 0 && player != null && !player.isPlaying()) {
            int state = player.getPlaybackState();
            if (state == Player.STATE_READY || state == Player.STATE_BUFFERING) {
                playbackSessionActive = true;
                startForegroundService();
                player.play();
                emitStateChanged();
                refreshNotification();
                return;
            }
        }
        PlaybackQueueItem item = queue.get(index);
        if ("stream".equals(item.type)) {
            queryMediaServerReachable(reachable -> {
                if (index < 0 || index >= queue.size()) {
                    return;
                }
                if (!reachable) {
                    currentIndex = index;
                    schedulePlayWhenServerReady(index, seekMs, emitTrackChangeEvent);
                    return;
                }
                startPlayAtIndex(index, seekMs, emitTrackChangeEvent);
            });
            return;
        }
        startPlayAtIndex(index, seekMs, emitTrackChangeEvent);
    }

    private void startPlayAtIndex(int index, long seekMs, boolean emitTrackChangeEvent) {
        if (index < 0 || index >= queue.size()) {
            return;
        }
        PlaybackQueueItem item = queue.get(index);
        cancelPendingPlay();
        int previousIndex = currentIndex;
        if (index != previousIndex) {
            streamTimeOffsetMs = 0;
            cancelPendingStreamUrlSeek();
            coalescedStreamSeekMs = -1;
            appliedStreamSeekMs = -1;
            pendingSeekTargetMs = -1;
            preferHintDuration = true;
            cachedPlayerDurationIndex = -1;
            cachedPlayerDurationMs = -1;
        }
        ensurePlayer();
        playbackSessionActive = true;
        stopBufferWatchdog();
        currentIndex = index;
        trackStartedAtMs = System.currentTimeMillis();
        trackTransitionUntilMs = trackStartedAtMs + 20000;
        lowBufferStrikes = 0;
        if (emitTrackChangeEvent && index != previousIndex) {
            emitTrackChanged(index, item);
        }
        Runnable beginPlayback = () -> startPlayerForIndex(index, seekMs, item);
        refreshNotification();
        startForegroundService();
        if ("stream".equals(item.type) && needsStreamMetadataBeforePlay(item)) {
            prefetchStreamMetadataBeforePlay(index, item, beginPlayback);
            return;
        }
        applyCachedStreamDuration(item);
        beginPlayback.run();
    }

    private void seedStreamDurationCache(PlaybackQueueItem item) {
        if (item == null || !"stream".equals(item.type)) {
            return;
        }
        if (item.webpageUrl == null || item.webpageUrl.isEmpty()) {
            return;
        }
        if (item.durationSec > 0) {
            resolvedStreamDurationSecByUrl.put(item.webpageUrl, item.durationSec);
        }
    }

    private boolean needsStreamMetadataBeforePlay(PlaybackQueueItem item) {
        if (item == null || !"stream".equals(item.type)) {
            return false;
        }
        if (item.webpageUrl == null || item.webpageUrl.isEmpty()) {
            return false;
        }
        Long cached = resolvedStreamDurationSecByUrl.get(item.webpageUrl);
        return cached == null || cached <= 0;
    }

    private void applyCachedStreamDuration(PlaybackQueueItem item) {
        if (item == null || item.durationSec > 0) {
            return;
        }
        if (item.webpageUrl == null || item.webpageUrl.isEmpty()) {
            return;
        }
        Long cached = resolvedStreamDurationSecByUrl.get(item.webpageUrl);
        if (cached != null && cached > 0) {
            item.updateDurationSec(cached);
        }
    }

    private void prefetchStreamMetadataBeforePlay(int index, PlaybackQueueItem item, Runnable beginPlayback) {
        PlaybackLog.info("awaiting stream metadata before play", PlaybackLog.with("index", index));
        final String webpageUrl = item.webpageUrl;
        networkExecutor.execute(() -> {
            long durationSec = fetchStreamDurationFromServer(webpageUrl);
            handler.post(() -> {
                if (currentIndex != index || index < 0 || index >= queue.size()) {
                    return;
                }
                PlaybackQueueItem current = queue.get(index);
                if (!webpageUrl.equals(current.webpageUrl)) {
                    return;
                }
                if (durationSec > 0) {
                    resolvedStreamDurationSecByUrl.put(webpageUrl, durationSec);
                    current.updateDurationSec(durationSec);
                    PlaybackLog.info(
                        "stream metadata ready before play",
                        PlaybackLog.put(PlaybackLog.with("durationSec", durationSec), "index", index)
                    );
                    refreshNotification();
                    emitStateChanged();
                } else {
                    PlaybackLog.warn(
                        "stream metadata unavailable before play",
                        PlaybackLog.with("index", index)
                    );
                }
                beginPlayback.run();
            });
        });
    }

    private void startPlayerForIndex(int index, long seekMs, PlaybackQueueItem item) {
        try {
            MediaItem mediaItem = buildMediaItemForPlayback(item, seekMs);
            JSONObject log = PlaybackLog.fields();
            PlaybackLog.put(log, "index", index);
            PlaybackLog.put(log, "seekMs", seekMs);
            PlaybackLog.put(log, "type", item.type);
            PlaybackLog.put(log, "offsetMs", streamTimeOffsetMs);
            PlaybackLog.put(log, "urlSeek", seekMs > 0 && "stream".equals(item.type));
            PlaybackLog.info("playAtIndex", log);
            if (player.getPlaybackState() == Player.STATE_ENDED) {
                player.stop();
                player.clearMediaItems();
            }
            player.setMediaItem(mediaItem);
            player.prepare();
            player.play();
            lastKnownPositionMs = seekMs > 0 ? seekMs : 0;
            if (seekMs > 0) {
                pendingSeekTargetMs = seekMs;
            }
            seekInFlight = seekMs > 0 && "stream".equals(item.type);
            playbackError = false;
            lastPlaybackErrorMessage = "";
            emitStateChanged();
            startBufferWatchdog(item);
            refreshNotification();
        } catch (Exception e) {
            playbackError = true;
            seekInFlight = false;
            PlaybackLog.error("playAtIndex failed", PlaybackLog.with("index", index), e);
            emitStateChanged();
        }
    }

    private MediaItem buildMediaItemForPlayback(PlaybackQueueItem item, long seekMs) throws IOException {
        MediaMetadata metadata = new MediaMetadata.Builder()
            .setTitle(item.title != null ? item.title : "Audio")
            .setArtist(item.artist != null ? item.artist : "Media Audio Finder")
            .build();

        if ("stream".equals(item.type) && seekMs > 0) {
            streamTimeOffsetMs = seekMs;
            String url = resolvePlayableUrl(item, seekMs);
            url += "&_ts=" + System.currentTimeMillis();
            PlaybackLog.info("stream url seek", PlaybackLog.with("seekMs", seekMs));
            return new MediaItem.Builder()
                .setUri(Uri.parse(url))
                .setMediaMetadata(metadata)
                .build();
        }

        streamTimeOffsetMs = 0;
        pendingSeekMs = 0;
        String url = resolvePlayableUrl(item, 0);
        MediaItem.Builder builder = new MediaItem.Builder()
            .setUri(Uri.parse(url))
            .setMediaMetadata(metadata);
        if (seekMs > 0 && !"stream".equals(item.type)) {
            pendingSeekMs = seekMs;
        }
        return builder.build();
    }

    void playPause() {
        runOnMain(this::playPauseInternal);
    }

    private void playPauseInternal() {
        ensurePlayer();
        if (player.isPlaying()) {
            lastKnownPositionMs = getReportedPositionMs();
            player.pause();
            emitStateChanged();
            return;
        }
        startForegroundService();
        int state = player.getPlaybackState();
        if (currentIndex >= 0 && currentIndex < queue.size()
            && (state == Player.STATE_IDLE || state == Player.STATE_ENDED)) {
            long resumeMs = Math.max(0, lastKnownPositionMs);
            PlaybackLog.info("resume playback", PlaybackLog.with("resumeMs", resumeMs));
            playAtIndexInternal(currentIndex, resumeMs, false);
            emitStateChanged();
            return;
        }
        player.play();
        emitStateChanged();
    }

    void skipNext() {
        runOnMain(this::skipNextInternal);
    }

    private void skipNextInternal() {
        if (queue.isEmpty()) {
            return;
        }
        if (currentIndex < queue.size() - 1) {
            playAtIndexInternal(currentIndex + 1, 0);
            return;
        }
        playAtIndexInternal(0, 0);
    }

    private boolean hasPreviousTrack() {
        if (queue.size() <= 1) {
            return player != null && getReportedPositionMs() > 3000;
        }
        return true;
    }

    private boolean hasNextTrack() {
        return queue.size() > 1 || (currentIndex >= 0 && currentIndex < queue.size() - 1);
    }

    void skipPrevious() {
        runOnMain(this::skipPreviousInternal);
    }

    private void skipPreviousInternal() {
        if (player != null && getReportedPositionMs() > 3000) {
            if (currentIndex >= 0 && currentIndex < queue.size()) {
                PlaybackQueueItem item = queue.get(currentIndex);
                if ("stream".equals(item.type)) {
                    playAtIndexInternal(currentIndex, 0, false);
                    return;
                }
            }
            streamTimeOffsetMs = 0;
            player.seekTo(0);
            player.play();
            emitStateChanged();
            return;
        }
        if (queue.isEmpty()) {
            return;
        }
        if (currentIndex > 0) {
            playAtIndexInternal(currentIndex - 1, 0);
            return;
        }
        playAtIndexInternal(queue.size() - 1, 0);
    }

    void seekTo(long positionMs) {
        runOnMain(() -> {
            if (player == null || currentIndex < 0 || currentIndex >= queue.size()) {
                PlaybackLog.warn("seek ignored", PlaybackLog.with("reason", "no player or index"));
                return;
            }
            long targetMs = Math.max(0, positionMs);
            PlaybackQueueItem item = queue.get(currentIndex);
            JSONObject log = PlaybackLog.fields();
            PlaybackLog.put(log, "targetMs", targetMs);
            PlaybackLog.put(log, "index", currentIndex);
            PlaybackLog.put(log, "type", item.type);
            PlaybackLog.put(log, "playerDurationMs", getPlayerDurationMs());
            PlaybackLog.put(log, "offsetMs", streamTimeOffsetMs);
            PlaybackLog.put(log, "playerPosMs", player.getCurrentPosition());
            if ("local".equals(item.type)) {
                streamTimeOffsetMs = 0;
                player.seekTo(targetMs);
                player.play();
                lastKnownPositionMs = targetMs;
                PlaybackLog.info("seek local", log);
                emitStateChanged();
                return;
            }
            if ("stream".equals(item.type)) {
                coalescedStreamSeekMs = targetMs;
                pendingSeekTargetMs = targetMs;
                long playerDuration = getPlayerDurationMs();
                if (playerDuration > 0 && targetMs <= playerDuration && !seekInFlight) {
                    cancelPendingStreamUrlSeek();
                    streamTimeOffsetMs = 0;
                    player.seekTo(targetMs);
                    player.play();
                    lastKnownPositionMs = targetMs;
                    appliedStreamSeekMs = targetMs;
                    seekInFlight = false;
                    playbackError = false;
                    lastPlaybackErrorMessage = "";
                    PlaybackLog.info("seek stream in-player", log);
                    emitStateChanged();
                    return;
                }
                PlaybackLog.info("seek stream debounce", log);
                scheduleStreamUrlSeek();
                return;
            }
            streamTimeOffsetMs = 0;
            player.seekTo(targetMs);
            player.play();
            lastKnownPositionMs = targetMs;
            PlaybackLog.info("seek other", log);
            emitStateChanged();
        });
    }

    private void cancelPendingPlay() {
        pendingPlayIndex = -1;
        playServerWaitAttempts = 0;
        if (pendingPlayRunnable != null) {
            handler.removeCallbacks(pendingPlayRunnable);
        }
    }

    private void schedulePlayWhenServerReady(int index, long seekMs, boolean emitTrackChangeEvent) {
        pendingPlayIndex = index;
        pendingPlaySeekMs = seekMs;
        pendingPlayEmitTrackChange = emitTrackChangeEvent;
        playServerWaitAttempts = 0;
        playbackError = false;
        lastPlaybackErrorMessage = "";
        if (pendingPlayRunnable == null) {
            pendingPlayRunnable = this::runPendingPlayWhenServerReady;
        }
        handler.removeCallbacks(pendingPlayRunnable);
        handler.postDelayed(pendingPlayRunnable, 500);
        PlaybackLog.info("play waiting for media server", PlaybackLog.with("index", index));
    }

    private void runPendingPlayWhenServerReady() {
        if (pendingPlayIndex < 0 || pendingPlayIndex >= queue.size()) {
            return;
        }
        final int waitIndex = pendingPlayIndex;
        queryMediaServerReachable(reachable -> {
            if (pendingPlayIndex < 0 || pendingPlayIndex != waitIndex) {
                return;
            }
            if (!reachable) {
                playServerWaitAttempts += 1;
                if (playServerWaitAttempts <= MAX_PLAY_SERVER_WAIT) {
                    PlaybackLog.warn(
                        "play waiting for media server",
                        PlaybackLog.with("attempt", playServerWaitAttempts)
                    );
                    handler.postDelayed(pendingPlayRunnable, PLAY_SERVER_WAIT_MS);
                    return;
                }
                pendingPlayIndex = -1;
                playServerWaitAttempts = 0;
                playbackError = true;
                lastPlaybackErrorMessage = "Debian メディアサーバー (127.0.0.1:8765) に接続できません。"
                    + " Terminal を開き systemctl --user restart media-audio-finder を実行してください。";
                PlaybackLog.error("play aborted: media server unavailable", PlaybackLog.fields());
                emitStateChanged();
                return;
            }
            playServerWaitAttempts = 0;
            int index = pendingPlayIndex;
            long seekMs = pendingPlaySeekMs;
            boolean emitTrackChange = pendingPlayEmitTrackChange;
            pendingPlayIndex = -1;
            startPlayAtIndex(index, seekMs, emitTrackChange);
        });
    }

    private void cancelPendingStreamUrlSeek() {
        coalescedStreamSeekMs = -1;
        appliedStreamSeekMs = -1;
        if (streamUrlSeekRunnable != null) {
            handler.removeCallbacks(streamUrlSeekRunnable);
        }
    }

    private void scheduleStreamUrlSeek() {
        if (streamUrlSeekRunnable == null) {
            streamUrlSeekRunnable = this::runStreamUrlSeek;
        }
        streamSeekServerWaitAttempts = 0;
        handler.removeCallbacks(streamUrlSeekRunnable);
        handler.postDelayed(streamUrlSeekRunnable, STREAM_SEEK_DEBOUNCE_MS);
    }

    private void runStreamUrlSeek() {
        if (player == null || currentIndex < 0 || currentIndex >= queue.size()) {
            return;
        }
        long targetMs = coalescedStreamSeekMs;
        if (targetMs < 0) {
            return;
        }
        if (seekInFlight) {
            handler.postDelayed(streamUrlSeekRunnable, STREAM_SEEK_RETRY_MS);
            return;
        }
        final long seekTargetMs = targetMs;
        queryMediaServerReachable(reachable -> {
            if (coalescedStreamSeekMs != seekTargetMs) {
                return;
            }
            if (!reachable) {
                streamSeekServerWaitAttempts += 1;
                if (streamSeekServerWaitAttempts <= MAX_STREAM_SEEK_SERVER_WAIT) {
                    PlaybackLog.warn(
                        "seek waiting for media server",
                        PlaybackLog.with("attempt", streamSeekServerWaitAttempts)
                    );
                    handler.postDelayed(streamUrlSeekRunnable, STREAM_SEEK_SERVER_WAIT_MS);
                    return;
                }
                playbackError = true;
                seekInFlight = false;
                streamSeekServerWaitAttempts = 0;
                lastPlaybackErrorMessage = "Debian メディアサーバー (127.0.0.1:8765) に接続できません。"
                    + " Terminal を開き systemctl --user restart media-audio-finder を実行してください。";
                PlaybackLog.error("seek aborted: media server unavailable", PlaybackLog.with("targetMs", seekTargetMs));
                emitStateChanged();
                return;
            }
            streamSeekServerWaitAttempts = 0;
            appliedStreamSeekMs = seekTargetMs;
            PlaybackLog.info("seek stream url reconnect", PlaybackLog.with("targetMs", seekTargetMs));
            startPlayAtIndex(currentIndex, seekTargetMs, false);
        });
    }

    private void queryMediaServerReachable(Consumer<Boolean> callback) {
        networkExecutor.execute(() -> {
            MediaServerBootstrap bootstrap = MediaServerBootstrap.getInstance(appContext);
            boolean reachable = bootstrap.probeHealth();
            if (!reachable) {
                bootstrap.launchTerminalIfNeeded();
            }
            handler.post(() -> callback.accept(reachable));
        });
    }

    void deliverPlaybackState(Consumer<JSObject> consumer) {
        runOnMain(() -> consumer.accept(buildPlaybackState()));
    }

    JSObject getPlaybackState() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return buildPlaybackState();
        }
        final JSObject[] result = new JSObject[1];
        final CountDownLatch latch = new CountDownLatch(1);
        handler.post(() -> {
            try {
                result[0] = buildPlaybackState();
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result[0] != null ? result[0] : buildPlaybackStateWithoutPlayer();
    }

    private JSObject buildPlaybackStateWithoutPlayer() {
        JSObject payload = new JSObject();
        payload.put("index", currentIndex);
        payload.put("loopMode", loopMode);
        payload.put("streamQuality", streamQuality);
        payload.put("playing", false);
        payload.put("positionMs", 0);
        payload.put("durationMs", -1);
        return payload;
    }

    private JSObject buildPlaybackState() {
        JSObject payload = new JSObject();
        payload.put("index", currentIndex);
        payload.put("loopMode", loopMode);
        payload.put("streamQuality", streamQuality);
        if (player == null) {
            payload.put("playing", false);
            payload.put("buffering", false);
            payload.put("error", playbackError);
            if (playbackError && !lastPlaybackErrorMessage.isEmpty()) {
                payload.put("errorMessage", lastPlaybackErrorMessage);
            }
            payload.put("positionMs", 0);
            payload.put("durationMs", -1);
            return payload;
        }
        payload.put("playing", player.isPlaying());
        payload.put("buffering", player.getPlaybackState() == Player.STATE_BUFFERING);
        payload.put("error", playbackError);
        if (playbackError && !lastPlaybackErrorMessage.isEmpty()) {
            payload.put("errorMessage", lastPlaybackErrorMessage);
        }
        payload.put("positionMs", getReportedPositionMs());
        payload.put("durationMs", resolveDurationMs());
        payload.put("hintDurationMs", getHintDurationMs());
        if (currentIndex >= 0 && currentIndex < queue.size()) {
            PlaybackQueueItem item = queue.get(currentIndex);
            payload.put("title", item.title);
            payload.put("artist", item.artist);
        }
        return payload;
    }

    void stopPlayback() {
        runOnMain(() -> {
            cancelPendingStreamUrlSeek();
            cancelPendingPlay();
            stopBufferWatchdog();
            if (player != null) {
                player.stop();
                player.clearMediaItems();
            }
            stopForegroundService();
            playbackSessionActive = false;
            emitStateChanged();
        });
    }

    void bindForegroundService(PlaybackForegroundService service) {
        foregroundService = service;
        foregroundServiceStartPending = false;
        if (foregroundNotificationRetry != null) {
            handler.removeCallbacks(foregroundNotificationRetry);
            foregroundNotificationRetry = null;
        }
        foregroundNotificationRetryCount = 0;
        PlaybackLog.info("foreground service bound", PlaybackLog.fields());
        ensureForegroundNotification(service);
    }

    void bindForegroundServiceSync(PlaybackForegroundService service) {
        bindForegroundService(service);
    }

    boolean isPlaybackSessionActive() {
        return playbackSessionActive;
    }

    void onNotificationDismissed() {
        if (!playbackSessionActive || currentIndex < 0) {
            return;
        }
        PlaybackLog.info("notification dismissed, restoring", PlaybackLog.fields());
        handler.post(() -> {
            if (foregroundService != null) {
                ensureForegroundNotification(foregroundService);
                return;
            }
            startForegroundService();
        });
    }

    void ensureForegroundNotification(PlaybackForegroundService service) {
        foregroundService = service;
        Notification notification = pendingForegroundNotification;
        if (notification == null) {
            notification = playbackSessionActive && currentIndex >= 0
                ? buildMediaNotification()
                : buildPlaceholderForegroundNotification();
        } else {
            pendingForegroundNotification = null;
        }
        lastPostedNotification = notification;
        try {
            publishNotification(service, notification);
        } catch (Exception e) {
            PlaybackLog.error("startForeground failed", PlaybackLog.fields(), e);
            Notification fallback = buildFallbackNotification("Media Audio Finder", "");
            lastPostedNotification = fallback;
            publishNotification(service, fallback);
        }
    }

    private boolean canShowNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true;
        }
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED;
    }

    private void publishNotification(PlaybackForegroundService service, Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            service.startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            );
        } else {
            service.startForeground(NOTIFICATION_ID, notification);
        }
        foregroundActive = true;
        PlaybackLog.info("startForeground ok", PlaybackLog.fields());
        if (!canShowNotifications()) {
            PlaybackLog.warn(
                "POST_NOTIFICATIONS not granted; notification may be hidden",
                PlaybackLog.fields()
            );
            return;
        }
        NotificationManager nm = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, notification);
            PlaybackLog.info("notification notify ok", PlaybackLog.fields());
        }
    }

    void refreshNotificationIfActive() {
        if (playbackSessionActive && currentIndex >= 0) {
            handler.post(this::refreshNotification);
        }
    }

    void detachForegroundService(PlaybackForegroundService service) {
        runOnMain(() -> {
            if (foregroundService == service) {
                foregroundService = null;
                if (foregroundActive) {
                    service.stopForeground(Service.STOP_FOREGROUND_REMOVE);
                    foregroundActive = false;
                }
            }
        });
    }

    boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    @Override
    public void onPlaybackStateChanged(int playbackState) {
        if (playbackState == Player.STATE_READY && pendingSeekMs > 0) {
            PlaybackQueueItem item = currentIndex >= 0 && currentIndex < queue.size()
                ? queue.get(currentIndex) : null;
            if (item != null && !"stream".equals(item.type)) {
                long target = pendingSeekMs;
                pendingSeekMs = 0;
                player.seekTo(target);
                JSONObject log = PlaybackLog.fields();
                PlaybackLog.put(log, "targetMs", target);
                PlaybackLog.put(log, "actualMs", player.getCurrentPosition());
                PlaybackLog.info("seek applied on ready", log);
            }
        }
        if (playbackState == Player.STATE_READY && streamTimeOffsetMs > 0) {
            JSONObject log = PlaybackLog.fields();
            PlaybackLog.put(log, "offsetMs", streamTimeOffsetMs);
            PlaybackLog.put(log, "playerPosMs", player.getCurrentPosition());
            PlaybackLog.put(log, "reportedMs", getReportedPositionMs());
            PlaybackLog.info("stream seek ready", log);
        }
        if (playbackState == Player.STATE_ENDED) {
            handleTrackEnded();
        }
        if (playbackState == Player.STATE_READY) {
            if (player.isPlaying() || player.getPlaybackState() == Player.STATE_BUFFERING) {
                seekInFlight = false;
                playbackError = false;
                lastPlaybackErrorMessage = "";
                streamSeekServerWaitAttempts = 0;
                if (coalescedStreamSeekMs >= 0
                    && appliedStreamSeekMs >= 0
                    && Math.abs(coalescedStreamSeekMs - appliedStreamSeekMs) > 1000) {
                    scheduleStreamUrlSeek();
                } else {
                    coalescedStreamSeekMs = -1;
                    pendingSeekTargetMs = -1;
                }
            }
            updatePreferHintDuration();
            updateCachedPlayerDuration();
            refreshNotification();
        }
        emitStateChanged();
    }

    @Override
    public void onEvents(Player player, Player.Events events) {
        if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED)) {
            updateCachedPlayerDuration();
            refreshNotification();
        }
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
        if (isPlaying || (player != null && player.getPlaybackState() == Player.STATE_BUFFERING)) {
            startProgressTicker();
        } else {
            stopProgressTicker();
        }
        emitStateChanged();
    }

    @Override
    public void onPlayerError(PlaybackException error) {
        if (pendingSeekTargetMs >= 0) {
            lastKnownPositionMs = pendingSeekTargetMs;
        } else {
            long reported = getReportedPositionMs();
            if (reported > 0) {
                lastKnownPositionMs = reported;
            }
        }
        seekInFlight = false;
        playbackError = true;
        JSONObject log = PlaybackLog.fields();
        PlaybackLog.put(log, "positionMs", lastKnownPositionMs);
        PlaybackLog.put(log, "pendingSeekMs", pendingSeekTargetMs);
        PlaybackLog.put(log, "code", error.errorCode);
        PlaybackLog.error("player error", log, error);
        emitStateChanged();
    }

    private void startProgressTicker() {
        stopProgressTicker();
        progressTicker = () -> {
            updateCachedPlayerDuration();
            emitStateChanged();
            refreshNotification();
            if (player != null
                && (player.isPlaying() || player.getPlaybackState() == Player.STATE_BUFFERING)) {
                handler.postDelayed(progressTicker, 500);
            }
        };
        handler.postDelayed(progressTicker, 500);
    }

    private void stopProgressTicker() {
        if (progressTicker != null) {
            handler.removeCallbacks(progressTicker);
            progressTicker = null;
        }
    }

    private void handleTrackEnded() {
        if (trackEndHandling) {
            return;
        }
        trackEndHandling = true;
        handler.post(() -> {
            try {
                handleTrackEndedInternal();
            } finally {
                trackEndHandling = false;
            }
        });
    }

    private void handleTrackEndedInternal() {
        if (player == null || player.getPlaybackState() != Player.STATE_ENDED) {
            return;
        }
        if (queue.isEmpty() || currentIndex < 0) {
            stopPlayback();
            return;
        }
        if ("single".equals(loopMode)) {
            playAtIndexInternal(currentIndex, 0);
            return;
        }
        int next = currentIndex + 1;
        if ("playlist".equals(loopMode)) {
            if (next >= queue.size()) {
                next = 0;
            }
            playAtIndexInternal(next, 0);
            return;
        }
        if (next < queue.size()) {
            playAtIndexInternal(next, 0);
            return;
        }
        stopPlayback();
    }

    private void ensurePlayer() {
        if (player != null) {
            return;
        }
        ensureNotificationChannel();
        player = new ExoPlayer.Builder(appContext)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build();
        player.addListener(this);
        queuePlayer = new QueueAwarePlayer(player);
        queuePlayer.setNavigationHandler(new QueueAwarePlayer.NavigationHandler() {
            @Override
            public boolean hasPreviousTrack() {
                return NativePlaybackManager.this.hasPreviousTrack();
            }

            @Override
            public boolean hasNextTrack() {
                return NativePlaybackManager.this.hasNextTrack();
            }

            @Override
            public void onSkipPrevious() {
                skipPreviousInternal();
            }

            @Override
            public void onSkipNext() {
                skipNextInternal();
            }
        });

        mediaSession = new MediaSession.Builder(appContext, queuePlayer)
            .setId("MediaAudioFinderPlayback")
            .setSessionActivity(launchPendingIntent())
            .setPeriodicPositionUpdateEnabled(true)
            .setCallback(new MediaSession.Callback() {
                @Override
                public ListenableFuture<SessionResult> onCustomCommand(
                    MediaSession session,
                    MediaSession.ControllerInfo controller,
                    SessionCommand customCommand,
                    Bundle args
                ) {
                    if (ACTION_CYCLE_LOOP.equals(customCommand.customAction)) {
                        cycleLoopModeInternal();
                        return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
                    }
                    return MediaSession.Callback.super.onCustomCommand(
                        session,
                        controller,
                        customCommand,
                        args
                    );
                }
            })
            .build();
        updateMediaSessionLayout();
    }

    private void updateMediaSessionLayout() {
        if (mediaSession == null) {
            return;
        }
        int loopIcon = CommandButton.ICON_REPEAT_OFF;
        if ("single".equals(loopMode)) {
            loopIcon = CommandButton.ICON_REPEAT_ONE;
        } else if ("playlist".equals(loopMode)) {
            loopIcon = CommandButton.ICON_REPEAT_ALL;
        }
        CommandButton loopButton = new CommandButton.Builder(loopIcon)
            .setDisplayName(loopModeLabel(loopMode))
            .setSessionCommand(new SessionCommand(ACTION_CYCLE_LOOP, Bundle.EMPTY))
            .build();
        mediaSession.setCustomLayout(ImmutableList.of(loopButton));
    }

    private long getReportedPositionMs() {
        if (player == null) {
            return 0;
        }
        return Math.max(0, player.getCurrentPosition() + streamTimeOffsetMs);
    }

    private void cycleLoopModeInternal() {
        switch (loopMode) {
            case "off":
                loopMode = "single";
                break;
            case "single":
                loopMode = "playlist";
                break;
            default:
                loopMode = "off";
                break;
        }
        emitLoopModeChanged();
        updateMediaSessionLayout();
        refreshNotification();
    }

    private PendingIntent controlPendingIntent(String action, int requestCode) {
        Intent intent = new Intent(appContext, PlaybackForegroundService.class);
        intent.setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return PendingIntent.getForegroundService(appContext, requestCode, intent, flags);
        }
        return PendingIntent.getService(appContext, requestCode, intent, flags);
    }

    private Notification buildMediaNotification() {
        CharSequence title = "Media Audio Finder";
        CharSequence artist = "";
        try {
            RemoteViews views = new RemoteViews(appContext.getPackageName(), R.layout.notification_media_controls);
            if (currentIndex >= 0 && currentIndex < queue.size()) {
                PlaybackQueueItem item = queue.get(currentIndex);
                if (item.title != null && !item.title.isEmpty()) {
                    title = item.title;
                }
                if (item.artist != null && !item.artist.isEmpty()) {
                    artist = item.artist;
                }
            }

            views.setTextViewText(R.id.notif_title, title);
            if (artist != null && artist.length() > 0) {
                views.setTextViewText(R.id.notif_artist, artist);
                views.setViewVisibility(R.id.notif_artist, android.view.View.VISIBLE);
            } else {
                views.setViewVisibility(R.id.notif_artist, android.view.View.GONE);
            }

            int progress = 0;
            long durationMs = resolveDurationMs();
            long positionMs = getReportedPositionMs();
            boolean showSeekBar = shouldShowNotificationSeekBar();
            if (showSeekBar && durationMs > 0) {
                progress = (int) Math.min(
                    1000L,
                    Math.max(0L, (1000L * positionMs) / durationMs)
                );
                views.setViewVisibility(R.id.notif_progress, android.view.View.VISIBLE);
                views.setViewVisibility(R.id.notif_time, android.view.View.VISIBLE);
                views.setProgressBar(R.id.notif_progress, 1000, progress, false);
                views.setTextViewText(R.id.notif_time, formatNotificationTime(positionMs, durationMs));
            } else {
                views.setViewVisibility(R.id.notif_progress, android.view.View.GONE);
                views.setViewVisibility(R.id.notif_time, android.view.View.GONE);
            }

            int playIcon = android.R.drawable.ic_media_play;
            if (player != null && player.isPlaying()) {
                playIcon = android.R.drawable.ic_media_pause;
            }

            int loopIcon = android.R.drawable.ic_menu_rotate;
            String loopActionLabel = loopModeLabel(loopMode);

            boolean ongoing = shouldKeepNotificationOngoing();

            return buildNotificationFromViews(
                views,
                title,
                artist,
                playIcon,
                loopIcon,
                loopActionLabel,
                ongoing,
                progress,
                showSeekBar
            );
        } catch (Exception e) {
            PlaybackLog.error("buildMediaNotification failed", PlaybackLog.with("title", title), e);
            return buildFallbackNotification(title, artist);
        }
    }

    private boolean shouldKeepNotificationOngoing() {
        return playbackSessionActive && currentIndex >= 0 && currentIndex < queue.size();
    }

    private boolean shouldShowNotificationSeekBar() {
        if (currentIndex < 0 || currentIndex >= queue.size()) {
            return false;
        }
        if (resolveDurationMs() <= 0) {
            return false;
        }
        PlaybackQueueItem item = queue.get(currentIndex);
        if (!"stream".equals(item.type)) {
            return true;
        }
        if (player == null) {
            return false;
        }
        int state = player.getPlaybackState();
        // Hide while waiting for prepare; show once buffering/ready (duration must be known).
        return state == Player.STATE_BUFFERING || state == Player.STATE_READY;
    }

    private Notification buildNotificationFromViews(
        RemoteViews views,
        CharSequence title,
        CharSequence artist,
        int playIcon,
        int loopIcon,
        String loopActionLabel,
        boolean ongoing,
        int progress,
        boolean showSeekBar
    ) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(artist != null ? artist : "")
            .setCustomContentView(views)
            .setCustomBigContentView(views)
            .setCustomHeadsUpContentView(views)
            .setContentIntent(launchPendingIntent())
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setOngoing(ongoing)
            .setLocalOnly(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT);
        if (showSeekBar) {
            builder.setProgress(1000, progress, false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        }
        builder
            .addAction(android.R.drawable.ic_media_previous, "前", controlPendingIntent(ACTION_PREVIOUS, 21))
            .addAction(playIcon, "再生", controlPendingIntent(ACTION_PLAY_PAUSE, 22))
            .addAction(loopIcon, loopActionLabel, controlPendingIntent(ACTION_CYCLE_LOOP, 24))
            .addAction(android.R.drawable.ic_media_next, "次", controlPendingIntent(ACTION_NEXT, 23));

        MediaStyle style = new MediaStyle().setShowActionsInCompactView(0, 1, 2);
        if (mediaSession != null) {
            style.setMediaSession(mediaSession.getSessionCompatToken());
        }
        builder.setStyle(style);

        Notification notification = builder.build();
        if (ongoing) {
            notification.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;
        }
        return notification;
    }

    private Notification buildPlaceholderForegroundNotification() {
        RemoteViews views = new RemoteViews(appContext.getPackageName(), R.layout.notification_media_controls);
        views.setTextViewText(R.id.notif_title, "Media Audio Finder");
        views.setViewVisibility(R.id.notif_artist, android.view.View.GONE);
        views.setViewVisibility(R.id.notif_progress, android.view.View.GONE);
        views.setViewVisibility(R.id.notif_time, android.view.View.GONE);
        return buildNotificationFromViews(
            views,
            "Media Audio Finder",
            "",
            android.R.drawable.ic_media_play,
            android.R.drawable.ic_menu_rotate,
            loopModeLabel(loopMode),
            true,
            0,
            false
        );
    }

    private static String formatNotificationTime(long positionMs, long durationMs) {
        return formatClock(positionMs) + " / " + (durationMs > 0 ? formatClock(durationMs) : "--:--");
    }

    private static String formatClock(long ms) {
        if (ms < 0) {
            ms = 0;
        }
        long totalSec = ms / 1000L;
        long hours = totalSec / 3600L;
        long minutes = (totalSec % 3600L) / 60L;
        long seconds = totalSec % 60L;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    private Notification buildFallbackNotification(CharSequence title, CharSequence artist) {
        boolean ongoing = shouldKeepNotificationOngoing();
        NotificationCompat.Builder builder = new NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(launchPendingIntent())
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setAutoCancel(false)
            .setOngoing(ongoing)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        MediaStyle style = new MediaStyle();
        if (mediaSession != null) {
            style.setMediaSession(mediaSession.getSessionCompatToken());
        }
        builder.setStyle(style);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        }
        Notification notification = builder.build();
        if (ongoing) {
            notification.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;
        }
        return notification;
    }

    private long getPlayerDurationMs() {
        if (player == null) {
            return -1;
        }
        long durationMs = player.getDuration();
        return durationMs > 0 ? durationMs : -1;
    }

    private long getHintDurationMs() {
        if (currentIndex >= 0 && currentIndex < queue.size()) {
            PlaybackQueueItem item = queue.get(currentIndex);
            if (item.durationSec > 0) {
                return item.durationSec * 1000L;
            }
            if ("stream".equals(item.type) && item.webpageUrl != null && !item.webpageUrl.isEmpty()) {
                Long fetched = resolvedStreamDurationSecByUrl.get(item.webpageUrl);
                if (fetched != null && fetched > 0) {
                    return fetched * 1000L;
                }
            }
        }
        return -1;
    }

    private long resolveDurationMs() {
        long hintMs = getHintDurationMs();
        if (hintMs > 0) {
            return hintMs;
        }
        long playerMs = getPlayerDurationMs();
        if (playerMs > 0) {
            if (preferHintDuration && hintMs > 0 && cachedPlayerDurationIndex != currentIndex) {
                return -1;
            }
            return playerMs;
        }
        if (cachedPlayerDurationIndex == currentIndex && cachedPlayerDurationMs > 0) {
            return cachedPlayerDurationMs;
        }
        return -1;
    }

    private long fetchStreamDurationFromServer(String webpageUrl) {
        HttpURLConnection connection = null;
        try {
            String requestUrl = "http://127.0.0.1:" + MediaAudioFinderPlugin.MEDIA_SERVER_PORT
                + "/api/metadata?url="
                + URLEncoder.encode(webpageUrl, StandardCharsets.UTF_8.name());
            connection = (HttpURLConnection) new URL(requestUrl).openConnection();
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(20000);
            connection.setRequestMethod("GET");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return -1;
            }
            String body = readHttpResponseBody(connection);
            JSONObject json = new JSONObject(body);
            if (!json.optBoolean("ok", false)) {
                return -1;
            }
            long durationSec = json.optLong("durationSec", -1);
            if (durationSec <= 0 && json.has("durationSec")) {
                durationSec = (long) json.optDouble("durationSec", -1);
            }
            return durationSec > 0 ? durationSec : -1;
        } catch (Exception e) {
            PlaybackLog.warn(
                "stream duration fetch failed",
                PlaybackLog.put(PlaybackLog.fields(), "error", String.valueOf(e.getMessage()))
            );
            return -1;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readHttpResponseBody(HttpURLConnection connection) throws IOException {
        java.io.InputStream input = connection.getInputStream();
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = input.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toString(StandardCharsets.UTF_8.name());
    }

    private void updateCachedPlayerDuration() {
        if (currentIndex < 0 || player == null) {
            return;
        }
        long playerMs = getPlayerDurationMs();
        if (playerMs <= 0) {
            return;
        }
        cachedPlayerDurationIndex = currentIndex;
        cachedPlayerDurationMs = playerMs;
    }

    private void updatePreferHintDuration() {
        if (!preferHintDuration) {
            return;
        }
        long hintMs = getHintDurationMs();
        if (hintMs <= 0) {
            if (cachedPlayerDurationIndex == currentIndex && cachedPlayerDurationMs > 0) {
                preferHintDuration = false;
            }
            return;
        }
        if (streamTimeOffsetMs > 0) {
            return;
        }
        long playerMs = getPlayerDurationMs();
        if (playerMs <= 0) {
            return;
        }
        if (playerMs >= hintMs - 3000) {
            preferHintDuration = false;
        }
    }

    private void postMediaNotification() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(this::postMediaNotification);
            return;
        }
        if (!playbackSessionActive || currentIndex < 0) {
            return;
        }
        Notification notification = buildMediaNotification();
        lastPostedNotification = notification;
        pendingForegroundNotification = notification;
        if (foregroundService != null) {
            pendingForegroundNotification = null;
            foregroundNotificationRetryCount = 0;
            publishNotification(foregroundService, notification);
            return;
        }
        if (!foregroundServiceStartPending) {
            foregroundServiceStartPending = true;
            PlaybackLog.info("notification waiting for foreground service", PlaybackLog.fields());
            startForegroundService();
            scheduleForegroundNotificationRetry();
        }
    }

    private void scheduleForegroundNotificationRetry() {
        if (foregroundNotificationRetry != null) {
            return;
        }
        foregroundNotificationRetryCount = 0;
        foregroundNotificationRetry = () -> {
            if (!playbackSessionActive || currentIndex < 0) {
                return;
            }
            if (foregroundService != null) {
                Notification notification = pendingForegroundNotification != null
                    ? pendingForegroundNotification
                    : lastPostedNotification;
                if (notification != null) {
                    pendingForegroundNotification = null;
                    publishNotification(foregroundService, notification);
                }
                foregroundNotificationRetryCount = 0;
                foregroundNotificationRetry = null;
                return;
            }
            if (foregroundNotificationRetryCount >= 15) {
                foregroundServiceStartPending = false;
                foregroundNotificationRetry = null;
                PlaybackLog.error(
                    "foreground notification bind timed out",
                    PlaybackLog.put(
                        PlaybackLog.fields(),
                        "postNotificationsGranted",
                        canShowNotifications()
                    )
                );
                return;
            }
            foregroundNotificationRetryCount += 1;
            startForegroundService();
            handler.postDelayed(foregroundNotificationRetry, 200);
        };
        handler.postDelayed(foregroundNotificationRetry, 200);
    }

    private void startBufferWatchdog(PlaybackQueueItem item) {
        stopBufferWatchdog();
        if (!"stream".equals(item.type)) {
            return;
        }
        stableHighBufferChecks = 0;
        bufferWatchdog = () -> {
            if (player == null || currentIndex < 0 || currentIndex >= queue.size()) {
                return;
            }
            PlaybackQueueItem current = queue.get(currentIndex);
            if (!"stream".equals(current.type)) {
                handler.postDelayed(bufferWatchdog, 3000);
                return;
            }
            if (System.currentTimeMillis() - trackStartedAtMs < BUFFER_WATCHDOG_STARTUP_GRACE_MS
                || System.currentTimeMillis() < trackTransitionUntilMs) {
                handler.postDelayed(bufferWatchdog, 3000);
                return;
            }
            long ahead = player.getBufferedPosition() - player.getCurrentPosition();
            if (player.isPlaying() && ahead >= 0 && ahead < 2000) {
                lowBufferStrikes += 1;
            } else {
                lowBufferStrikes = 0;
            }
            stableHighBufferChecks = 0;
            // Mid-track quality switches reconnect the stream and cause audible gaps.
            // Quality is chosen per track at playAtIndex instead.
            handler.postDelayed(bufferWatchdog, 3000);
        };
        handler.postDelayed(bufferWatchdog, 3000);
    }

    private void stopBufferWatchdog() {
        if (bufferWatchdog != null) {
            handler.removeCallbacks(bufferWatchdog);
            bufferWatchdog = null;
        }
    }

    private void downgradeStreamQuality() {
        String nextQuality;
        if ("high".equals(streamQuality)) {
            nextQuality = "medium";
        } else {
            nextQuality = "low";
        }
        if (nextQuality.equals(streamQuality)) {
            return;
        }
        streamQuality = nextQuality;
        lastStreamQualityChangeMs = System.currentTimeMillis();
        stableHighBufferChecks = 0;
        long pos = Math.max(0, getReportedPositionMs() - 500);
        playAtIndexInternal(currentIndex, pos, false);
    }

    private void upgradeStreamQuality() {
        if (!canChangeStreamQualityNow()) {
            return;
        }
        String nextQuality;
        if ("low".equals(streamQuality)) {
            nextQuality = "medium";
        } else if ("medium".equals(streamQuality)) {
            nextQuality = "high";
        } else {
            return;
        }
        streamQuality = nextQuality;
        lastStreamQualityChangeMs = System.currentTimeMillis();
        long pos = Math.max(0, getReportedPositionMs() - 500);
        playAtIndexInternal(currentIndex, pos, false);
    }

    private boolean canChangeStreamQualityNow() {
        return System.currentTimeMillis() - lastStreamQualityChangeMs >= STREAM_QUALITY_CHANGE_COOLDOWN_MS;
    }

    private String resolvePlayableUrl(PlaybackQueueItem item, long startMs) throws IOException {
        if ("local".equals(item.type)) {
            if (libraryPathResolver == null) {
                throw new IOException("Library resolver unavailable");
            }
            String resolved = libraryPathResolver.resolveLibraryAudioPath(item.fullPath);
            if (resolved == null || resolved.isEmpty()) {
                throw new IOException("Local file not found: " + item.fullPath);
            }
            LocalAudioHttpServer server = LocalAudioHttpServer.ensureStarted(
                appContext,
                libraryPathResolver.getLibraryRoot()
            );
            return server.buildAudioUrl(resolved);
        }
        if (item.webpageUrl == null || item.webpageUrl.isEmpty()) {
            throw new IOException("Missing stream URL");
        }
        String url = "http://127.0.0.1:" + MediaAudioFinderPlugin.MEDIA_SERVER_PORT
            + "/stream?url="
            + URLEncoder.encode(item.webpageUrl, StandardCharsets.UTF_8.name())
            + "&quality="
            + URLEncoder.encode(streamQuality, StandardCharsets.UTF_8.name());
        if (startMs > 0) {
            double startSec = startMs / 1000.0;
            url += "&start="
                + URLEncoder.encode(String.format(Locale.US, "%.3f", startSec), StandardCharsets.UTF_8.name());
        }
        return url;
    }

    void startForegroundService() {
        try {
            Intent intent = new Intent(appContext, PlaybackForegroundService.class);
            ContextCompat.startForegroundService(appContext, intent);
            PlaybackLog.info("startForegroundService requested", PlaybackLog.fields());
        } catch (Exception e) {
            PlaybackLog.error("startForegroundService failed", PlaybackLog.fields(), e);
        }
    }

    private void stopForegroundService() {
        pendingForegroundNotification = null;
        foregroundServiceStartPending = false;
        foregroundNotificationRetryCount = 0;
        if (foregroundNotificationRetry != null) {
            handler.removeCallbacks(foregroundNotificationRetry);
            foregroundNotificationRetry = null;
        }
        NotificationManager nm = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(NOTIFICATION_ID);
        }
        appContext.stopService(new Intent(appContext, PlaybackForegroundService.class));
        foregroundActive = false;
        lastPostedNotification = null;
    }

    private void refreshNotification() {
        postMediaNotification();
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "Media playback",
            NotificationManager.IMPORTANCE_DEFAULT
        );
        channel.setDescription("Now playing and transport controls");
        nm.createNotificationChannel(channel);
    }

    private PendingIntent launchPendingIntent() {
        Intent launch = appContext.getPackageManager().getLaunchIntentForPackage(appContext.getPackageName());
        if (launch == null) {
            return null;
        }
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(appContext, 0, launch, flags);
    }

    private void emitTrackChanged(int index, PlaybackQueueItem item) {
        if (plugin == null) {
            return;
        }
        JSObject payload = new JSObject();
        payload.put("index", index);
        payload.put("title", item.title);
        payload.put("artist", item.artist);
        payload.put("type", item.type);
        payload.put("fullPath", item.fullPath);
        payload.put("webpageUrl", item.webpageUrl);
        payload.put("streamQuality", streamQuality);
        payload.put("positionMs", 0);
        payload.put("durationMs", -1);
        payload.put("hintDurationMs", resolveDurationMs() > 0 ? resolveDurationMs() : getHintDurationMs());
        payload.put("durationSec", item.durationSec > 0 ? item.durationSec : -1);
        if (item.durationSec <= 0 && item.webpageUrl != null && !item.webpageUrl.isEmpty()) {
            Long fetched = resolvedStreamDurationSecByUrl.get(item.webpageUrl);
            if (fetched != null && fetched > 0) {
                payload.put("durationSec", fetched);
                payload.put("hintDurationMs", fetched * 1000L);
            }
        }
        plugin.notifyPlaybackEvent(EVENT_TRACK_CHANGED, payload);
    }

    private void emitStateChanged() {
        if (plugin == null) {
            return;
        }
        if (player != null && (player.isPlaying() || player.getPlaybackState() == Player.STATE_BUFFERING)) {
            lastKnownPositionMs = getReportedPositionMs();
        }
        plugin.notifyPlaybackEvent(EVENT_STATE_CHANGED, buildPlaybackState());
        if (player != null && player.getPlaybackState() != Player.STATE_IDLE) {
            refreshNotification();
        }
    }

    private void emitLoopModeChanged() {
        if (plugin == null) {
            return;
        }
        JSObject payload = new JSObject();
        payload.put("loopMode", loopMode);
        plugin.notifyPlaybackEvent(EVENT_LOOP_MODE_CHANGED, payload);
    }

    private static String normalizeLoopMode(String mode) {
        String value = String.valueOf(mode == null ? "off" : mode).toLowerCase(Locale.ROOT);
        if ("single".equals(value) || "playlist".equals(value)) {
            return value;
        }
        return "off";
    }

    private static String loopModeLabel(String mode) {
        switch (normalizeLoopMode(mode)) {
            case "single":
                return "単曲ループ";
            case "playlist":
                return "プレイリスト";
            default:
                return "ループオフ";
        }
    }

    static final class PlaybackQueueItem {
        final String type;
        final String fullPath;
        final String webpageUrl;
        final String title;
        final String artist;
        long durationSec;

        private PlaybackQueueItem(String type, String fullPath, String webpageUrl, String title, String artist, long durationSec) {
            this.type = type;
            this.fullPath = fullPath;
            this.webpageUrl = webpageUrl;
            this.title = title;
            this.artist = artist;
            this.durationSec = durationSec;
        }

        void updateDurationSec(long durationSec) {
            if (durationSec > 0) {
                this.durationSec = durationSec;
            }
        }

        @Nullable
        static PlaybackQueueItem fromJson(@Nullable JSONObject raw) {
            if (raw == null) {
                return null;
            }
            String type = raw.optString("type", "");
            String fullPath = raw.optString("fullPath", "");
            String webpageUrl = raw.optString("webpageUrl", "");
            String title = raw.optString("title", "(No title)");
            String artist = raw.optString("artist", "-");
            long durationSec = raw.optLong("durationSec", -1);
            if (durationSec <= 0 && raw.has("durationSec")) {
                double durationValue = raw.optDouble("durationSec", -1);
                if (durationValue > 0) {
                    durationSec = (long) durationValue;
                }
            }
            if (durationSec <= 0) {
                durationSec = parseDurationSec(raw.optString("duration", ""));
            }
            if ("local".equals(type) || (!fullPath.isEmpty() && webpageUrl.isEmpty())) {
                return new PlaybackQueueItem("local", fullPath, "", title, artist, durationSec);
            }
            if (!webpageUrl.isEmpty()) {
                return new PlaybackQueueItem("stream", "", webpageUrl, title, artist, durationSec);
            }
            return null;
        }
    }

    private static long parseDurationSec(String raw) {
        if (raw == null) {
            return -1;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || "-".equals(trimmed)) {
            return -1;
        }
        if (trimmed.matches("\\d+")) {
            return Long.parseLong(trimmed) * 60L;
        }
        String[] parts = trimmed.split(":");
        try {
            if (parts.length == 2) {
                return Long.parseLong(parts[0]) * 60L + Long.parseLong(parts[1]);
            }
            if (parts.length == 3) {
                return Long.parseLong(parts[0]) * 3600L
                    + Long.parseLong(parts[1]) * 60L
                    + Long.parseLong(parts[2]);
            }
        } catch (NumberFormatException ignored) {
            // ignore malformed duration strings
        }
        return -1;
    }
}
