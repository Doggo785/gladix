package dev.brahmkshatriya.echo.ui.feed

import android.R.attr.colorPrimary
import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.paging.LoadState
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.databinding.ItemLoadingBinding
import dev.brahmkshatriya.echo.playback.PlayerState
import dev.brahmkshatriya.echo.ui.common.GridAdapter
import dev.brahmkshatriya.echo.ui.feed.FeedData.FeedTab
import dev.brahmkshatriya.echo.ui.feed.FeedLoadingAdapter.Companion.createListener
import dev.brahmkshatriya.echo.ui.feed.FeedType.Enum.Category
import dev.brahmkshatriya.echo.ui.feed.FeedType.Enum.CategoryGrid
import dev.brahmkshatriya.echo.ui.feed.FeedType.Enum.Header
import dev.brahmkshatriya.echo.ui.feed.FeedType.Enum.HorizontalList
import dev.brahmkshatriya.echo.ui.feed.FeedType.Enum.Media
import dev.brahmkshatriya.echo.ui.feed.FeedType.Enum.MediaGrid
import dev.brahmkshatriya.echo.ui.feed.FeedType.Enum.Video
import dev.brahmkshatriya.echo.ui.feed.FeedType.Enum.VideoHorizontal
import dev.brahmkshatriya.echo.ui.feed.viewholders.CategoryViewHolder
import dev.brahmkshatriya.echo.ui.feed.viewholders.FeedViewHolder
import dev.brahmkshatriya.echo.ui.feed.viewholders.HeaderViewHolder
import dev.brahmkshatriya.echo.ui.feed.viewholders.HorizontalListViewHolder
import dev.brahmkshatriya.echo.ui.feed.viewholders.MediaGridViewHolder
import dev.brahmkshatriya.echo.ui.feed.viewholders.MediaViewHolder
import dev.brahmkshatriya.echo.ui.feed.viewholders.VideoHorizontalViewHolder
import dev.brahmkshatriya.echo.ui.feed.viewholders.VideoViewHolder
import dev.brahmkshatriya.echo.ui.player.PlayerViewModel
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import dev.brahmkshatriya.echo.utils.ui.AnimationUtils.animatedWithAlpha
import dev.brahmkshatriya.echo.utils.ui.UiUtils.dpToPx
import dev.brahmkshatriya.echo.utils.ui.scrolling.ScrollAnimPagingAdapter
import kotlinx.coroutines.flow.combine
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import java.lang.ref.WeakReference

