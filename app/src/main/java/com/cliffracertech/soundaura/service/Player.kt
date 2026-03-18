/* This file is part of SoundAura, which is released under
 * the terms of the Apache License 2.0. See license.md in
 * the project's root directory to see the full license. */
package com.cliffracertech.soundaura.service

import android.content.Context
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as ExoPlayerState
import androidx.media3.exoplayer.ExoPlayer
import com.cliffracertech.soundaura.model.database.Track

data class ActivePlaylistSummary(
    val id: Long,
    val shuffle: Boolean,
    val playSequentially: Boolean,
    val volume: Float,
    val volumeBoostDb: Int)

typealias ActivePlaylist = Map.Entry<ActivePlaylistSummary, List<Track>>
val ActivePlaylist.id get() = key.id
val ActivePlaylist.shuffle get() = key.shuffle
val ActivePlaylist.playSequentially get() = key.playSequentially
val ActivePlaylist.volume get() = key.volume
val ActivePlaylist.volumeBoostDb get() = key.volumeBoostDb
val ActivePlaylist.tracks get() = value
val ActivePlaylist.trackUris get() = value.map(Track::uri)

class Player(
    private val context: Context,
    private var playlist: ActivePlaylist,
    startImmediately: Boolean = false,
    masterVolume: Float = 1f,
    private val onPlaybackFailure: (List<Uri>) -> Unit,
    private val onPlaybackComplete: () -> Unit = {},
) {
    private var exoPlayers: List<ExoPlayer> = emptyList()
    private var volumeBoosters: List<LoudnessEnhancer> = emptyList()
    private var targetBoostDb = playlist.volumeBoostDb
    private var hasReportedCompletion = false
    var masterVolume = masterVolume
        set(value) {
            field = value
            setVolume(playlist.volume)
        }

    init {
        initializeExoPlayer(startImmediately)
    }

    fun play() {
        if (isPlaybackComplete) {
            exoPlayers.forEach(ExoPlayer::seekToDefaultPosition)
            hasReportedCompletion = false
        }
        exoPlayers.forEach { it.playWhenReady = true }
    }
    fun pause() = exoPlayers.forEach(ExoPlayer::pause)

    fun stop() {
        exoPlayers.forEach(ExoPlayer::pause)
        exoPlayers.forEach(ExoPlayer::seekToDefaultPosition)
    }

    fun setVolume(volume: Float) {
        exoPlayers.forEach { it.volume = volume * (masterVolume * masterVolume) }
    }

    fun update(newPlaylist: ActivePlaylist, startImmediately: Boolean) {
        if (
            newPlaylist.shuffle != playlist.shuffle ||
            newPlaylist.playSequentially != playlist.playSequentially ||
            newPlaylist.tracks != playlist.tracks
        ) {
            initializeExoPlayer(startImmediately, newPlaylist)
        } else {
            setVolume(newPlaylist.volume)
            targetBoostDb = newPlaylist.volumeBoostDb
            applyVolumeBoost()
            exoPlayers.forEach { it.playWhenReady = startImmediately }
        }
        playlist = newPlaylist
    }

    val isPlaybackComplete get() =
        exoPlayers.isNotEmpty() && exoPlayers.all { it.playbackState == ExoPlayerState.STATE_ENDED }

    fun release() {
        volumeBoosters.forEach { it.enabled = false }
        exoPlayers.forEach(ExoPlayer::release)
    }

    private fun initializeExoPlayer(
        startImmediately: Boolean,
        sourcePlaylist: ActivePlaylist = playlist,
    ) {
        targetBoostDb = sourcePlaylist.volumeBoostDb
        hasReportedCompletion = false
        volumeBoosters.forEach { it.enabled = false }
        exoPlayers.forEach(ExoPlayer::release)

        val tracks = sourcePlaylist.tracks
        if (tracks.isEmpty()) {
            exoPlayers = emptyList()
            onPlaybackFailure(emptyList())
            return
        }

        exoPlayers = if (sourcePlaylist.playSequentially) {
            listOf(ExoPlayer.Builder(context).build().apply {
                setMediaItems(sourcePlaylist.trackUris.map(MediaItem::fromUri))
                repeatMode = ExoPlayerState.REPEAT_MODE_ALL
                shuffleModeEnabled = sourcePlaylist.shuffle
                volume = sourcePlaylist.volume * (masterVolume * masterVolume)
                addListener(object : ExoPlayerState.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        notifyPlaybackCompleteIfNeeded()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        val failed = currentMediaItem?.localConfiguration?.uri
                        onPlaybackFailure(if (failed == null) sourcePlaylist.trackUris else listOf(failed))
                    }
                })
                prepare()
                playWhenReady = startImmediately
            })
        } else tracks.map { track ->
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(track.uri))
                repeatMode = if (track.loopEnabled)
                    ExoPlayerState.REPEAT_MODE_ONE
                else ExoPlayerState.REPEAT_MODE_OFF
                volume = sourcePlaylist.volume * (masterVolume * masterVolume)
                addListener(object : ExoPlayerState.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        notifyPlaybackCompleteIfNeeded()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        onPlaybackFailure(listOf(track.uri))
                    }
                })
                prepare()
                playWhenReady = startImmediately
            }
        }
        applyVolumeBoost()
    }

    private fun notifyPlaybackCompleteIfNeeded() {
        if (!hasReportedCompletion && isPlaybackComplete) {
            hasReportedCompletion = true
            onPlaybackComplete()
        }
    }

    private fun applyVolumeBoost() {
        volumeBoosters.forEach { it.enabled = false }
        volumeBoosters = if (targetBoostDb == 0) emptyList() else exoPlayers.mapNotNull { player ->
            val sessionId = player.audioSessionId
            if (sessionId == C.AUDIO_SESSION_ID_UNSET) null else
                LoudnessEnhancer(sessionId).apply {
                    setTargetGain(targetBoostDb * 100)
                    enabled = true
                }
        }
    }
}

