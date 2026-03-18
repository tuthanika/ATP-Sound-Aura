package com.cliffracertech.soundaura.di

import android.content.Context
import com.cliffracertech.soundaura.model.AndroidUriPermissionHandler
import com.cliffracertech.soundaura.model.UriPermissionHandler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object UriPermissionModule {

    @Provides
    @Singleton
    fun provideUriPermissionHandler(
        @ApplicationContext context: Context
    ): UriPermissionHandler {
        // Aquí devolvemos la implementación concreta que queremos que use la app
        return AndroidUriPermissionHandler(context)
    }
}