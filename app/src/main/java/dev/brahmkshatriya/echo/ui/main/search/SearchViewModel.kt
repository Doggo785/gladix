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
import kotlinx.coroutines.launch

class SearchViewModel(
    private val app: App,
    extensionLoader: ExtensionLoader
) : ViewModel() {
    val queryFlow = MutableStateFlow("")
    private val music = extensionLoader.music
    val quickFeed = MutableStateFlow<List<QuickSearchItem>>(emptyList())
    fun quickSearch(extensionId: String, query: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val extension = music.getExtension(extensionId)
            val list = extension?.getIf<QuickSearchClient, List<QuickSearchItem>>(app.throwFlow) {
                quickSearch(query)
            } ?: defaultQuickSearch(extension, app.context, query)
            quickFeed.value = list
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
    fun clearSearchHistory(extensionId: String, query: String) {
        viewModelScope.launch {
            val extension = music.getExtension(extensionId)
            val history = quickFeed.value.filterIsInstance<QuickSearchItem.Query>()
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
    fun deleteSearch(extensionId: String, item: QuickSearchItem, query: String) {
        viewModelScope.launch {
            val extension = music.getExtension(extensionId)
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