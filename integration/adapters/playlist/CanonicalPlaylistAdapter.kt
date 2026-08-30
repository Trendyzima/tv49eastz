package tv49eastz.integration.adapters.playlist

import tv49eastz.core.model.Playlist
import tv49eastz.integration.contracts.PlaylistProvider

/** Adapter seam for any M3U/playlist implementation. */
class CanonicalPlaylistAdapter(
    private val loader: suspend () -> List<Playlist>
) : PlaylistProvider {
    override suspend fun loadPlaylists(): List<Playlist> = loader()
}
