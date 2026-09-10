package dev.brahmkshatriya.echo.playback.listener

import android.util.Log
import dev.brahmkshatriya.echo.BuildConfig
import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getIf
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * ENDLESS-QUEUE FALLBACK: when the playing extension's radio has nothing useful left, ask Last.fm for
 * similar tracks and map them back through the extension's own search.
 *
 * ⚠️ EXTENSION-AGNOSTIC BY CONSTRUCTION, DEEZER-MOTIVATED BY EVIDENCE. Nothing here is Deezer-specific —
 * the trigger reads the post-dedup append, the mapping goes through the common SearchFeedClient — but
 * Deezer is the extension with the reproducible failure and the only one this is known to help.
 * Reach as measured 2026-09-09: REACHED by four extensions — Deezer (motivating case), Offline
 * (deliberately excluded, see below), Unified (delegates to sub-extensions), YouTube Music (implements
 * RadioClient but currently throws upstream: ytmkt requires `musicQueueRenderer` at a watchNext tab index
 * YouTube no longer populates). HELPS one. Spotify untestable for unrelated reasons; Combine unknown.
 * Reach is not a design goal for this pass and should not be designed toward.
 *
 * THE FAILURE IT EXISTS FOR, verified on device: "Underwater" by The Frogmen (1961 surf instrumental).
 * Deezer's track radio returns only that same recording, twice, under two album ids; artist radio and
 * album radio return the same; "Similar artists" is populated for The Beatles and EMPTY for The Frogmen,
 * so it fails for exactly the artists that need it. Deezer's own app has the same failure, so there is no
 * Deezer behaviour to copy. Last.fm's track.getSimilar for that track returns Soul Surfer / Johnny
 * Fortune, Fiberglass Jungle / The Crossfires, Intoxica and Surfs Up / The Original Surfaris, Surfer's
 * Cry / The Torquays, Mr. Moto / The Belairs — all genuine 1960s surf instrumentals, five of six present
 * in Deezer's catalogue when searched by artist + title.
 *
 * ⚠️ DEFERRED, NOT REJECTED — AUTO-RESUME AFTER A LATE APPEND. The lookup runs while the last track is
 * still playing. If it outlives that track, STATE_ENDED fires, PlayerEventListener settles playWhenReady,
 * and the append then lands into a PAUSED queue — the user presses play once. Resuming automatically is
 * the obvious completion and the mechanism exists (tvDriveRadio's seekToNextMediaItem + play).
 * IT IS DEFERRED ON PURPOSE, PENDING ONE SPECIFIC OPEN BUG: cold-start autoplay, whose finding is that
 * "a real play request is arriving and its source is unidentified", with ShufflePlayer.play() /
 * setPlayWhenReady(true) named as the only app-reachable universal gate. Adding a NEW app-initiated play()
 * now would put another candidate into exactly the set that investigation is trying to narrow — and the
 * queue parked at ENDED is already what converts a phantom play into audible playback.
 * REVISIT WHEN THAT ITEM CLOSES. It is a small change and the cost of waiting is one button press in a
 * rare case; the cost of not waiting is a harder diagnosis on an open bug.
 *
 * ⚠️ THIS IS A FALLBACK, NOT A REPLACEMENT. It does not run on the normal path, does not replace the
 * extension's radio, and does not guarantee a match. The final rung is still the queue ending cleanly —
 * which since 2026-09-09 is a settled state (PlayerEventListener pauses at STATE_ENDED) rather than a
 * stuck one.
 */
object RadioFallback {

    // ⚠️ NOT AN ARCHITECTURAL FIRST, AND I INITIALLY THOUGHT IT WAS. The app already makes direct
    // third-party calls outside any extension: ExceptionUtils.getPasteLink (paste.rs),
    // AddViewModel.getExtensionList, and AppUpdater (GitHub releases). All three construct a BARE
    // `OkHttpClient()` per call site with DEFAULT timeouts — connect/read/write 10s and, critically,
    // callTimeout = 0, i.e. NO OVERALL BOUND. That pattern is not safe to copy onto the playback path:
    // this project has already had a third-party extension's non-cancellable blocking I/O wedge a mutex
    // and hang every browse node. So this client sets an explicit callTimeout, and the call is ALSO
    // wrapped in withTimeout — two independent ceilings, the same belt-and-braces shape as
    // StreamableLoader's withTimeout(30_000) sitting outside DeezerApi's own socket timeouts.
    private val client by lazy {
        OkHttpClient.Builder()
            .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true }

    private const val CALL_TIMEOUT_S = 5L
    private const val OUTER_TIMEOUT_MS = 6_000L

    // Last.fm's free tier is 5 req/sec and needs no authentication — only an api_key in the query string.
    // We issue at most one request per exhausted station, so the rate limit is not reachable by design.
    private const val ENDPOINT = "https://ws.audioscrobbler.com/2.0/"

    // How many similar tracks to ask for, and how many catalogue matches to append. Kept small: the point
    // is to keep the queue alive, not to build a station. Each candidate costs one extension search.
    private const val SIMILAR_LIMIT = 12
    private const val MAX_APPEND = 6

    // ⚠️ EXCLUDED BY IDENTITY, NOT BY CAPABILITY — AND THE CAPABILITY TEST WOULD NOT CATCH IT.
    // OfflineExtension implements BOTH RadioClient and SearchFeedClient, and its radio is a real local
    // feature (loadTracks(radio) pulls same-artist songs plus library.songList.shuffled().take(25)), so it
    // CAN produce a thin append and WOULD reach this code. Two independent reasons it must not:
    //   1. IT CANNOT HELP. A Last.fm match can only map to something already in the local library, which
    //      that shuffle already covers. There is nothing for the lookup to add.
    //   2. IT WOULD BE WRONG EVEN IF IT HELPED. A network request made on behalf of the offline extension
    //      is wrong regardless of outcome — the user may be deliberately offline, which the
    //      connectivity-veto work already treats as a first-class state rather than an error.
    // The exclusion is a correctness boundary, not an optimisation. Do not "generalise" it away.
    private const val OFFLINE_ID = "echo-offline"

    val isEnabled get() = BuildConfig.LASTFM_API_KEY.isNotBlank()

    /**
     * Returns catalogue tracks to append, or an empty list. NEVER throws except on cancellation.
     *
     * @param extension the extension that owns the PLAYING item — not whatever the UI is browsing.
     *   PlayerRadio resolves this from player.currentMediaItem, so this inherits the right one by
     *   sitting where it does; do not reach for ExtensionLoader's "current" extension, which tracks
     *   BROWSING and would search the wrong catalogue.
     */
    suspend fun similarTracks(extension: Extension<*>, seed: Track): List<Track> {
        val artist = seed.artists.firstOrNull()?.name?.trim().orEmpty()
        val title = seed.title.trim()
        val q = "$artist - $title"
        val started = System.currentTimeMillis()

        fun log(reason: String, similar: Int = 0, matched: Int = 0) = Log.d(
            "GladixRadio",
            "LASTFM q=\"$q\" reason=$reason similar=$similar matched=$matched " +
                "ms=${System.currentTimeMillis() - started}"
        )

        // ⚠️ "NO KEY" IS ITS OWN REASON, NOT SILENCE. local.properties is git-ignored, so a fresh clone,
        // a new machine or a CI run builds with LASTFM_API_KEY empty and the fallback disabled — and a
        // silently-disabled feature is indistinguishable from a broken one. This line is what separates
        // "this build has no key" from the six runtime outcomes below. It costs one log line per exhausted
        // station, which is rare by construction (single-slot latch, one lookup per station).
        if (!isEnabled) { log("no-api-key"); return emptyList() }
        if (extension.id == OFFLINE_ID) return emptyList()
        if (artist.isEmpty() || title.isEmpty()) { log("no-seed-fields"); return emptyList() }

        // ⚠️ SIX REASONS, NOT FOUR, AND THAT IS THE POINT. A permanently failing endpoint must be
        // DISTINGUISHABLE from a fallback that simply never fires — otherwise "the fallback never helps"
        // and "the fallback never ran" look identical. This project has already carried a dead endpoint
        // for weeks: channels/home-pipe was recorded as "TIMEOUT (confirmed dead)" in June and was still
        // being called on every load afterwards. A persisting reason=no-similar is that signal.
        // Fires ONLY on this path, so a healthy session that never exhausts a station prints nothing.
        val similar = runCatching { fetchSimilar(artist, title) }
            .getOrElse { e ->
                if (e is CancellationException) throw e
                log(if (e is IllegalStateException) "parse" else "timeout")
                return emptyList()
            }
        if (similar.isEmpty()) { log("no-similar"); return emptyList() }

        val matched = mapToCatalogue(extension, similar) { log("no-search-client", similar.size) }
        if (matched.isEmpty()) { log("no-match", similar.size); return emptyList() }
        log("ok", similar.size, matched.size)
        return matched
    }

    private data class Similar(val artist: String, val title: String)

    private suspend fun fetchSimilar(artist: String, title: String): List<Similar> =
        withTimeout(OUTER_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                val url = ENDPOINT.toHttpUrlLike(artist, title)
                val body = client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    r.body.string()
                }
                // ⚠️ DETECT HTML BEFORE DESERIALISING. Last.fm can serve an error page, a rate-limit page
                // or a maintenance page — with a 200 as easily as a 5xx — and a JSON decode on that throws
                // a PARSE exception, not a network one. Two precedents, one of them in this tree:
                //   • AddViewModel.getExtensionList crashed on HTML when the marketplace URL 404'd after a
                //     repo rename; the fix was exactly this, detect HTML before deserialisation.
                //   • a "Failed to parse JSON: no available server" report whose key read
                //     extension_id: echo-offline sent an investigation after an extension that had made no
                //     network call at all.
                // Raised as IllegalStateException so the caller logs reason=parse rather than reason=timeout.
                check(!body.trimStart().startsWith("<")) { "Last.fm returned HTML, not JSON" }
                val root = json.parseToJsonElement(body).jsonObject
                root["similartracks"]?.jsonObject?.get("track")?.jsonArray.orEmpty().mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    val t = o["name"]?.jsonPrimitive?.contentOrNullSafe() ?: return@mapNotNull null
                    val a = o["artist"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNullSafe()
                        ?: return@mapNotNull null
                    Similar(a, t)
                }
            }
        }

    /**
     * Maps Last.fm results back to playable tracks through the PLAYING extension's own search.
     *
     * ⚠️ DELIBERATELY SIMPLE, NOT CLEVER. Wrong matches are acceptable here — live versions and
     * re-recordings already reach the queue today with older catalogue, and a wrong-but-plausible
     * neighbour beats silence. The failure mode of cleverness is worse than the failure mode of
     * strictness: a mis-scored fuzzy match is unexplainable, a dropped candidate is one fewer track.
     * So: exact-ish compare after the same version-suffix strip the append dedup uses, first result only,
     * no scoring and no second-choice logic.
     *
     * NOTE ON PRECEDENT: the smarttracklist work is NOT a precedent for this — it resolved by ID
     * (GraphQL edges[].node.id -> song.getListData), never by text. This is a new operation for this
     * codebase. The working template is AndroidAutoCallback.performSearch, which this mirrors.
     */
    private suspend fun mapToCatalogue(
        extension: Extension<*>, similar: List<Similar>, onNoSearchClient: () -> Unit
    ): List<Track> {
        var hadClient = false
        val out = mutableListOf<Track>()
        for (s in similar) {
            if (out.size >= MAX_APPEND) break
            val found = extension.getIf<SearchFeedClient, List<Track>> {
                hadClient = true
                val feed = loadSearchFeed("${s.artist} ${s.title}")
                // Case-insensitive TRACK tab, with a firstOrNull fallback for extensions that name it
                // differently — same shape AndroidAutoCallback.performSearch uses.
                val tab = feed.notSortTabs.firstOrNull { it.id.equals("TRACK", ignoreCase = true) }
                    ?: feed.notSortTabs.firstOrNull()
                val (shelves, _) = feed.getPagedData(tab).pagedData.loadPage(null)
                shelves.toTracks()
            }.getOrNull()
            val first = found?.firstOrNull() ?: continue
            if (matches(first, s)) out.add(first)
        }
        if (!hadClient) onNoSearchClient()
        return out
    }

    private fun matches(track: Track, s: Similar): Boolean {
        val a = track.artists.firstOrNull()?.name?.strip() ?: return false
        return a == s.artist.strip() && track.title.strip() == s.title.strip()
    }

    // ⚠️ USES PlayerRadio's DEFINITION — THERE IS EXACTLY ONE APP-SIDE COPY, AND IT MUST STAY THAT WAY.
    // This file briefly carried its own identical regex, which made THREE in the tree. Two of those three
    // were both in the `app` module and had no justification at all: an internal reference is free.
    // The remaining duplication (app <-> DeezerRadioClient) is the only genuine one, and it stays — see
    // the note on PlayerRadio.VERSION_SUFFIX for why `common` is not the answer.
    // IF YOU NEED THIS NORMALISER IN A FOURTH APP-SIDE PLACE, REFERENCE IT; DO NOT RETYPE IT.
    private fun String.strip() = PlayerRadio.stripVersionSuffix(this).lowercase()

    private fun String.toHttpUrlLike(artist: String, title: String) =
        "$this?method=track.getsimilar&artist=${artist.enc()}&track=${title.enc()}" +
            "&api_key=${BuildConfig.LASTFM_API_KEY}&format=json&autocorrect=1&limit=$SIMILAR_LIMIT"

    private fun String.enc() = java.net.URLEncoder.encode(this, "UTF-8")

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe() =
        runCatching { content.takeIf { it.isNotBlank() } }.getOrNull()

    private suspend fun List<Shelf>.toTracks(): List<Track> = flatMap { shelf ->
        when (shelf) {
            is Shelf.Item -> listOfNotNull(shelf.media as? Track)
            is Shelf.Lists.Tracks -> shelf.list
            is Shelf.Lists.Items -> shelf.list.filterIsInstance<Track>()
            else -> emptyList()
        }
    }
}
