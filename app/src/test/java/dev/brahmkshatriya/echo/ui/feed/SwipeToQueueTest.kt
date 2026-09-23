package dev.brahmkshatriya.echo.ui.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decision math for swipe-right-to-play-next. Pure JVM — no player, no views.
 *
 * SCOPE NOTE: two behaviors cannot run here (no Espresso/Robolectric offline) and
 * stay device-verified on hardware: the end-to-end "swipe adds the track and the
 * snackbar shows", and the animated snap-back under scroll/paging updates. What
 * decides those paths — gating, haptic timing, restore targeting, undo lookup,
 * decor math — is extracted into SwipeToQueue and pinned below.
 */
class SwipeToQueueTest {

    // Threshold spec: the trigger both the haptic and ItemTouchHelper agree on.

    @Test
    fun `threshold is a quarter of the row`() {
        assertEquals(0.25f, SwipeToQueue.SWIPE_THRESHOLD)
    }

    // Only track rows arm the swipe — albums, artists, etc. never do.

    @Test
    fun `track media row is swipeable`() {
        assertTrue(SwipeToQueue.isSwipeable(isMediaRow = true, isTrack = true))
    }

    @Test
    fun `non-track media row is not swipeable`() {
        assertFalse(SwipeToQueue.isSwipeable(isMediaRow = true, isTrack = false))
    }

    @Test
    fun `non-media row is not swipeable even for a track`() {
        assertFalse(SwipeToQueue.isSwipeable(isMediaRow = false, isTrack = true))
    }

    @Test
    fun `fully foreign row is not swipeable`() {
        assertFalse(SwipeToQueue.isSwipeable(isMediaRow = false, isTrack = false))
    }

    // Haptic fires exactly once per gesture, at the threshold crossing.

    @Test
    fun `buzz past the threshold on an active gesture`() {
        // 0.25 * 1000 = 250; 300 is past it.
        assertTrue(SwipeToQueue.shouldBuzz(true, true, 300f, 1000))
    }

    @Test
    fun `buzz exactly at the threshold`() {
        assertTrue(SwipeToQueue.shouldBuzz(true, true, 250f, 1000))
    }

    @Test
    fun `no buzz below the threshold`() {
        assertFalse(SwipeToQueue.shouldBuzz(true, true, 249f, 1000))
    }

    @Test
    fun `no second buzz once the flag cleared`() {
        assertFalse(SwipeToQueue.shouldBuzz(false, true, 900f, 1000))
    }

    @Test
    fun `no buzz when the gesture is not active`() {
        assertFalse(SwipeToQueue.shouldBuzz(true, false, 900f, 1000))
    }

    @Test
    fun `no buzz on a zero-width row`() {
        assertFalse(SwipeToQueue.shouldBuzz(true, true, 300f, 0))
    }

    @Test
    fun `no buzz swiping the wrong way`() {
        assertFalse(SwipeToQueue.shouldBuzz(true, true, -300f, 1000))
    }

    // Snap-back targeting: each position goes to its own adapter.

    @Test
    fun `live binding position targets the binding adapter`() {
        assertEquals(
            SwipeToQueue.RestoreTarget.BindingAdapter(7),
            SwipeToQueue.restoreTarget(
                bindingPos = 7,
                absolutePos = SwipeToQueue.NO_POSITION,
                layoutPos = SwipeToQueue.NO_POSITION
            )
        )
    }

    @Test
    fun `binding position wins when everything is live`() {
        assertEquals(
            SwipeToQueue.RestoreTarget.BindingAdapter(7),
            SwipeToQueue.restoreTarget(bindingPos = 7, absolutePos = 12, layoutPos = 12)
        )
    }

    @Test
    fun `recycled row falls back to the recycler adapter position`() {
        // Row recycled mid-gesture by a paging refresh: binding dead, absolute live.
        assertEquals(
            SwipeToQueue.RestoreTarget.RecyclerAdapter(12),
            SwipeToQueue.restoreTarget(
                bindingPos = SwipeToQueue.NO_POSITION,
                absolutePos = 12,
                layoutPos = SwipeToQueue.NO_POSITION
            )
        )
    }

    @Test
    fun `layout position is the last resort`() {
        assertEquals(
            SwipeToQueue.RestoreTarget.RecyclerAdapter(12),
            SwipeToQueue.restoreTarget(
                bindingPos = SwipeToQueue.NO_POSITION,
                absolutePos = SwipeToQueue.NO_POSITION,
                layoutPos = 12
            )
        )
    }

    @Test
    fun `dead everywhere rebinds nothing`() {
        assertEquals(
            SwipeToQueue.RestoreTarget.None,
            SwipeToQueue.restoreTarget(
                bindingPos = SwipeToQueue.NO_POSITION,
                absolutePos = SwipeToQueue.NO_POSITION,
                layoutPos = SwipeToQueue.NO_POSITION
            )
        )
    }

