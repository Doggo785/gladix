package dev.brahmkshatriya.echo.ui.feed

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.paging.LoadState
import dev.brahmkshatriya.echo.databinding.ItemShelfErrorBinding
import dev.brahmkshatriya.echo.databinding.ItemShelfLoginRequiredBinding
import dev.brahmkshatriya.echo.databinding.ItemShelfNotLoadingBinding
import dev.brahmkshatriya.echo.extensions.cache.Cached
import dev.brahmkshatriya.echo.extensions.exceptions.AppException
import dev.brahmkshatriya.echo.ui.common.ExceptionUtils.getFinalTitle
import dev.brahmkshatriya.echo.ui.common.ExceptionUtils.getMessage
import dev.brahmkshatriya.echo.ui.common.ExceptionUtils.openLoginException
import dev.brahmkshatriya.echo.ui.common.GridAdapter
import dev.brahmkshatriya.echo.ui.common.PagedSource
import dev.brahmkshatriya.echo.utils.ui.scrolling.ScrollAnimLoadStateAdapter
import dev.brahmkshatriya.echo.utils.ui.scrolling.ScrollAnimViewHolder

class FeedLoadingAdapter(
    val listener: Listener? = null,
    val loadingAdapter: (ViewGroup) -> ViewHolder,
) : ScrollAnimLoadStateAdapter<FeedLoadingAdapter.ViewHolder>(), GridAdapter {

    interface Listener {
        fun onRetry()
        fun onError(view: View, error: Throwable)
        fun onLoginRequired(view: View, error: AppException.LoginRequired)
    }

    abstract class ViewHolder(val view: View) : ScrollAnimViewHolder(view) {
        open fun bind(loadState: LoadState) {}
    }

    data class NotLoading(
        val inflater: LayoutInflater,
        val parent: ViewGroup,
        val listener: Listener?,
        val binding: ItemShelfNotLoadingBinding =
            ItemShelfNotLoadingBinding.inflate(inflater, parent, false)
    ) : ViewHolder(binding.root) {
        override fun bind(loadState: LoadState) {
            binding.retry.setOnClickListener {
                listener?.onRetry()
            }
        }
    }

    data class Error(
        val inflater: LayoutInflater,
        val parent: ViewGroup,
        val listener: Listener?,
        val binding: ItemShelfErrorBinding =
            ItemShelfErrorBinding.inflate(inflater, parent, false)
    ) : ViewHolder(binding.root) {
        override fun bind(loadState: LoadState) {
            loadState as LoadState.Error
            val throwable = loadState.error
            binding.error.run {
                transitionName = throwable.hashCode().toString()
                text = context.getFinalTitle(throwable)
            }
            binding.errorView.setOnClickListener {
                listener?.onError(binding.error, throwable)
            }
            binding.retry.setOnClickListener {
                listener?.onRetry()
            }
        }
    }

    data class LoginRequired(
        val inflater: LayoutInflater,
        val parent: ViewGroup,
        val listener: Listener?,
        val binding: ItemShelfLoginRequiredBinding
        = ItemShelfLoginRequiredBinding.inflate(inflater, parent, false)
    ) : ViewHolder(binding.root) {
        override fun bind(loadState: LoadState) {
            // ⚠⚠ THIS UNCHECKED CAST IS SAFE ONLY BECAUSE getStateViewType BELOW GATES ON THE SAME TYPE.
            // Holder type 3 is returned there ONLY for `is AppException.LoginRequired`, so nothing else can
            // reach this bind. THE GUARANTEE LIVES IN A DIFFERENT FUNCTION AND THERE IS NO LOCAL EVIDENCE OF
            // IT — widening that `when` without fixing this line manufactures a ClassCastException.
            //
            // ⚠️ THE CLASS IS LIVE, NOT THEORETICAL — ONE OF THESE HAS ALREADY FIRED. AndroidAutoCallback
            // carried `itemMap[id] as Playlist`, safe only while every caller happened to supply a
            // playlist-typed item; a sub-extension returning a mistyped item threw a ClassCastException in
            // the field (reported with extension_id = echo_combine). It was hardened in 3d159cbb = build 978
            // to `as? Playlist ?: return@getList emptyList()`, and the whole file was swept — there are now
            // ZERO unchecked casts in AndroidAutoCallback.
            // PATTERN: an unchecked cast whose safety lives in another function is a LATENT CRASH. It reads
            // as correct at both sites — the cast looks guarded, the gate looks total — and the coupling is
            // invisible from either one. The AA site proves the failure mode is reachable in practice, not
            // just in principle.
            // NOT FIXED HERE, DELIBERATELY (2026-09-10): unlike the AA case there is no field evidence for
            // this one, and `as?` here would need a fallback rendering for a state that currently cannot
            // occur. Recorded so the coupling is visible from the risky end, which is where the AA one was
            // missed until it fired.
            val error = (loadState as LoadState.Error).error
            val appError = error as AppException.LoginRequired
            binding.error.run {
                text = context.getFinalTitle(appError)
            }
            binding.login.transitionName = appError.hashCode().toString()
            binding.login.setOnClickListener {
                listener?.onLoginRequired(it, appError)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, loadState: LoadState): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (getStateViewType(loadState)) {
            0 -> loadingAdapter(parent)
            1 -> NotLoading(inflater, parent, listener)
            2 -> Error(inflater, parent, listener)
            3 -> LoginRequired(inflater, parent, listener)
            else -> throw IllegalStateException()
        }
    }

    override fun getStateViewType(loadState: LoadState): Int {
        return when (loadState) {
            is LoadState.Loading -> 0
            is LoadState.NotLoading -> 1
            is LoadState.Error -> {
                // ⚠️ THIS `when` IS LOAD-BEARING FOR A CAST IT DOES NOT MENTION. The LoginRequired arm is the
                // only thing that makes `error as AppException.LoginRequired` in the LoginRequired holder's
                // bind() safe. Widening this arm — or adding a type that also routes to 3 — crashes there,
                // not here. Read the note at that cast before touching this branch.
                when (loadState.error) {
                    is AppException.LoginRequired -> 3
                    is PagedSource.LoadingException -> 0
                    is Cached.NotFound -> 1
                    else -> 2
                }
            }
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, loadState: LoadState) {
        super.onBindViewHolder(holder, loadState)
        holder.bind(loadState)
    }

    override val adapter = this
    override fun getSpanSize(position: Int, width: Int, count: Int) = count

    companion object {
        fun Fragment.createListener(retry: () -> Unit) =
            object : Listener {
                override fun onRetry() {
                    retry()
                }

                override fun onError(view: View, error: Throwable) {
                    requireActivity().getMessage(error, view).action?.handler?.invoke()
                }

                override fun onLoginRequired(view: View, error: AppException.LoginRequired) {
                    requireActivity().openLoginException(error, view)
                }
            }
    }
}