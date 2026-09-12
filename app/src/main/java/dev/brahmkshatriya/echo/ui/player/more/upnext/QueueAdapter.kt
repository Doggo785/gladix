package dev.brahmkshatriya.echo.ui.player.more.upnext

import android.annotation.SuppressLint
import android.graphics.drawable.Animatable
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.media3.common.MediaItem
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.databinding.ItemPlaylistTrackBinding
import dev.brahmkshatriya.echo.playback.MediaItemUtils.isLoaded
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.ui.feed.viewholders.MediaViewHolder.Companion.subtitle
import dev.brahmkshatriya.echo.utils.image.ImageUtils.loadInto
import dev.brahmkshatriya.echo.utils.ui.AnimationUtils.applyTranslationYAnimation
import dev.brahmkshatriya.echo.utils.ui.UiUtils.marquee
import dev.brahmkshatriya.echo.utils.ui.scrolling.ScrollAnimViewHolder

class QueueAdapter(
    private val listener: Listener,
    private val inactive: Boolean = false
) : ListAdapter<Pair<Boolean?, MediaItem>, QueueAdapter.ViewHolder>(DiffCallback) {

    // ⚠⚠ THE QUEUE DRAG DEPENDS ON areItemsTheSame COMPARING mediaId. IT IS NOT SAFE BY
    // DEFAULT - IT IS SAFE BECAUSE OF THIS LINE, AND CHANGING IT WOULD BREAK THE DRAG SILENTLY FROM A LONG
    // WAY AWAY. QueueFragment.onMove reorders this adapter's list in place and submits it; DiffUtil can only
    // resolve that to a MOVE if items are identified by a stable key. Compare positions or whole objects
    // instead and the diff becomes remove+insert, the dragged row is recycled, and
    // ItemTouchHelper.onChildViewDetachedFromWindow (ItemTouchHelper.java:902-916, recyclerview 1.4.0)
    // calls select(null, ACTION_STATE_IDLE) - the drag ends mid-gesture with nothing logged.
    //
    // ⚠️ [CORRECTED 2026-09-12] A PROJECT NOTE CLAIMED areContentsTheSame IS EFFECTIVELY ALWAYS
    // FALSE - "MediaItem.equals() uses Bundle.equals() which is REFERENCE equality, so two separately-built
    // items with identical data always produce false, guaranteeing bind() on every queue replacement."
    // THAT DOES NOT HOLD FOR media3 1.11.0. MediaMetadata.equals compares extras for NULL-NESS ONLY -
    // its final term is `((extras == null) == (that.extras == null))` - and the field's own doc
    // (MediaMetadata.java:1180) states "the contents of these extras are not considered in the equals and
    // hashCode". Bundle.equals is never reached. So contents compare by VALUE: title, artist, artwork uri,
    // ratings and the rest.
    // ⚠️ AND FOR THE DRAG IT IS MOOT EITHER WAY, WHICH IS THE STRONGER POINT: onMove reorders the
    // SAME INSTANCES rather than rebuilding them, so every areContentsTheSame call compares a value to
    // itself. One move, zero rebinds, whatever equals does. A submit carrying freshly-built items (from
    // queueFlow) is the case that rebinds - and those are gated out during a drag by isDragging.
    //
    // ⚠️ UNVERIFIED: DUPLICATE mediaIds. A radio top-up can re-add a track already queued, giving
    // two upcoming rows that are genuinely interchangeable to DiffUtil. For a single-element move the diff
    // should still resolve to one move, because the rest of the list is unchanged and the moved element is
    // matched by position-within-equal-keys. Not proven. The capture is where to watch for it: a duplicate
    // mishandled would show as `QUEUEDRAG clearView` firing mid-drag, the same signature as a detach.
    object DiffCallback : DiffUtil.ItemCallback<Pair<Boolean?, MediaItem>>() {
        override fun areItemsTheSame(
            oldItem: Pair<Boolean?, MediaItem>,
            newItem: Pair<Boolean?, MediaItem>
        ) = oldItem.second.mediaId == newItem.second.mediaId

        override fun areContentsTheSame(
            oldItem: Pair<Boolean?, MediaItem>,
            newItem: Pair<Boolean?, MediaItem>
        ) = oldItem == newItem

    }

    open class Listener {
        open fun onItemClicked(position: Int) {}
        open fun onItemClosedClicked(position: Int) {}
        open fun onDragHandleTouched(viewHolder: RecyclerView.ViewHolder) {}
    }

    @SuppressLint("ClickableViewAccessibility")
    inner class ViewHolder(
        val binding: ItemPlaylistTrackBinding
    ) : ScrollAnimViewHolder(binding.root) {

        init {
            binding.playlistItemClose.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                listener.onItemClosedClicked(pos)
            }

            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                listener.onItemClicked(pos)
            }

            binding.playlistItemDrag.setOnTouchListener { _, event ->
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnTouchListener false
                if (event.actionMasked != MotionEvent.ACTION_DOWN) return@setOnTouchListener false
                listener.onDragHandleTouched(this)
                true
            }
        }

    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return ViewHolder(ItemPlaylistTrackBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.onBind(position)
        holder.itemView.applyTranslationYAnimation(scrollAmount)
    }

    private fun ViewHolder.onBind(position: Int) {
        val (current, item) = getItem(position)
        val isCurrent = current != null
        val isPlaying = current == true
        val track = item.track
        binding.bind(track)
        binding.isPlaying(isPlaying)
        binding.playlistItemClose.isVisible = !inactive
        binding.playlistItemDrag.isVisible = !inactive
        binding.playlistCurrentItem.isVisible = isCurrent
        binding.playlistProgressBar.isVisible = isCurrent && !item.isLoaded
        binding.playlistItem.alpha = if (inactive) 0.5f else 1f
    }

    private var scrollAmount: Int = 0
    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            scrollAmount = dy
        }
    }

    var recyclerView: RecyclerView? = null
    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = recyclerView
        recyclerView.addOnScrollListener(scrollListener)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recyclerView.removeOnScrollListener(scrollListener)
        this.recyclerView = null
    }

    companion object {
        fun ItemPlaylistTrackBinding.bind(track: Track) {
            playlistItemTitle.run {
                text = track.title
                marquee()
            }

            track.cover.loadInto(playlistItemImageView, R.drawable.art_music)
            val subtitle = track.subtitle(root.context)
            playlistItemAuthor.run {
                isVisible = !subtitle.isNullOrEmpty()
                text = subtitle
                marquee()
            }
        }

        fun ItemPlaylistTrackBinding.isPlaying(isPlaying: Boolean) {
            playlistItemNowPlaying.isVisible = isPlaying
            (playlistItemNowPlaying.drawable as Animatable).start()
        }
    }
}
