package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.QuickSearchItem
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.extension.DeezerApi
import dev.brahmkshatriya.echo.extension.DeezerExtension
import dev.brahmkshatriya.echo.extension.DeezerParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

class DeezerSearchClient(private val deezerExtension: DeezerExtension, private val api: DeezerApi, private val scope: CoroutineScope, private val history: Boolean, private val parser: DeezerParser) {

    @Volatile
    private var oldSearch: Triple<String, List<Shelf>, JsonObject?>? = null

    private fun JsonArray?.toQueryList(key: String, historyFlag: Boolean) =
        this?.mapNotNull { item ->
            item.jsonObject[key]?.jsonPrimitive?.content?.let { QuickSearchItem.Query(it, historyFlag) }
        } ?: emptyList()

    suspend fun quickSearch(query: String): List<QuickSearchItem.Query> {
        deezerExtension.handleArlExpiration()
        return if (query.isBlank()) {
            val jsonObject = api.getSearchHistory()
            val resultObject = jsonObject["results"]!!.jsonObject
            val searchObject = resultObject["SEARCH_HISTORY"]?.jsonObject
            val dataArray = searchObject?.get("data")?.jsonArray
            val trendingObject = resultObject["TRENDING_QUERIES"]?.jsonObject
            val dataTrendingArray = trendingObject?.get("data")?.jsonArray
            dataArray.toQueryList("query", true) + dataTrendingArray.toQueryList("QUERY", false)
        } else {
            runCatching {
                val jsonObject = api.searchSuggestions(query)
                val resultObject = jsonObject["results"]?.jsonObject
                val suggestionArray = resultObject?.get("SUGGESTION")?.jsonArray
                suggestionArray.toQueryList("QUERY", false)
            }.getOrElse {
                emptyList()
            }
        }
    }

    suspend fun loadSearchFeed(
        query: String, shelf: String, isUserInitiated: Boolean = true
    ): Feed<Shelf> {
        deezerExtension.handleArlExpiration()
        query.ifBlank { return browseFeed(shelf).toFeed() }

        // ⚠⚠ TWO CONDITIONS, AND THEY MEAN DIFFERENT THINGS — DO NOT COLLAPSE THEM.
        //   `history`         — the USER'S SETTING. Master switch: off means never record, ever.
        //   `isUserInitiated` — WHETHER THIS PARTICULAR SEARCH WAS A GESTURE. Narrows WHICH searches count.
        // This write goes to the user's Deezer ACCOUNT (user.addEntryInSearchHistory), not to a local list,
        // so it is visible in Deezer's own app and is not clearable per-entry from here.
        // WHAT WENT WRONG WITHOUT THE SECOND CONDITION (device, 2026-09-09): the app's endless-queue radio
        // fallback searches the catalogue once per Last.fm candidate — up to fifteen per exhausted station,
        // matched or not — and every one became a "Recent" entry, pushing the user's own searches out.
        // Nothing was wrong with this code; it simply could not tell the two apart.
        if (history && isUserInitiated) {
            scope.launch { runCatching { api.setSearchHistory(query) } }
        }

        return Feed(loadSearchFeedTabs(query)) { tab ->
            if (tab?.id == "TOP_RESULT") return@Feed emptyList<Shelf>().toFeedData()

            val cached = oldSearch?.takeIf { it.first == query }

            if (tab?.id == "All") {
                return@Feed cached?.second?.toFeedData() ?: emptyList<Shelf>().toFeedData()
            }

            val resultObject = cached?.third
                ?: api.search(query)["results"]?.jsonObject

            val dataArray = resultObject?.get(tab?.id ?: "")?.jsonObject?.get("data")?.jsonArray

            return@Feed dataArray?.mapNotNull { item ->
                parser.run { item.jsonObject.toEchoMediaItem()?.toShelf() }
            }.orEmpty().toFeedData()
        }
    }