class FeedAdapter(
    private val viewModel: FeedData,
    private val listener: FeedClickListener,
    private val takeFullScreen: Boolean = false,
    // Resolved once at construction (see getFeedAdapter), never per getSpanSize call.
    private val isTV: Boolean = false,
    private val phoneSingleColumn: Boolean = false,
) : ScrollAnimPagingAdapter<FeedType, FeedViewHolder<*>>(DiffCallback), GridAdapter {

    object DiffCallback : DiffUtil.ItemCallback<FeedType>() {
        override fun areContentsTheSame(oldItem: FeedType, newItem: FeedType) = oldItem == newItem
        override fun areItemsTheSame(oldItem: FeedType, newItem: FeedType): Boolean {
            if (oldItem.extensionId != newItem.extensionId) return false
            if (newItem.type != oldItem.type) return false
            if (oldItem.id != newItem.id) return false
            return true
        }
    }

    private val viewPool = RecyclerView.RecycledViewPool()
    // ⚠⚠ peek, NOT getItem, AND THE DIFFERENCE IS AN ANR. Build 1107 ANR'd on the fast-scroller
    // drag path with this call at the bottom of the stack. THE CHAIN: a thumb drag runs
    // PixelFastScrollViewHelper.scrollTo -> nestedScrollBy -> fill -> layoutChunk ->
    // getItemDecorInsetsForChild -> GridAdapter.VerticalSpacingItemDecoration.getItemOffsets, which
    // calls GridLayoutManager.SpanSizeLookup.getSpanGroupIndex. THAT METHOD WALKS: its loop is
    // `for (i = start; i < adapterPosition; i++) getSpanSize(i)`, and `start` is 0 whenever the span
    // cache is cold. So ONE child's decoration cost is O(position) getSpanSize calls - each routed
    // through TWO GridAdapter.Concat levels on a media-details page - and each landing HERE.
    // ⚠️ getItem IS NOT A LOOKUP. AsyncPagingDataDiffer.getItem does two StateFlow CAS updates
    // and writes lastAccessedIndex, then PagingDataPresenter.get does two MORE and calls
    // `hintReceiver.processHint(pageStore.createAccessHintForIndex(index))` - a PREFETCH SIGNAL. At
    // O(position) calls per child that is thousands of spurious access hints per layout, which also
    // CLOSES A LOOP: a hint triggers a page load, the insert fires onItemsAdded, GridLayoutManager
    // clears the span-group cache, and the next walk starts from 0 again.
    // peek returns the identical value with none of it (paging 3.5.1, AsyncPagingDataDiffer:484).
    //
    // ⚠⚠ IT IS A DROP-IN ONLY BECAUSE enablePlaceholders = false (PagedSource.kt). THIS IS THE
    // PART THAT WILL BE REDISCOVERED THE HARD WAY. With placeholders OFF, every index in
    // [0, itemCount) is a loaded item, so peek and getItem return the same non-null object. TURN
    // PLACEHOLDERS ON AND THEY DIVERGE: peek returns null for an unloaded slot where getItem would
    // have triggered the load - and the `?: 0` fallback below IS NOT NEUTRAL. Ordinal 0 is
    // FeedType.Enum.Header, and getSpanSize maps Header -> count, i.e. FULL WIDTH. So enabling
    // placeholders would silently render every unloaded row as a full-width header, with no crash
    // and no log. If placeholders are ever enabled, this fallback must be revisited FIRST.
    //
    // ⚠️ onBindViewHolder MUST KEEP USING getItem - do not "finish the job" there. That call
    // fires a hint for an item actually being displayed, which is the signal Paging is designed
    // around. Removing the walk's hints makes that signal MORE accurate, not less: the walk was
    // rewriting lastAccessedIndex thousands of times per layout and leaving it at position-1.
    override fun getItemViewType(position: Int) =
        runCatching { peek(position)!! }.getOrNull()?.type?.ordinal ?: 0

    private var isPlayButtonShown = false
    private fun FeedType.toTrack(): Track? = when (this) {
        is FeedType.Media -> item as? Track
        is FeedType.MediaGrid -> item as? Track
        is FeedType.Video -> item
        else -> null
    }

    fun getAllTracks(feed: FeedType): Pair<List<Track>, Int> {
        if (!isPlayButtonShown) return listOfNotNull(feed.toTrack()) to 0
        val list = snapshot().mapNotNull { it }
        val index = list.indexOfFirst { it.id == feed.id }
        if (index == -1) return listOf<Track>() to -1
        val from = list.take(index).indexOfLast { it.type != feed.type }
        val to = list.drop(index + 1).indexOfFirst { it.type != feed.type }
        val feeds = list.subList(from + 1, if (to == -1) list.size else index + to + 1)
        val tracks = feeds.mapNotNull { it.toTrack() }
        val newIndex = tracks.indexOfFirst { it.id == feed.id }
        return tracks to newIndex
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FeedViewHolder<*> {
        val type = FeedType.Enum.entries[viewType]
        return when (type) {
            Header -> HeaderViewHolder(parent, listener)
            HorizontalList -> HorizontalListViewHolder(parent, listener, viewPool)
            Category -> CategoryViewHolder(parent, listener)
            CategoryGrid -> CategoryViewHolder(parent, listener)
            Media -> MediaViewHolder(parent, listener, ::getAllTracks)
            MediaGrid -> MediaGridViewHolder(parent, listener, ::getAllTracks)
            Video -> VideoViewHolder(parent, listener, ::getAllTracks)
            VideoHorizontal -> VideoHorizontalViewHolder(parent, listener, ::getAllTracks)
        }
    }

    override fun onBindViewHolder(holder: FeedViewHolder<*>, position: Int) {
        super.onBindViewHolder(holder, position)
        val feed = runCatching { getItem(position) }.getOrNull() ?: return
        when (holder) {
            is HeaderViewHolder -> holder.bind(feed as FeedType.Header)
            is CategoryViewHolder -> holder.bind(feed as FeedType.Category)
            is MediaViewHolder -> holder.bind(feed as FeedType.Media)
            is MediaGridViewHolder -> holder.bind(feed as FeedType.MediaGrid)
            is VideoViewHolder -> holder.bind(feed as FeedType.Video)
            is VideoHorizontalViewHolder -> holder.bind(feed as FeedType.Video)
            is HorizontalListViewHolder -> {
                holder.bind(feed as FeedType.HorizontalList)
                viewModel.visibleScrollableViews[position] = WeakReference(holder)
                holder.layoutManager.apply {
                    val state = viewModel.layoutManagerStates[position]
                    if (state != null) onRestoreInstanceState(state)
                    else scrollToPosition(0)
                }
            }
        }
        holder.onCurrentChanged(current)
    }

    override fun onViewRecycled(holder: FeedViewHolder<*>) {
        if (holder is HorizontalListViewHolder) saveScrollState(holder) {
            viewModel.visibleScrollableViews.remove(holder.bindingAdapterPosition)
        }
    }

    override fun onViewAttachedToWindow(holder: FeedViewHolder<*>) {
        holder.onCurrentChanged(current)
    }

    class LoadingViewHolder(
        parent: ViewGroup,
        val binding: ItemLoadingBinding = ItemLoadingBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        ),
    ) : FeedLoadingAdapter.ViewHolder(binding.root) {
        init {
            binding.textView.isVisible = false
        }

        override fun bind(loadState: LoadState) {
            binding.root.alpha = 0f
            binding.root.animatedWithAlpha(500)
        }
    }

    fun getAllTracks() = snapshot().mapNotNull {
        when (it) {
            is FeedType.Media -> listOfNotNull(it.item as? Track)
            is FeedType.MediaGrid -> listOfNotNull(it.item as? Track)
            is FeedType.Video -> listOf(it.item)
            is FeedType.HorizontalList -> it.shelf.list.filterIsInstance<Track>()
            else -> null
        }
    }.flatten()

    fun withLoading(fragment: Fragment, vararg before: GridAdapter, initialButtons: Boolean = false, onCreatePlaylistClick: (() -> Unit)? = null, onEditPlaylistClick: (() -> Unit)? = null): GridAdapter.Concat {
        val tabs = TabsAdapter<FeedTab>({ tab.title }) { view, index, tab ->
            listener.onTabSelected(view, tab.feedId, tab.extensionId, index)
        }
        fragment.observe(viewModel.tabsFlow) { tabs.data = it }
        fragment.observe(viewModel.selectedTabIndexFlow) { tabs.selected = it }
        var buttonsAdapter: ButtonsAdapter? = null
        val speechLauncher = fragment.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()?.let { text ->
                        viewModel.searchToggled = true
                        viewModel.searchQuery = text
                        buttonsAdapter?.notifyItemChanged(0)
                        viewModel.onSearchClicked()
                    }
            }
        }
        val buttons = ButtonsAdapter(viewModel, listener, ::getAllTracks, onCreatePlaylistClick, onEditPlaylistClick) {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            }
            runCatching { speechLauncher.launch(intent) }
        }
        buttonsAdapter = buttons
        if (initialButtons) buttons.buttons = FeedData.Buttons("", "", Feed.Buttons(showPlayAndShuffle = true))
        fragment.observe(viewModel.buttonsFlow) {
            buttons.buttons = it
            isPlayButtonShown = it?.buttons?.showPlayAndShuffle == true
        }
        val loadStateListener = fragment.createListener { retry() }
        val header = FeedLoadingAdapter(loadStateListener) { LoadingViewHolder(it) }
        val footer = FeedLoadingAdapter(loadStateListener) { LoadingViewHolder(it) }
        val empty = EmptyAdapter()
        fragment.observe(
            loadStateFlow.combine(viewModel.shouldShowEmpty) { a, b -> a to b }
        ) { (loadStates, shouldShowEmpty) ->
            val isEmpty =
                shouldShowEmpty && itemCount == 0 && loadStates.append is LoadState.NotLoading
            empty.loadState = if (isEmpty) LoadState.Loading else LoadState.NotLoading(false)
        }
        var hasEverLoaded = false
        addLoadStateListener { loadStates ->
            if (loadStates.refresh is LoadState.NotLoading) hasEverLoaded = true
            header.loadState = if (hasEverLoaded && loadStates.refresh is LoadState.Loading)
                LoadState.NotLoading(false)
            else loadStates.refresh
            footer.loadState = loadStates.append
        }
        return GridAdapter.Concat(*before, tabs, buttons, header, empty, this, footer)
    }

    override val adapter = this
    override fun getSpanSize(position: Int, width: Int, count: Int) =
        when (FeedType.Enum.entries[getItemViewType(position)]) {
            Header, HorizontalList -> count
            Category, Media, Video -> when {
                takeFullScreen -> count                  // checked first: full width on phone AND TV
                // TV: pin to 2 columns regardless of feed width. span = count/2 yields exactly 2 tiles per
                // row for any even count (configureGridLayout forces even), same idiom as CategoryGrid below.
                // Was 2.coerceAtMost(count) = count/2-ish columns, which drifted 1<->2 as the width-derived
                // spanCount crossed the floor/even boundary. count==1 (feed too narrow) degrades to 1 column.
                isTV -> (count / 2).coerceAtLeast(1)
                phoneSingleColumn -> count               // sw<600dp: full width -> 1 column
                else -> 2.coerceAtMost(count)            // sw>=600dp tablet: today's behavior verbatim
            }
            // Pin category-grid previews to 2 columns regardless of device width: span = count/2
            // yields exactly 2 tiles per row for any even count (Home/Search use even=true), so the
            // 6-card preview is always 3 rows of 2 on every device. coerceAtLeast(1) guards count==1.
            CategoryGrid -> (count / 2).coerceAtLeast(1)
            MediaGrid, VideoHorizontal -> 1
        }

    // peek for the same reason as getItemViewType - see the note there. THIS IS THE SECOND ROUTE
    // FROM THE SAME getItemOffsets, and changing only the other one would have left it firing
    // access hints on the hot path. Once per child rather than O(position), so it was never the
    // ANR, but it is the same call on the same drag and they move together.
    override fun isSectionHeader(position: Int) =
        runCatching { peek(position) }.getOrNull()?.type == Header


    private fun clearState() {
        viewModel.layoutManagerStates.clear()
        viewModel.visibleScrollableViews.clear()
    }

    private fun saveScrollState(
        holder: HorizontalListViewHolder, block: ((HorizontalListViewHolder) -> Unit)? = null,
    ) = runCatching {
        val layoutManagerStates = viewModel.layoutManagerStates
        layoutManagerStates[holder.bindingAdapterPosition] =
            holder.layoutManager.onSaveInstanceState()
        block?.invoke(holder)
    }

    fun saveState() {
        viewModel.visibleScrollableViews.values.forEach { item ->
            item.get()?.let { saveScrollState(it) }
        }
        viewModel.visibleScrollableViews.clear()
    }

    init {
        addLoadStateListener {
            if (it.refresh == LoadState.Loading) clearState()
        }
    }

    private var current: PlayerState.Current? = null
    fun onCurrentChanged(current: PlayerState.Current?) {
        this.current = current
        onEachViewHolder { onCurrentChanged(current) }
    }

    companion object {
        fun Fragment.getFeedAdapter(
            viewModel: FeedData,
            listener: FeedClickListener,
            takeFullScreen: Boolean = false,
        ): FeedAdapter {
            val playerViewModel by activityViewModel<PlayerViewModel>()
            val context = requireContext()
            val isTV = (context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager)
                .currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
            val phoneSingleColumn = context.resources.getBoolean(R.bool.feed_phone_single_column)
            val adapter = FeedAdapter(
                viewModel, listener, takeFullScreen, isTV, phoneSingleColumn
            )
            observe(viewModel.pagingFlow) {
                adapter.saveState()
                adapter.submitData(it)
            }
            observe(playerViewModel.playerState.current) { adapter.onCurrentChanged(it) }
            return adapter
        }

        // Swipe a track RIGHT (END in LTR, Spotify/Deezer direction) to play it next.
        // The row always snaps back: swiping is an action, not a removal.
        fun getTouchHelper(listener: FeedClickListener) = ItemTouchHelper(
            object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.END) {
                // Cached across frames: resolving the theme color and loading the icon
                // on every onChildDraw call would allocate per touch frame.
                private var bg: ColorDrawable? = null
                private var icon: Drawable? = null
                private var iconSize = 0
                private var iconMargin = 0

                private fun ensureDecor(context: Context) {
                    if (bg != null) return
                    // User's app color (custom theme color -> colorPrimary), like
                    // Spotify's green but themed.
                    val color = MaterialColors.getColor(context, colorPrimary, Color.BLACK)
                    bg = ColorDrawable(color)
                    icon = ContextCompat.getDrawable(context, R.drawable.ic_queue_music)?.let {
                        DrawableCompat.wrap(it).mutate().apply {
                            DrawableCompat.setTint(this, Color.WHITE)
                        }
                    }
                    iconSize = 24.dpToPx(context)
                    iconMargin = 24.dpToPx(context)
                }

                // Exactly-once haptic per swipe: fired when the gesture crosses the
                // trigger threshold (Spotify-style immediate confirmation), or on
                // release for a fast fling that never drew a past-threshold frame.
                private var hapticPending = true

                override fun onSelectedChanged(
                    viewHolder: RecyclerView.ViewHolder?, actionState: Int
                ) {
                    super.onSelectedChanged(viewHolder, actionState)
                    if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) hapticPending = true
                }

                private fun buzz(view: View) {
                    hapticPending = false
                    view.isHapticFeedbackEnabled = true
                    if (view.performHapticFeedback(HapticFeedbackConstantsCompat.CONFIRM)) return
                    // View-level feedback refused (detached row, OEM quirk): short
                    // direct vibration instead — unless the user cut system haptics.
                    // VIBRATE permission is already declared; one 35ms one-shot per
                    // swipe is negligible for battery.
                    val context = view.context
                    val systemHaptics = try {
                        Settings.System.getInt(
                            context.contentResolver,
                            Settings.System.HAPTIC_FEEDBACK_ENABLED, 1
                        ) == 1
                    } catch (_: Exception) {
                        true
                    }
                    if (!systemHaptics) return
                    try {
                        val vibrator = context.getSystemService(Vibrator::class.java)
                            ?: @Suppress("DEPRECATION")
                            (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
                            ?: return
                        if (!vibrator.hasVibrator()) return
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vibrator.vibrate(
                                VibrationEffect.createOneShot(
                                    35, VibrationEffect.DEFAULT_AMPLITUDE
                                )
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            vibrator.vibrate(35)
                        }
                    } catch (_: Exception) {
                    }
                }

                override fun getMovementFlags(
                    recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
                ): Int {
                    // Track media rows only — albums, artists, etc. never arm.
                    val isMediaRow = viewHolder is MediaViewHolder
                    val isTrack = (viewHolder as? MediaViewHolder)?.feed?.item is Track
                    if (!SwipeToQueue.isSwipeable(isMediaRow, isTrack)) return 0
                    return makeMovementFlags(0, ItemTouchHelper.END)
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    val feed = (viewHolder as? MediaViewHolder)?.feed
                    val track = feed?.item as? Track
                    if (track != null) {
                        if (hapticPending) buzz(viewHolder.itemView)
                        listener.onTrackSwiped(viewHolder.itemView, feed.extensionId, track)
                    }
                    // Snap-back: posted so ItemTouchHelper finishes its swipe cleanup
                    // first and the recover animation brings the row home instead of
                    // fighting the rebind. Target picked by SwipeToQueue.restoreTarget.
                    val recyclerView = viewHolder.itemView.parent as? RecyclerView
                    when (val target = SwipeToQueue.restoreTarget(
                        viewHolder.bindingAdapterPosition,
                        viewHolder.absoluteAdapterPosition,
                        viewHolder.layoutPosition
                    )) {
                        is SwipeToQueue.RestoreTarget.BindingAdapter -> {
                            val adapter = viewHolder.bindingAdapter
                            if (recyclerView != null) recyclerView.post {
                                adapter?.notifyItemChanged(target.position)
                            } else adapter?.notifyItemChanged(target.position)
                        }

                        is SwipeToQueue.RestoreTarget.RecyclerAdapter -> {
                            if (recyclerView != null) recyclerView.post {
                                recyclerView.adapter?.notifyItemChanged(target.position)
                            }
                        }

                        SwipeToQueue.RestoreTarget.None -> Unit
                    }
                }

                override fun onChildDraw(
                    c: Canvas,
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    dX: Float,
                    dY: Float,
                    actionState: Int,
                    isCurrentlyActive: Boolean
                ) {
                    if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE &&
                        dX > 0f && viewHolder is MediaViewHolder
                    ) {
                        ensureDecor(recyclerView.context)
                        val itemView = viewHolder.itemView
                        if (SwipeToQueue.shouldBuzz(
                                hapticPending, isCurrentlyActive, dX, itemView.width
                            )
                        ) buzz(itemView)
                        val right = SwipeToQueue.backgroundRight(
                            itemView.left, itemView.right, dX
                        )
                        // Plain null checks, not ?.let: let captures locals into a
                        // Function1 per touch frame; this runs on every frame of the
                        // gesture and must not allocate.
                        val b = bg
                        if (b != null) {
                            b.setBounds(itemView.left, itemView.top, right, itemView.bottom)
                            b.draw(c)
                        }
                        val ic = icon
                        if (ic != null) {
                            ic.alpha = SwipeToQueue.iconAlpha(dX, itemView.width)
                            val top = itemView.top + (itemView.height - iconSize) / 2
                            val left = itemView.left + iconMargin
                            ic.setBounds(left, top, left + iconSize, top + iconSize)
                            ic.draw(c)
                        }
                    }
                    super.onChildDraw(
                        c, recyclerView, viewHolder, dX, dY,
                        actionState, isCurrentlyActive
                    )
                }

                override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder) =
                    SwipeToQueue.SWIPE_THRESHOLD
                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder,
                ) = false
            }
        )
    }
}