package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.extension.DeezerParser
import dev.brahmkshatriya.echo.extension.DeezerSession
import dev.brahmkshatriya.echo.extension.clients.DeezerArtistClient.Companion.filterArtistLikedTracks
import dev.brahmkshatriya.echo.extension.clients.DeezerArtistClient.Companion.isLikedShelfId
import dev.brahmkshatriya.echo.extension.clients.DeezerArtistClient.Companion.likedCategory
import dev.brahmkshatriya.echo.extension.clients.DeezerArtistClient.Companion.likedShelfId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-artist "Liked Music" shelf. Pure helpers only — no network: the likes fetch
 * (favorite_song.getList) and the FALLBACK graft are covered by FavoritesPlaylistTest.
 */
class ArtistLikedShelfTest {

    private fun artist(id: String) = Artist(id = id, name = id)

    private fun track(id: String, vararg artistIds: String) = Track(
        id = id,
        title = id,
        artists = artistIds.map { artist(it) }
    )

    // Id scheme shared with Unified's localized retitle.

    @Test
    fun `liked id carries the artist`() {
        assertEquals("liked_42", likedShelfId("42"))
    }

    @Test
    fun `both id directions match`() {
        assertTrue(isLikedShelfId("liked_42"))
        assertTrue(isLikedShelfId("42_liked"))
    }

    @Test
    fun `other shelves do not match`() {
        assertFalse(isLikedShelfId("top"))
        assertFalse(isLikedShelfId("42_tracks"))
        assertFalse(isLikedShelfId("42_albums"))
        assertFalse(isLikedShelfId("favorites"))
    }

    // Filtering: main or featured artist counts, others do not.

    @Test
    fun `keeps tracks where the artist appears anywhere`() {
        val tracks = listOf(
            track("a", "42"),
            track("b", "7", "42"),
            track("c", "7")
        )
        assertEquals(listOf("a", "b"), filterArtistLikedTracks(tracks, "42").map { it.id })
    }

    @Test
    fun `no match is empty so the caller hides the shelf`() {
        assertTrue(filterArtistLikedTracks(listOf(track("a", "7")), "42").isEmpty())
        assertTrue(filterArtistLikedTracks(emptyList(), "42").isEmpty())
    }

    // Menu shape: full-width card opening the whole list (where swipe-to-queue
    // lives), count extra + cover for Unified's localized dressing. Placement is
    // fixed in getShelves (menu first, above Top) — nothing to unit-test.

    @Test
    fun `menu carries id, fallback title, count and a feed`() {
        val menu = likedCategory("42", listOf(track("l1", "42"), track("l2", "7", "42")))
        assertEquals("liked_42", menu.id)
        assertEquals(DeezerArtistClient.LIKED_TITLE_FALLBACK, menu.title)
        assertEquals("2", menu.extras[DeezerArtistClient.LIKED_COUNT_EXTRA])
        assertTrue(menu.feed != null)
    }

    @Test
    fun `menu leaves dressing to unified - no subtitle, cover from first track`() {
        val cover = "https://example.com/cover.jpg".toImageHolder()
        val withCover = track("l1", "42").copy(cover = cover)
        val menu = likedCategory("42", listOf(withCover))
        assertEquals(null, menu.subtitle)
        assertEquals(cover, menu.image)
        assertEquals(null, likedCategory("42", listOf(track("l1", "42"))).image)
    }

    // Raw pre-filter: must agree with the full parse (never drop a match, cheaply
    // skip the rest). Entries mirror real favorite_song.getList shapes.

    private val parser = DeezerParser(DeezerSession())

    private fun entry(json: String): JsonObject =
        Json.parseToJsonElement(json).jsonObject

    private fun mentions(json: String, artistId: String) =
        parser.run { entry(json).mentionsArtist(artistId) }

    @Test
    fun `prefilter matches the legacy ART_ID field`() {
        assertTrue(mentions("""{"SNG_ID":"1","SNG_TITLE":"T","ART_ID":"42","ART_NAME":"A"}""", "42"))
    }

    @Test
    fun `prefilter matches a featured artist in the ARTISTS array`() {
        assertTrue(
            mentions(
                """{"SNG_ID":"2","SNG_TITLE":"T","ART_ID":"7","ART_NAME":"Other",
                    "ARTISTS":[{"ART_ID":"7","ART_NAME":"Other"},{"ART_ID":"42","ART_NAME":"A"}]}""",
                "42"
            )
        )
    }

    @Test
    fun `prefilter matches through FALLBACK display data`() {
        assertTrue(
            mentions(
                """{"SNG_ID":"3","SNG_TITLE":"Dead","ART_ID":"7","ART_NAME":"Other",
                    "FALLBACK":{"SNG_ID":"3","SNG_TITLE":"Live","ART_ID":"42","ART_NAME":"A"}}""",
                "42"
            )
        )
    }

    @Test
    fun `prefilter drops unrelated entries and tolerates bare ones`() {
        assertFalse(mentions("""{"SNG_ID":"4","SNG_TITLE":"T","ART_ID":"7","ART_NAME":"Other"}""", "42"))
        assertFalse(mentions("""{"SNG_ID":"5","SNG_TITLE":"T"}""", "42"))
        assertFalse(mentions("""{}""", "42"))
    }
}