    private fun JsonObject.toBrowseShelves(shelf: String): List<Shelf> {
        val browsePageResults = this["results"]!!.jsonObject
        val browseSections = browsePageResults["sections"]?.jsonArray ?: JsonArray(emptyList())
        return browseSections.mapNotNull { section ->
            val id = section.jsonObject["module_id"]!!.jsonPrimitive.content
            if (id == GO_BEYOND_STREAMING_MODULE_ID) return@mapNotNull null
            val layout = section.jsonObject["layout"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val title = section.jsonObject["title"]?.jsonPrimitive?.content.orEmpty()
            if (title.isBlank() || title == "?") return@mapNotNull null
            when {
                id == EXPLORE_MODULE_ID || layout == "grid" -> {
                    parser.run {
                        section.toShelfCategoryList(title, shelf) { target ->
                           deezerExtension.channelFeed(target)
                        }
                    }.takeIf { it.list.isNotEmpty() }
                }

                else -> {
                    parser.run {
                        val secShelf =
                            section.toShelfItemsList(title) as? Shelf.Lists.Items
                                ?: return@run null
                        val list = secShelf.list
                        Shelf.Lists.Items(
                            id = secShelf.id,
                            title = secShelf.title,
                            subtitle = secShelf.subtitle,
                            type = Shelf.Lists.Type.Linear,
                            more = PagedData.Single<Shelf> {
                                list.map {
                                    it.toShelf()
                                }
                            }.toFeed(),
                            list = list
                        )
                    }
                }
            }
        }
    }

    private suspend fun browseFeed(shelf: String): List<Shelf> {
        try {
            deezerExtension.handleArlExpiration()
            api.updateCountry()
        } catch (e: Exception) {
            println("GladixDeezer Search ERROR: ${e.message}")
            throw e
        }

        val (searchHomePipeShelves, exploreTabShelves) = coroutineScope {
            val searchHomePipe = async {
                runCatching { withTimeout(5000) { api.page("channels/search-home-pipe") } }
                    .onSuccess { logSections("search-home-pipe", it) }
                    .onFailure { println("GladixDeezer PAGE[channels/search-home-pipe] ERROR: ${it.message}") }
                    .getOrNull()?.toBrowseShelves(shelf) ?: emptyList()
            }
            val exploreTab = async {
                runCatching { withTimeout(5000) { api.page("channels/explore/explore-tab") } }
                    .onSuccess { logSections("explore-tab", it) }
                    .onFailure { println("GladixDeezer PAGE[channels/explore/explore-tab] ERROR: ${it.message}") }
                    .getOrNull()?.toBrowseShelves(shelf) ?: emptyList()
            }
            searchHomePipe.await() to exploreTab.await()
        }

        // ⚠⚠ THE ONE LINE THAT DECIDES WHETHER channels/search-home-pipe STAYS. logSections
        // above already reports the sections each endpoint RETURNED; this reports what survived
        // toBrowseShelves, and the gap between the two is real - that function drops
        // GO_BEYOND_STREAMING_MODULE_ID, drops blank/"?" titles, drops grid sections whose category list
        // comes back empty (takeIf), and drops non-grid sections that do not cast to Shelf.Lists.Items.
        // So a healthy `sections:` line does NOT prove a non-empty shelf list, which is exactly the
        // question the parked item asks.
        // PREDICTION, STATED BEFORE THE CAPTURE so the number cannot be read after the fact:
        //   search-home-pipe >= 2  -> it is carrying the Genres/Categories grid the June work switched to
        //                             it for. IT STAYS. Parked item closes as "load-bearing".
        //   search-home-pipe == 0  -> dead weight on every Search-tab open. The endpoint, its async block
        //                             and searchHomePipeShelves can all go, leaving explore-tab alone.
        //   search-home-pipe == 1  -> partial; read the sections line above to see WHICH of Genres or
        //                             Categories is being dropped, and by which of the four filters.
        // A zero here with a NON-EMPTY sections line above means the endpoint works and toBrowseShelves
        // is rejecting it - a different bug, and do not delete the endpoint on that reading.
        // REMOVE THIS LINE once the parked item is closed either way; the logSections pair above is
        // permanent instrumentation, this one is not.
        println(
            "GladixDeezer BROWSE shelves: search-home-pipe=${searchHomePipeShelves.size} " +
                "explore-tab=${exploreTabShelves.size}"
        )

        // ⚠️ CLOSED 2026-09-12, DO NOT RE-OPEN: channels/explore/explore-tab IS NOT UNTESTED.
        // It was investigated twice with temporary logging and DELIBERATELY DEMOTED. Confirmed then: it
        // returns personalized content ("Dig deeper", "Evening chill") rather than the Genres/Categories
        // grid of Deezer's official Search tab, and returns exactly 5 sections - the personalized ones plus
        // EXPLORE_MODULE_ID ("Explore all"). It is CORRECT PER ITS OWN CONTRACT; it is simply a personalized
        // browse feed rather than a genre grid. That is why channels/search-home-pipe became the primary
        // source and this one is appended BELOW it rather than removed. Ordering in the sum below is that
        // decision, not an accident - do not reorder it, and do not re-investigate this endpoint as though
        // its behaviour were unknown.
        return searchHomePipeShelves + exploreTabShelves
    }

    suspend fun loadSearchFeedTabs(query: String): List<Tab> {
        deezerExtension.handleArlExpiration()
        query.ifBlank { return emptyList() }

        val jsonObject = api.search(query)
        val resultObject = jsonObject["results"]?.jsonObject
        val orderObject = resultObject?.get("ORDER")?.jsonArray

        val tabs = orderObject?.mapNotNull { tab ->
            val tabId = tab.jsonPrimitive.content
            if (tabId !in SKIP_TAB_IDS) {
                Tab(
                    tabId,
                    tabId.lowercase()
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() })
            } else {
                null
            }
        } ?: emptyList()

        val allShelves = tabs.mapNotNull { tab ->
            val name = tab.id
            val tabObject = resultObject?.get(name)?.jsonObject
            val dataArray = tabObject?.get("data")?.jsonArray
            parser.run {
                dataArray?.toShelfItemsList(
                    name.lowercase()
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() })
            }
        }
        oldSearch = Triple(query, allShelves, resultObject)
        return listOf(Tab("All", "All")) + tabs
    }

    companion object {
        private fun logSections(label: String, page: JsonObject) {
            val sections = page["results"]?.jsonObject?.get("sections")?.jsonArray ?: JsonArray(emptyList())
            val summary = sections.joinToString(", ") { section ->
                val obj = section.jsonObject
                val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "?"
                val layout = obj["layout"]?.jsonPrimitive?.contentOrNull ?: "?"
                val moduleId = obj["module_id"]?.jsonPrimitive?.contentOrNull ?: "?"
                val target = obj["target"]?.jsonPrimitive?.contentOrNull ?: "?"
                "$title/$layout/$moduleId/$target"
            }
            println("GladixDeezer PAGE[$label] sections: $summary")
        }

        private val SKIP_TAB_IDS =
            setOf("TOP_RESULT", "FLOW_CONFIG", "LIVESTREAM", "RADIO", "LYRICS", "CHANNEL", "USER")

        private const val EXPLORE_MODULE_ID = "8b2c6465-874d-4752-a978-1637ca0227b5"
        private const val GO_BEYOND_STREAMING_MODULE_ID = "20748fc9-bf55-41e6-a50f-2b26c0c8da48"
    }
}