package dev.brahmkshatriya.echo.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Invariants of the session user block (Play Next + Queue), Spotify model.
 * Pure math — no player needed.
 */
class UserBlockTest {

    // queueInsertIndex: currentIndex + flags past current -> insert index.

    @Test
    fun `empty block inserts right after current`() {
        assertEquals(3, UserBlock.queueInsertIndex(2, listOf(false, false, false)))
    }

    @Test
    fun `no upcoming inserts right after current`() {
        assertEquals(1, UserBlock.queueInsertIndex(0, emptyList()))
    }

    @Test
    fun `contiguous block appends at its end`() {
        assertEquals(4, UserBlock.queueInsertIndex(0, listOf(true, true, true, false, false)))
    }

    @Test
    fun `interleaved block inserts after last flagged, never splits it`() {
        // [C, U, R, U, R] -> after the second U (index 3), i.e. insert at 4.
        assertEquals(4, UserBlock.queueInsertIndex(0, listOf(true, false, true, false)))
    }

    @Test
    fun `all flagged appends at end`() {
        assertEquals(3, UserBlock.queueInsertIndex(0, listOf(true, true)))
    }

    // 1. Play Next LIFO: two taps land at current+1 both times, so the second plays first.
    @Test
    fun `play next taps stack lifo after current`() {
        val order = mutableListOf("C")
        var current = 0
        // Tap A.
        order.add(UserBlock.playNextInsertIndex(current), "A")
        // Tap B.
        order.add(UserBlock.playNextInsertIndex(current), "B")
        assertEquals(listOf("C", "B", "A"), order)
    }

    // 2. Queue FIFO across a drag-interleaved block: Q3 lands after the last flagged item.
    @Test
    fun `queue fifo survives a drag-interleaved block`() {
        // [C, Q1, R1, Q2, R2] as flags; a count would say 1+2=3 and split Q1/Q2.
        val insert = UserBlock.queueInsertIndex(0, listOf(true, false, true, false))
        assertEquals(4, insert)
    }

    @Test
    fun `queue fifo appends successive adds in tap order`() {
        val flags = mutableListOf<Boolean>()
        val at1 = UserBlock.queueInsertIndex(0, flags)
        flags.add(at1 - 1, true) // Q1 lands at 1.
        val at2 = UserBlock.queueInsertIndex(0, flags)
        flags.add(at2 - 1, true) // Q2 lands at 2.
        assertEquals(1, at1)
        assertEquals(2, at2)
    }

    // 3. Shuffle pin: only the identical current instance is excluded; a value-equal
    // duplicate of it is an ordinary entry and must survive.
    @Test
    fun `partition excludes only the identical current instance`() {
        data class Row(val id: String, val flagged: Boolean)

        val current = Row("same", false)
        val dupe = Row("same", false)
        val items = listOf(current, dupe, Row("n", true))
        val (head, pinned, rest) =
            UserBlock.partition(items, { it === current }, { it.flagged })
        assertEquals(current, head)
        assertEquals(listOf("n"), pinned.map { it.id })
        assertEquals(listOf("same"), rest.map { it.id })
    }

    // 4. Drag promotion: an unflagged moved item joins the block; a flagged one is untouched.
    @Test
    fun `drag promotes only unflagged items`() {
        assertEquals(true, UserBlock.shouldPromoteOnMove(false))
        assertEquals(false, UserBlock.shouldPromoteOnMove(true))
    }

    @Test
    fun `promoted drag extends the block for the next queue add`() {
        // [C, R] then R is dragged (promoted) -> [C, U]; next Queue add lands at 2.
        val flags = mutableListOf(false)
        if (UserBlock.shouldPromoteOnMove(flags[0])) flags[0] = true
        assertEquals(2, UserBlock.queueInsertIndex(0, flags))
    }

    // 5. Unshuffle mirror: unshuffled applies the timeline index, shuffled follows the anchor.
    @Test
    fun `unshuffled insert applies timeline index clamped`() {
        assertEquals(2, UserBlock.unshuffledInsertIndex(false, 2, 5, anchorOriginalIndex = 4))
        assertEquals(5, UserBlock.unshuffledInsertIndex(false, 9, 5, anchorOriginalIndex = null))
        assertEquals(0, UserBlock.unshuffledInsertIndex(false, -3, 5, anchorOriginalIndex = null))
    }

    @Test
    fun `shuffled insert follows anchor or appends`() {
        assertEquals(3, UserBlock.unshuffledInsertIndex(true, 7, 5, anchorOriginalIndex = 2))
        assertEquals(5, UserBlock.unshuffledInsertIndex(true, 7, 5, anchorOriginalIndex = null))
        assertEquals(5, UserBlock.unshuffledInsertIndex(true, 0, 5, anchorOriginalIndex = 99))
    }

    // partition: current head, pinned in order, rest in order, duplicates distinct.

    @Test
    fun `partition keeps pinned order and excludes current once`() {
        data class Row(val id: String, val flagged: Boolean)

        val current = Row("c", false)
        val items = listOf(
            current,
            Row("n1", true),
            Row("r1", false),
            Row("q1", true),
        )
        val (head, pinned, rest) = UserBlock.partition(items, { it === current }, { it.flagged })
        assertEquals(current, head)
        assertEquals(listOf("n1", "q1"), pinned.map { it.id })
        assertEquals(listOf("r1"), rest.map { it.id })
    }

    @Test
    fun `partition never merges value-equal duplicates`() {
        data class Row(val id: String, val flagged: Boolean)

        val first = Row("same", true)
        val second = Row("same", true)
        val items = listOf(first, second, Row("r", false))
        // No current in the list: head null, both dupes land in pinned as distinct entries.
        val (head, pinned, rest) = UserBlock.partition(items, { false }, { it.flagged })
        assertEquals(null, head)
        assertEquals(2, pinned.size)
        assertEquals(1, rest.size)
    }

    @Test
    fun `partition with missing current degrades to head null`() {
        val items = listOf("a", "b")
        val (head, pinned, rest) = UserBlock.partition(items, { false }, { false })
        assertEquals(null, head)
        assertEquals(emptyList<String>(), pinned)
        assertEquals(listOf("a", "b"), rest)
    }
}
