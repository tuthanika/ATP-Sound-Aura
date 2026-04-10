/* This file is part of SoundAura, which is released under
 * the terms of the Apache License 2.0. See license.md in
 * the project's root directory to see the full license. */
package com.cliffracertech.soundaura.service

import android.os.Build
import android.service.quicksettings.Tile.STATE_ACTIVE
import android.service.quicksettings.Tile.STATE_INACTIVE
import android.service.quicksettings.TileService
import android.support.v4.media.session.PlaybackStateCompat
import com.cliffracertech.soundaura.R

/**
 * A [TileService] to control the app's audio playback.
 *
 * In order to update the tile's visual state, the static function
 * [TileService.requestListeningState] must be called after changes are made to
 * [PlayerService]'s playback state if the static property [listening] is false.
 * The tile will appear in its active state if the app's playback state is
 * equal to [PlaybackStateCompat.STATE_PLAYING], and will appear inactive
 * otherwise.
 *
 * Tile clicks will attempt to modify the playback state to
 * [PlaybackStateCompat.STATE_PLAYING] or [PlaybackStateCompat.STATE_STOPPED]
 * when the tile is clicked in its inactive state or its active state,
 * respectively.
 */
class TogglePlaybackTileService: TileService() {

    companion object {
        var listening = false
            private set
    }

    private val playbackChangeListener =
        PlayerService.Companion.PlaybackChangeListener { newState: Int ->
            // BUG-9 fix: use newState parameter directly (not PlayerService.playbackState)
            // to eliminate TOCTOU race condition. Also add null guard on qsTile.
            val tile = qsTile ?: return@PlaybackChangeListener
            tile.label = getString(R.string.app_name)
            if (newState == PlaybackStateCompat.STATE_PLAYING) {
                tile.state = STATE_ACTIVE
                tile.contentDescription = getString(R.string.tile_active_description)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    tile.subtitle = getString(R.string.playing)
            } else {
                tile.state = STATE_INACTIVE
                tile.contentDescription = getString(R.string.tile_inactive_description)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    tile.subtitle = getString(
                        if (newState == PlaybackStateCompat.STATE_PAUSED)
                            R.string.paused
                        else R.string.stopped)
            }
            tile.updateTile()
        }

    override fun onStartListening() {
        listening = true
        PlayerService.addPlaybackChangeListener(
            updateImmediately = true, playbackChangeListener)
    }

    override fun onStopListening() {
        listening = false
        PlayerService.removePlaybackChangeListener(playbackChangeListener)
    }

    override fun onClick() {
        if (PlayerService.playbackState == PlaybackStateCompat.STATE_PLAYING)
            startService(PlayerService.stopIntent(this))
        else startService(PlayerService.playIntent(this))
    }
}