class PlayerMap(
    private val context: Context,
    private val onPlaybackFailure: (uris: List<Uri>) -> Unit,
    private val onAllPlaybackComplete: () -> Unit = {},
) {
    private var playerMap: MutableMap<Long, Player> = hashMapOf()
    private var masterVolume = 1f

    val isEmpty get() = playerMap.isEmpty()

    private fun onPlayerPlaybackComplete() {
        if (playerMap.isNotEmpty() && playerMap.values.all(Player::isPlaybackComplete))
            onAllPlaybackComplete()
    }

    fun play() = playerMap.values.forEach(Player::play)
    fun pause() = playerMap.values.forEach(Player::pause)
    fun stop() = playerMap.values.forEach(Player::stop)

    fun setPlayerVolume(playlistId: Long, volume: Float) =
        playerMap[playlistId]?.setVolume(volume)

    fun setMasterVolume(volume: Float) {
        masterVolume = volume
        playerMap.values.forEach { it.masterVolume = volume }
    }

    fun releaseAll() = playerMap.values.forEach(Player::release)

    fun update(playlists: Map<ActivePlaylistSummary, List<Track>>, startPlaying: Boolean) {
        val oldMap = playerMap
        playerMap = HashMap(playlists.size)

        for (playlist in playlists) {
            val existingPlayer = oldMap
                .remove(playlist.id)
                ?.apply { update(playlist, startPlaying) }

            playerMap[playlist.id] = existingPlayer ?:
                Player(
                    context = context,
                    playlist = playlist,
                    startImmediately = startPlaying,
                    masterVolume = masterVolume,
                    onPlaybackFailure = onPlaybackFailure,
                    onPlaybackComplete = ::onPlayerPlaybackComplete,
                )
        }
        oldMap.values.forEach(Player::release)
    }
}