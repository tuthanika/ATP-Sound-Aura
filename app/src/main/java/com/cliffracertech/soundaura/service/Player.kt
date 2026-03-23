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
import com.cliffracertech.soundaura.model.database.TrackWithVolume

data class ActivePlaylistSummary(
    val id: Long,
    val shuffle: Boolean,
    val playSequentially: Boolean,
    val volume: Float,
    val volumeBoostDb: Int)

typealias ActivePlaylist = Map.Entry<ActivePlaylistSummary, List<TrackWithVolume>>
val ActivePlaylist.id get() = key.id
val ActivePlaylist.shuffle get() = key.shuffle
val ActivePlaylist.playSequentially get() = key.playSequentially
val ActivePlaylist.volume get() = key.volume
val ActivePlaylist.volumeBoostDb get() = key.volumeBoostDb
val ActivePlaylist.tracks get() = value
val ActivePlaylist.trackUris get() = value.map(TrackWithVolume::uri)

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
        if (playlist.playSequentially) {
            exoPlayers.firstOrNull()?.apply {
                val currentTrack = currentMediaItemIndex.let {
                    if (it in playlist.tracks.indices) playlist.tracks[it] else null
                }
                val trackVol = currentTrack?.volume ?: 1f
                this.volume = volume * (masterVolume * masterVolume) * trackVol
            }
        } else exoPlayers.forEachIndexed { index, player ->
            player.volume = volume * (masterVolume * masterVolume) * playlist.tracks[index].volume
        }
    }

    fun update(newPlaylist: ActivePlaylist, startImmediately: Boolean) {
        val tracksChanged = newPlaylist.tracks.size != playlist.tracks.size ||
                            newPlaylist.tracks.zip(playlist.tracks).any { (new, old) ->
                                new.uri != old.uri || new.loopEnabled != old.loopEnabled
                            }
        if (newPlaylist.shuffle != playlist.shuffle ||
            newPlaylist.playSequentially != playlist.playSequentially ||
            tracksChanged
        ) {
            initializeExoPlayer(startImmediately, newPlaylist)
        } else {
            playlist = newPlaylist
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
        volumeBoosters.forEach { 
            try { it.enabled = false; it.release() } 
            catch (e: Exception) { /* already released */ }
        }
        volumeBoosters = emptyList()
        exoPlayers.forEach(ExoPlayer::release)
    }

    private fun initializeExoPlayer(
        startImmediately: Boolean,
        sourcePlaylist: ActivePlaylist = playlist,
    ) {
        targetBoostDb = sourcePlaylist.volumeBoostDb
        hasReportedCompletion = false
        volumeBoosters.forEach {
            try { it.enabled = false; it.release() }
            catch (e: Exception) { /* already released */ }
        }
        volumeBoosters = emptyList()
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
                // Sequential playback uses the playlist volume as the base
                // for all tracks; per-track volume is not used for sequential
                // playback because it might be confusing if the volume jumps
                // between tracks in a single playlist.
                // However, the user request says "volume các sound trong 1 playlist là cấp 3"
                // which might imply it should apply to sequential too.
                // Let's check the current item index if possible, but ExoPlayer.volume is per player.
                // To support per-track volume in sequential playback, we'd need to update
                // the volume on each media item transition.
                val updateSequentialVolume = {
                    val currentTrack = currentMediaItemIndex.let { 
                        if (it in tracks.indices) tracks[it] else null
                    }
                    val trackVol = currentTrack?.volume ?: 1f
                    volume = sourcePlaylist.volume * (masterVolume * masterVolume) * trackVol
                }
                updateSequentialVolume()
                addListener(object : ExoPlayerState.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        updateSequentialVolume()
                    }
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
                volume = sourcePlaylist.volume * (masterVolume * masterVolume) * track.volume
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
        volumeBoosters.forEach { 
            try { it.enabled = false; it.release() }
            catch (e: Exception) { /* already released */ }
        }
        volumeBoosters = if (targetBoostDb == 0) emptyList() else exoPlayers.mapNotNull { player ->
            val sessionId = player.audioSessionId
            if (sessionId == C.AUDIO_SESSION_ID_UNSET) null else try {
                LoudnessEnhancer(sessionId).apply {
                    setTargetGain(targetBoostDb * 100)
                    enabled = true
                }
            } catch (e: Exception) { null }
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

    fun update(
        playlists: Map<ActivePlaylistSummary, List<TrackWithVolume>>,
        startPlaying: Boolean
    ) {
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