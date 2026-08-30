package tv49eastz.integration.contracts

import tv49eastz.core.model.Playlist

/** Imports and normalizes playlists into the canonical domain model. */
interface PlaylistProvider {
    suspend fun loadPlaylists(): List<Playlist>
}
