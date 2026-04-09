/* This file is part of SoundAura, which is released under
 * the terms of the Apache License 2.0. See license.md in
 * the project's root directory to see the full license. */
package com.cliffracertech.soundaura.model

import android.content.Context
import android.content.Intent
import androidx.annotation.FloatRange
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import com.cliffracertech.soundaura.collectAsState
import com.cliffracertech.soundaura.edit
import com.cliffracertech.soundaura.preferenceFlow
import com.cliffracertech.soundaura.service.PlayerService
import com.cliffracertech.soundaura.settings.PrefKeys
import com.cliffracertech.soundaura.settings.dataStore
import com.cliffracertech.soundaura.widget.SoundAuraWidget
import com.cliffracertech.soundaura.widget.SoundAuraWidgetReceiver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityRetainedComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityRetainedScoped
import java.time.Duration
import java.time.Instant

/** An interface that describes the playback state of a set of simultaneously-
 * playing playlists and an automatic stop timer. The 'is playing' state is
 * read via the [isPlaying] property and manipulated using the [toggleIsPlaying]
 * method. The current stop timer is accessed through the property [stopTime]
 * and changed or cleared using [setTimer] and [clearTimer], respectively. The
 * volume for a particular playlist can be manipulated using [setPlaylistVolume]. */
interface PlaybackState {
    /** The current 'is playing' state of the playback. */
    val isPlaying: Boolean
    /** Toggle the playback state of the sound mix between playing/paused. */
    fun toggleIsPlaying()

    /** The [Instant] at which playback will be automatically stopped, or null if no timer is set. */
    val stopTime: Instant?
    /** Set a timer to automatically stop playback after [duration] has elapsed. */
    fun setTimer(duration: Duration)
    /** Clear any set stop timer. */
    fun clearTimer()

    /** Set the playlist identified by [playlistId]'s volume to [volume]. */
    fun setPlaylistVolume(playlistId: Long, @FloatRange(0.0, 1.0) volume: Float)

    /** The current 'master volume' scaling factor for the app's sound mix. */
    val masterVolume: Float
    /** Set the app's master volume scaling factor to [volume]. */
    fun setMasterVolume(@FloatRange(0.0, 1.0) volume: Float)
}

/** An implementation of [PlaybackState] that is backed by a [PlayerService] instance. */
class PlayerServicePlaybackState(
    @ApplicationContext private val context: Context
): PlaybackState {
    override var isPlaying by mutableStateOf(
        PlayerService.playbackState == android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING)
        private set

    private var _masterVolume by mutableStateOf(1f)
    override val masterVolume get() = _masterVolume

    init {
        val scope = ProcessLifecycleOwner.get().lifecycleScope
        PlayerService.playbackStateFlow
            .onEach { state ->
                isPlaying = state == android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING
            }.launchIn(scope)
        val masterVolumeKey = floatPreferencesKey(PrefKeys.masterVolume)
        context.dataStore.preferenceFlow(masterVolumeKey, 1f)
            .onEach { _masterVolume = it }
            .launchIn(scope)
    }

    override fun toggleIsPlaying() {
        PlayerService.binder?.toggleIsPlaying() ?:
            context.startService(PlayerService.playIntent(context))
    }

    override val stopTime get() = PlayerService.binder?.stopTime
    override fun setTimer(duration: Duration) {
        PlayerService.binder?.setStopTimer(duration) ?:
            context.startService(PlayerService.setTimerIntent(context, duration))
    }
    override fun clearTimer() {
        PlayerService.binder?.clearStopTimer() ?:
            context.startService(PlayerService.setTimerIntent(context, null))
    }

    override fun setPlaylistVolume(playlistId: Long, volume: Float) {
        // If the PlayerService is not started, we do nothing on the assumption
        // that the volume change will be written to the app's database and
        // will therefore be reflected next time the service is started and
        // the active tracks (and their volumes) are read from the database.
        PlayerService.binder?.setPlaylistVolume(playlistId, volume)
    }

    override fun setMasterVolume(volume: Float) {
        _masterVolume = volume
        PlayerService.binder?.setMasterVolume(volume)
        val scope = ProcessLifecycleOwner.get().lifecycleScope
        val masterVolumeKey = floatPreferencesKey(PrefKeys.masterVolume)
        context.dataStore.edit(masterVolumeKey, volume, scope)
        
        val updateIntent = Intent(context, SoundAuraWidgetReceiver::class.java).apply {
            action = SoundAuraWidget.ACTION_UPDATE_WIDGET
        }
        context.sendBroadcast(updateIntent)
    }
}

@Module @InstallIn(ActivityRetainedComponent::class)
class PlaybackStateModule {
    @Provides @ActivityRetainedScoped
    fun providePlaybackState(
        @ApplicationContext context: Context
    ): PlaybackState = PlayerServicePlaybackState(context)
}

/**
 * An implementation of [PlaybackState] for use in testing.
 *
 * The implementation of [PlaybackState] used in the release version of the
 * app, [PlayerServicePlaybackState], is unreliable during testing due to its
 * reliance on a bound service. [TestPlaybackState] can be used in tests for
 * greater reliability. This implementation is used instead of a mock due to
 * the fact that mocking seems to be incompatible with Compose SnapshotState
 * objects (which [PlayerServicePlaybackState] uses internally).
 */
class TestPlaybackState: PlaybackState {
    override var isPlaying = false
        private set
    override fun toggleIsPlaying() { isPlaying = !isPlaying }

    override var stopTime: Instant? = null
        private set
    override fun setTimer(duration: Duration) { stopTime = Instant.now() + duration }
    override fun clearTimer() { stopTime = null }

    override fun setPlaylistVolume(playlistId: Long, volume: Float) = Unit

    private var _masterVolume = 1f
    override val masterVolume get() = _masterVolume
    override fun setMasterVolume(volume: Float) { _masterVolume = volume }
}