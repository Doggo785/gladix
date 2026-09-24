package dev.brahmkshatriya.echo.ui.feed

/**
 * Pure decision math for swipe-right-to-queue ([FeedAdapter.getTouchHelper])
 * and its snackbar Undo ([dev.brahmkshatriya.echo.ui.player.PlayerViewModel.undoAddToQueue]).
 *
 * Everything here is framework-free on purpose: no View, no ViewHolder, no MediaItem.
 * The touch helper and the ViewModel call these so the behavior is tested, not copied.
 * Covered by SwipeToQueueTest. What cannot run on JVM (Espresso/Robolectric are not
 * available here) stays device-verified: the end-to-end "swipe adds + snackbar shows"
 * and the animated snap-back under scroll/paging updates were checked on hardware.
 */
internal object SwipeToQueue {

    /** Mirrors RecyclerView.NO_POSITION (-1); avoids the framework dep in tests. */
    const val NO_POSITION = -1

    /** Fraction of the row width past which the swipe triggers. */
    const val SWIPE_THRESHOLD = 0.25f

    /** Icon alpha at rest and its sweep across the gesture (80..255). */
    const val ICON_ALPHA_BASE = 80
    const val ICON_ALPHA_RANGE = 175

    /** Which rows can start a swipe: track media rows only, never albums/artists/etc. */
    fun isSwipeable(isMediaRow: Boolean, isTrack: Boolean): Boolean =
        isMediaRow && isTrack

    /**
     * Fire the threshold haptic. Exactly-once per gesture is the caller's job:
     * pass the live hapticPending flag and clear it in buzz().
     */
    fun shouldBuzz(
        hapticPending: Boolean,
        isCurrentlyActive: Boolean,
        dX: Float,
        itemWidth: Int
    ): Boolean =
        hapticPending && isCurrentlyActive && itemWidth > 0 &&
            dX >= SWIPE_THRESHOLD * itemWidth

    /** White queue icon alpha for the drawn decor, clamped to [80, 255]. */
    fun iconAlpha(dX: Float, itemWidth: Int): Int {
        if (itemWidth <= 0) return ICON_ALPHA_BASE
        val progress = (dX / itemWidth).coerceIn(0f, 1f)
        return (ICON_ALPHA_BASE + ICON_ALPHA_RANGE * progress).toInt()
    }

    /** Right edge of the revealed background fill, clamped to the row. */
    fun backgroundRight(itemLeft: Int, itemRight: Int, dX: Float): Int =
        (itemLeft + dX.toInt()).coerceAtMost(itemRight)

    /**
     * Decide the add at release. ItemTouchHelper never commits a swipe anymore
     * (see getSwipeThreshold/getSwipeEscapeVelocity in FeedAdapter), so this is
     * the single commit condition, at the same fraction the haptic crossed.
     *
     * Distance in either horizontal direction: updateDxDy clamps mDx to the
     * allowed side, so the sign only encodes LTR vs RTL — matching the abs()
     * ItemTouchHelper itself used to commit with.
     *
     * @param dX last drawn translation of the gesture (an active frame only).
     * @param itemWidth swiped row width; 0 means nothing to measure.
     */
    fun pastThreshold(dX: Float, itemWidth: Int): Boolean =
        itemWidth > 0 && kotlin.math.abs(dX) >= SWIPE_THRESHOLD * itemWidth

    /**
     * Undo index for a Play Next add. Play Next front-inserts LIFO right after
     * current, so the just-added item is the first Play Next past current with the
     * added track's id. Matched by id rather than a stored index because the insert
     * resolves asynchronously — the index is unknowable at tap time.
     *
     * @param mediaIds queue mediaIds in order.
     * @param isPlayNext parallel flags (USER_QUEUED_NEXT stamp present).
     * @param trackIds parallel resolved track ids, null when unresolvable.
     * @param currentMediaId currently playing mediaId, null when unknown.
     * @param targetTrackId added track id, null when the added item is not a Track
     *   (then the first Play Next past current wins — see the documented limitation:
     *   a menu-driven multi-track add only ever removes one item).
     * @return queue index to remove, or null when the item hasn't landed yet or was
     *   already consumed.
     */
    fun undoIndex(
        mediaIds: List<String>,
        isPlayNext: List<Boolean>,
        trackIds: List<String?>,
        currentMediaId: String?,
        targetTrackId: String?
    ): Int? {
        val currentIndex =
            if (currentMediaId == null) NO_POSITION else mediaIds.indexOf(currentMediaId)
        val from = if (currentIndex == NO_POSITION) 0 else currentIndex + 1
        for (i in from until mediaIds.size) {
            if (!isPlayNext.getOrElse(i) { false }) continue
            if (targetTrackId == null || trackIds.getOrNull(i) == targetTrackId) return i
        }
        return null
    }

    /**
     * Undo index for a Queue add (the swipe's path): FIFO lands at the tail of
     * the user block, after the last user-flagged item past current — see
     * [dev.brahmkshatriya.echo.playback.UserBlock.queueInsertIndex]. So the
     * just-added copy is the LAST queue-flagged match past current, the mirror
     * of [undoIndex]'s first-Play-Next rule (LIFO front-insert). A Play Next
     * copy of the same track never wins: only USER_QUEUED_QUEUE-flagged rows
     * qualify. Same id-matching rationale as [undoIndex] — the insert resolves
     * asynchronously, the index is unknowable at swipe time.
     *
     * @param mediaIds queue mediaIds in order.
     * @param isQueue parallel flags (USER_QUEUED_QUEUE stamp present).
     * @param trackIds parallel resolved track ids, null when unresolvable.
     * @param currentMediaId currently playing mediaId, null when unknown.
     * @param targetTrackId added track id; the swipe path always passes one
     *   (only track rows are swipeable), null kept for symmetry with [undoIndex]
     *   — then the last queue-flagged row past current wins.
     * @return queue index to remove, or null when the item hasn't landed yet or
     *   was already consumed.
     */
    fun queueUndoIndex(
        mediaIds: List<String>,
        isQueue: List<Boolean>,
        trackIds: List<String?>,
        currentMediaId: String?,
        targetTrackId: String?
    ): Int? {
        val currentIndex =
            if (currentMediaId == null) NO_POSITION else mediaIds.indexOf(currentMediaId)
        val from = if (currentIndex == NO_POSITION) 0 else currentIndex + 1
        var match = NO_POSITION
        for (i in from until mediaIds.size) {
            if (!isQueue.getOrElse(i) { false }) continue
            if (targetTrackId == null || trackIds.getOrNull(i) == targetTrackId) match = i
        }
        return match.takeIf { it != NO_POSITION }
    }
}
