/* This file is part of SoundAura, which is released under the terms of the Apache
 * License 2.0. See license.md in the project's root directory to see the full license. */

package com.cliffracertech.soundaura

import android.content.ComponentName
import android.content.Intent
import android.service.quicksettings.TileService
import android.util.Log
import com.cliffracertech.soundaura.service.PlayerService
import com.cliffracertech.soundaura.service.PlaylistRecoveryService
import com.cliffracertech.soundaura.service.TogglePlaybackTileService
import com.cliffracertech.soundaura.model.database.SoundAuraDatabase
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SoundAuraApplication : android.app.Application() {
    
    @Inject
    lateinit var database: SoundAuraDatabase

    // BUG-8 fix: keep a named reference so addPlaybackChangeListener's contains() check
    // works correctly across warm restarts (anonymous lambdas are always new instances).
    private val tileUpdateListener = PlayerService.Companion.PlaybackChangeListener {
        if (!TogglePlaybackTileService.listening)
            TileService.requestListeningState(
                this, ComponentName(this, TogglePlaybackTileService::class.java))
    }

    override fun onCreate() {
        super.onCreate()

        PlayerService.addPlaybackChangeListener(listener = tileUpdateListener)

        // --- Start the recovery service on app startup ---
        // Esto intentará adquirir permisos persistentes para todos los tracks existentes.
        val recoveryIntent = Intent(this, PlaylistRecoveryService::class.java)
        startService(recoveryIntent)
    }
}

fun logd(message: String) = Log.d("SoundAuraTag", message)