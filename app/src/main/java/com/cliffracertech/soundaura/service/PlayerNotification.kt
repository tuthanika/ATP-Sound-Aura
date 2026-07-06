/* This file is part of SoundAura, which is released under
 * the terms of the Apache License 2.0. See license.md in
 * the project's root directory to see the full license. */
package com.cliffracertech.soundaura.service

import android.app.*
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.app.Service.STOP_FOREGROUND_REMOVE
import android.content.Context.NOTIFICATION_SERVICE
import android.content.Intent
import android.os.Build
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.MediaMetadataCompat.*
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.session.PlaybackStateCompat.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.cliffracertech.soundaura.MainActivity
import com.cliffracertech.soundaura.R
import com.cliffracertech.soundaura.mediacontroller.toHMMSSstring
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/**
 * A manager for a notification for a foreground media playing service.
 *
 * PlayerNotification can post a notification for a foreground media playing
 * service that contains a string describing a playback state (e.g. playing,
 * paused), a toggle play/pause action, a stop action, and optionally a
 * volume cycle action and active playlist information. Using the values
 * of [playbackState] and [stopTime] that are provided in its constructor,
 * PlayerNotification will automatically call [Service.startForeground] for
 * the client service during creation. PlayerNotification should be notified
 * of changes to the playback state, auto stop time, active playlists, or
 * master volume afterwards via the method [update]. The notification can
 * be cleared when the service is stopping with the function [remove].

 * @param service The foreground media playing service that PlayerNotification
 *     is serving. Note that this reference to the service is held onto for
 *     PlayerNotification's lifetime; PlayerNotification should therefore never
 *     outlive the service instance used here.
 * @param playIntent The intent that, when fired, will cause the service that
 *     PlayerNotification is serving to start its playback.
 * @param pauseIntent The intent that, when fired, will cause the service that
 *     PlayerNotification is serving to pause its playback.
 * @param stopIntent The intent that, when fired, will cause the service that
 *     PlayerNotification is serving to stop its playback.
 * @param cancelTimerIntent The intent that, when fired, will cause the
 *     cancellation of the current stop timer
 * @param cycleVolumeIntent The intent that, when fired, will cycle the master
 *     volume through preset levels (0 → 25% → 50% → 75% → 100% → 0).
 * @param playbackState The initial playback state that will be displayed
 *     in the notification. playbackState can be changed after creation by
 *     passing the new value to the method update.
 * @param stopTime The time at which playback will be automatically stopped,
 *     if any. The duration between now and the stop time will be calculated
 *     and displayed in the notification.
 * @param activePlaylistNames The names of the currently active playlists.
 *     Used to build a label shown in the notification sub-text.
 * @param masterVolume The initial master volume (0..1). Shown as a percentage
 *     in the notification and used to pick the correct volume icon.
 * @param useMediaSession Whether or not a [MediaSessionCompat] instance should
 *     be tied to the notification. If true, the notification will appear in
 *     the media session section of the status bar. If false, the notification
 *     will appear as a regular notification instead. PlayerNotification's
 *     property of the same name can be used to change this after creation.
 */
