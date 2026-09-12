package dev.brahmkshatriya.echo.ui.playlist.edit.search

import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.ui.feed.FeedClickListener

class EditPlaylistSearchClickListener(fragment: Fragment) : FeedClickListener(
    fragment, fragment.parentFragmentManager, R.id.playlistSearchContainer
) {

    private val parentFragment: Fragment
        get() = fragmentManager.fragments.first().requireParentFragment()

    private val viewModel by lazy {
        parentFragment.viewModels<EditPlaylistSearchViewModel>().value
    }

    // ⚠️ `ordered` is declared but UNUSED here, and must be: Kotlin requires an override to
    // repeat the full parameter list (without the default). This listener adds the tapped track to a
    // playlist and never reaches the base branch, so the ordered/discrete distinction is irrelevant
    // to it - but the parameter has to exist or this stops compiling.
    override fun onTracksClicked(
        view: View?, extensionId: String?, context: EchoMediaItem?, tracks: List<Track>?, pos: Int,
        ordered: Boolean
    ): Boolean {
        val track = tracks?.getOrNull(pos) ?: return notFoundSnack(R.string.track)
        viewModel.addTrack(track)
        return true
    }

    override fun onMediaClicked(
        view: View?, extensionId: String?, item: EchoMediaItem?, context: EchoMediaItem?
    ) = when (item) {
        is Track -> {
            viewModel.addTrack(item)
            true
        }

        else -> super.onMediaClicked(view, extensionId, item, context)
    }
}