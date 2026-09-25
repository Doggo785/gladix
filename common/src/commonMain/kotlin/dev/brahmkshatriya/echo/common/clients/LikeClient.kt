package dev.brahmkshatriya.echo.common.clients

import dev.brahmkshatriya.echo.common.models.EchoMediaItem

/**
 * Used to like or unlike an item. with the [EchoMediaItem.isLikeable] set to true.
 */
interface LikeClient {
    /**
     * Likes or unlikes an item.
     *
     * @param item the item to like or unlike.
     * @param shouldLike whether the item should be liked or unliked.
     */
    suspend fun likeItem(item: EchoMediaItem, shouldLike: Boolean)

    /**
     * Checks if an item is liked.
     *
     * @param item the item to check.
     * @return true if the item is liked, false otherwise.
     */
    suspend fun isItemLiked(item: EchoMediaItem): Boolean

    /**
     * Drops any cached liked-content (e.g. a session snapshot of the user's likes) so
     * the next read re-fetches. Called on manual refresh. Best-effort: the default
     * implementation is a no-op for clients that hold no such cache.
     *
     * Called defensively (runCatching) by generic callers: extensions compiled against
     * an older :common without this method would otherwise throw AbstractMethodError
     * instead of degrading to no-op.
     */
    suspend fun bustLikedCache() {}
}