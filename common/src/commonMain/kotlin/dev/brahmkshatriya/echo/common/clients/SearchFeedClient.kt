package dev.brahmkshatriya.echo.common.clients

import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Shelf

/**
 * Used to show the search the feed.
 *
 * @see Feed
 * @see MusicExtension
 */
interface SearchFeedClient {

    /**
     * Gets the search feed.
     *
     * @param query the query to search for, will be empty if the user hasn't typed anything.
     * @return the feed.
     *
     * @see Feed
     */
    suspend fun loadSearchFeed(query: String): Feed<Shelf>

    /**
     * Gets the search feed, saying whether the search was a USER GESTURE.
     *
     * Extensions that record search history (server-side or local) should record only when
     * [isUserInitiated] is true. Everything else about the search should be identical.
     *
     * ⚠️ WHY THIS EXISTS: an app-initiated search is not a user gesture, but it reaches the same method,
     * so extensions cannot tell them apart. In this app that produced a real regression — an endless-queue
     * radio fallback searched the catalogue for each of up to fifteen candidates per exhausted station,
     * and every one of those queries landed in the user's Deezer ACCOUNT search history, pushing their own
     * searches out. The extension was behaving correctly; it simply had no way to know.
     * The general rule this encodes: A SEARCH THE APP MAKES ON THE USER'S BEHALF MUST NOT ENTER ANY
     * HISTORY. Applies equally to "recents", "continue listening" and play counts — methods designed for a
     * gesture carry gesture side effects.
     *
     * ⚠️ THE DEFAULT BODY IS LOAD-BEARING AND SO IS THE COMPILER MODE BEHIND IT. An extension compiled
     * against an older :common has no two-arg method; the call resolves to THIS default, which delegates to
     * the one-arg form, and the extension behaves exactly as before. That only holds because :common
     * compiles interface defaults to REAL Java default methods — verified from bytecode, not assumed:
     * `javap` on ExtensionClient shows `public default java.lang.Object onExtensionSelected(...)` plus an
     * ExtensionClient$DefaultImpls class. If that mode ever changed, this would compile to an ABSTRACT
     * method and every installed extension would throw AbstractMethodError on the two-arg call. The mode is
     * therefore PINNED in common/build.gradle.kts — read the note there before touching it.
     * ⚠️ THAT FAILURE IS NOT THEORETICAL IN THIS PROJECT. Build 1058 shipped a NoSuchMethodError caused by
     * a :common member change and broke installed extensions; its Crashlytics trace is kept as a working
     * control for that error path. AbstractMethodError from a missing default is the same family. The
     * compatibility mode is precisely what stops this change repeating it.
     *
     * ⚠️ BEST-EFFORT ACROSS EXTENSIONS, AND "SHIPPED" DOES NOT MEAN "STOPPED EVERYWHERE". An extension that
     * does not override this keeps its current behaviour, INCLUDING writing history for app-initiated
     * searches. As of 2026-09-10 the fix is effective for Deezer and for Unified-over-Deezer; YouTube Music,
     * Spotify and Combine continue writing until their authors adopt the two-arg form. There is no way to
     * force it and no way to detect which have adopted.
     *
     * @param query the query to search for.
     * @param isUserInitiated false when the app is searching on the user's behalf rather than because the
     *   user asked for this search.
     * @return the feed.
     */
    suspend fun loadSearchFeed(query: String, isUserInitiated: Boolean): Feed<Shelf> =
        loadSearchFeed(query)
}