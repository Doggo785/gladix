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
}
