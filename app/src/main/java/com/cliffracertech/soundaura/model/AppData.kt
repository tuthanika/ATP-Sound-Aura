/* This file is part of SoundAura, which is released under
 * the terms of the Apache License 2.0. See license.md in
 * the project's root directory to see the full license. */
package com.cliffracertech.soundaura.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class TrackMetadata(
    val uri: String,
    val hasError: Boolean,
    val loopEnabled: Boolean
)

@Serializable
data class PlaylistMetadata(
    val id: Long,
    val name: String,
    val shuffle: Boolean,
    val playSequentially: Boolean,
    val isActive: Boolean,
    val volume: Float,
    val volumeBoostDb: Int
)

@Serializable
data class PlaylistTrackMetadata(
    val playlistId: Long,
    val playlistOrder: Int,
    val trackUri: String,
    val volume: Float = 1f
)

@Serializable
data class PresetMetadata(
    val name: String
)

@Serializable
data class PresetPlaylistMetadata(
    val presetName: String,
    val playlistName: String,
    val playlistVolume: Float
)

@Serializable
data class AppData(
    val version: Int = 1,
    val settings: Map<String, JsonElement>,
    val tracks: List<TrackMetadata>,
    val playlists: List<PlaylistMetadata>,
    val playlistTracks: List<PlaylistTrackMetadata>,
    val presets: List<PresetMetadata>,
    val presetPlaylists: List<PresetPlaylistMetadata>
)
