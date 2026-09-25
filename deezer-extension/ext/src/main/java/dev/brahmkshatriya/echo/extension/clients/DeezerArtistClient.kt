package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.DeezerApi
import dev.brahmkshatriya.echo.extension.DeezerExtension
import dev.brahmkshatriya.echo.extension.DeezerParser
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DeezerArtistClient(private val deezerExtension: DeezerExtension, private val api: DeezerApi, private val parser: DeezerParser) {

    fun getShelves(artist: Artist): Feed<Shelf> = PagedData.Single {
        deezerExtension.handleArlExpiration()
        // The artist page and the likes fetch run concurrently (no shared lock on the
        // gateway path): cold-open latency is the SLOWER of the two, not the sum.
        // Warm (session cache hit) the liked branch is a memory filter and adds nothing.
        supervisorScope {
            val base = async { loadBaseShelves(artist) }
            val liked = async { buildLikedShelf(artist) }
            listOfNotNull(liked.await()) + base.await()
        }
    }.toFeed()

    private suspend fun loadBaseShelves(artist: Artist): List<Shelf> {
        val jsonObject = api.artist(artist.id)
        val resultsObject = jsonObject["results"]?.jsonObject ?: return emptyList()
        return orderedKeys.flatMap { key ->
            val payload = resultsObject[key] ?: return@flatMap emptyList()
            when (key) {
                "ALBUMS" -> listOfNotNull(buildAlbumsShelf(artist, payload.jsonObject))
                "TOP" -> buildTopTracksShelf(artist, payload.jsonObject)
                "RELATED_ARTISTS" -> listOfNotNull(buildRelatedArtistsShelf(artist, payload.jsonObject))
                else -> listOfNotNull(shelfFactories[key]?.invoke(parser, payload.jsonObject))
            }
        }
    }

    private fun buildTopTracksShelf(artist: Artist, jObject: JsonObject): List<Shelf> {
        val shelf = parser.run {
            jObject["data"]?.jsonArray?.toShelfItemsList("Top") as? Shelf.Lists.Items
        }
        val list = shelf?.list?.filterIsInstance<Track>().orEmpty()
        if (list.isEmpty()) return emptyList()
        return listOf(Shelf.Category("top", "Top", feed = null)) +
            list.take(5).map { Shelf.Item(it) }
    }

    /**
     * "Liked Music" menu for this artist, like the Deezer app's per-artist liked section.
     * A full-width clickable [Shelf.Category] opening the whole track list (big rows
     * with swipe-to-queue, play/shuffle) — tapping it must land on tracks you can
     * swipe, not on an inline preview. Reads the user's likes via the existing
     * favorite_song.getList endpoint and keeps only the tracks where this artist
     * appears (main or featured — [Track.artists] carries all of them after
     * [DeezerParser.graftFavTrack]).
     *
     * Null when there is nothing to show (no likes, none for this artist, or the likes
     * request failed e.g. offline) so the caller renders nothing instead of an empty menu.
     * Failures are swallowed deliberately: a likes outage must not break the whole artist page.
     */
    /**
     * The artist's liked tracks, read through the session cache and pre-filtered on the
     * raw form so only the handful of matches pay the full parse. Re-runnable: the menu
     * feed below calls it again on invalidate (pull-to-refresh inside the opened list),
     * which re-reads the cache — busted first on manual refresh — instead of replaying
     * a stale captured list.
     */
    private suspend fun loadLikedTracks(artistId: String): List<Track> {
        val entries = deezerExtension.getLikedEntriesCached()
        return entries.filter { parser.run { it.mentionsArtist(artistId) } }
            .map { parser.graftFavTrack(it) }
            .let { filterArtistLikedTracks(it, artistId) }
    }

    private suspend fun buildLikedShelf(artist: Artist): Shelf? {
        val tracks = runCatching { loadLikedTracks(artist.id) }.getOrNull().orEmpty()
        if (tracks.isEmpty()) return null
        return likedCategory(artist.id, tracks) { loadLikedTracks(artist.id) }
    }

    private fun buildRelatedArtistsShelf(artist: Artist, jObject: JsonObject): Shelf? {
        val shelf = parser.run {
            jObject["data"]?.jsonArray?.toShelfItemsList("Related Artists") as? Shelf.Lists.Items
        }
        val list = shelf?.list.orEmpty()
        if (list.isEmpty()) return null
        return Shelf.Lists.Items(
            id = shelf!!.id,
            title = shelf.title,
            subtitle = shelf.subtitle,
            type = Shelf.Lists.Type.Linear,
            more = PagedData.Continuous<Shelf> { continuation ->
                deezerExtension.handleArlExpiration()
                val index = continuation?.toIntOrNull() ?: 0
                val response = api.artistRelated(artist.id, index)
                val total = response["total"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val artists = response["data"]?.jsonArray?.mapNotNull { element ->
                    runCatching {
                        parser.run { element.jsonObject.toArtistFromRestApi() }.toShelf()
                    }.getOrNull()
                } ?: emptyList()
                val nextIndex = index + PAGE_SIZE
                Page(artists, if (nextIndex < total) nextIndex.toString() else null)
            }.toFeed(),
            list = list
        )
    }

    private fun buildAlbumsShelf(artist: Artist, jObject: JsonObject): Shelf? {
        val shelf = parser.run {
            jObject["data"]?.jsonArray?.toShelfItemsList("Albums") as? Shelf.Lists.Items
        }
        val list = shelf?.list.orEmpty()
        if (list.isEmpty()) return null
        return Shelf.Lists.Items(
            id = shelf!!.id,
            title = shelf.title,
            subtitle = shelf.subtitle,
            type = Shelf.Lists.Type.Linear,
            more = PagedData.Continuous<Shelf> { continuation ->
                deezerExtension.handleArlExpiration()
                val index = continuation?.toIntOrNull() ?: 0
                val response = api.artistAlbums(artist.id, index)
                val total = response["total"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val albums = response["data"]?.jsonArray?.mapNotNull { element ->
                    runCatching {
                        parser.run { element.jsonObject.toAlbumFromRestApi(artist) }.toShelf()
                    }.getOrNull()
                } ?: emptyList()
                val nextIndex = index + PAGE_SIZE
                Page(albums, if (nextIndex < total) nextIndex.toString() else null)
            }.toFeed(),
            list = list
        )
    }

    suspend fun loadArtist(artist: Artist): Artist {
        deezerExtension.handleArlExpiration()
        val jsonObject = api.artist(artist.id)
        val resultsObject = jsonObject["results"]?.jsonObject ?: return artist
        return parser.run { resultsObject.toArtist() }
    }

    suspend fun isFollowing(item: EchoMediaItem): Boolean {
        val dataArray = api.getArtists()["results"]?.jsonObject
            ?.get("TAB")?.jsonObject
            ?.get("artists")?.jsonObject
            ?.get("data")?.jsonArray ?: return false

        return dataArray.any { artistItem ->
            val artistId = artistItem.jsonObject["ART_ID"]?.jsonPrimitive?.content
            artistId == item.id
        }
    }

    fun getFollowersCount(item: EchoMediaItem): Long? = item.extras["followers"]?.toLongOrNull()

    companion object {
        // Liked-per-artist shelf: the id carries the artist so Unified can retitle it with
        // the localized string (the extension itself has no Context and stays on the
        // English fallback). Both directions are matched ("liked_<id>" here,
        // "<id>_liked" offline) — see UnifiedExtension.localizeLikedTitle.
        const val LIKED_TITLE_FALLBACK = "Liked Music"
        const val LIKED_COUNT_EXTRA = "liked_count"
        private const val LIKED_ID_PREFIX = "liked_"

        internal fun likedShelfId(artistId: String) = "$LIKED_ID_PREFIX$artistId"

        internal fun isLikedShelfId(id: String) =
            id.startsWith(LIKED_ID_PREFIX) || id.endsWith("_liked")

        // Pure helpers, unit-tested without network.

        internal fun filterArtistLikedTracks(tracks: List<Track>, artistId: String) =
            tracks.filter { track -> track.artists.any { it.id == artistId } }

        // The menu itself (pure, unit-tested): a full-width card opening the whole
        // list. Title stays the English fallback and subtitle stays null — this module
        // has no Context; Unified dresses both (localized title + "12 tracks" subtitle)
        // from the count travelling in extras. The cover dresses the card like the
        // other menu cards on the page.
        // The feed re-runs [load] on invalidate (pull-to-refresh inside the opened
        // list) instead of replaying the captured preview, so a manual refresh is
        // always fresh. Defaults to the given tracks for the pure unit-test path.
        internal fun likedCategory(
            artistId: String,
            tracks: List<Track>,
            load: suspend () -> List<Track> = { tracks },
        ): Shelf.Category =
            Shelf.Category(
                id = likedShelfId(artistId),
                title = LIKED_TITLE_FALLBACK,
                feed = PagedData.Single<Shelf> { load().map { it.toShelf() } }.toFeed(
                    Feed.Buttons(showPlayAndShuffle = true, customTrackList = tracks)
                ),
                image = tracks.firstOrNull()?.cover,
                extras = mapOf(LIKED_COUNT_EXTRA to tracks.size.toString())
            )

        // Liked menu first, above Top: the card's own padding separates it from Top.
        private fun Shelf.isEffectivelyEmpty(): Boolean = when (this) {
            is Shelf.Lists.Items -> list.isEmpty()
            is Shelf.Lists.Tracks -> list.isEmpty()
            else -> false
        }
        private fun Shelf?.nullIfEmpty(): Shelf? = this?.takeIf { !it.isEffectivelyEmpty() }

        private val shelfFactories: Map<String, DeezerParser.(JsonObject) -> Shelf?> = mapOf(
            "HIGHLIGHT" to filterEmpty { jObject ->
                jObject["ITEM"]?.jsonObject?.toShelfItemsList("Highlight")
                    ?.takeIf { it !is Shelf.Lists.Items || it.list.size > 1 }
            },
            "SELECTED_PLAYLIST" to filterEmpty { jObject ->
                jObject["data"]?.jsonArray?.toShelfItemsList("Selected Playlists").nullIfEmpty()
            },
            "RELATED_PLAYLIST" to filterEmpty { jObject ->
                jObject["data"]?.jsonArray?.toShelfItemsList("Related Playlists").nullIfEmpty()
            },
        )

        private const val PAGE_SIZE = 50

        private fun filterEmpty(
            block: DeezerParser.(JsonObject) -> Shelf?
        ): DeezerParser.(JsonObject) -> Shelf? = { json ->
            block(this, json)?.takeIf { !it.isEffectivelyEmpty() }
        }

        private val orderedKeys = listOf(
            "TOP",
            "HIGHLIGHT",
            "SELECTED_PLAYLIST",
            "ALBUMS",
            "RELATED_PLAYLIST",
            "RELATED_ARTISTS"
        )
    }
}