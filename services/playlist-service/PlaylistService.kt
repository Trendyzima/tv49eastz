package tv49eastz.services.playlistservice

import tv49eastz.core.model.Playlist
import tv49eastz.integration.contracts.PlaylistProvider

class PlaylistService(private val providers: List<PlaylistProvider>) {
    suspend fun loadPlaylists(): List<Playlist> = providers.flatMap { it.loadPlaylists() }.distinctBy(Playlist::id)
}