class PlayerNotification(
    private val service: LifecycleService,
    private val playIntent: Intent,
    private val pauseIntent: Intent,
    private val stopIntent: Intent,
    private val cancelTimerIntent: Intent,
    private val cycleVolumeIntent: Intent,
    private var playbackState: Int,
    stopTime: Instant?,
    activePlaylistNames: List<String>,
    masterVolume: Float,
    useMediaSession: Boolean
) {
    private val playActionRequestCode = 1
    private val pauseActionRequestCode = 2
    private val stopActionRequestCode = 3
    private val cancelTimerRequestCode = 4
    private val cycleVolumeRequestCode = 5
    private val notificationId get() = if (useMediaSession) 1 else 2
    private val notificationManager =
        service.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    private val playbackStateBuilder = PlaybackStateCompat.Builder()
    private lateinit var notificationStyle: androidx.media.app.NotificationCompat.MediaStyle

    private var updateTimeLeftJob: Job? = null
    private var stopTime: Instant? = stopTime
    private var timeUntilStop: Duration? =
        stopTime?.let { Duration.between(Instant.now(), it) }
    private var activePlaylistNames: List<String> = activePlaylistNames
    private var masterVolume: Float = masterVolume

    private var backgroundBitmap: android.graphics.Bitmap? = null

    private var mediaSession: MediaSessionCompat? = null
    var useMediaSession: Boolean = useMediaSession
        set(value) {
            if (field == value) return
            field = value
            rebuildMediaStyleAndNotificationBuilder()
        }
    private val usingTiramisuMediaControls get() = useMediaSession &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    private val channelId = service.getString(
            R.string.player_notification_channel_id
        ).also { channelId ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                    notificationManager.getNotificationChannel(channelId) != null)
                return@also

            val title = service.getString(R.string.player_notification_channel_name)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(channelId, title, importance)
            channel.description = service.getString(
                R.string.player_notification_channel_description)
            channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            notificationManager.createNotificationChannel(channel)
        }

    private val notificationBuilder: NotificationCompat.Builder =
        NotificationCompat.Builder(service, channelId)
            .setOngoing(true)
            .setColorized(true)
            .setSmallIcon(R.drawable.tile_and_notification_icon)
            .setContentIntent(PendingIntent.getActivity(service, 0,
                Intent(service, MainActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }, FLAG_IMMUTABLE))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

    /** A notification action that will fire a [PendingIntent.getService] call
     * when triggered. The started service will be provided the [playIntent]. */
    private val playAction = NotificationCompat.Action(
        R.drawable.ic_baseline_play_24,
        service.getString(R.string.play),
        PendingIntent.getService(
            service, playActionRequestCode, playIntent,
            FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT))

    /** A notification action that will fire a [PendingIntent.getService] call
     * when triggered. The started service will be provided the [pauseIntent]. */
    private val pauseAction = NotificationCompat.Action(
        R.drawable.ic_baseline_pause_24,
        service.getString(R.string.pause),
        PendingIntent.getService(
            service, pauseActionRequestCode, pauseIntent,
            FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT))

    /** Return the [playAction] or [pauseAction] depending on the value of the parameter [isPlaying]. */
    private fun togglePlayPauseAction(isPlaying: Boolean) =
        if (isPlaying) pauseAction
        else           playAction

    /** A notification action that will fire a [PendingIntent.getService] call
     * when triggered. The started service will be provided the provided stopIntent. */
    private val stopAction = NotificationCompat.Action(
        R.drawable.ic_baseline_close_24,
        service.getString(R.string.close),
        PendingIntent.getService(
            service, stopActionRequestCode,
            stopIntent, FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT))

    /** A notification action that will fire a [PendingIntent.getService] call
     * when triggered. The started service will be provided the [cancelTimerIntent] */
    private val cancelTimerAction = NotificationCompat.Action(
        R.drawable.ic_baseline_alarm_off_24,
        service.getString(R.string.cancel_stop_timer_action),
        PendingIntent.getService(
            service, cancelTimerRequestCode, cancelTimerIntent,
            FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT))

    /** Build a volume cycle action whose icon and label reflect the current [masterVolume]. */
    private fun buildVolumeAction(): NotificationCompat.Action {
        val volumePct = (masterVolume * 100).toInt()
        val icon = if (masterVolume < 0.01f)
            R.drawable.ic_baseline_volume_off_24
        else
            R.drawable.ic_baseline_volume_up_24
        val label = service.getString(R.string.notification_volume_action, volumePct)
        val pendingIntent = PendingIntent.getService(
            service, cycleVolumeRequestCode, cycleVolumeIntent,
            FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT)
        return NotificationCompat.Action(icon, label, pendingIntent)
    }

    init {
        rebuildMediaStyleAndNotificationBuilder()
    }

    private fun rebuildMediaStyleAndNotificationBuilder() {
        // Because the notification is being used for a foreground service,
        // Service.stopForeground and Service.startForeground must be used
        // instead of NotificationManager.cancel to get the notification to
        // reappear in the correct location.
        service.stopForeground(STOP_FOREGROUND_REMOVE)
        mediaSession?.isActive = false
        mediaSession?.release() // Release native resources before reassigning to prevent MediaSession leak
        mediaSession = null     // Explicitly null out before reassigning below

        notificationStyle = androidx.media.app.NotificationCompat.MediaStyle()
        mediaSession = if (!useMediaSession) null else
            MediaSessionCompat(service, PlayerNotification::class.toString()).apply {
                // We use an off center zoomed out app icon for the API 33+
                // media notification because it is used as the background
                // for the media controls card. For pre-API 33 media controls
                // we use the standard app icon because it is displayed in a
                // box at the starting edge of the media controls .
                backgroundBitmap = ContextCompat.getDrawable(
                        service,
                        if (usingTiramisuMediaControls)
                            R.drawable.media_controls_background
                        else R.drawable.ic_launcher_foreground
                    )?.toBitmap()
                setMetadata(updatedMetadata(playbackState))
                isActive = true
                notificationStyle.setMediaSession(sessionToken)
                setCallback(object: MediaSessionCompat.Callback() {
                    override fun onPlay() { service.startService(playIntent) }
                    override fun onPause() { service.startService(pauseIntent) }
                    override fun onStop() { service.startService(stopIntent) }
                })
            }
        notificationBuilder.setStyle(notificationStyle)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            service.startForeground(notificationId, updatedNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            service.startForeground(notificationId, updatedNotification())
        }
    }

    fun remove() {
        service.stopForeground(STOP_FOREGROUND_REMOVE)
        mediaSession?.isActive = false
        mediaSession?.release()
    }

    fun update(
        playbackState: Int,
        stopTime: Instant?,
        activePlaylistNames: List<String> = this.activePlaylistNames,
        masterVolume: Float = this.masterVolume,
    ) {
        this.playbackState = playbackState
        this.stopTime = stopTime
        this.activePlaylistNames = activePlaylistNames
        this.masterVolume = masterVolume

        timeUntilStop = stopTime?.let { Duration.between(Instant.now(), it) }
        updateTimeLeftJob?.cancel()
        val stopTimeCopy = stopTime
        if (stopTimeCopy != null)
            updateTimeLeftJob = service.lifecycleScope.launch {
                while (timeUntilStop != null) {
                    delay(1000)
                    val now = Instant.now()
                    if (now >= stopTimeCopy) {
                        // BUG-10 fix: don't call update() recursively — it would cancel THIS
                        // coroutine and race-launch a new updateTimeLeftJob simultaneously.
                        // Simply clear the timer and break; the caller (PlayerService) will
                        // call setPlaybackState(STOPPED) which triggers update() correctly.
                        timeUntilStop = null
                        val notification = updatedNotification(this@PlayerNotification.playbackState, null)
                        notificationManager.notify(notificationId, notification)
                        break
                    }
                    timeUntilStop = Duration.between(now, stopTimeCopy)
                    val notification = updatedNotification(this@PlayerNotification.playbackState, timeUntilStop)
                    notificationManager.notify(notificationId, notification)
                }
            }


        val notification = updatedNotification(playbackState, timeUntilStop)
        notificationManager.notify(notificationId, notification)
        mediaSession?.setPlaybackState(updatedPlaybackState(playbackState))
        mediaSession?.setMetadata(updatedMetadata(playbackState))
    }

    /** Build a compact subtitle string combining playlist info and volume. */
    private fun buildSubText(): String {
        val playlistPart = when (activePlaylistNames.size) {
            0    -> service.getString(R.string.notification_no_active_playlists)
            1    -> activePlaylistNames[0]
            else -> service.getString(
                R.string.notification_active_playlists_count, activePlaylistNames.size)
        }
        val volumePct = (masterVolume * 100).toInt()
        return "$playlistPart  •  🔊 $volumePct%"
    }

    private fun NotificationCompat.Builder.updateText(
        timeUntilStop: Duration?
    ): NotificationCompat.Builder = apply {
        val stateString = service.getString(when(playbackState) {
            STATE_PLAYING -> R.string.playing
            STATE_PAUSED ->  R.string.paused
            else ->          R.string.stopped
        })
        setContentTitle(stateString)
        // Always show playlist info + volume as sub-text (visible in expanded notification)
        setSubText(buildSubText())

        // Starting with API level 33, the media controls use a text
        // animation that looks really bad with the timer countdown.
        // It also doesn't allow custom actions like the cancel timer
        // action, so we just don't show the timer in this case.
        if (timeUntilStop == null || usingTiramisuMediaControls)
            setContentText(null)
        else setContentText(service.getString(
            R.string.stop_timer_description, timeUntilStop.toHMMSSstring()))
    }

    private fun updatedNotification(
        playbackState: Int = this.playbackState,
        timeUntilStop: Duration? = this.timeUntilStop,
    ): Notification {
        val builder = notificationBuilder
            .updateText(timeUntilStop)
            .clearActions()

        builder.addAction(togglePlayPauseAction(
            isPlaying = playbackState == STATE_PLAYING))
        // Action index 1: volume cycle (tap icon to cycle volume)
        builder.addAction(buildVolumeAction())
        builder.addAction(stopAction)
        if (timeUntilStop != null)
            builder.addAction(cancelTimerAction)

        // Compact view: show play/pause (0) and volume (1)
        // Timer action is only shown in expanded view to keep compact view clean
        notificationStyle.setShowActionsInCompactView(0, 1)

        return builder.build()
    }

    private fun updatedPlaybackState(playbackState: Int) = playbackStateBuilder
        .setState(playbackState, PLAYBACK_POSITION_UNKNOWN, 1f)
        .setActions(ACTION_PLAY_PAUSE or ACTION_PLAY or
                    ACTION_PAUSE or ACTION_STOP)
        .build()

    private fun updatedMetadata(playbackState: Int): MediaMetadataCompat {
        val stateString = service.getString(when(playbackState) {
            STATE_PLAYING -> R.string.playing
            STATE_PAUSED ->  R.string.paused
            else ->          R.string.stopped
        })
        // Show playlist info as the media album or artist field so it appears
        // in the media session card on older Android versions.
        val playlistLabel = when (activePlaylistNames.size) {
            0    -> service.getString(R.string.notification_no_active_playlists)
            1    -> activePlaylistNames[0]
            else -> service.getString(
                R.string.notification_active_playlists_count, activePlaylistNames.size)
        }
        val volumePct = (masterVolume * 100).toInt()
        val artistLabel = "$stateString  •  $playlistLabel  •  🔊 $volumePct%"
        return MediaMetadataCompat.Builder()
            .putBitmap(METADATA_KEY_ART, backgroundBitmap)
            .putString(METADATA_KEY_TITLE, service.getString(R.string.app_name))
            .putString(METADATA_KEY_ARTIST, artistLabel)
            .build()
    }
}