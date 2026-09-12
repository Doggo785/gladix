package dev.brahmkshatriya.echo.ui.player.more.upnext

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.transition.MaterialSharedAxis
import dev.brahmkshatriya.echo.databinding.FragmentPlayerQueueBinding
import dev.brahmkshatriya.echo.ui.player.PlayerViewModel
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import dev.brahmkshatriya.echo.utils.ui.AnimationUtils.setupTransition
import dev.brahmkshatriya.echo.utils.ui.AutoClearedValue.Companion.autoClearedNullable
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class QueueFragment : Fragment() {

    private var binding by autoClearedNullable<FragmentPlayerQueueBinding>()
    private val viewModel by activityViewModel<PlayerViewModel>()

    // ⚠⚠ A DRAG MUST NOT BE INTERRUPTED BY A RESUBMIT, AND THE VISIBLE JUMP IS THE LESSER HALF
    // OF WHY. onMove calls viewModel.moveQueueItems, which reorders the player's timeline, which emits
    // fullQueueFlow (after PlayerEventListener.emitFullQueue's 50ms debounce) -> queueFlow -> submit().
    // THE DRAG TRIGGERS ITS OWN RESUBMIT; no radio top-up or outside mutation is needed, which is why it
    // reproduced on every drag.
    // ⚠️ THE OBVIOUS FIX - SUPPRESS ONLY THE scrollToPosition - WOULD HAVE FIXED THE HALF YOU CAN
    // SEE AND LEFT THE DRAG STILL CANCELLABLE. Read from recyclerview 1.4.0:
    // ItemTouchHelper.onChildViewDetachedFromWindow (ItemTouchHelper.java:902-916) does
    // `if (mSelected != null && holder == mSelected) select(null, ACTION_STATE_IDLE);` - if a resubmit
    // recycles the row under the finger, THE DRAG IS TERMINATED. So the whole submit is deferred, not the
    // scroll. That is also what PlaylistTrackAdapter already does; this screen simply never got it.
    // ACCEPTED COST, STATED RATHER THAN WIDENED: tracks appended mid-drag (a radio top-up, say) do not
    // appear until the finger lifts. A few seconds of staleness beats a cancelled drag.
    private var isDragging = false

    // ⚠⚠ A BOOLEAN, NOT PlaylistTrackAdapter'S pendingList, AND THE DIFFERENCE IS NOT STYLE.
    // There the observer CARRIES the value (`observe(vm.currentTracks) { ... pendingList = it }`), so the
    // deferred list has to be stored or it is lost - pendingList is NECESSARY there.
    // Here submit() takes no argument and reads viewModel.queue and playerState.current fresh at call time,
    // so storing a snapshot would replay a list that may be several mutations stale by the time the finger
    // lifts. Recording only THAT a submit was suppressed lets the catch-up read the latest state. Copying
    // pendingList across would have been the faithful-looking choice and the worse one.
    private var submitSuppressed = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentPlayerQueueBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    private val queueAdapter: QueueAdapter by lazy {
        QueueAdapter(object : QueueAdapter.Listener() {
            override fun onDragHandleTouched(viewHolder: RecyclerView.ViewHolder) {
                touchHelper.startDrag(viewHolder)
            }

            override fun onItemClicked(position: Int) {
                viewModel.play(position)
            }

            override fun onItemClosedClicked(position: Int) {
                viewModel.removeQueueItem(position)
            }
        })
    }

    private val touchHelper: ItemTouchHelper by lazy {
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            ItemTouchHelper.START
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                if (fromPos == RecyclerView.NO_POSITION || toPos == RecyclerView.NO_POSITION)
                    return false
                // Seam 2/G2: keep current at index 0 — an upcoming track can't be dropped at/above the
                // current row, so nothing gets stranded above current. Current index read from the
                // viewModel (same source as submit()), NOT queueAdapter — referencing the adapter here
                // creates a by-lazy ↔ by-lazy type-inference cycle with its touchHelper-using listener.
                val currentPos = viewModel.playerState.current.value?.let { c ->
                    viewModel.queue.indexOfFirst { it.mediaId == c.mediaItem.mediaId }
                } ?: -1
                if (currentPos != -1 && toPos <= currentPos) return false
                viewModel.moveQueueItems(fromPos, toPos)
                return true
            }

            // Armed before any onMove can fire. Only ACTION_STATE_DRAG arms it - a swipe removes a row
            // outright and has no in-progress state to protect.
            override fun onSelectedChanged(
                viewHolder: RecyclerView.ViewHolder?, actionState: Int
            ) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) isDragging = true
            }

            // ⚠⚠ REACHABILITY IS TOTAL; THE TIMING IS DELIBERATELY NOT IMMEDIATE - READ BOTH
            // HALVES BEFORE "FIXING" THE LAG. clearView is reached from all five call sites in
            // recyclerview 1.4.0 (ItemTouchHelper.java:336, 504, 652, 678, 913), and the cancellation path
            // is covered rather than special-cased: a detach at :908 calls select(null, ACTION_STATE_IDLE),
            // which lands on :652/:678 like any other end. So the flag cannot latch true.
            // BUT IT IS NOT CLEARED WHEN THE FINGER LIFTS. On the animated path clearView fires at
            // onAnimationEnd (:652) and can defer further into mPendingCleanup - the library's own comment
            // there reads "wait until remove animation is complete". THE FLAG THEREFORE STAYS SET THROUGH
            // THE SETTLE ANIMATION, AND THAT IS THE POINT: submitting while the row is still animating home
            // recycles it exactly as badly as submitting mid-drag. This is a choice, not lag.
            override fun clearView(
                recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
            ) {
                super.clearView(recyclerView, viewHolder)
                isDragging = false
                if (!submitSuppressed) return
                submitSuppressed = false
                // ⚠⚠ NO SCROLL ON THE CATCH-UP, AND THE RECORD IS WHY. The
                // scrollToPosition in submit() arrived on 2026-06-24 with fullCurrentIndex as part of the
                // WINDOWED-INDEX fix - viewModel.queue had been populated from the 50-item windowed
                // MediaController timeline, so every index into it pointed at the wrong track. The scroll
                // was one-time orientation onto the newly-correct row, NOT a continuous invariant that
                // every commit owes. The drag case was never considered, which is what makes this a gap
                // being closed rather than a trade-off being reopened.
                // AND HERE THE CURRENT ROW CANNOT HAVE MOVED: getMovementFlags gives it no drag flags, and
                // onMove refuses any toPos <= currentPos. So a drag-end scroll would travel to a row that
                // is exactly where it was, dragging the viewport away from the drop the user is looking at
                // at the worst possible moment - the instant they lift.
                submit(scroll = false)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val pos = viewHolder.bindingAdapterPosition
                viewModel.removeQueueItem(pos)
            }

            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                // Seam 2/G2: the current track is pinned — no drag. Current index read from the
                // viewModel (same source as submit()), NOT queueAdapter, to avoid the by-lazy cycle.
                val pos = viewHolder.bindingAdapterPosition
                val currentPos = viewModel.playerState.current.value?.let { c ->
                    viewModel.queue.indexOfFirst { it.mediaId == c.mediaItem.mediaId }
                } ?: -1
                val isCurrent = pos != RecyclerView.NO_POSITION && pos == currentPos
                val dragFlags = if (isCurrent) 0 else ItemTouchHelper.UP or ItemTouchHelper.DOWN
                return makeMovementFlags(dragFlags, ItemTouchHelper.START)
            }
        })
    }

    private fun submitOrDefer() {
        if (isDragging) {
            submitSuppressed = true
            return
        }
        submit()
    }

    // Hoisted out of onViewCreated so clearView can reach it. The explicit types on queueAdapter and
    // touchHelper above are what make that safe: the by-lazy pair reference each other (the adapter's
    // listener calls touchHelper.startDrag), and routing a third reference through an INFERRED type is
    // what produces the cycle the onMove/getMovementFlags notes warn about. Declared types break it at the
    // root; those two notes stay accurate about why they read the index from the viewModel.
    private fun submit(scroll: Boolean = true) {
        val current = viewModel.playerState.current.value
        val fullCurrentIndex = current?.let { c ->
            viewModel.queue.indexOfFirst { it.mediaId == c.mediaItem.mediaId }
        } ?: -1
        val it = viewModel.queue.mapIndexed { index, mediaItem ->
            if (fullCurrentIndex == index) current!!.isPlaying to current.mediaItem
            else null to mediaItem
        }
        queueAdapter.submitList(it) {
            if (!scroll || fullCurrentIndex < 0) return@submitList
            binding?.root?.scrollToPosition(fullCurrentIndex)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setupTransition(view, false, axis = MaterialSharedAxis.Y)
        val recyclerView = binding!!.root
        recyclerView.adapter = queueAdapter
        // ⚠️ NO FAST SCROLLER HERE, AND THAT IS THE WHOLE STORY - NOT A BROKEN ONE, A
        // NEVER-WIRED ONE. Closing a parked "QueueFragment fast scroller" item 2026-09-12: this screen has
        // never called FastScrollerHelper.applyTo, so the two fast-scroller explanations for the queue-drag
        // jump are STRUCTURALLY dead rather than merely unlikely.
        //   - "the scroller reacts to ItemTouchHelper's edge auto-scroll" needs a scroller to react.
        //   - "attach order is wrong" needs two things to order; the load-bearing ordering recorded at
        //     FeedFragment and MediaDetailsFragment (touch helper attached BEFORE the scroller) has no
        //     counterpart here because there is only one attach.
        // Do not "restore" an ordering that never existed, and do not add a scroller here to match the
        // other screens without a reason of its own - the queue is bounded by what is upcoming, which is
        // not the long-list case the scroller exists for.
        touchHelper.attachToRecyclerView(recyclerView)
        val manager = recyclerView.layoutManager as LinearLayoutManager
        val screenHeight = view.resources.displayMetrics.heightPixels / 3

        // BOTH observers defer: a track transition during a drag would scroll just as disruptively as a
        // queue mutation, and playerState.current fires on every play/pause too.
        observe(viewModel.playerState.current) { submitOrDefer() }
        observe(viewModel.queueFlow) { submitOrDefer() }

        val currentForScroll = viewModel.playerState.current.value ?: return
        val index = viewModel.queue.indexOfFirst { it.mediaId == currentForScroll.mediaItem.mediaId }
        if (index < 0) return
        manager.scrollToPositionWithOffset(index + 1, screenHeight)
    }
}