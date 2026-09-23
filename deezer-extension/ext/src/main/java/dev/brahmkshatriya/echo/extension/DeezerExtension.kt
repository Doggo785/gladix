package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.FollowClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.LikeClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.PlaylistEditClient
import dev.brahmkshatriya.echo.common.clients.QuickSearchClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.SaveClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.ShareClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.clients.TrackerClient
import dev.brahmkshatriya.echo.common.clients.TrackerMarkClient
import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.helpers.WebViewRequest
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.common.models.NetworkRequest.Companion.toGetRequest
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.QuickSearchItem
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.TrackDetails
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingCategory
import dev.brahmkshatriya.echo.common.settings.SettingList
import dev.brahmkshatriya.echo.common.settings.SettingSlider
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.DeezerCountries.getDefaultCountryIndex
import dev.brahmkshatriya.echo.extension.DeezerCountries.getDefaultLanguageIndex
import dev.brahmkshatriya.echo.extension.clients.DeezerAlbumClient
import dev.brahmkshatriya.echo.extension.clients.DeezerArtistClient
import dev.brahmkshatriya.echo.extension.clients.DeezerHomeFeedClient
import dev.brahmkshatriya.echo.extension.clients.DeezerLibraryClient
import dev.brahmkshatriya.echo.extension.clients.DeezerLyricsClient
import dev.brahmkshatriya.echo.extension.clients.DeezerPlaylistClient
import dev.brahmkshatriya.echo.extension.clients.DeezerRadioClient
import dev.brahmkshatriya.echo.extension.clients.DeezerSearchClient
import dev.brahmkshatriya.echo.extension.clients.DeezerTrackClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class DeezerExtension : HomeFeedClient, TrackClient, LikeClient, RadioClient,
    SearchFeedClient, QuickSearchClient,AlbumClient, ArtistClient, FollowClient, PlaylistClient, LyricsClient, ShareClient,
    TrackerClient, TrackerMarkClient, LoginClient.WebView, LoginClient.CustomInput,
    LibraryFeedClient, PlaylistEditClient, SaveClient {

    private val extensionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val session by lazy { DeezerSession.getInstance() }
    private val api by lazy { DeezerApi(session) }
    private val parser by lazy { DeezerParser(session) }
    private var likedTrackIds: HashSet<String>? = null

    override suspend fun getSettingItems(): List<Setting> {
        return listOf(
            SettingList(
                "Use Proxy",
                "proxy",
                "Use proxy to prevent GEO-Blocking",
                mutableListOf("No Proxy", "UK 1", "UK 2", "RU 1", "RU 2", "MD"),
                mutableListOf("", "uk1.proxy.murglar.app", "uk2.proxy.murglar.app", "ru1.proxy.murglar.app", "ru2.proxy.murglar.app", "md.proxy.murglar.app"),
                0
            ),
            SettingSwitch(
                "Enable Logging",
                "log",
                "Enables logging to deezer",
                true
            ),
            SettingSwitch(
                "Enable Search History",
                "history",
                "Enables the search history",
                true
            ),
            SettingCategory(
                "Quality",
                "quality",
                mutableListOf(
                    SettingList(
                        "Wi-Fi Streaming Quality",
                        "unmetered_stream_quality",
                        "Audio quality used on Wi-Fi or unmetered connections",
                        mutableListOf("High (FLAC)", "Medium (320kbps)", "Low (128kbps)", "Auto (Global App Setting)"),
                        mutableListOf("highest", "medium", "lowest", "off"),
                        3
                    ),
                    SettingList(
                        "Mobile Data Streaming Quality",
                        "stream_quality",
                        "Audio quality used on mobile data connections",
                        mutableListOf("High (FLAC)", "Medium (320kbps)", "Low (128kbps)", "Auto (Global App Setting)"),
                        mutableListOf("highest", "medium", "lowest", "off"),
                        3
                    ),
                    SettingSlider(
                        "Image Quality",
                        "image_quality",
                        "Choose your preferred image quality (Can impact loading times)",
                        240,
                        120,
                        1920,
                        120
                    )
                )
            ),
            SettingCategory(
                "Language & Country",
                "langcount",
                mutableListOf(
                    SettingList(
                        "Language",
                        "lang",
                        "Choose your preferred language for loaded stuff",
                        DeezerCountries.languages.map { it.name },
                        DeezerCountries.languages.map { it.code },
                        getDefaultLanguageIndex(session.settings)
                    ),
                    SettingList(
                        "Country",
                        "country",
                        "Choose your preferred country for browse recommendations",
                        DeezerCountries.countries.map { it.name },
                        DeezerCountries.countries.map { it.code },
                        getDefaultCountryIndex(session.settings)
                    )
                )
            ),
            SettingCategory(
                "Appearance",
                "appearance",
                mutableListOf(
                    SettingList(
                        "Shelf Type",
                        "shelf",
                        "Choose your preferred shelf type",
                        mutableListOf("Grid", "Linear"),
                        mutableListOf("grid", "linear"),
                        0
                    )
                )
            )
        )
    }

    override fun setSettings(settings: Settings) {
        session.settings = settings
    }

    /**
     * ⚠⚠ THE runCatching LETS ClientException.LoginRequired THROUGH AND SWALLOWS THE REST,
     * AND THAT ASYMMETRY IS THE POINT. It used to swallow everything, which meant THE EARLIEST
     * MOMENT WE KNOW THE CREDENTIALS ARE DEAD WAS THE ONE MOMENT WE SAID NOTHING: a user whose
     * stored credentials had gone stale got silence at app open and only met the sign-in prompt
     * when they next tapped a track.
     * The chain that produces it, traced rather than assumed: handleArlExpiration -> api.makeUser()
     * -> callApi("deezer.getUserData") -> the invalid-CSRF branch -> a silent re-login -> refused
     * -> DeezerAuthRejectedException -> converted there to LoginRequired. The conversion was always
     * working; this line was discarding its result.
     *
     * ⚠️ EVERYTHING ELSE IS STILL SWALLOWED, DELIBERATELY. Selecting an extension must not
     * fail because a background token refresh hit a network error - that would make the extension
     * unselectable while offline. Only the one exception the user can ACT on gets through.
     *
     * ⚠⚠ CancellationException IS STILL SWALLOWED HERE. THE SHAPE IS NOW ESTABLISHED -
     * RETHROW-CORRECT - AND IT IS STILL NOT CHANGED HERE, because that is a separate decision from
     * the LoginRequired rethrow above. Recorded in full so it is not re-derived.
     *
     * THE THIRD WORKED INSTANCE OF THE THREE-SHAPES RULE, AND THE DISCRIMINATOR IS: WHAT IS WAITING
     * ON COMPLETION?
     *   CoroutineUtils.futureCatching  RETHROW IS WRONG. It bridges a coroutine to a
     *     ListenableFuture; a rethrow leaves the future uncompleted and Media3 waits forever. The
     *     exception has no destination that satisfies the future's contract. (Still parked, on
     *     exactly that blocker.)
     *   ContextUtils.listenFuture      OPPOSITE POLARITY - swallowing IS the fix there.
     *   THIS SITE                      RETHROW IS CORRECT. Three reasons, in order of weight:
     *     (a) Nothing is waiting on a completion signal. This is a plain suspend function in a
     *         coroutine-to-coroutine chain, so cancellation has a well-defined destination - the
     *         caller's Job - and nothing is stranded by letting it travel there.
     *     (b) The swallow does not merely LOSE an exception, it CORRUPTS STATE. Injectable.value()
     *         runs `injections.forEach { it(t) }` and only then `injections = emptyList()`. A
     *         swallowed cancellation lets the forEach complete, so the injection list is marked DONE
     *         though the ARL refresh never ran - and can never re-run for that instance. Rethrowing
     *         leaves the injections pending and the block self-heals on the next value().
     *         Sharper still, the same function then behaves two ways: the plain assignment
     *         `injections = emptyList()` executes (no cancellation check), while the suspending
     *         `injectionsMap.values.forEach` throws at its first suspension point and
     *         `injectionsMap.clear()` never runs. The LIST leaks into "done"; the MAP correctly
     *         stays pending.
     *     (c) The rest of the path already obeys the convention - ExtensionUtils.get routes through
     *         toAppException, which has `is CancellationException -> throw this`. This is the ONLY
     *         link in the chain that breaks it.
     * ⚠️ AND IT IS REACHABLE, WHICH IS WHY IT IS NOT INERT. onExtensionSelected has two call
     * sites with OPPOSITE exposure: ExtensionLoader.setupMusicExtension launches it on a
     * SupervisorJob scope that nothing ever cancels, but ExtensionLoader.injected puts it in the
     * INJECTION BLOCK, which runs lazily on whichever coroutine first calls Injectable.value() -
     * LoginViewModel's viewModelScope, an AA future, any collectLatest block. Those cancel routinely.
     * ⚠️ RESOLVING THIS TELLS US NOTHING ABOUT futureCatching. Its blocker is a property of
     * the FUTURE BRIDGE, not of cancellation handling, so a verdict here does not transfer. Three
     * sites, three answers, one shared symptom - which is the rule's point, not a counterexample.
     */
    override suspend fun onExtensionSelected() {
        session.settings?.let { setSettings(it) }
        runCatching { handleArlExpiration() }.getOrElse {
            // Rethrow EXCEPT on a never-logged-in session. With no credentials at all (no
            // email/pass, no ARL) and no recorded refusal, nothing is "required" - the user
            // simply never signed in - and throwing here is not just noisy, it is FATAL:
            // this runs inside the Injectable injection block, and a throwing block is never
            // cleared, so EVERY later value() re-runs it and re-throws. That bricks the login
            // screen itself (LoginViewModel.init maps the failed capability query to
            // "Login is not supported") AND any later onLogin (same value() path) - login can
            // never complete. A session holding credentials, or a latched refusal, still throws,
            // so the rejected-sign-in prompt this rethrow was added for is preserved.
            val creds = session.credentials
            val neverLoggedIn = !session.credentialsRejected &&
                creds.email.isEmpty() && creds.pass.isEmpty() && creds.arl.isEmpty()
            if (!neverLoggedIn && it is ClientException.LoginRequired) throw it
        }
    }

    //<============= HomeTab =============>

    private val deezerHomeFeedClient by lazy { DeezerHomeFeedClient(this, api, parser) }

    override suspend fun loadHomeFeed(): Feed<Shelf> = deezerHomeFeedClient.loadHomeFeed(shelf)

    //<============= Library =============>

    private val deezerLibraryClient by lazy { DeezerLibraryClient(this, api, parser) }

    override suspend fun loadLibraryFeed(): Feed<Shelf> = deezerLibraryClient.loadLibraryFeed()

    override suspend fun addTracksToPlaylist(
        playlist: Playlist,
        tracks: List<Track>,
        index: Int,
        new: List<Track>
    ) {
        handleArlExpiration()
        api.addToPlaylist(playlist, new)
    }

    override suspend fun removeTracksFromPlaylist(
        playlist: Playlist,
        tracks: List<Track>,
        indexes: List<Int>
    ) {
        handleArlExpiration()
        api.removeFromPlaylist(playlist, tracks, indexes)
    }

    override suspend fun createPlaylist(title: String, description: String?): Playlist {
        handleArlExpiration()
        val jsonObject = api.createPlaylist(title, description)
        val id = jsonObject["results"]?.jsonPrimitive?.content.orEmpty()
        val playlist = Playlist(
            id = id,
            title = title,
            description = description,
            isEditable = true
        )
        return playlist
    }

    override suspend fun deletePlaylist(playlist: Playlist) {
        handleArlExpiration()
        api.deletePlaylist(playlist.id)
    }

    override suspend fun editPlaylistMetadata(
        playlist: Playlist,
        title: String,
        description: String?
    ) {
        handleArlExpiration()
        api.updatePlaylist(playlist.id, title, description)
    }

    override suspend fun likeItem(item: EchoMediaItem, shouldLike: Boolean) {
        handleArlExpiration()
        when (item) {
            is Track -> {
                if (shouldLike) {
                    likedTrackIds?.add(item.id)
                    api.addFavoriteTrack(item.id)
                } else {
                    likedTrackIds?.remove(item.id)
                    api.removeFavoriteTrack(item.id)
                }
            }
            else -> {}
        }
    }

    override suspend fun isItemLiked(item: EchoMediaItem): Boolean {
        if (item !is Track) return false
        val ids = likedTrackIds ?: fetchLikedTrackIds().also { likedTrackIds = it }
        return item.id in ids
    }

    private suspend fun fetchLikedTrackIds(): HashSet<String> {
        val dataArray = runCatching {
            api.getTracks()["results"]?.jsonObject?.get("data")?.jsonArray
        }.getOrNull() ?: return hashSetOf()
        return dataArray.mapNotNull {
            runCatching { it.jsonObject["SNG_ID"]?.jsonPrimitive?.content }.getOrNull()
        }.toHashSet()
    }

    override suspend fun listEditablePlaylists(track: Track?): List<Pair<Playlist, Boolean>> {
        handleArlExpiration()
        val playlistList = mutableListOf<Pair<Playlist, Boolean>>()
        val jsonObject = api.getPlaylists()
        val resultObject = jsonObject["results"]!!.jsonObject
        val tabObject = resultObject["TAB"]!!.jsonObject
        val playlistObject = tabObject["playlists"]!!.jsonObject
        val dataArray = playlistObject["data"]!!.jsonArray
        dataArray.forEach {
            val playlist = parser.run { it.jsonObject.toPlaylist() }
            if (playlist.isEditable) {
                playlistList.add(Pair(playlist, false))
            }
        }
        return playlistList
    }

    override suspend fun moveTrackInPlaylist(
        playlist: Playlist,
        tracks: List<Track>,
        fromIndex: Int,
        toIndex: Int
    ) {
        handleArlExpiration()
        val idArray = tracks.map { it.id }.toMutableList()
        idArray.add(toIndex, idArray.removeAt(fromIndex))
        api.updatePlaylistOrder(playlist.id, idArray)
    }

    override suspend fun isItemSaved(item: EchoMediaItem): Boolean {
        return when (item) {
            is Album -> {
                if (item.type == Album.Type.Show) {
                    getIsItemSaved(api::getShows, "SHOW_ID", item.id)
                } else {
                    getIsItemSaved(api::getAlbums, "ALB_ID", item.id)
                }
            }

            is Playlist -> {
                getIsItemSaved(api::getPlaylists, "PLAYLIST_ID", item.id)
            }

            is Track -> {
                getIsItemSaved(api::getTracks, "SNG_ID", item.id)
            }

            else -> false
        }
    }

    private suspend fun getIsItemSaved(
        getItems: suspend () -> JsonObject,
        idKey: String,
        itemId: String
    ): Boolean {
        val dataObject = getItems()["results"]?.jsonObject
        val dataArray = if (idKey == "SNG_ID") {
            dataObject?.get("data")?.jsonArray ?: return false

        } else {
            dataObject?.get("TAB")?.jsonObject
                ?.values?.firstOrNull()?.jsonObject
                ?.get("data")?.jsonArray ?: return false
        }
        return dataArray.any { item ->
            val id = item.jsonObject[idKey]?.jsonPrimitive?.content
            id == itemId
        }
    }

    override suspend fun saveToLibrary(item: EchoMediaItem, shouldSave: Boolean) {
        handleArlExpiration()
        when (item) {
            is Album -> {
                if (item.type == Album.Type.Show) {
                    if (shouldSave) api.addFavoriteShow(item.id) else api.removeFavoriteShow(
                        item.id
                    )
                } else {
                    if (shouldSave) api.addFavoriteAlbum(item.id) else api.removeFavoriteAlbum(
                        item.id
                    )
                }

            }

            is Playlist -> {
                if (shouldSave) api.addFavoritePlaylist(item.id) else api.removeFavoritePlaylist(item.id)
            }

            is Track -> {
                if (shouldSave) api.addFavoriteTrack(item.id) else api.removeFavoriteTrack(item.id)
            }

            else -> {}
        }
    }

    //<============= Search =============>

    private val deezerSearchClient by lazy { DeezerSearchClient(this, api, extensionScope, history, parser) }

    override suspend fun quickSearch(query: String): List<QuickSearchItem.Query> = deezerSearchClient.quickSearch(query)

    // ⚠⚠ `item` IS DELIBERATELY UNUSED. THIS IS NOT AN OVERSIGHT — NO PER-ENTRY DELETION METHOD IS KNOWN.
    // The interface promises "deletes a quick search item"; this calls user.clearSearchHistory (via
    // api.deleteSearchHistory -> DeezerSearch.deleteSearchHistory, USER_ID only), which wipes the ACCOUNT'S
    // ENTIRE search history. That is a contract violation, and it is upstream Echo's code — unmodified here
    // since 8f4c2f29 imported the extension from a submodule — so it is a candidate to send upstream rather
    // than only carry.
    //
    // VERIFIED, TWO SOURCES, BOTH FOUND NOTHING (2026-09-10), per the open-source-verification rule:
    //   • deezer-py (RemixDev/deezer-py, deezer/gw.py) implements NO search-history methods at all — no add,
    //     no clear, no per-entry delete.
    //   • dzr (yne/dzr, the `dzr` script) implements none either.
    //   • This tree knows exactly two: user.addEntryInSearchHistory and user.clearSearchHistory.
    // Gemini independently reached the same conclusion, so this is two searches finding nothing rather than
    // one — which is weak evidence of absence, but it is the evidence there is.
    //
    // ⚠️ THE OPEN END, SO NOBODY RE-DERIVES IT: deezer.com's own UI DOES offer removing individual
    // entries, so an endpoint presumably exists — its NAME is simply unverified. A browser-devtools capture
    // of the kind that recovered the smarttracklist GraphQL query would close this. Do not guess a method
    // name; the gateway is not enumerable and a wrong guess fails silently as an empty result.
    //
    // WHAT THE UI DOES ABOUT IT MEANWHILE: the per-row ✕ that called this was removed on 2026-09-10 — it
    // appeared on Query AND Media rows and promised per-item removal on both. Clearing now lives on the
    // search overlay's overflow menu, confirmed, where the affordance matches the behaviour. See
    // search_mic_menu_white.xml.
    override suspend fun deleteQuickSearch(item: QuickSearchItem) = api.deleteSearchHistory()

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> =
        deezerSearchClient.loadSearchFeed(query, shelf, isUserInitiated = true)

    // The two-arg form is the one non-user callers reach; the one-arg form above keeps the old meaning
    // (a user typed it) so no existing caller changes behaviour by omission.
    override suspend fun loadSearchFeed(query: String, isUserInitiated: Boolean): Feed<Shelf> =
        deezerSearchClient.loadSearchFeed(query, shelf, isUserInitiated)

    /**
     * ⚠️ A MISSING SECTION TITLE IS NOT A REASON TO DROP THE SECTION. Until 2026-09-07 this required
     * `section.title` and returned null without it, which discarded the entire page: the device capture of
     * channels/module/46b377f1 shows ONE section, `title=<null>`, layout=grid, holding 25 playlist items
     * of which all 25 parse with the existing code. The items were never the problem — the header was.
     * That is also why the four /channels/module/<uuid> Home rows opened to nothing.
     *
     * TITLE FALLBACK, in order, each level giving something the previous one cannot:
     *   1. section["title"] — per-section, the only level that can distinguish two sections of one page.
     *      Null on module pages; present on multi-section channel pages, which is who it is for.
     *   2. results["title"] — the PAGE title (resultsKeys carries one even when the section does not).
     *      For a module page this is the label Deezer is showing today.
     *   3. "" — render with NO header rather than dropping. FeedType emits a Header for every Shelf.Lists,
     *      and HeaderViewHolder does `title.isVisible = feed.title.isNotEmpty()`, so a blank title is an
     *      invisible header row followed by the items. Content survives; only decoration is lost.
     * Deliberately NOT a level: the caller's own shelf title from Home. It is the most stable string we
     * hold, but channelFeed is reached through a `more` Feed lambda that does not carry it, so plumbing it
     * through would mean threading a display string down two layers for a case level 2 already covers.
     * Worth revisiting only if a module page turns up with no page title either.
     *
     * ⚠️ THE ID DOES NOT COME FROM THE TITLE. section_id, else module_id, else the target. Module labels
     * ROTATE — 46b377f1 was "Summer in slow-mo" days before it was "Hello, sunshine" — so a title-derived
     * id would change under the same content, and two untitled sections sharing a page title would collide
     * on one cache key in Cached.getFeedShelf.
     */
    suspend fun channelFeed(target: String): List<Shelf> {
        val jsonObject = api.page(target.substringAfter("/"))
        // ⚠️ THESE TWO WERE `!!` UNTIL 2026-09-07 AND THE SECOND ONE CRASHED THE APP. `target` is not one
        // endpoint, it is four families, and /artist/<id> is an ARTIST endpoint with no "sections" key at
        // all — the retraced NPE (build 1077 mapping, pg_map_id 9d7725eb…, frame nm0.a:127). Returning an
        // empty list instead is what makes an unhandled family DEGRADE rather than throw: the caller's
        // `more` treats an empty fetch as "use the row's own items", so the arrow behaves exactly as it
        // does with the fetch branch off. See the note at DeezerParser.toShelfItemsList's `more`.
        // ⚠️ PERMANENT, NOT A TEMPORARY DIAGNOSTIC — same carve-out as the two DROP lines, and for the
        // same reason. The `more` fallback in DeezerParser.toShelfItemsList makes a failed fetch look
        // EXACTLY like a successful one (the row's own items appear either way), which is right for users
        // and blinding for us: a family that quietly degrades is indistinguishable from one that works.
        // A silent degradation is how "Made for you" went unnoticed for months. These fire ONLY on the
        // failure path, so a working family is silent and a healthy session prints nothing.
        val channelPageResults = jsonObject["results"] as? JsonObject ?: run {
            println("GladixDeezer FALLBACK target=$target reason=no-results rootKeys=${jsonObject.keys}")
            return emptyList()
        }
        val channelSections = channelPageResults["sections"] as? JsonArray ?: run {
            // keys= is the field that earns this line: it names what the page DID return, which is the
            // whole question for /channels/new and for any family nobody has captured.
            println(
                "GladixDeezer FALLBACK target=$target reason=no-sections " +
                    "resultsKeys=${channelPageResults.keys}"
            )
            return emptyList()
        }
        val pageTitle = channelPageResults["title"]?.jsonPrimitive?.contentOrNull
        return supervisorScope {
            channelSections.map { section ->
                async(Dispatchers.Default) {
                    parser.run {
                        val obj = section.jsonObject
                        val title = obj["title"]?.jsonPrimitive?.contentOrNull
                            ?: pageTitle.orEmpty()
                        val id = obj["section_id"]?.jsonPrimitive?.contentOrNull
                            ?: obj["module_id"]?.jsonPrimitive?.contentOrNull
                            ?: target
                        // Recursive by design: a channel page's rows get fetching arrows too when they
                        // carry a target. See toShelfItemsList's depth note — bounded by Deezer's graph,
                        // not by us.
                        section.toShelfItemsList(title, id) { t -> channelFeed(t) }
                    }
                }
            }.awaitAll().filterNotNull().also { shelves ->
                // Third distinct cause: the page WAS section-shaped and still yielded nothing, i.e. the
                // sections parsed to zero items. Distinguishing it from the two above is what separates
                // "we asked the wrong endpoint" from "we asked the right one and cannot read it".
                if (shelves.isEmpty() && channelSections.isNotEmpty()) println(
                    "GladixDeezer FALLBACK target=$target reason=no-section-parsed " +
                        "sections=${channelSections.size}"
                )
            }
        }
    }

    //<============= Play =============>

    private val deezerTrackClient by lazy { DeezerTrackClient(this, api, parser) }

    override suspend fun loadStreamableMedia(streamable: Streamable, isDownload: Boolean): Streamable.Media = deezerTrackClient.loadStreamableMedia(streamable)

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track = deezerTrackClient.loadTrack(track)

    override suspend fun loadFeed(track: Track): Feed<Shelf> = loadFeed(track.artists.first())

    //<============= Radio =============>

    private val deezerRadioClient by lazy { DeezerRadioClient(api, parser) }

    override suspend fun loadTracks(radio: Radio): Feed<Track> = deezerRadioClient.loadTracks(radio)

    override suspend fun radio(item: EchoMediaItem, context: EchoMediaItem?): Radio = deezerRadioClient.radio(item, context)

    override suspend fun loadRadio(radio: Radio): Radio  = radio

    //<============= Lyrics =============>

    private val deezerLyricsClient by lazy { DeezerLyricsClient(api) }

    override suspend fun loadLyrics(lyrics: Lyrics): Lyrics = lyrics

    override suspend fun searchTrackLyrics(clientId: String, track: Track): Feed<Lyrics> = deezerLyricsClient.searchTrackLyrics(track)

    //<============= Album =============>

    private val deezerAlbumClient by lazy { DeezerAlbumClient(this, api, parser) }

    override suspend fun loadFeed(album: Album): Feed<Shelf> = loadFeed(album.artists.first())

    override suspend fun loadAlbum(album: Album): Album = deezerAlbumClient.loadAlbum(album)

    override suspend fun loadTracks(album: Album): Feed<Track> = deezerAlbumClient.loadTracks(album)

    //<============= Playlist =============>

    private val deezerPlaylistClient by lazy { DeezerPlaylistClient(this, api, parser) }

    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? = deezerPlaylistClient.getShelves(playlist)

    override suspend fun loadPlaylist(playlist: Playlist): Playlist = deezerPlaylistClient.loadPlaylist(playlist)

    override suspend fun loadTracks(playlist: Playlist): Feed<Track> = deezerPlaylistClient.loadTracks(playlist)

    //<============= Artist =============>

    private val deezerArtistClient by lazy { DeezerArtistClient(this, api, parser) }

    override suspend fun loadFeed(artist: Artist): Feed<Shelf> = deezerArtistClient.getShelves(artist)

    override suspend fun loadArtist(artist: Artist): Artist = deezerArtistClient.loadArtist(artist)

    override suspend fun isFollowing(item: EchoMediaItem): Boolean = deezerArtistClient.isFollowing(item)

    override suspend fun getFollowersCount(item: EchoMediaItem): Long? = deezerArtistClient.getFollowersCount(item)

    override suspend fun followItem(item: EchoMediaItem, shouldFollow: Boolean) {
        if (shouldFollow) api.followArtist(item.id) else api.unfollowArtist(item.id)
    }

    //<============= Login =============>

    override suspend fun getCurrentUser(): User {
        val userList = api.makeUser()
        return userList.firstOrNull() ?: throw Exception("Login failed: could not retrieve user after authentication")
    }

    override val webViewRequest = object : WebViewRequest.Headers<List<User>> {
        override suspend fun onStop(requests: List<NetworkRequest>): List<User> {
            val request = requests.firstOrNull() ?: throw Exception("Login failed: no auth request intercepted — try logging in again")
            val data = request.headers
            val arl = extractCookieValue(data, "arl")
            val sid = extractCookieValue(data, "sid")
            if (arl != null && sid != null) {
                session.updateCredentials(arl = arl, sid = sid)
                val credJObj = api.decodeJson(request.body?.decodeToString()!!)
                val mail = credJObj["MAIL"]?.jsonPrimitive?.content!!
                val pass = credJObj["PASSWORD"]?.jsonPrimitive?.content!!
                session.updateCredentials(
                    email = mail,
                    pass = pass
                )
                return api.makeUser(mail, pass)
            } else if (data.isEmpty()) {
                throw Exception("Ignore this")
            } else {
                throw Exception("Failed to retrieve ARL and SID from cookies")
            }
        }

        override val initialUrl = "https://www.deezer.com/login?redirect_type=page&redirect_link=%2Faccount%2F".toGetRequest(
            mapOf(
                Pair(
                    "user-agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
                )
            )
        )

        override val interceptUrlRegex = "https://www\\.deezer\\.com/ajax/gw-light\\.php\\?method=deezer_userAuth.*".toRegex()

        override val stopUrlRegex = "https://www\\.deezer\\.com/account/.*".toRegex()

        private fun extractCookieValue(data: Map<String,String>, key: String): String? {
            return data["cookie"]?.substringAfter("$key=")?.substringBefore(";").takeIf { it?.isNotEmpty() == true }
        }
    }

    override val forms: List<LoginClient.Form> = listOf(
        LoginClient.Form(
            key = "userPass",
            label = "E-Mail and Password",
            icon = LoginClient.InputField.Type.Email,
            inputFields = listOf(
                LoginClient.InputField(
                    type = LoginClient.InputField.Type.Email,
                    key = "email",
                    label = "E-Mail",
                    isRequired = true,
                ),
                LoginClient.InputField(
                    type = LoginClient.InputField.Type.Password,
                    key = "pass",
                    label = "Password",
                    isRequired = true
                )
            )
        ),
        LoginClient.Form(
            key = "manual",
            label = "ARL",
            icon = LoginClient.InputField.Type.Misc,
            inputFields = listOf(
                LoginClient.InputField(
                    type = LoginClient.InputField.Type.Misc,
                    key = "arl",
                    label = "ARL",
                    isRequired = false,
                )

            )
        )
    )

    override suspend fun onLogin(key: String, data: Map<String, String?>): List<User> {
        if(data["email"] != null && data["pass"] != null) {
            val email = data["email"]!!
            val password = data["pass"]!!

            session.updateCredentials(email = email, pass = password)

            api.getArlByEmail(email, password, 3)
            val userList = api.makeUser(email, password)
            // ⚠⚠ CLEARED HERE AS WELL AS IN setLoginUser, AND THE DUPLICATION IS THE FIX.
            // setLoginUser is the CANONICAL clear, but it is driven by the host's DB flow
            // (ExtensionLoader combines db.currentUsersFlow), so on the USER-INITIATED login path it
            // fires LATER: onLogin returns -> afterLogin writes Room -> the flow emits -> only then
            // setLoginUser. In that window the latch is still set and handleArlExpiration would
            // short-circuit on credentials that were JUST accepted - a spurious sign-in prompt, and a
            // reachable one if AA rebuilds a browse root while someone re-logs in on the phone.
            // Same asynchrony the May work hit, and the same remedy it used: a synchronous write in
            // onLogin beside the existing updateCredentials.
            // ⚠️ AFTER success, never at entry: a FAILED attempt must leave a still-valid latch
            // alone. Nothing above this line can be reached if getArlByEmail threw.
            // The silent re-login path needs none of this - callApi's CSRF branch calls setLoginUser
            // directly and synchronously, and `session` is a singleton, so it clears immediately.
            session.setCredentialsRejected(false)
            return userList
        } else {
            session.updateCredentials(arl = data["arl"] ?: "")
            api.getSid()
            val userList = api.makeUser()
            userList.firstOrNull()?.extras?.let { extras ->
                session.updateCredentials(
                    token = extras["token"] ?: "",
                    userId = extras["user_id"] ?: "",
                    licenseToken = extras["license_token"] ?: "",
                    sid = extras["sid"] ?: ""
                )
            }
            // Same reasoning as the email/pass branch above - a successful ARL login proves the
            // stored credentials work, so the refusal latch must not outlive it.
            session.setCredentialsRejected(false)
            return userList
        }
    }

    override fun setLoginUser(user: User?) {
        likedTrackIds = null
        // THE ONLY PLACE THE REFUSAL LATCH IS CLEARED, and it covers both directions: a successful
        // login (new credentials, so the old refusal is stale) and a logout (nothing left to refuse).
        // Chosen over onLogin because this is the chokepoint the host drives - it runs on login, on
        // logout, and on a user switch, whereas onLogin misses the last two.
        session.setCredentialsRejected(false)
        if (user != null) {
            session.updateCredentials(
                arl = user.extras["arl"] ?: "",
                sid = user.extras["sid"] ?: "",
                token = user.extras["token"] ?: "",
                userId = user.extras["user_id"] ?: "",
                licenseToken = user.extras["license_token"] ?: "",
                email = user.extras["email"] ?: "",
                pass = user.extras["pass"] ?: ""
            )
        } else {
            session.updateCredentials(
                arl = "",
                sid = "",
                token = "",
                userId = "",
                licenseToken = "",
                email = "",
                pass = ""
            )
        }
    }

    //<============= Share =============>

    override suspend fun onShare(item: EchoMediaItem): String {
        return when (item) {
            is Track -> "https://www.deezer.com/track/${item.id}"
            is Artist -> "https://www.deezer.com/artist/${item.id}"
            //is EchoMediaItem.Profile.UserItem -> "https://www.deezer.com/profile/${item.id}"
            is Album -> "https://www.deezer.com/album/${item.id}"
            is Playlist -> "https://www.deezer.com/playlist/${item.id}"
            is Radio -> TODO("Does not exist")
        }
    }

    //<============= Tracking =============>

    override suspend fun onTrackChanged(details: TrackDetails?) {}

    override suspend fun getMarkAsPlayedDuration(details: TrackDetails): Long = 30000L

    override suspend fun onMarkAsPlayed(details: TrackDetails) {
        if (log) api.log(details.track)
    }

    override suspend fun onPlayingStateChanged(details: TrackDetails?, isPlaying: Boolean) {
        val track = details?.track
        if (track?.type == Track.Type.Podcast && !isPlaying) {
            api.bookmarkEpisode(
                track.id,
                details.currentPosition.div(1000),
                details.totalDuration?.div(1000)?.toDouble() ?: 0.0
            )
        }
    }

    //<============= Utils =============>

    /**
     * ⚠⚠ A GUARD WAS NEARLY SHIPPED HERE AND WOULD HAVE COMPILED AND NEVER FIRED. Recorded
     * because the thing that pointed at this function was a RECORD ENTRY, and the entry was right
     * about the gap it described and wrong about where the fix went. Tracing the caller is what
     * caught it: makeUser() calls callApi("deezer.getUserData") and never getArlByEmail, so the
     * exception the guard would have caught cannot arrive on this path. A citation naming a site is
     * a LEAD, not a location - check the callers before building on it, including the user's own
     * notes, which is exactly the case this instance covers.
     *
     * ⚠⚠ NOTHING CATCHES A REFUSED RE-LOGIN HERE, AND ADDING ONE WOULD BE INERT. The
     * credentials-present branch below calls makeUser(), which NEVER calls getArlByEmail - it
     * calls callApi("deezer.getUserData"). A re-login is only reached by coming back through
     * callApi's invalid-CSRF branch, where DeezerAuthRejectedException is already converted to
     * ClientException.LoginRequired. A guard here would look correct, compile, and never fire.
     * ⚠⚠ [2026-09-22] THE AUGUST CLOSURE THAT KEPT THIS AMBIGUOUS IS NOW INVALID, AND THAT
     * UNBLOCKS A FIX ELSEWHERE. The closure read: "LoginRequired carries nothing ... RETRY IS
     * ARGUABLY CORRECT FOR THE ARL CASE, so routing the raw form to the login shelf would send ARL
     * failures to a login screen that leads nowhere." That was true while a recoverable stale ARL
     * could surface as LoginRequired. IT NO LONGER CAN.
     * EVERY LoginRequired THE DEEZER EXTENSION CAN THROW, enumerated 2026-09-22 (scope: a recursive
     * grep of deezer-extension/ext/src/main/java - FOUR sites, no others):
     *   DeezerApi, CSRF catch      a silent re-login was REFUSED          -> sign in
     *   DeezerApi, getToken 403    the auth endpoint refused the creds    -> sign in
     *   this file, flag branch     already-latched refusal                -> sign in
     *   this file, isArlExpired    creds ABSENT and the ARL is expired    -> sign in
     * The silently-recoverable case is the THIRD branch below - `runCatching { api.makeUser() }` -
     * which swallows everything and never throws. So no ambiguous LoginRequired can escape, and a
     * sign-in affordance is now the correct rendering for all four.
     * ⚠️ IF A FIFTH THROW SITE IS EVER ADDED, CHECK IT AGAINST THIS TABLE FIRST. One that can
     * mean "recoverable without the user" would reopen the August problem, and the consumer of that
     * decision is PagedSource.load - see the note there.
     *
     * ⚠️ AND THE RECORDED "LoginRequired CARRIES NOTHING" GAP IS ABOUT THE `else if
     * (isArlExpired)` BRANCH ONLY - the one where credentials are ABSENT, so "user must sign in"
     * and "internal token went stale, no user action possible" genuinely cannot be told apart.
     * It does NOT cover this whole function, and reading it that way is what made the third state
     * (credentials present, used, and REFUSED) look unfixable. That state is distinguishable where
     * it happens; see the note at DeezerApi.callApi's CSRF branch.
     */
    suspend fun handleArlExpiration() {
        val creds = session.credentials
        val isArlExpired = session.arlExpired || creds.arl.isEmpty()
        if (isArlExpired || creds.sid.isEmpty() || creds.token.isEmpty()) {
            if (creds.email.isNotEmpty() && creds.pass.isNotEmpty()) {
                // ⚠⚠ ALREADY REFUSED ONCE - FAIL WITHOUT TOUCHING THE NETWORK. Same outcome as
                // letting makeUser() run (it would reach callApi's invalid-CSRF branch, re-login, be
                // refused, and convert to this exact exception), at none of the cost: no round trip,
                // no rejected credential re-submitted to a shared-account endpoint, and the
                // Injectable mutex is not held across a network call. Cleared on the next successful
                // login - see DeezerSession.credentialsRejected.
                if (session.credentialsRejected) throw ClientException.LoginRequired()
                api.makeUser()
            } else if (isArlExpired) {
                throw ClientException.LoginRequired()
            } else {
                runCatching { api.makeUser() }
            }
        }
    }

    private val shelf: String get() = session.settings?.getString("shelf") ?: DEFAULT_TYPE
    private val log: Boolean get() = session.settings?.getBoolean("log") == true
    private val history: Boolean get() = session.settings?.getBoolean("history") != false

    companion object {
        private const val DEFAULT_TYPE = "grid"
    }
}