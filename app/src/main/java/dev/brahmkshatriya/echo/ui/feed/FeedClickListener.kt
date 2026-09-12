package dev.brahmkshatriya.echo.ui.feed

import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.ui.common.FragmentUtils.openFragment
import dev.brahmkshatriya.echo.ui.common.SnackBarHandler.Companion.createSnack
import dev.brahmkshatriya.echo.ui.media.MediaFragment
import dev.brahmkshatriya.echo.ui.media.MediaFragment.Companion.getBundle
import dev.brahmkshatriya.echo.ui.media.more.MediaMoreBottomSheet
import dev.brahmkshatriya.echo.ui.player.PlayerViewModel
import dev.brahmkshatriya.echo.ui.playlist.edit.search.EditPlaylistSearchClickListener
import org.koin.androidx.viewmodel.ext.android.viewModel

open class FeedClickListener(
    private val fragment: Fragment,
    val fragmentManager: FragmentManager,
    private val containerId: Int,
    private val afterOpen: () -> Unit = {}
) {
    companion object {
        fun Fragment.getFeedListener(
            navFragment: Fragment = this,
            afterOpen: () -> Unit = {}
        ): FeedClickListener {
            val key = arguments?.getString("feedListener")
            return when (key) {
                "playlist_search" -> EditPlaylistSearchClickListener(this)
                else -> FeedClickListener(
                    this,
                    navFragment.parentFragmentManager,
                    navFragment.id,
                    afterOpen
                )
            }
        }
    }

    open fun onTabSelected(
        view: View?,
        feedId: String?,
        extensionId: String?,
        position: Int
    ): Boolean {
        val vm by fragment.viewModel<FeedViewModel>()
        val feedData = vm.feedDataMap[feedId] ?: return notFoundSnack(R.string.feed)
        feedData.selectTab(extensionId, position)
        return true
    }

    open fun onSortClicked(view: View?, feedId: String?): Boolean {
        val vm by fragment.viewModel<FeedViewModel>()
        val feedData = vm.feedDataMap[feedId] ?: return notFoundSnack(R.string.feed)
        // ⚠️ ARMS THE STATE, AND DELIBERATELY DOES NOT PERSIST IT. Opening the sheet is not a user
        // choice of sort, so there is nothing to save yet; adding a persistSortState() call here to
        // "match" the other feedSortState writers would write an empty sort on every sheet open.
        // ⚠️ THE ARMING ITSELF IS LOAD-BEARING — do not remove it as a no-op. A non-null feedSortState is
        // what puts the feed on getFeedSourceData's sort branch, and that branch is what assigns
        // loadedShelves, which FeedSortBottomSheet reads to build its chip list (getSorts). Without it the
        // sheet opens with no sorts to choose from.
        feedData.feedSortState.value = feedData.feedSortState.value ?: FeedSort.State()
        FeedSortBottomSheet.newInstance(feedId!!).show(fragment.childFragmentManager, null)
        return true
    }

    open fun onPlayClicked(
        view: View?,
        extensionId: String?,
        context: EchoMediaItem?,
        tracks: List<Track>?,
        shuffle: Boolean
    ): Boolean {
        if (extensionId == null) return notFoundSnack(R.string.extension)
        val vm by fragment.activityViewModels<PlayerViewModel>()
        if (tracks != null) {
            if (tracks.isEmpty()) return notFoundSnack(R.string.tracks)
            vm.setQueue(extensionId, tracks, 0, context)
            vm.setShuffle(shuffle, true)
            vm.setPlaying(true)
            return true
        }
        if (context == null) return notFoundSnack(R.string.item)
        if (shuffle) vm.shuffle(extensionId, context, true)
        else vm.play(extensionId, context, true)
        return true
    }

    open fun openFeed(
        view: View?,
        extensionId: String?,
        feedId: String?,
        title: String?,
        subtitle: String?,
        feed: Feed<Shelf>?
    ): Boolean {
        val fragment = fragmentManager.findFragmentById(containerId)
            ?: return notFoundSnack(R.string.view)
        val vm by fragment.activityViewModels<FeedFragment.VM>()
        vm.extensionId = extensionId ?: return notFoundSnack(R.string.extension)
        vm.feedId = feedId ?: return notFoundSnack(R.string.item)
        vm.feed = feed ?: return notFoundSnack(R.string.feed)
        fragment.openFragment<FeedFragment>(view, FeedFragment.getBundle(title.orEmpty(), subtitle))
        afterOpen()
        return true
    }

    fun notFoundSnack(id: Int): Boolean = with(fragment) {
        val notFound = getString(R.string.no_x_found, getString(id))
        createSnack(notFound)
        false
    }

    open fun onMediaClicked(
        view: View?, extensionId: String?, item: EchoMediaItem?, context: EchoMediaItem?
    ): Boolean {
        if (extensionId == null) return notFoundSnack(R.string.extension)
        if (item == null) return notFoundSnack(R.string.item)
        return when (item) {
            is Radio -> {
                val vm by fragment.activityViewModels<PlayerViewModel>()
                vm.play(extensionId, item, false)
                true
            }

            else -> {
                val fragment = fragmentManager.findFragmentById(containerId)
                    ?: return notFoundSnack(R.string.view)
                fragment.openFragment<MediaFragment>(view, getBundle(extensionId, item, false))
                afterOpen()
                true
            }
        }
    }

    open fun onMediaLongClicked(
        view: View?, extensionId: String?, item: EchoMediaItem?, context: EchoMediaItem?,
        tabId: String?, index: Int
    ): Boolean {
        if (extensionId == null) return notFoundSnack(R.string.extension)
        if (item == null) return notFoundSnack(R.string.item)
        MediaMoreBottomSheet.show(
            fragment, fragmentManager,
            containerId, extensionId, item, false,
            context = context, tabId = tabId, pos = index
        )
        return true
    }

    open fun onTracksClicked(
        view: View?,
        extensionId: String?,
        context: EchoMediaItem?,
        tracks: List<Track>?,
        pos: Int,
        // Whether these tracks came from an ORDERED collection. Defaulted true so any caller or override
        // that does not pass it keeps today's queueing behaviour, which is the safe direction.
        ordered: Boolean = true,
    ): Boolean {
        if (extensionId == null) return notFoundSnack(R.string.extension)
        if (tracks.isNullOrEmpty()) return notFoundSnack(R.string.tracks)

        // A single track tapped with no surrounding context IS a "radio" tile (Home "Mixes inspired by",
        // search): play the SEED first, then extend it into a radio. Route to playTrackRadio, which queues
        // the seed and APPENDS the generated radio server-side — mirroring phone's setQueue+auto-radio, but
        // explicit so it works on TV where auto-radio never fires (the seed used to loop). Videos are
        // excluded (a "<title> Radio" label doesn't fit video). Multi-track / in-context taps queue normally.
        // ⚠⚠ THIS PREDICATE MUST KEY ON `ordered` ALONE, PLUS THE VIDEO EXCLUSION. IT MUST NOT
        // REGAIN A `tracks.size == 1` TERM. THAT IS THE MOST IMPORTANT LINE IN THIS FILE.
        // It used to read `context == null && tracks.size == 1 && ...`, and search worked ONLY because
        // SearchFragment overrode this method to MANUFACTURE a 1-element list. Search results are
        // Shelf.Lists.Items, so with that override gone the list is the full run and a size term would
        // silently stop radioing on the one screen that already worked - no crash, no log, just a tap that
        // queues instead of starting a radio.
        // ⚠️ THAT IS THE 2026 LESSON IN A NEW COSTUME. The earlier reroute of this same branch
        // was believed TV-only, regressed phone, and the lesson recorded was "different code = new failure
        // modes; prove phone paths untouched, not just same-outcome". A size term left in by habit is
        // exactly that: same intent, different path, silent regression.
        //
        // ⚠️ `context` IS GONE FROM THE TEST DELIBERATELY, NOT DROPPED BY ACCIDENT. It is
        // FEED-WIDE, not per-shelf - toFeedType threads one value (FeedData's state.item, i.e. what the
        // FEED is about) into every row - so it cannot distinguish two shelves on the same screen. A
        // discrete "related tracks" row on an album page has context = that Album and should still radio.
        //
        // ⚠⚠ AND "JUST MOVE SearchFragment'S OVERRIDE INTO getFeedListener" IS WRONG, which is
        // worth stating because it is the obvious-looking fix. That override works by flattening the list
        // to one element, which DESTROYS THE SURROUNDING RUN - generalising it would turn every album and
        // playlist tap into a radio on that one track. The shelf kind is the real signal; list size was
        // only ever a proxy for it.
        val single = tracks.getOrNull(pos)?.takeIf {
            !ordered && it.type != Track.Type.Video && it.type != Track.Type.HorizontalVideo
        }
        val vm by fragment.activityViewModels<PlayerViewModel>()
        if (single != null) {
            vm.playTrackRadio(extensionId, single)
            return true
        }
        vm.setQueue(extensionId, tracks, pos, context)
        vm.setPlaying(true)
        return true
    }

    open fun onTrackSwiped(
        view: View?, extensionId: String?, track: Track?,
    ): Boolean {
        if (extensionId == null) return notFoundSnack(R.string.extension)
        if (track == null) return notFoundSnack(R.string.track)
        val vm by fragment.activityViewModels<PlayerViewModel>()
        vm.addToNext(extensionId, track, false)
        return true
    }
}