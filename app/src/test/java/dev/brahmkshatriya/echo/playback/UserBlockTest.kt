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
