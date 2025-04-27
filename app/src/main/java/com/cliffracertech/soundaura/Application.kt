/* This file is part of SoundAura, which is released under the terms of the Apache
 * License 2.0. See license.md in the project's root directory to see the full license. */

package com.cliffracertech.soundaura

import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.content.ContextCompat
import com.cliffracertech.soundaura.service.PlayerService
import com.cliffracertech.soundaura.service.TogglePlaybackTileService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

@HiltAndroidApp
class SoundAuraApplication : android.app.Application() {
    override fun onCreate() {
        super.onCreate()

        PlayerService.addPlaybackChangeListener {
            TogglePlaybackTileService.updateState(
                context = applicationContext, state = it)
        }

        TogglePlaybackTileService.addPlaybackStateRequestListener {
            if (it == PlaybackStateCompat.STATE_PLAYING)
                ContextCompat.startForegroundService(
                    this, PlayerService.playIntent(this))
            else startService(PlayerService.stopIntent(this))
        }
    }
}

@Module @InstallIn(SingletonComponent::class)
class DispatcherModule {
    @Singleton @Provides
    fun dispatcherProvider() = Dispatchers.IO
}

fun logd(message: String) = Log.d("SoundAuraTag", message)