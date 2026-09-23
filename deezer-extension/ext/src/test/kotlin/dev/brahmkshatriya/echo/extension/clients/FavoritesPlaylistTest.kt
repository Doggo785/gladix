package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.DeezerParser
import dev.brahmkshatriya.echo.extension.DeezerSession
import dev.brahmkshatriya.echo.extension.clients.DeezerLibraryClient.Companion.favoritesCard
import dev.brahmkshatriya.echo.extension.clients.DeezerLibraryClient.Companion.prependCardToPlaylists
import dev.brahmkshatriya.echo.extension.clients.DeezerLibraryClient.Companion.withFavoritesCard
import dev.brahmkshatriya.echo.extension.clients.DeezerPlaylistClient.Companion.FAVORITES_EXTRA
import dev.brahmkshatriya.echo.extension.clients.DeezerPlaylistClient.Companion.isFavoritesPlaylist
import dev.brahmkshatriya.echo.extension.clients.DeezerPlaylistClient.Companion.withNextExtras
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Virtual "Favorite Tracks" playlist card. No network, no player: the card shape,
 * both shelf placements, the routing predicate, the NEXT chain and the FALLBACK
 * graft are all pure. What stays device-verified: the tab_playlist payload actually
 * missing the entry, and the opened card playing likes in order end to end.
 */
class FavoritesPlaylistTest {

    private val parser = DeezerParser(DeezerSession())

    private fun playlist(id: String, extras: Map<String, String> = mapOf()) =
        Playlist(id = id, title = "P", isEditable = true, extras = extras)

    // Card shape.

    @Test
    fun `card carries the routing extra`() {
        assertTrue(favoritesCard().extras.containsKey(FAVORITES_EXTRA))
    }

    @Test
    fun `card id is non-numeric so it never collides with a real playlist`() {
        val id = favoritesCard().id
        assertEquals(DeezerLibraryClient.FAVORITES_ID, id)
        assertFalse(id.all { it.isDigit() })
    }

    @Test
    fun `card is inert - nothing to edit, save, follow, share or radio`() {
        val card = favoritesCard()
        assertEquals(DeezerLibraryClient.FAVORITES_TITLE, card.title)
        assertFalse(card.isEditable)
        assertFalse(card.isRadioSupported)
        assertFalse(card.isFollowable)
        assertFalse(card.isSaveable)
        assertFalse(card.isShareable)
    }

    // All tab: card heads the Playlists carousel shelf.

    @Test
    fun `all tab prepends the card to parsed playlists`() {
        val shelf = Shelf.Lists.Items(
            id = "Playlists", title = "Playlists",
            list = listOf(playlist("1"), playlist("2"))
        )
        val result = withFavoritesCard("Playlists", shelf) as Shelf.Lists.Items
        assertEquals("Playlists", result.id)
        assertEquals(listOf("favorites", "1", "2"), result.list.map { it.id })
        assertTrue(isFavoritesPlaylist(result.list.first() as Playlist))
    }

    @Test
    fun `all tab still shows the card with zero owned playlists`() {
        val result = withFavoritesCard("Playlists", null) as Shelf.Lists.Items
        assertEquals(listOf("favorites"), result.list.map { it.id })
    }

    @Test
    fun `all tab placement only depends on the items list, not its kind`() {
        val other = Shelf.Lists.Items(
            id = "Albums", title = "Albums",
            list = listOf(playlist("9"))
        )
        // withFavoritesCard is only ever called for the Playlists shelf; it prepends
        // to whatever Items list it is given without re-checking the kind.
        val result = withFavoritesCard("Playlists", other) as Shelf.Lists.Items
        assertEquals(listOf("favorites", "9"), result.list.map { it.id })
    }

    // Playlists tab: card heads the vertical rows.

    @Test
    fun `playlists tab prepends the card row`() {
        val rows = listOf(Shelf.Item(playlist("1")), Shelf.Item(playlist("2")))
        val result = prependCardToPlaylists(rows)
        assertEquals(3, result.size)
        assertEquals("favorites", result.first().id)
        assertEquals(listOf("1", "2"), result.drop(1).map { it.id })
    }

    @Test
    fun `playlists tab shows the card alone when empty`() {
        val result = prependCardToPlaylists(emptyList())
        assertEquals(listOf("favorites"), result.map { it.id })
    }

    // Routing predicate: loadPlaylist/loadTracks branch on this alone.

    @Test
    fun `synthetic card routes to favorites`() {
        assertTrue(isFavoritesPlaylist(favoritesCard()))
    }

    @Test
    fun `real playlist does not route to favorites`() {
        assertFalse(isFavoritesPlaylist(playlist("123")))
    }

    @Test
    fun `smarttracklist extra alone does not route to favorites`() {
        assertFalse(
            isFavoritesPlaylist(
                playlist("x", mapOf(DeezerPlaylistClient.SMART_TRACKLIST_EXTRA to "y"))
            )
        )
    }

    // NEXT chain inside the virtual playlist.

    private fun track(id: String, extras: Map<String, String> = mapOf()) =
        Track(id = id, title = id, extras = extras)

    @Test
    fun `next links each track to its successor, last to empty`() {
        val result = withNextExtras(listOf(track("a"), track("b"), track("c")))
        assertEquals(listOf("b", "c", ""), result.map { it.extras["NEXT"] })
    }

    @Test
    fun `next keeps pre-existing extras and adds no playlist id`() {
        val result = withNextExtras(listOf(track("a", mapOf("k" to "v"))))
        assertEquals("v", result.single().extras["k"])
        assertEquals("", result.single().extras["NEXT"])
        assertFalse(result.single().extras.containsKey("playlist_id"))
    }

    @Test
    fun `next of empty is empty`() {
        assertTrue(withNextExtras(emptyList()).isEmpty())
    }

    // FALLBACK graft shared with the Library Tracks shelf.

    private fun entry(json: String): JsonObject =
        Json.parseToJsonElement(json).jsonObject

    @Test
    fun `graft without fallback keeps top-level as-is`() {
        val track = parser.graftFavTrack(
            entry("""{"SNG_ID":"42","SNG_TITLE":"Plain","ART_NAME":"Top Artist"}""")
        )
        assertEquals("42", track.id)
        assertEquals("Plain", track.title)
        assertEquals(listOf("Top Artist"), track.artists.map { it.name })
        assertNull(track.cover)
    }

    @Test
    fun `graft takes display fields from fallback but keeps the top-level id`() {
        val track = parser.graftFavTrack(
            entry(
                """{
                    "SNG_ID":"42","SNG_TITLE":"Dead Title","ART_NAME":"Dead Artist",
                    "ALB_TITLE":"Dead Album",
                    "FALLBACK":{
                        "SNG_ID":"99","SNG_TITLE":"Live Title","ART_NAME":"Live Artist",
                        "ALB_TITLE":"Live Album","ALB_PICTURE":"abc123"
                    }
                }"""
            )
        )
        // Streaming identity stays top-level (the confirmed-playable record)...
        assertEquals("42", track.id)
        // ...while display fields come from the fallback catalog data.
        assertEquals(listOf("Live Artist"), track.artists.map { it.name })
        assertEquals("Live Album", track.album?.title)
        assertNotNull(track.cover)
    }
}
