/* This file is part of SoundAura, which is released under
 * the terms of the Apache License 2.0. See license.md in
 * the project's root directory to see the full license. */
package com.cliffracertech.soundaura

import android.content.Context
import android.content.Intent
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cliffracertech.soundaura.model.database.Track
import com.cliffracertech.soundaura.service.PlayerService
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerServiceTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @get:Rule val dbTestRule = SoundAuraDbTestRule(context)

    private val dao get() = dbTestRule.db.playlistDao()
    private val testPlaylistUri = "test playlist 1"

    @Before fun init() {
        // This test track is added so that PlayerService doesn't prevent
        // changes to STATE_PLAYING due to there not being any active playlists.
        runTest {
            val id = dao.insertPlaylist(
                playlistName = testPlaylistUri,
                shuffle = false,
                tracks = listOf(Track(testPlaylistUri.toUri())))
            dao.toggleIsActive(id)
        }
    }

    @After fun cleanup() {
        context.stopService(Intent(context, PlayerService::class.java))
        runTest { waitUntil {
            PlayerService.playbackState == PlaybackStateCompat.STATE_STOPPED
        }}
    }

    @Test fun playback_begins_in_paused_state_by_default() = runTest {
        context.startService(Intent(context, PlayerService::class.java))
        waitUntil { PlayerService.playbackState != PlaybackStateCompat.STATE_STOPPED }
        assertThat(PlayerService.playbackState).isEqualTo(PlaybackStateCompat.STATE_PAUSED)
    }

    @Test fun play_intent() = runTest {
        waitUntil { !dao.getNoPlaylistsAreActive().first() }
        context.startService(PlayerService.playIntent(context))
        waitUntil { PlayerService.playbackState == PlaybackStateCompat.STATE_PLAYING }
        assertThat(PlayerService.playbackState).isEqualTo(PlaybackStateCompat.STATE_PLAYING)
    }

    @Test fun pause_intent() = runTest {
        context.startService(PlayerService.pauseIntent(context))
        waitUntil { PlayerService.playbackState != PlaybackStateCompat.STATE_STOPPED }
        assertThat(PlayerService.playbackState).isEqualTo(PlaybackStateCompat.STATE_PAUSED)
    }

    @Test fun stop_intent_while_stopped_no_ops() = runTest {
        context.startService(PlayerService.stopIntent(context))
        waitUntil { PlayerService.playbackState != PlaybackStateCompat.STATE_STOPPED } // should time out
        assertThat(PlayerService.playbackState).isEqualTo(PlaybackStateCompat.STATE_STOPPED)
    }

    @Test fun stop_intent_while_playing() = runTest {
        waitUntil { !dao.getNoPlaylistsAreActive().first() }
        context.startService(PlayerService.playIntent(context))
        waitUntil { PlayerService.playbackState != PlaybackStateCompat.STATE_STOPPED }
        context.startService(PlayerService.stopIntent(context))
        waitUntil { PlayerService.playbackState != PlaybackStateCompat.STATE_PLAYING }
        assertThat(PlayerService.playbackState).isEqualTo(PlaybackStateCompat.STATE_STOPPED)
    }

    @Test fun binding_succeeds() = runTest {
        assertThat(PlayerService.binder).isNull()
        context.startService(Intent(context, PlayerService::class.java))
        waitUntil { PlayerService.binder != null }
        assertThat(PlayerService.binder).isNotNull()
    }

    @Test fun binder_is_playing_state() = runTest {
        context.startService(Intent(context, PlayerService::class.java))
        waitUntil { PlayerService.binder != null }
        val binder = PlayerService.binder
        assertThat(binder?.isPlaying).isFalse()

        context.startService(PlayerService.playIntent(context))
        waitUntil { PlayerService.playbackState != PlaybackStateCompat.STATE_PAUSED }
        assertThat(binder?.isPlaying).isTrue()

        context.startService(PlayerService.pauseIntent(context))
        waitUntil { PlayerService.playbackState != PlaybackStateCompat.STATE_PLAYING }
        assertThat(binder?.isPlaying).isFalse()

        context.startService(PlayerService.stopIntent(context))
        waitUntil { PlayerService.playbackState != PlaybackStateCompat.STATE_PAUSED }
        assertThat(binder?.isPlaying).isFalse()
    }

    @Test fun binder_toggle_is_playing() = runTest {
        context.startService(Intent(context, PlayerService::class.java))
        waitUntil { PlayerService.binder != null }
        val binder = PlayerService.binder
        assertThat(binder?.isPlaying).isFalse()

        binder?.toggleIsPlaying()
        waitUntil { PlayerService.playbackState != PlaybackStateCompat.STATE_STOPPED }
        assertThat(binder?.isPlaying).isTrue()

        binder?.toggleIsPlaying()
        waitUntil { PlayerService.playbackState != PlaybackStateCompat.STATE_PLAYING }
        assertThat(binder?.isPlaying).isFalse()
    }

    @Test fun service_prevents_playing_with_no_active_playlists() = runTest {
        context.startService(Intent(context, PlayerService::class.java))
        waitUntil { PlayerService.binder != null }
        waitUntil { PlayerService.playbackState != PlaybackStateCompat.STATE_STOPPED } // should time out
        assertThat(PlayerService.playbackState).isEqualTo(PlaybackStateCompat.STATE_STOPPED)

        val id = dao.getPlaylistsSortedByNameAsc().first().first().id
        dao.toggleIsActive(id)
        waitUntil { !dao.getNoPlaylistsAreActive().first() }

        context.startService(PlayerService.playIntent(context))
        waitUntil { PlayerService.playbackState == PlaybackStateCompat.STATE_PLAYING } // should time out
        assertThat(PlayerService.playbackState).isEqualTo(PlaybackStateCompat.STATE_PAUSED)

        waitUntil { PlayerService.playbackState == PlaybackStateCompat.STATE_PLAYING } // should time out
        assertThat(PlayerService.playbackState).isEqualTo(PlaybackStateCompat.STATE_PAUSED)
    }
}