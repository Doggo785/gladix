package dev.brahmkshatriya.echo.playback

/**
 * Pure queue-block math for the session user block (Play Next + Queue), Spotify model.
 *
 * Deliberately free of Player/MediaItem so the invariants are unit-tested without a player:
 * callers collect flags on the main thread, then delegate here. Index-based throughout, so
 * duplicated tracks stay distinct entries.
 */
internal object UserBlock {

    /**
     * Insert index for a new Queue item (FIFO): right after the LAST flagged item past current.
     *
     * [flagged] covers indices `currentIndex + 1` onward, in order. Counting the block size is
     * only equal to the block end when the block is contiguous — drag promotion interleaves radio
     * items into it, so the last flagged index is the honest anchor. Falls back to
     * `currentIndex + 1` when nothing past current is flagged.
     */
    fun queueInsertIndex(currentIndex: Int, flagged: List<Boolean>): Int {
        var insert = currentIndex + 1
        flagged.forEachIndexed { offset, isFlagged ->
            if (isFlagged) insert = currentIndex + 1 + offset + 1
        }
        return insert
    }

    /**
     * Insert index for a new Play Next item (LIFO): always directly after current, so the
     * newest tap plays first and pushes older Play Next items down.
     */
    fun playNextInsertIndex(currentIndex: Int): Int = currentIndex + 1

    /**
     * Partition for shuffle: [current] head, [pinned] user items in order, [rest] everything else
     * (shuffling itself stays at the call site). Predicates — not values — so value-equal
     * duplicates are never merged: identity checks (`===`) belong to the caller.
     */
    fun <T> partition(
        items: List<T>,
        isCurrent: (T) -> Boolean,
        isFlagged: (T) -> Boolean,
    ): Triple<T?, List<T>, List<T>> {
        val current = items.firstOrNull(isCurrent)
        val pinned = items.filter { !isCurrent(it) && isFlagged(it) }
        val rest = items.filter { !isCurrent(it) && !isFlagged(it) }
        return Triple(current, pinned, rest)
    }

    /**
     * Whether a drag-moved item joins the user block: every hand-placed item does, unless it is
     * already flagged. The player cannot tell a drag from a programmatic move, so this rule lives
     * on the documented assumption that moveMediaItem's only caller is the queue drag.
     */
    fun shouldPromoteOnMove(isUserQueued: Boolean): Boolean = !isUserQueued

    /**
     * Mirror index for a timeline insert into the unshuffle reference (`original`).
     *
     * Unshuffled, timeline and reference share the same order, so the timeline index applies
     * directly. Shuffled, the anchor is the reference position of the timeline predecessor
     * ([anchorOriginalIndex], null when unknown) and the item lands right after it; a missing
     * anchor falls back to append. Single-entry removal elsewhere relies on this staying exact.
     */
    fun unshuffledInsertIndex(
        isShuffled: Boolean,
        timelineIndex: Int,
        originalSize: Int,
        anchorOriginalIndex: Int?,
    ): Int {
        if (!isShuffled) return timelineIndex.coerceIn(0, originalSize)
        val anchor = anchorOriginalIndex?.takeIf { it in 0 until originalSize } ?: return originalSize
        return anchor + 1
    }
}
