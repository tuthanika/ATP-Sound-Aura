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
import kotlinx.coroutines.SupervisorJob
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
        // BUG-7 fix: use try/finally so stopSelf() is guaranteed to run even if
        // recoverPlaylistPermissions() throws. Without this the service becomes a zombie.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                recoverPlaylistPermissions()
            } catch (e: Exception) {
                android.util.Log.e("PlaylistRecovery", "Recovery failed", e)
            } finally {
                stopSelf()
            }
        }
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
        // stopSelf() moved to finally block in onStartCommand
    }
}
