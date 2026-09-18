package dev.brahmkshatriya.echo.extensions.exceptions

import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.models.Metadata
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.coroutines.cancellation.CancellationException

private val COLLAPSIBLE_WHITESPACE = Regex("\\s+")

/**
 * Bounds an exception message that is about to be SHOWN or RECORDED.
 *
 * ⚠⚠ NOTHING BOUNDED THIS BEFORE, AND EXTENSION MESSAGES ARE ARBITRARY THIRD-PARTY TEXT. The
 * three caps that already existed all protect something else: TRACE_CHAR_CAP (ExceptionUtils) guards
 * the TRACE against TransactionTooLarge on onSaveInstanceState, and MAX_CAUSE_LEN / MAX_MSG_LEN /
 * MAX_EXT_NAME_LEN (PlayerEventListener) guard the Crashlytics `lastCauses` STRING. None touches the
 * message that reaches a snackbar or becomes a Crashlytics issue TITLE.
 * WHAT THAT COST: a YouTube Music request refused by Google's bot detection came back as a 403 whose
 * body is the whole "Sorry" HTML page, and Ktor's DefaultResponseValidation puts that page in the
 * exception message verbatim. It reached the user as two ellipsised lines of markup (Material's
 * snackbar TextView is maxLines=2, ellipsize=end - it was never a wall of text on screen) and, worse,
 * it became the CRASHLYTICS ISSUE TITLE in full.
 * SECOND INSTANCE OF AN OPEN CLASS: "extension APIs returning HTML on error are parsed as JSON and
 * throw a decoding exception the user cannot read". The Deezer half was fixed AT ITS PARSE SITE
 * (DeezerApi.requireJsonObject). This one cannot be: the parse site is in a third-party extension.
 * So this BOUNDS the damage; it does not fix the cause, and it is not meant to.
 *
 * ⚠️ IT IS A CAP, NOT A SCRUB, AND THE TWO DO NOT COME TOGETHER HERE. The presentation and
 * recording paths have NEVER been scrubbed: the July 2026 URL sanitiser covers only the `lastCauses`
 * string, and App.kt's recordException records the whole chain UNSCRUBBED. Truncating a message does
 * not redact anything inside the first 256 chars. If scrubbing is ever wanted it belongs at that
 * recordException call, not here - do not read this cap as having made that path safe.
 *
 * ⚠️ 256 IS BORROWED FROM MAX_MSG_LEN, AND THE NUMBER IS LOAD-BEARING RATHER THAN ROUND. That
 * constant's own note records why it is 256: Cached's wrong-item guard reports BOTH ids ("expected X,
 * got Y") at ~240 chars, and a smaller budget cut it mid-escape before ", got" was ever reached,
 * making it read as though only one id was logged. A fresh 200 here would have re-broken a message
 * somebody had already sized. Raise both together or neither.
 *
 * Whitespace is collapsed BEFORE truncating, so an HTML page's newlines and indentation cannot spend
 * the budget; the suffix states how much was cut, so a truncated message is never mistaken for a
 * complete one.
 */
const val MAX_MESSAGE_LEN = 256

fun String.capMessage(): String {
    val collapsed = replace(COLLAPSIBLE_WHITESPACE, " ").trim()
    if (collapsed.length <= MAX_MESSAGE_LEN) return collapsed
    return collapsed.take(MAX_MESSAGE_LEN) +
        "\u2026 [+${collapsed.length - MAX_MESSAGE_LEN} chars]"
}

// First non-null message walking down the cause chain, falling back to the class name if every
// message is null. Keeps a wrapper exception (null message) from masking its cause's real reason.
// ⚠️ THE CAP IS APPLIED TO THE RESULT, NOT INSIDE THE WALK. The walk must still visit every
// cause - a wrapper with a null message has to fall through to the one that has it - so capping here
// bounds what is returned without changing which cause is chosen.
private fun Throwable.deepestMessage(): String {
    var t: Throwable? = this
    while (t != null) {
        t.message?.let { return it.capMessage() }
        t = t.cause
    }
    return this::class.simpleName ?: "Unknown"
}

sealed class AppException : Exception() {

    abstract val extension: Metadata

    open class LoginRequired(
        override val extension: Metadata
    ) : AppException()

    class Unauthorized(
        override val extension: Metadata,
        val userId: String
    ) : LoginRequired(extension)

    class NotSupported(
        override val cause: Throwable,
        override val extension: Metadata,
        val operation: String
    ) : AppException() {
        override val message: String
            get() = "$operation is not supported in ${extension.name}"
    }

    class Other(
        override val cause: Throwable,
        override val extension: Metadata
    ) : AppException() {
        // Walk the cause chain for the first non-null message: a wrapper like ExceptionInInitializerError
        // has a null message while the real reason (e.g. "Expected URL scheme...") sits on its cause, so
        // the raw .message (used by crash logging) reads "null error in X" without this. The user-facing
        // snackbar already unwraps via ExceptionUtils.getFinalTitle; this aligns the logged message too.
        override val message: String
            get() = "${cause.deepestMessage()} error in ${extension.name}"
    }

    companion object {
        fun Throwable.toAppException(extension: Extension<*>) = toAppException(extension.metadata)
        fun Throwable.toAppException(extension: Metadata): AppException = when (this) {
            // Already wrapped (e.g. by a nested aggregator combine re-entering the wrap): return as-is
            // so a wrapping cycle collapses to ONE error instead of an unbounded "error in X ...
            // error in X" chain / 1MB message. Also preserves the real leaf extension's attribution.
            is AppException -> this
            // A withTimeout() timeout is a real, reportable failure, so keep wrapping it. Must be
            // checked before CancellationException (its supertype) below.
            is TimeoutCancellationException -> Other(this, extension)
            // Cooperative cancellation must never be reported as an error — propagate it.
            is CancellationException -> throw this
            is ClientException.Unauthorized -> Unauthorized(extension, userId)
            is ClientException.LoginRequired -> LoginRequired(extension)
            is ClientException.NotSupported -> NotSupported(this, extension, operation)
            else -> Other(this, extension)
        }
    }
}