    // Undo lookup: first Play Next past current with the added track id.

    // Queue shapes below: C = current, N = Play Next, Q = session Queue, R = radio.
    private fun undo(
        mediaIds: List<String>,
        isPlayNext: List<Boolean>,
        trackIds: List<String?>,
        current: String? = "c",
        target: String? = "t"
    ) = SwipeToQueue.undoIndex(mediaIds, isPlayNext, trackIds, current, target)

    @Test
    fun `undo finds the added track right after current`() {
        assertEquals(
            1, undo(
                mediaIds = listOf("c", "m1"),
                isPlayNext = listOf(false, true),
                trackIds = listOf("c", "t")
            )
        )
    }

    @Test
    fun `undo skips non-play-next rows before the match`() {
        // [C, R, N(t)] — radio between current and the added track.
        assertEquals(
            2, undo(
                mediaIds = listOf("c", "r", "m1"),
                isPlayNext = listOf(false, false, true),
                trackIds = listOf("c", "r", "t")
            )
        )
    }

    @Test
    fun `undo skips a play-next with the wrong track id`() {
        // [C, N(other), N(t)] — an older Play Next is not the just-added one.
        assertEquals(
            2, undo(
                mediaIds = listOf("c", "m0", "m1"),
                isPlayNext = listOf(false, true, true),
                trackIds = listOf("c", "other", "t")
            )
        )
    }

    @Test
    fun `undo ignores play-next at or before current`() {
        // Stale N before current must never win over the fresh one past it.
        assertEquals(
            2, undo(
                mediaIds = listOf("m0", "c", "m1"),
                isPlayNext = listOf(true, false, true),
                trackIds = listOf("t", "c", "t")
            )
        )
    }

    @Test
    fun `undo with unknown current searches from the head`() {
        assertEquals(
            0, undo(
                mediaIds = listOf("m1", "r"),
                isPlayNext = listOf(true, false),
                trackIds = listOf("t", "r"),
                current = null
            )
        )
    }

    @Test
    fun `undo with a consumed current searches from the head`() {
        assertEquals(
            0, undo(
                mediaIds = listOf("m1"),
                isPlayNext = listOf(true),
                trackIds = listOf("t"),
                current = "gone"
            )
        )
    }

    @Test
    fun `undo misses when the add has not landed yet`() {
        assertNull(
            undo(
                mediaIds = listOf("c", "r"),
                isPlayNext = listOf(false, false),
                trackIds = listOf("c", "r")
            )
        )
    }

    @Test
    fun `undo misses on an empty queue`() {
        assertNull(undo(emptyList(), emptyList(), emptyList()))
    }

    @Test
    fun `undo misses when current is last`() {
        assertNull(
            undo(
                mediaIds = listOf("r", "c"),
                isPlayNext = listOf(false, false),
                trackIds = listOf("r", "c"),
                target = "t"
            )
        )
    }

    @Test
    fun `undo of a non-track add takes the first play-next past current`() {
        assertEquals(
            1, undo(
                mediaIds = listOf("c", "m0", "m1"),
                isPlayNext = listOf(false, true, true),
                trackIds = listOf("c", "x", "y"),
                target = null
            )
        )
    }

    @Test
    fun `undo of duplicates takes the first`() {
        assertEquals(
            1, undo(
                mediaIds = listOf("c", "m1", "m2"),
                isPlayNext = listOf(false, true, true),
                trackIds = listOf("c", "t", "t")
            )
        )
    }

    // Decor math: themed fill edge and icon fade across the gesture.

    @Test
    fun `background edge follows the finger`() {
        assertEquals(340, SwipeToQueue.backgroundRight(100, 1100, 240f))
    }

    @Test
    fun `background edge clamps to the row`() {
        assertEquals(1100, SwipeToQueue.backgroundRight(100, 1100, 2000f))
    }

    @Test
    fun `icon is dim at rest`() {
        assertEquals(80, SwipeToQueue.iconAlpha(0f, 1000))
    }

    @Test
    fun `icon is full at the far edge`() {
        assertEquals(255, SwipeToQueue.iconAlpha(1000f, 1000))
    }

    @Test
    fun `icon fades linearly mid-gesture`() {
        // 80 + 175 * 0.5 = 167.5 -> 167.
        assertEquals(167, SwipeToQueue.iconAlpha(500f, 1000))
    }

    @Test
    fun `icon clamps past the edge`() {
        assertEquals(255, SwipeToQueue.iconAlpha(1500f, 1000))
    }

    @Test
    fun `icon clamps on a backwards drag`() {
        assertEquals(80, SwipeToQueue.iconAlpha(-200f, 1000))
    }

    @Test
    fun `icon stays dim on a zero-width row`() {
        assertEquals(80, SwipeToQueue.iconAlpha(300f, 0))
    }
}
