package com.cliffracertech.soundaura.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.cliffracertech.soundaura.model.UriPermissionHandler
import com.cliffracertech.soundaura.model.database.PlaylistDao
import com.cliffracertech.soundaura.model.database.Track
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class PlaylistRecoveryService : Service() {

    @Inject
    lateinit var playlistDao: PlaylistDao

    @Inject
    lateinit var permissionHandler: UriPermissionHandler

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Run recovery in the background without blocking the main thread
        CoroutineScope(Dispatchers.IO).launch {
            recoverPlaylistPermissions()
        }
        // We don't want the service to stay alive if not necessary.
        // It will stop itself when the coroutine finishes.
        return START_NOT_STICKY
    }

    private suspend fun recoverPlaylistPermissions() {
        // 1. Get all tracks from the database
        val allTracks: List<Track> = playlistDao.getAllTracks()

        // 2. Filter for URIs that do not already have persistable permissions
        val persistedUris = contentResolver.persistedUriPermissions.map { it.uri }.toSet()
        val urisToRecover = allTracks.map { it.uri }.filter { it !in persistedUris }

        if (urisToRecover.isNotEmpty()) {
            // 3. Attempt to acquire permissions. This will not block the UI.
            permissionHandler.acquirePermissionsFor(urisToRecover)
        }

        // 4. Stop the service once finished
        stopSelf()
    }
}
