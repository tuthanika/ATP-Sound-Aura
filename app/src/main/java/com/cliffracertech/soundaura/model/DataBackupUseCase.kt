/* This file is part of SoundAura, which is released under
 * the terms of the Apache License 2.0. See license.md in
 * the project's root directory to see the full license. */
package com.cliffracertech.soundaura.model

import android.provider.MediaStore
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.cliffracertech.soundaura.Dispatcher
import com.cliffracertech.soundaura.toAbsolutePathOrNull
import com.cliffracertech.soundaura.model.database.*
import com.cliffracertech.soundaura.settings.PrefKeys
import com.cliffracertech.soundaura.model.UriPermissionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.InputStream
import java.io.OutputStream
import androidx.documentfile.provider.DocumentFile
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DataBackupUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playlistDao: PlaylistDao,
    private val presetDao: PresetDao,
    private val dataStore: DataStore<Preferences>,
    private val database: SoundAuraDatabase,
    private val permissionHandler: UriPermissionHandler,
) {
    private val json = Json { 
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun exportAppData(outputStream: OutputStream) = withContext(Dispatcher.IO) {
        val prefs = dataStore.data.first().asMap()
        val settings = prefs.mapKeys { it.key.name }.mapValues { (_, value) ->
            when (value) {
                is Boolean -> JsonPrimitive(value)
                is Int -> JsonPrimitive(value)
                is Long -> JsonPrimitive(value)
                is Float -> JsonPrimitive(value)
                is Double -> JsonPrimitive(value)
                else -> JsonPrimitive(value.toString())
            }
        }

        val tracks = playlistDao.getAllTracks().map {
            TrackMetadata(it.uri.toString(), it.hasError, it.loopEnabled)
        }
        val playlists = playlistDao.getAllPlaylists().map {
            PlaylistMetadata(it.id, it.name, it.shuffle, it.playSequentially, 
                             it.isActive, it.volume, it.volumeBoostDb)
        }
        val playlistTracks = playlistDao.getAllPlaylistTracks().map {
            PlaylistTrackMetadata(it.playlistId, it.playlistOrder, it.trackUri.toString(), it.volume)
        }
        val presets = presetDao.getAllPresets().map {
            PresetMetadata(it.name)
        }
        val presetPlaylists = presetDao.getAllPresetPlaylists().map {
            PresetPlaylistMetadata(it.presetName, it.playlistName, it.playlistVolume)
        }

        val appData = AppData(
            version = 1,
            settings = settings,
            tracks = tracks,
            playlists = playlists,
            playlistTracks = playlistTracks,
            presets = presets,
            presetPlaylists = presetPlaylists
        )

        val jsonString = json.encodeToString(appData)
        outputStream.write(jsonString.toByteArray())
    }

    suspend fun importAppData(inputStream: InputStream): Result<Unit> = withContext(Dispatcher.IO) {
        try {
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val appData = json.decodeFromString<AppData>(jsonString)

            // BUG-2 fix: removed two empty runInTransaction {} blocks that were
            // left over from refactoring and served no purpose.
            database.clearAllTables()

            // Restore DB
            val uriMap = mutableMapOf<String, Uri>()
            val tracks = appData.tracks.map { track ->
                val uriString = track.uri
                var finalUri = Uri.parse(uriString)
                var isReadable = false

                // 1. Thử xem URI cũ còn sống không
                try {
                    context.contentResolver.openInputStream(finalUri)?.close()
                    isReadable = true
                } catch (e: Exception) {}

                // 2. BỘ MÁY DỊCH NGƯỢC AUTO-RELINK (Mô phỏng sức mạnh của Rolify)
                if (!isReadable && uriString.startsWith("content://com.android.externalstorage.documents")) {
                    try {
                        val decodedUrl = Uri.decode(uriString)
                        val pathComponent = decodedUrl.substringAfterLast("/document/", "")

                        if (pathComponent.isNotEmpty() && pathComponent.contains(":")) {
                            val split = pathComponent.split(":", limit = 2)
                            val volume = split[0]
                            val filePath = split[1]
                            val fileName = filePath.substringAfterLast("/")

                            // Tạo đường dẫn file vật lý (POSIX)
                            val rawAbsolutePath = if (volume.equals("primary", ignoreCase = true)) {
                                "/storage/emulated/0/$filePath"
                            } else {
                                "/storage/$volume/$filePath"
                            }

                            // SEC-1 fix: validate canonical path to prevent path traversal attacks
                            // from a malicious backup JSON file.
                            val canonicalPath = try { java.io.File(rawAbsolutePath).canonicalPath } catch (e: Exception) { null }
                            val allowedRoot = if (volume.equals("primary", ignoreCase = true))
                                "/storage/emulated/0"
                            else "/storage/$volume"

                            if (canonicalPath == null || !canonicalPath.startsWith(allowedRoot)) {
                                // Path traversal detected — skip auto-relink for this track
                                android.util.Log.w("DataBackup", "Path traversal attempt blocked for URI: $uriString")
                            } else {
                                val absolutePath = canonicalPath

                                // Cứu cánh 1: Dùng quyền Global đọc thẳng File vật lý
                                val file = java.io.File(absolutePath)
                                if (file.exists() && file.canRead()) {
                                    finalUri = Uri.fromFile(file)
                                    isReadable = true
                                }
                                // Cứu cánh 2: Dùng MediaStore.Files tóm cổ file qua Tên và Đường dẫn
                                else {
                                    val projection = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DATA)
                                    val urisToQuery = mutableListOf(MediaStore.Files.getContentUri("external"))

                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                        MediaStore.getExternalVolumeNames(context).forEach {
                                            urisToQuery.add(MediaStore.Files.getContentUri(it))
                                        }
                                    }

                                    for (queryUri in urisToQuery) {
                                        context.contentResolver.query(
                                            queryUri, projection,
                                            "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ?", arrayOf(fileName), null
                                        )?.use { cursor ->
                                            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                                            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)

                                            // Quét tìm file có đường dẫn trùng khớp tuyệt đối
                                            while (cursor.moveToNext()) {
                                                val data = cursor.getString(dataCol)
                                                if (data == absolutePath) {
                                                    val id = cursor.getLong(idCol)
                                                    finalUri = ContentUris.withAppendedId(queryUri, id)
                                                    isReadable = true
                                                    break
                                                }
                                            }
                                            // Nếu không khớp đường dẫn, lấy tạm file đầu tiên trùng tên
                                            if (!isReadable && cursor.moveToFirst()) {
                                                val id = cursor.getLong(idCol)
                                                finalUri = ContentUris.withAppendedId(queryUri, id)
                                                isReadable = true
                                            }
                                        }
                                        if (isReadable) break
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {}
                }

                uriMap[track.uri] = finalUri
                
                // Mặc định nạp thẳng đường dẫn an toàn mới vào hệ thống
                Track(finalUri, hasError = !isReadable, track.loopEnabled)
            }
            playlistDao.insertTracks(tracks)
            
            // Cố gắng xin/phục hồi quyền truy cập (nếu hệ thống cho phép)
            permissionHandler.acquirePermissionsFor(tracks.map { it.uri })

            playlistDao.insertPlaylists(appData.playlists.map {
                Playlist(it.id, it.name, it.shuffle, it.playSequentially, 
                         it.isActive, it.volume, it.volumeBoostDb)
            })
            playlistDao.insertPlaylistTracks(appData.playlistTracks.map {
                val finalUri = uriMap[it.trackUri] ?: Uri.parse(it.trackUri)
                PlaylistTrack(it.playlistId, it.playlistOrder, finalUri, it.volume)
            })
            presetDao.insertPresets(appData.presets.map { Preset(it.name) })
            presetDao.insertPresetPlaylists(appData.presetPlaylists.map {
                PresetPlaylist(it.presetName, it.playlistName, it.playlistVolume)
            })

            // Restore Settings — only restore known keys to avoid garbage in DataStore.
                // IMP-3 fix: notificationPermissionRequested is intentionally excluded because
                // restoring it would prevent a fresh device from ever being asked for
                // notification permission after a backup restore.
                dataStore.edit { prefs ->
                appData.settings.forEach { (keyName, value) ->
                    val valuePrimitive = value as? JsonPrimitive ?: return@forEach
                    when (keyName) {
                        PrefKeys.showActivePlaylistsFirst,
                        PrefKeys.playInBackground,
                        PrefKeys.stopInsteadOfPause,
                        PrefKeys.playButtonLongClickHintShown,
                        PrefKeys.isVolumeSliderVisible -> {
                            prefs[booleanPreferencesKey(keyName)] = valuePrimitive.boolean
                        }
                        PrefKeys.lastLaunchedVersionCode,
                        PrefKeys.playlistSort,
                        PrefKeys.appTheme,
                        PrefKeys.onZeroVolumeAudioDeviceBehavior -> {
                            prefs[intPreferencesKey(keyName)] = valuePrimitive.int
                        }
                        PrefKeys.masterVolume -> {
                            prefs[floatPreferencesKey(keyName)] = valuePrimitive.float
                        }
                        PrefKeys.activePresetName -> {
                            prefs[stringPreferencesKey(keyName)] = valuePrimitive.content
                        }
                    }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun relinkTracks(rootFolderUri: Uri): Int = withContext(Dispatcher.IO) {
        // Take a persistable permission on the tree URI so all files inside remain
        // accessible after the app restarts (tree grants cover the whole subtree).
        try {
            context.contentResolver.takePersistableUriPermission(
                rootFolderUri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            // Permission may already be persisted or not grantable; continue anyway
        }

        var relinkCount = 0
        val allTracks = playlistDao.getAllTracks()
        val rootFolder = DocumentFile.fromTreeUri(context, rootFolderUri) ?: return@withContext 0
        
        val allFiles = listFilesRecursive(rootFolder)
        
        val oldUrisToRelease = mutableListOf<Uri>()
        val newUrisToAcquire = mutableListOf<Uri>()

        allTracks.forEach { track ->
            val fileName = track.uri.lastPathSegment
                ?.substringAfterLast('/')?.substringAfterLast(':') ?: return@forEach

            // BUG-6 fix: if multiple files share the same name, skip relinking to avoid
            // false-positive matches (e.g. rain.mp3 in /Music/ AND /Downloads/).
            val candidates = allFiles.filter { it.name == fileName }
            val matchingFile = when {
                candidates.size == 1 -> candidates.first()
                candidates.size > 1 -> {
                    android.util.Log.w("DataBackup", "Ambiguous relink: $fileName has ${candidates.size} matches — skipping")
                    null
                }
                else -> null
            }

            if (matchingFile != null && matchingFile.uri != track.uri) {
                playlistDao.updateTrackUri(track.uri, matchingFile.uri)
                playlistDao.setTrackHasError(matchingFile.uri, false)
                oldUrisToRelease.add(track.uri)
                newUrisToAcquire.add(matchingFile.uri)
                relinkCount++
            } else if (matchingFile != null && matchingFile.uri == track.uri && track.hasError) {
                // Same URI but marked as error — just clear the error
                playlistDao.setTrackHasError(track.uri, false)
                relinkCount++
            }
        }

        // Release old permissions and acquire new ones so they persist after restart
        if (newUrisToAcquire.isNotEmpty()) {
            permissionHandler.releasePermissionsFor(oldUrisToRelease)
            permissionHandler.acquirePermissionsFor(newUrisToAcquire)
        }

        relinkCount
    }

    private fun listFilesRecursive(directory: DocumentFile, depth: Int = 0): List<DocumentFile> {
        if (depth > 5) return emptyList()
        val files = mutableListOf<DocumentFile>()
        // IMP-6 fix: wrap in try-catch — listFiles() can crash if the SD card is unmounted
        // or the permission was revoked between acquiring the tree URI and iterating.
        val children = try { directory.listFiles() } catch (e: Exception) {
            android.util.Log.w("DataBackup", "listFiles() failed for ${directory.uri}", e)
            emptyArray()
        }
        children.forEach { file ->
            if (file.isDirectory) {
                files.addAll(listFilesRecursive(file, depth + 1))
            } else if (file.name != null) {
                files.add(file)
            }
        }
        return files
    }
}
