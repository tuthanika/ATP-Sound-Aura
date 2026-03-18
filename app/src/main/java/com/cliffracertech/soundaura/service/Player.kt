/* This file is part of SoundAura, which is released under
 * the terms of the Apache License 2.0. See license.md in
 * the project's root directory to see the full license. */
package com.cliffracertech.soundaura.service

import android.content.Context
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as ExoPlayerState
import androidx.media3.exoplayer.ExoPlayer

data class ActivePlaylistSummary(
    val id: Long,
    val shuffle: Boolean,
    val volume: Float,
    val volumeBoostDb: Int)

typealias ActivePlaylist = Map.Entry<ActivePlaylistSummary, List<Uri>>
val ActivePlaylist.id get() = key.id
val ActivePlaylist.shuffle get() = key.shuffle
val ActivePlaylist.volume get() = key.volume
val ActivePlaylist.volumeBoostDb get() = key.volumeBoostDb
val ActivePlaylist.tracks get() = value

/**
 * An [ExoPlayer] wrapper that allows for seamless looping of the provided
 * [ActivePlaylist]. The [update] method can be used when the [ActivePlaylist]'s
 * properties change. The property [volume] describes the current volume for
 * both audio channels, and is initialized to the [ActivePlaylist]'s [volume]
 * field.
 *
 * The methods [play], [pause], and [stop] can be used to control playback of
 * the Player.
 *
 * If there is a problem with one or more [Uri]s within the [ActivePlaylist]'s
 * [tracks], the provided callback [onPlaybackFailure] will be invoked.
 *
 * @param context A [Context] instance. Note that the provided context instance
 *     is held onto for the lifetime of the Player instance, and so should not
 *     be a [Context] that the Player might outlive.
 * @param playlist The [ActivePlaylist] whose contents will be played
 * @param startImmediately Whether or not the Player should start playback
 *     as soon as it is ready
 * @param onPlaybackFailure A callback that will be invoked if playback fails
 *     for one or more [Uri]s in the playlist
 */
class Player(
    private val context: Context,
    private var playlist: ActivePlaylist,
    startImmediately: Boolean = false,
    private val onPlaybackFailure: (List<Uri>) -> Unit,
) {
    private var exoPlayer: ExoPlayer? = null
    private var volumeBooster: LoudnessEnhancer? = null
    private var targetBoostDb = playlist.volumeBoostDb
    private var isPlaying = startImmediately

    init {
        initializeExoPlayer(startImmediately)
    }

    fun play() {
        isPlaying = true
        exoPlayer?.playWhenReady = true
    }

    fun pause() {
        isPlaying = false
        exoPlayer?.pause()
    }

    fun stop() {
        isPlaying = false
        exoPlayer?.pause()
        exoPlayer?.seekToDefaultPosition()
    }

    fun setVolume(volume: Float) {
        exoPlayer?.volume = volume
    }

    /** Reset the Player to play the [newPlaylist]*/
    fun update(newPlaylist: ActivePlaylist, startImmediately: Boolean) {
        isPlaying = startImmediately

        if (newPlaylist.shuffle != playlist.shuffle || newPlaylist.tracks != playlist.tracks)
            initializeExoPlayer(startImmediately, newPlaylist)
        else {
            exoPlayer?.volume = newPlaylist.volume
            targetBoostDb = newPlaylist.volumeBoostDb
            applyVolumeBoost()
            exoPlayer?.playWhenReady = startImmediately
        }
        playlist = newPlaylist
    }

    fun release() {
        volumeBooster?.enabled = false
        exoPlayer?.release()
    }

    private fun initializeExoPlayer(
        startImmediately: Boolean,
        sourcePlaylist: ActivePlaylist = playlist,
    ) {
        targetBoostDb = sourcePlaylist.volumeBoostDb
        volumeBooster?.enabled = false
        exoPlayer?.release()

        val tracks = sourcePlaylist.tracks
        if (tracks.isEmpty()) {
            exoPlayer = null
            onPlaybackFailure(emptyList())
            return
        }

        val newPlayer = ExoPlayer.Builder(context).build().apply {
            val items = tracks.map(MediaItem::fromUri)
            setMediaItems(items)
            repeatMode = if (tracks.size < 2)
                ExoPlayerState.REPEAT_MODE_ONE
            else ExoPlayerState.REPEAT_MODE_ALL
            shuffleModeEnabled = sourcePlaylist.shuffle
            volume = sourcePlaylist.volume
            addListener(object : ExoPlayerState.Listener {
                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    applyVolumeBoost()
                }

                override fun onPlayerError(error: PlaybackException) {
                    val failed = currentMediaItem
                        ?.localConfiguration
                        ?.uri
                    onPlaybackFailure(if (failed == null) tracks else listOf(failed))
                }
            })
            prepare()
            playWhenReady = startImmediately
        }
        exoPlayer = newPlayer
        applyVolumeBoost()
    }

    private fun applyVolumeBoost() {
        val sessionId = exoPlayer?.audioSessionId ?: return
        volumeBooster?.enabled = false
        volumeBooster = if (targetBoostDb == 0) null else
            LoudnessEnhancer(sessionId).apply {
                setTargetGain(targetBoostDb * 100)
                enabled = true
            }
    }
}

/**
 * A collection of [Player] instances.
 *
 * [PlayerMap] manages a collection of [Player] instances for a collection
 * of [ActivePlaylist]s. The collection of [Player]s is updated via the
 * method [update]. Whether or not the collection of players is empty can
 * be queried with the property [isEmpty].
 *
 * The playing/paused/stopped state can be set for all [Player]s at once
 * with the methods [play], [pause], and [stop]. The volume for individual
 * playlists can be set with the method [setPlayerVolume]. The method
 * [releaseAll] should be called before the PlayerMap is destroyed so that
 * all [Player] instances can be released first.
 *
 * @param context A [Context] instance. Note that the context instance
 *     will be held onto for the lifetime of the [PlayerSet].
 * @param onPlaybackFailure The callback that will be invoked
 *     when playback for the provided list of [Uri]s has failed
 */
class PlayerMap(
    private val context: Context,
    private val onPlaybackFailure: (uris: List<Uri>) -> Unit,
) {
    private var playerMap: MutableMap<Long, Player> = hashMapOf()

    val isEmpty get() = playerMap.isEmpty()

    fun play() = playerMap.values.forEach(Player::play)
    fun pause() = playerMap.values.forEach(Player::pause)
    fun stop() = playerMap.values.forEach(Player::stop)

    fun setPlayerVolume(playlistId: Long, volume: Float) =
        playerMap[playlistId]?.setVolume(volume)

    fun releaseAll() = playerMap.values.forEach(Player::release)

    /** Update the PlayerSet with new [Player]s to match the provided [playlists].
     * If [startPlaying] is true, playback will start immediately. Otherwise, the
     * [Player]s will begin paused. */
    fun update(playlists: Map<ActivePlaylistSummary, List<Uri>>, startPlaying: Boolean) {
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
                    onPlaybackFailure = onPlaybackFailure,
                )
        }
        oldMap.values.forEach(Player::release)
    }
}