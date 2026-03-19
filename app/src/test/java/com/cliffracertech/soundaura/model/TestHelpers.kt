/* This file is part of SoundAura, which is released under
 * the terms of the Apache License 2.0. See license.md in
 * the project's root directory to see the full license. */
package com.cliffracertech.soundaura.model

import android.net.Uri
import java.time.Duration
import java.time.Instant

/** A mock [UriPermissionHandler] whose methods simulate
 * a limited number of permission allowances. */
class TestPermissionHandler: UriPermissionHandler {
    private val grantedPermissions = mutableSetOf<Uri>()

    override val totalAllowance = 12
    override val usedAllowance get() = grantedPermissions.size

    override fun acquirePermissionsFor(uris: List<Uri>): Boolean {
        val newUris = uris.filterNot(grantedPermissions::contains)
        val hasEnoughSpace = remainingAllowance >= uris.size
        if (hasEnoughSpace)
            grantedPermissions.addAll(newUris)
        return hasEnoughSpace
    }
    override fun releasePermissionsFor(uris: List<Uri>) {
        grantedPermissions.removeAll(uris.toSet())
    }
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
