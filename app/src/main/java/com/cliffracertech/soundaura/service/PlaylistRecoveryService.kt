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
        // Corremos la recuperación en segundo plano sin bloquear el hilo principal
        CoroutineScope(Dispatchers.IO).launch {
            recoverPlaylistPermissions()
        }
        // No queremos que el servicio se mantenga vivo si no es necesario.
        // Se detendrá a sí mismo cuando termine la corrutina.
        return START_NOT_STICKY
    }

    private suspend fun recoverPlaylistPermissions() {
        // 1. Obtener todas las pistas de la base de datos
        val allTracks: List<Track> = playlistDao.getAllTracks()

        // 2. Intentar adquirir permisos para todas las URIs
        val urisToRecover = allTracks.map { it.uri }

        if (urisToRecover.isNotEmpty()) {
            // 3. Intentar adquirir permisos. Esto no bloqueará la UI.
            permissionHandler.acquirePermissionsFor(urisToRecover)
        }

        // 4. Detener el servicio una vez finalizado
        stopSelf()
    }
}
