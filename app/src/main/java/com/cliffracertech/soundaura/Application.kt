/* This file is part of SoundAura, which is released under the terms of the Apache
 * License 2.0. See license.md in the project's root directory to see the full license. */

package com.cliffracertech.soundaura

import android.content.ComponentName
import android.service.quicksettings.TileService
import android.util.Log
import com.cliffracertech.soundaura.service.PlayerService
import com.cliffracertech.soundaura.service.TogglePlaybackTileService
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SoundAuraApplication : android.app.Application() {
    override fun onCreate() {
        super.onCreate()

        PlayerService.addPlaybackChangeListener {
            if (!TogglePlaybackTileService.listening)
                TileService.requestListeningState(
                    this, ComponentName(this, TogglePlaybackTileService::class.java))
        }
    }
}

fun logd(message: String) = Log.d("SoundAuraTag", message)