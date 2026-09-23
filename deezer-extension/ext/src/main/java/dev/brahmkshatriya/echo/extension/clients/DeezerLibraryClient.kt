package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.DeezerApi
import dev.brahmkshatriya.echo.extension.DeezerExtension
import dev.brahmkshatriya.echo.extension.DeezerParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class DeezerLibraryClient(
    private val deezerExtension: DeezerExtension,
    private val api: DeezerApi,
    private val parser: DeezerParser,
    private val cpuDispatcher: CoroutineDispatcher = Dispatchers.Default
) {

    private val tabs: List<Tab> = listOf(
        Tab(TabId.ALL.id, "All"),
        Tab(TabId.PLAYLISTS.id, "Playlists"),
        Tab(TabId.ALBUMS.id, "Albums"),
        Tab(TabId.TRACKS.id, "Tracks"),
        Tab(TabId.ARTISTS.id, "Artists"),
    )

    private data class TabConfig(
        val id: TabId,
        val title: String,
        val request: suspend DeezerApi.() -> JsonObject,
        val extractor: (JsonObject) -> JsonArray?
    )

    private enum class TabId(val id: String) {
        ALL("all"),
        PLAYLISTS("playlists"),
        ALBUMS("albums"),
        TRACKS("tracks"),
        ARTISTS("artists")
    }

    private val configs: Map<String, TabConfig> = listOf(
        TabConfig(TabId.PLAYLISTS, "Playlists", { getPlaylists() }) { it.tabDataArray("playlists") },
        TabConfig(TabId.ALBUMS, "Albums", { getAlbums() }) { it.tabDataArray("albums") },
        TabConfig(TabId.TRACKS, "Tracks", { getTracks() }) { it.resultsDataArray() },
        TabConfig(TabId.ARTISTS, "Artists", { getArtists() }) { it.tabDataArray("artists") },
    ).associateBy { it.id.id }


    suspend fun loadLibraryFeed(): Feed<Shelf> {
        deezerExtension.handleArlExpiration()
        return Feed(tabs) { tab ->
            val id = tab?.id
            val data = when (id) {
                TabId.ALL.id -> loadAll()
                else -> loadSingle(id)
            }
            val buttons = if (id == TabId.TRACKS.id) Feed.Buttons(showPlayAndShuffle = true)
            else Feed.Buttons()
            data.toFeedData(buttons)
        }
    }

    private suspend fun loadAll(): List<Shelf> = supervisorScope {
        deezerExtension.handleArlExpiration()
        configs.values.map { cfg ->
            async(cpuDispatcher) {
                val json = cfg.request(api)
                val items = cfg.extractor(json) ?: return@async null
                if (cfg.id == TabId.TRACKS) {
                    val grafted = items.mapNotNull { el -> (el as? JsonObject)?.let { graftFavTrack(it) } }
                    grafted.takeIf { it.isNotEmpty() }
                        ?.let { Shelf.Lists.Items(id = cfg.title, title = cfg.title, list = it) }
                } else if (cfg.id == TabId.PLAYLISTS) {
                    withFavoritesCard(cfg.title, parser.run { items.toShelfItemsList(cfg.title) })
                } else parser.run { items.toShelfItemsList(cfg.title) }
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun loadSingle(id: String?): List<Shelf> {
        val cfg = configs[id] ?: return emptyList()
        deezerExtension.handleArlExpiration()
        val json = cfg.request(api)
        val arr = cfg.extractor(json) ?: return emptyList()
        if (id == TabId.TRACKS.id)
            return arr.mapNotNull { el -> (el as? JsonObject)?.let { graftFavTrack(it).toShelf() } }
        val items = parser.run { arr.mapNotNull { it.jsonObject.toEchoMediaItem()?.toShelf() } }
        if (id == TabId.PLAYLISTS.id) return prependCardToPlaylists(items)
        return items
    }

    private fun JsonObject.results(): JsonObject? = this["results"]?.jsonObject
    private fun JsonObject.resultsDataArray(): JsonArray? =
        results()?.get("data")?.jsonArray
    private fun JsonObject.tabDataArray(tabId: String): JsonArray? =
        results()?.get("TAB")?.jsonObject?.get(tabId)?.jsonObject?.get("data")?.jsonArray

    // FALLBACK-graft for favorites/liked TRACKS — now DeezerParser.graftFavTrack (shared
    // with DeezerPlaylistClient's virtual Favorite Tracks playlist). Kept as a one-line
    // delegate rather than inlining the call sites so both shelves keep reading identically.
    private fun graftFavTrack(entry: JsonObject): Track = parser.graftFavTrack(entry)

    companion object {
        // Virtual "Favorite Tracks" playlist: Deezer's tab_playlist no longer carries the
        // favorites pseudo-playlist the real app shows, so the library synthesizes the card
        // at the head of the Playlists shelf (All tab and Playlists tab alike, even when the
        // user owns zero playlists). The routing key is DeezerPlaylistClient.FAVORITES_EXTRA
        // (same precedent as SMART_TRACKLIST_EXTRA); the id is non-numeric so it can never
        // collide with a real playlist id. Opening the card yields a Playlist context, so
        // taps play the likes in order via the existing ordered-collection path — no
        // tap-logic change needed.
        const val FAVORITES_ID = "favorites"
        const val FAVORITES_TITLE = "Favorite Tracks"

        fun favoritesCard(): Playlist = Playlist(
            id = FAVORITES_ID,
            title = FAVORITES_TITLE,
            isEditable = false,
            // Not a real playlist: nothing to save/follow/share, and radio has no id
            // Deezer could resolve. Ordered playback (the card's whole job) needs none.
            isRadioSupported = false,
            isFollowable = false,
            isSaveable = false,
            isShareable = false,
            extras = mapOf(DeezerPlaylistClient.FAVORITES_EXTRA to "1")
        )

        // Head-of-shelf placement for both Playlists surfaces. Internal for tests;
        // loadAll (All tab carousel) and prependCardToPlaylists (Playlists tab rows)
        // are the only callers.
        internal fun withFavoritesCard(title: String, shelf: Shelf?): Shelf? {
            val card = favoritesCard()
            val list = (shelf as? Shelf.Lists.Items)?.list.orEmpty()
            return Shelf.Lists.Items(id = title, title = title, list = listOf(card) + list)
        }

        internal fun prependCardToPlaylists(items: List<Shelf>): List<Shelf> =
            listOf(favoritesCard().toShelf()) + items
    }
}