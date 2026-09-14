package dev.brahmkshatriya.echo.ui.main.search

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.clients.QuickSearchClient
import dev.brahmkshatriya.echo.common.models.QuickSearchItem
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getExtension
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getIf
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SearchViewModel(
    private val app: App,
    extensionLoader: ExtensionLoader
) : ViewModel() {
    val queryFlow = MutableStateFlow("")
    private val music = extensionLoader.music
    private val current = extensionLoader.current

    /**
     * Resolves the extension quick search should ask, the SAME WAY THE SEARCH FEED DOES
     * (`SearchFragment.feedData`'s loader: `music.getExtension(argId) ?: current`).
     *
     * ⚠⚠ THIS EXISTS BECAUSE OF AN EMPTY-STRING SENTINEL THAT NOBODY CHECKED, AND THE SENTINEL
     * IS THE ACTUAL DEFECT - not the fetch, not the observer. `SearchFragment` used to hold
     * `private var extensionId = ""` and pass it here. That field was assigned at exactly ONE place:
     * inside `feedData`'s LOADED lambda, AFTER a network search feed came back - not on creation, not
     * when the extension resolved, and not in the `cached` branch beside it. So until a search feed had
     * loaded, every quick search ran with `""`, and `""` IS INDISTINGUISHABLE FROM A REAL ID all the
     * way down:
     *   `getExtension("")` -> `find { it.id == "" }` -> null   (no error - a legitimate "not found")
     *   -> `defaultQuickSearch(null, ..)` -> `prefs(context) ?: return emptyList()`
     *   -> `quickFeed` latched EMPTY, WHICH RENDERS AS "NO SEARCH HISTORY".
     * Three layers, no throw, no log, no retry. Symptom: after a cold start the history list is empty
     * for the first few overlay opens and then starts working - the count being HOW MANY OPENS IT TAKES
     * FOR THE FEED LAMBDA TO RUN, i.e. EVENT-DRIVEN, NOT TIMED. That is why it looked like a race and
     * why "wait longer" never explained it.
     *
     * ⚠️ THE FIX BELONGS HERE, NOT AT THE OBSERVER AND NOT AT THE TRIGGER, and both of those
     * were reached for first. `SearchFragment`'s `observe(quickFeed)` was ALREADY CORRECT - a live
     * fragment-STARTED observer that renders any late write; the late write simply arrived EMPTY. And
     * fetching on overlay-open instead of on text-change would have called `quickSearch("")` and got
     * the same empty list. Both treat a symptom whose cause was one field away.
     *
     * ⚠️ INFERENCE, NOT MEASURED: `current` is set by `ExtensionLoader`'s init collector
     * (`music.collectLatest { setCurrentExtension() }`), a DIFFERENT coroutine on a different
     * dispatcher from this one, so `current.value` can still be null in the instant after
     * `getExtension` resumes. Hence `first { it != null }` rather than `.value` - it cannot observe
     * that gap. It parks forever if the music list is non-empty but EVERY extension is disabled
     * (`setCurrentExtension` returns early in exactly that case), which is harmless: nothing awaits
     * this, the coroutine dies with the ViewModel, and a screen with no enabled extension has no
     * history to show anyway.
     *
     * ⚠⚠ THIS DELIBERATELY DOES NOT APPLY ANDROID AUTO'S `aaEligible` GATING, AND COPYING IT
     * HERE WOULD BE A BUG RATHER THAN A HARDENING. `AndroidAutoCallback.getCurrentExtension` (and
     * `PlayerCallback`'s override) vet their candidate with `isEnabled && id != UNIFIED_ID`. The
     * question was raised here because `current` genuinely CAN hold Unified -
     * `ExtensionLoader.setCurrentExtension` is `list.find { it.id == last && it.isEnabled } ?:
     * list.firstOrNull { it.isEnabled }`, neither clause excluding UNIFIED_ID, and
     * `UnifiedExtension.metadata` carries `isEnabled = true`. It is reachable. It is also CORRECT.
     *
     * ⚠️ BECAUSE THE WRITE SIDE IS UNGATED AND KEYED BY EXTENSION. `SearchFragment.feedData`'s
     * loader does `music.getExtension(argId) ?: current.value!!` and then
     * `curr.saveInHistory(context, query)`, which writes `search_history` into `curr.prefs(context)` -
     * and `ExtensionUtils.prefs` keys those SharedPreferences `extensionPrefId(type.name, id)`, i.e.
     * PER EXTENSION. Gating the READ while the WRITE stays ungated would point the two at DIFFERENT
     * PREFERENCE FILES: history saved under Unified, looked for under something else. The mismatch is
     * the thing the gate would create, not prevent. Same for the disabled axis - if `current` were
     * ever disabled, the SEARCH FEED ITSELF is loading from it, so history from anywhere else would be
     * history for searches the user never ran here.
     * `UnifiedExtension` implements `SearchFeedClient` but NOT `QuickSearchClient`, so its quick search
     * falls to `defaultQuickSearch` reading its own prefs - populated by that same `saveInHistory`.
     * Self-consistent by construction.
     *
     * ⚠️ AND THE AA PREDICATE IS NOT A GENERAL-PURPOSE ONE TO SHARE. It exists to VET
     * `lastBrowsedExtId` - an id parsed out of a browse tree a head unit may have cached, which the
     * notes at both `getCurrentExtension` bodies record as STILL untrustworthy on two surviving axes
     * (raciness, staleness). This path has no such field; it reads `current` directly, the same value
     * the rest of the UI is using. Do not factor the two together - see the reciprocal note there.
     */
    private suspend fun resolve(extensionId: String?) =
        music.getExtension(extensionId) ?: current.first { it != null }

    /**
     * Quick search results, EACH PAIRED WITH THE ID OF THE EXTENSION THAT PRODUCED IT.
     *
     * ⚠️ THE ID TRAVELS WITH THE ITEMS DELIBERATELY, AND THE PAIR IS WHAT MAKES THE SENTINEL
     * UNREPRESENTABLE. A separate id field - which is what `SearchFragment.extensionId` was - needs a
     * value before any result exists, so it needs a "not ready" default, and a String's only free one
     * is `""`. Pairing removes the question: an unresolved fetch contributes NO ELEMENTS, so there is
     * no id to invent, and a row's label cannot drift from the list it arrived with (e.g. if the user
     * switches extension between the fetch and the render).
     */
    val quickFeed = MutableStateFlow<List<Pair<String, QuickSearchItem>>>(emptyList())

    fun quickSearch(extensionId: String?, query: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val extension = resolve(extensionId)
            val list = extension?.getIf<QuickSearchClient, List<QuickSearchItem>>(app.throwFlow) {
                quickSearch(query)
            } ?: defaultQuickSearch(extension, app.context, query)
            val id = extension?.id
            quickFeed.value = if (id == null) emptyList() else list.map { id to it }
        }
    }

    /**
     * Clears the whole search history for [extensionId] — the overlay's "Clear search history" action.
     *
     * ⚠️ ITERATES deleteQuickSearch RATHER THAN CALLING A BULK METHOD, BECAUSE THERE ISN'T ONE.
     * QuickSearchClient exposes only per-item deletion, so a bulk clear has to be expressed as "delete
     * each history entry". That is the honest shape for BOTH semantics currently in the tree:
     *   • an extension that deletes per entry (SearchViewModel.defaultDeleteSearch, used by Offline and
     *     Unified on the local store) removes each one and ends up empty — correct;
     *   • Deezer IGNORES the item and calls user.clearSearchHistory, so the FIRST call empties the account
     *     and the rest are redundant re-wipes of an already empty list — also correct, just wasteful.
     * A single call with an arbitrary item would be correct for Deezer and wrong for the first kind.
     *
     * Only `searched` items are passed: quickSearch("") returns history AND trending queries concatenated
     * (Deezer flags trending as searched=false), and trending is not the user's data to delete.
     * Refreshes once at the end rather than per deletion.
     */
    fun clearSearchHistory(extensionId: String?, query: String) {
        viewModelScope.launch {
            val extension = resolve(extensionId)
            val history = quickFeed.value.map { it.second }
                .filterIsInstance<QuickSearchItem.Query>()
                .filter { it.searched }
            history.forEach { item ->
                extension?.getIf<QuickSearchClient, Any?>(app.throwFlow) {
                    deleteQuickSearch(item)
                } ?: defaultDeleteSearch(extension, app.context, item)
            }
            quickSearch(extensionId, query)
        }
    }

    // Retained: no UI calls it since the per-row ✕ was removed on 2026-09-10 (see
    // search_mic_menu_white.xml), but it is the correct per-item operation and the thing to re-wire if a
    // per-entry deletion method ever becomes known for the extensions that need one.
    fun deleteSearch(extensionId: String?, item: QuickSearchItem, query: String) {
        viewModelScope.launch {
            val extension = resolve(extensionId)
            extension?.getIf<QuickSearchClient, Any?>(app.throwFlow) {
                deleteQuickSearch(item)
            } ?: defaultDeleteSearch(extension, app.context, item)
            quickSearch(extensionId, query)
        }
    }

    companion object {
        fun defaultQuickSearch(
            extension: Extension<*>?, context: Context, query: String
        ): List<QuickSearchItem> {
            val setting = extension?.prefs(context) ?: return emptyList()
            if (query.isNotBlank()) return emptyList()
            return getHistory(setting).map { QuickSearchItem.Query(it, true) }
        }

        fun defaultDeleteSearch(extension: Extension<*>?, context: Context, item: QuickSearchItem) {
            val setting = extension?.prefs(context) ?: return
            val history = getHistory(setting).toMutableList()
            history.remove(item.title)
            setting.edit { putString("search_history", history.joinToString(",")) }
        }

        private fun getHistory(setting: SharedPreferences): List<String> {
            return setting.getString("search_history", "")
                ?.split(",")?.mapNotNull {
                    it.takeIf { it.isNotBlank() }
                }?.distinct()?.take(5)
                ?: emptyList()
        }

        fun Extension<*>.saveInHistory(context: Context, query: String) {
            if (query.isBlank()) return
            val setting = prefs(context)
            val history = getHistory(setting).toMutableList()
            history.add(0, query)
            setting.edit { putString("search_history", history.joinToString(",")) }
        }
    }
}