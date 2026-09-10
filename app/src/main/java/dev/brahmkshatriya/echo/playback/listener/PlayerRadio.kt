package dev.brahmkshatriya.echo.playback.listener

import android.content.SharedPreferences
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Timeline
import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed.Companion.pagedDataOfFirst
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.download.Downloader
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.get
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getExtension
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getIf
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getOrThrow
import dev.brahmkshatriya.echo.extensions.MediaState
import dev.brahmkshatriya.echo.playback.MediaItemUtils
import dev.brahmkshatriya.echo.playback.MediaItemUtils.context
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.playback.PlayerState
import dev.brahmkshatriya.echo.utils.ui.UiUtils.isTv
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerRadio(
    private val app: App,
    private val scope: CoroutineScope,
    private val player: Player,
    private val throwFlow: MutableSharedFlow<Throwable>,
    private val stateFlow: MutableStateFlow<PlayerState.Radio>,
    private val extensionList: StateFlow<List<MusicExtension>>,
    private val downloadFlow: StateFlow<List<Downloader.Info>>
) : Player.Listener {

    companion object {
        const val AUTO_START_RADIO = "auto_start_radio"
        private const val RADIO_PREFETCH_THRESHOLD = 3

        // How far back down the queue the append dedup looks. Big enough to survive a rotation of the same
        // recording across several album ids; small enough that a long restored queue is not rescanned and
        // that a station revisiting a track much later is not treated as a loop. Not tuned against data —
        // if a rotation ever exceeds this, the symptom is the loop returning and this is the first suspect.
        private const val DEDUP_WINDOW = 24

        // ⚠⚠ THE SINGLE APP-SIDE DEFINITION. RadioFallback calls stripVersionSuffix below rather than
        // holding a copy; anything else app-side that needs it must do the same.
        //
        // WHY THIS IS NOT IN `common`, WHICH IS THE OBVIOUS HOME SINCE BOTH THE APP AND EVERY EXTENSION
        // DEPEND ON IT — judged 2026-09-09 and worth stating because "the app cannot depend on an extension
        // module" is TRUE BUT INCOMPLETE as a reason:
        //   • `common` IS THE EXTENSION ABI. Every symbol there is a permanent public contract kept by
        //     `-keep class dev.brahmkshatriya.echo.common.** { *; }` and anchored in verifyExtensionAbi.
        //     ADDING is much safer than changing — the R8 break was repackaging, not surface size — but it
        //     creates a NEW VERSION-SKEW HAZARD: an extension compiled against a `common` that has this
        //     function, running against an app whose `common` does not, throws the
        //     NoSuchMethodError/AbstractMethodError family. ExtensionUtils.getOrThrow already degrades that
        //     family SILENTLY as "outdated extension", so the failure would be invisible.
        //   • That is a real, if small, permanent cost. The thing being shared is a TWO-LINE REGEX. The
        //     trade does not clear the bar this project sets for that surface.
        // SO: ONE COPY PER MODULE BOUNDARY IS THE MAXIMUM DEFENSIBLE, and that is now the state — one here,
        // one in DeezerRadioClient. Three was one too many and the extra one was pure duplication.
        //
        // ⚠️ WHAT MAKES THE REMAINING PAIR DRIFT-RESISTANT, BEYOND THE MIRROR NOTES — because notes alone
        // have demonstrably not been enough in this repo: STRUCTURE, not prose. There is now exactly ONE
        // definition reachable from app code, so a future app-side consumer references it instead of
        // retyping (retyping is what produced the third copy in the first place). The cross-module pair is
        // only reconcilable by the notes, so it is kept to the SMALLEST possible surface — one regex, one
        // function, no options and no call-site variation — which is what makes a divergence obvious on
        // sight rather than something to reason about.
        private val VERSION_SUFFIX = Regex("""\s*\(.*\)\s*$""", RegexOption.IGNORE_CASE)

        fun stripVersionSuffix(s: String) = s.replace(VERSION_SUFFIX, "").trim()

        // Null when the track carries nothing comparable — caller keeps it. See the note at the call site.
        private fun Track.dedupKey(): String? {
            isrc?.trim()?.takeIf { it.isNotEmpty() }?.let { return "isrc:${it.uppercase()}" }
            val t = stripVersionSuffix(title)
            if (t.isEmpty()) return null
            val a = artists.firstOrNull()?.name?.trim().orEmpty()
            return "ta:${t.lowercase()}\u0000${a.lowercase()}"
        }
        suspend fun start(
            throwableFlow: MutableSharedFlow<Throwable>,
            extension: Extension<*>,
            item: EchoMediaItem,
            itemContext: EchoMediaItem?
        ): PlayerState.Radio.Loaded? {
            if (!item.isRadioSupported) return null
            return extension.getIf<RadioClient, PlayerState.Radio.Loaded?> {
                val radio = radio(item, itemContext)
                val tracks = loadTracks(radio).pagedDataOfFirst()
                PlayerState.Radio.Loaded(extension.id, radio, null) {
                    extension.get { tracks.loadPage(it) }.getOrThrow(throwableFlow)
                }
            }.getOrThrow(throwableFlow)
        }

        // Single-slot latch for the endless-queue fallback: at most ONE Last.fm lookup per station, ever.
        // A thin station would otherwise re-trigger on every topUpQueue. Keyed on the station's id
        // (loaded.context is the Radio item), single-valued so it cannot grow, and reset implicitly when a
        // different station becomes current. Companion-scoped because play() is a companion function with
        // five call sites and no instance to hang state on — deliberately NOT a field on
        // PlayerState.Radio.Loaded, which is a shared model.
        @Volatile
        private var fallbackTriedForRadioId: String? = null

        suspend fun play(
            player: Player,
            downloadFlow: StateFlow<List<Downloader.Info>>,
            app: App,
            stateFlow: MutableStateFlow<PlayerState.Radio>,
            loaded: PlayerState.Radio.Loaded,
            // The extension that owns the PLAYING item, for the endless-queue fallback's catalogue search.
            // Nullable with a default so a call site that cannot cheaply resolve it still compiles and
            // simply does not get the fallback — rather than forcing a resolution that could be wrong.
            extension: Extension<*>? = null
        ) {
            stateFlow.value = PlayerState.Radio.Loading
            val tracks = loaded.tracks(loaded.cont) ?: run {
                // Page load failed: extension.get caught the throwable and getOrThrow already reported it to
                // throwFlow, returning null. Restore the prior Loaded state instead of leaving stateFlow pinned
                // at Loading — a stuck Loading makes topUpQueue() and startRadio() no-op for the rest of the
                // context, stranding the WHOLE radio subsystem (not just this fetch). Restoring Loaded lets the
                // next track transition retry (correct for a transient failure); a genuinely exhausted radio
                // takes the continuation==null path below and becomes Empty instead. Generic by construction:
                // null is the universal failure signal here, so this covers any extension whose loadPage throws.
                stateFlow.value = loaded
                return
            }

            stateFlow.value = if (tracks.continuation == null) PlayerState.Radio.Empty
            else loaded.copy(cont = tracks.continuation)

            withContext(Dispatchers.Main) {
                // ⚠️ NEVER APPEND THE TRACK THAT IS PLAYING RIGHT NOW.
                //
                // WHY HERE AND NOT IN THE EXTENSION, which is the obvious instinct since that is where the
                // duplicate comes from: the invariant is a property of THE PLAYER forcing a first track, not
                // of any extension's radio algorithm. DeezerRadioClient does filter its seed out of results
                // — but only for RadioKind.TRACK (see its `if (kind == RadioKind.TRACK)` branch), because
                // TRACK used to be the only kind that ever had a track forced ahead of it. ARTIST and FLOW
                // are unfiltered, and ARTIST could not filter correctly anyway: its Radio carries
                // `id = <artist id>`, so an `it.id != radio.id` test compares track ids against an artist id
                // and never matches. PLAYLIST/ALBUM deliberately re-include their own seeds. Fixing it
                // per-kind in Deezer would need a new seed_id extra on two branches AND would leave every
                // other extension carrying the same bug — the filter belongs on the side that created the
                // situation.
                //
                // WHAT MADE THIS REACHABLE: History taps on a Radio context now force the tapped track as
                // the queue and let the stored station generate behind it (HistoryFragment's seed branch),
                // so a non-TRACK station is generated behind a forced first track for the first time. It
                // also covers the pre-existing case of an album auto-radio returning the album's last track.
                //
                // Applied to EVERY append, not just the first: a station appending the currently playing
                // track is wrong whenever it happens, and topUpQueue reaches this same function.
                //
                // ⚠️ THIS CAN EMPTY THE APPEND. If a page returns only the current track, addMediaItems gets
                // an empty list and the queue does not grow. That is a NO-OP, not a stall: stateFlow was
                // already advanced above, so a continuation leaves Loaded and the next topUpQueue loads the
                // NEXT page, while a null continuation leaves Empty and startRadio/topUpQueue call
                // loadPlaylist() to regenerate. The one behaviour change is that a station whose entire
                // remaining content is the current track now ENDS instead of replaying that track — which is
                // the correct outcome and the point of the filter.
                // ⚠⚠ DEDUP AGAINST THE QUEUE TAIL — THIS REPLACED AN id-ONLY CHECK AGAINST THE CURRENT
                // ITEM ON 2026-09-09, AND THE GAP IT CLOSES WAS IDENTIFIED TWO WEEKS BEFORE IT WAS FELT.
                //
                // THE DEFECT, STATED PLAINLY: THERE WAS NO DEDUP ON NON-TRACK STATION APPENDS, ON EITHER
                // SIDE. DeezerRadioClient dedups its seed for RadioKind.TRACK ONLY (title + artist, with a
                // version-suffix strip); ARTIST, PLAYLIST, ALBUM and FLOW hit its `else tracks` branch and
                // are dedup'd by nothing at all except the app's old id-only test against the SINGLE
                // current item. That is not enough for the data shape Deezer actually serves.
                //
                // MEASURED, BOTH DIRECTIONS, SAME RECORDING, SAME DAY (2026-09-09, "Underwater" by The
                // Frogmen — Deezer returns that one recording under TWO album ids, i.e. two track ids):
                //   RadioKind.TRACK      — the extension's title+artist filter removed BOTH copies, the
                //                          append was empty, and the queue ended in silence.
                //   any other kind       — nothing filtered but `it.id != currentId`, so the copy that was
                //                          not currently playing survived, was appended, became current,
                //                          and next round the OTHER copy survived. AN INDEFINITE
                //                          TWO-TRACK LOOP: same recording, forever, invisible to both the
                //                          buffering watchdog and StuckPlayerDetector because the player is
                //                          READY and genuinely progressing.
                // One data shape, opposite outcomes, decided entirely by which RadioKind was in play.
                //
                // ⚠️ [CORRECTED 2026-09-09] THE DECISION THAT LEFT THIS OPEN. A July pass considered exactly
                // this and declined it: "P3 — no dedup on appends — LEFT AS-IS (deliberate)… radio already
                // has its own dedup." THAT PREMISE IS TRUE FOR RadioKind.TRACK AND FALSE FOR EVERY OTHER
                // KIND. It is also disproved by this project's own later finding, recorded in August at the
                // note below: forcing a first track "created the FIRST CASE WHERE A NON-TRACK STATION IS
                // GENERATED BEHIND A FORCED SEED. Deezer's own seed filter is RadioKind.TRACK-only, and for
                // ARTIST it could not work anyway — it compares it.id != radio.id, where radio.id is the
                // ARTIST id" and so never matches a track id. The gap was identified, written down, and left
                // for two weeks; the loop above is that finding arriving as a symptom. Recorded here rather
                // than deleted because the reasoning is the instructive part: "the extension handles it" was
                // checked against ONE code path and generalised to five.
                //
                // WHY APP-SIDE IS THE RIGHT LAYER — unchanged from the note below, which already said it:
                // the invariant is a property of THE PLAYER forcing a first track, not of any extension's
                // radio algorithm. This is not a competing mechanism; it is the one that should have been
                // here.
                //
                // ⚠️ THE KEY IS ISRC FIRST, NORMALISED TITLE+ARTIST AS FALLBACK. An ISRC identifies a
                // RECORDING, which is exactly the equivalence wanted: the same master on two albums shares
                // one ISRC, while a remaster, a live take and a cover each get their own. Track.isrc exists
                // on the common model and DeezerParser populates it from data.ISRC. Where it is null or
                // blank the fallback mirrors the extension's own rule (strip a trailing parenthesised
                // suffix, compare case-insensitively) — weaker, because it WILL merge a re-recording or a
                // same-artist cover, and will NOT catch " - Live at X" since only "(…)" is stripped. That
                // asymmetry is deliberate: a false merge costs one skipped track, a false split costs an
                // infinite loop. A NULL KEY MEANS "CANNOT COMPARE" AND THE TRACK IS KEPT — never dropped.
                //
                // ⚠️ COMPARED AGAINST A WINDOW OF THE QUEUE, NOT AGAINST THE CURRENT ITEM. A pairwise check
                // against `current` cannot see a THREE-WAY rotation (A,B,C where each append is merely not
                // the one now playing), and this is the same recording served under N album ids — nothing
                // bounds N at two. The queue itself is the record of what was appended, so the window needs
                // no new state, survives process death, and is correct for every caller of play(), which
                // matters because this is a companion function with three call sites and no instance to
                // hold a set on. Bounded rather than whole-queue: a 5,000-item restored queue must not be
                // rescanned per append, and a station legitimately revisiting a recording an hour later is
                // not a loop.
                val existing = HashSet<String>()
                player.currentMediaItem?.track?.dedupKey()?.let { existing.add(it) }
                val windowStart = (player.mediaItemCount - DEDUP_WINDOW).coerceAtLeast(0)
                for (i in windowStart until player.mediaItemCount) {
                    runCatching { player.getMediaItemAt(i).track }.getOrNull()
                        ?.dedupKey()?.let { existing.add(it) }
                }
                val item = tracks.data
                    // add() returns false when the key is already present, so this also removes duplicates
                    // WITHIN a single page, not just against what is already queued.
                    .filter { t -> t.dedupKey()?.let { existing.add(it) } ?: true }
                    .map {
                        MediaItemUtils.build(
                            app,
                            downloadFlow.value,
                            MediaState.Unloaded(loaded.clientId, it),
                            loaded.context
                        )
                    }
                player.addMediaItems(item)
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                item.size
            }.let { appended ->
                // ── ENDLESS-QUEUE FALLBACK ── see RadioFallback for the whole rationale.
                //
                // TRIGGER: EMPTY OR THIN, MEASURED AFTER FILTERING. Both are "the extension has nothing
                // useful left" and both were observed on the same track on the same day:
                //   empty  — every candidate was a duplicate of what is already queued (the silence case)
                //   thin   — one track and NO CONTINUATION, i.e. a station that cannot sustain a queue
                // Deliberately NOT "fewer than RADIO_PREFETCH_THRESHOLD": a healthy station legitimately
                // appends small pages WITH a continuation, and treating that as failure would fire the
                // fallback constantly on working stations.
                // Measured post-dedup on purpose — the raw page in the loop case had two entries and looked
                // perfectly healthy; it was only after filtering that it was revealed as nothing.
                val thin = appended == 0 || (appended < 2 && tracks.continuation == null)
                val radioId = loaded.context.id
                if (thin && extension != null && fallbackTriedForRadioId != radioId) {
                    fallbackTriedForRadioId = radioId
                    val seed = withContext(Dispatchers.Main) { player.currentMediaItem?.track }
                    if (seed != null) {
                        val extra = RadioFallback.similarTracks(extension, seed)
                        if (extra.isNotEmpty()) withContext(Dispatchers.Main) {
                            // ⚠️ STAMP FROM THE SEARCH RESULT'S EXTENSION, NEVER THE STATION'S. This is the
                            // FOURTH extension_id failure of this family in one week — after the radio
                            // non-fatal (fixed by ResumptionUtils.restamped), loadTrack's missing stamp
                            // masked by the cache fallback, and the four UnifiedExtension tracker
                            // callbacks. See also Track.toSlim's extras stripping, which is the mechanism
                            // that keeps producing them.
                            // It matters MORE here than it looks: under Unified the station belongs to one
                            // sub-extension while the search fans out across all of them, so the matched
                            // track can legitimately come from a DIFFERENT sub-extension. Inheriting the
                            // station's id would then fail at loadStreamableMedia — one layer below and
                            // several seconds after the mistake, with no obvious link back to here.
                            // MediaState.Unloaded carries the clientId that resolution will use, so
                            // extension.id (the searched extension) is the correct value.
                            player.addMediaItems(
                                extra.map {
                                    MediaItemUtils.build(
                                        app, downloadFlow.value,
                                        MediaState.Unloaded(extension.id, it), loaded.context
                                    )
                                }
                            )
                            if (player.playbackState == Player.STATE_IDLE) player.prepare()
                        }
                    }
                }
            }
        }
    }

    private var radioQueueActive = false

    // TV drives radio continuation/start from the explicit hooks below (tvDriveRadio) instead of
    // startRadio()/topUpQueue(), which don't reliably fire on TV. Phone is untouched: isTv is false there,
    // so onTimelineChanged / onMediaItemTransition fall through to the exact same startRadio()/topUpQueue()
    // calls and onPlaybackStateChanged is a no-op. Detection matches the rest of the app (UiModeManager
    // UI_MODE_TYPE_TELEVISION || FEATURE_LEANBACK); the mode is fixed at runtime, so lazy eval is safe.
    private val isTv by lazy { app.context.isTv() }

    // Reset-safe idempotency guard for the TV driver — deliberately NOT the radioFlow==Loading state (which
    // can strand). compareAndSet is checked BEFORE the try, so every exit inside the try (early returns,
    // exceptions, coroutine cancellation) unwinds through finally and always releases it.
    private val tvInFlight = AtomicBoolean(false)

    private suspend fun loadPlaylist() {
        val mediaItem = withContext(Dispatchers.Main) { player.currentMediaItem } ?: return
        val extensionId = mediaItem.extensionId
        val item = mediaItem.track
        // A LABEL_ONLY_RADIO context is a display-only header stamp (bare-track / Radio-History seed), not
        // a real radio to generate — strip it so radio() receives null exactly as before, keeping the real
        // auto-radio identical and extension-agnostic. The MediaItem's context is untouched, so the header
        // still reads "Playing from <track> Radio".
        val itemContext = mediaItem.context?.takeUnless {
            it is Radio && it.extras[MediaItemUtils.LABEL_ONLY_RADIO] == "true"
        }
        val prior = stateFlow.value
        stateFlow.value = PlayerState.Radio.Loading
        val extension = extensionList.getExtension(extensionId) ?: run {
            // Extension gone after Loading was set — restore the prior state rather than strand at Loading.
            // Same principle as play() restoring its `loaded`: reset to whatever we were before Loading. Here
            // prior is always Empty (loadPlaylist is only reached from the Empty branches of topUpQueue/
            // startRadio), so this is Empty today, but capturing it keeps the intent explicit and robust.
            stateFlow.value = prior
            return
        }
        val loaded = start(throwFlow, extension, item, itemContext)
        stateFlow.value = loaded ?: PlayerState.Radio.Empty
        if (loaded != null) {
            radioQueueActive = true
            play(player, downloadFlow, app, stateFlow, loaded, extension)
        }
    }

    private suspend fun topUpQueue() {
        if (!radioQueueActive) return
        if (stateFlow.value is PlayerState.Radio.Loading) return
        val remaining = withContext(Dispatchers.Main) {
            // Remaining upcoming tracks = full count minus the current index; drives radio prefetch.
            val fullIndex = player.currentMediaItemIndex
            player.mediaItemCount - fullIndex - 1
        }
        if (remaining > RADIO_PREFETCH_THRESHOLD) return
        when (val state = stateFlow.value) {
            is PlayerState.Radio.Loaded ->
                play(player, downloadFlow, app, stateFlow, state, extensionList.getExtension(state.clientId))
            is PlayerState.Radio.Empty -> loadPlaylist()
            else -> {}
        }
    }

    private var autoStartRadio = app.settings.getBoolean(AUTO_START_RADIO, true)

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { pref, key ->
        if (key != AUTO_START_RADIO) return@OnSharedPreferenceChangeListener
        autoStartRadio = pref.getBoolean(AUTO_START_RADIO, true)
    }

    init {
        app.settings.registerOnSharedPreferenceChangeListener(listener)
        scope.coroutineContext[Job]?.invokeOnCompletion {
            app.settings.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    private suspend fun startRadio() {
        if (!autoStartRadio) return
        val shouldNotStart = withContext(Dispatchers.Main) {
            player.run {
                currentMediaItem == null || repeatMode != REPEAT_MODE_OFF || hasNextMediaItem()
            }
        }
        if (shouldNotStart) return
        when (val state = stateFlow.value) {
            is PlayerState.Radio.Loading -> {}
            is PlayerState.Radio.Empty -> loadPlaylist()
            // No `extension` argument, so the endless-queue fallback does NOT run on TV. Deliberate for
            // this pass: TV owns its own end-of-queue path (tvDriveRadio) and reach beyond the motivating
            // extension is explicitly not a goal here. Wiring it is one argument, the same as line 335.
            is PlayerState.Radio.Loaded -> play(player, downloadFlow, app, stateFlow, state)
        }
    }

    // TV-only radio driver (see isTv). Mirrors what auto-radio should do, driven from the listener callbacks
    // that DO fire on TV: (1) extend an active radio when running low — like topUpQueue, but gated on the
    // current item being a Radio rather than radioQueueActive, so explicitly-started radios (context menu,
    // card/search trackRadio) extend too, and NOT gated on autoStartRadio (matching topUpQueue); (2) start a
    // radio at the end of ANY queue — album/playlist/track — like startRadio, honoring autoStartRadio.
    // Reuses the unmodified loadPlaylist(), which derives itemContext from the current track exactly as on
    // phone, so the generated radio is identical. tvInFlight (not the radioFlow state) serializes overlapping
    // transition / STATE_ENDED calls so a boundary never double-appends.
    private suspend fun tvDriveRadio(atEnd: Boolean) {
        if (!tvInFlight.compareAndSet(false, true)) return
        try {
            val info = withContext(Dispatchers.Main) {
                val current = player.currentMediaItem ?: return@withContext null
                Triple(
                    current.context,
                    player.mediaItemCount - player.currentMediaItemIndex - 1,
                    player.repeatMode != REPEAT_MODE_OFF
                )
            } ?: return
            val (ctx, remaining, repeating) = info
            if (repeating) return
            val runningLow = ctx is Radio && remaining <= RADIO_PREFETCH_THRESHOLD
            val startAtEnd = (atEnd || remaining <= 0) && autoStartRadio
            if (!runningLow && !startAtEnd) return
            loadPlaylist()
            // If we appended because the queue had fully ENDED (the STATE_ENDED belt fired before a
            // running-low transition could pre-append), playback is parked at the end — advance into the
            // freshly appended radio and resume. In the normal case the append happened while the last track
            // was still playing, so the player isn't ENDED here and this is a no-op.
            if (atEnd) withContext(Dispatchers.Main) {
                if (player.playbackState == Player.STATE_ENDED && player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.play()
                }
            }
        } finally {
            tvInFlight.set(false)
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (isTv) return // TV: radio is driven by onMediaItemTransition + onPlaybackStateChanged instead
        scope.launch { startRadio() }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (player.mediaItemCount == 0) {
            stateFlow.value = PlayerState.Radio.Empty
            radioQueueActive = false
        }
        if (isTv) {
            scope.launch { tvDriveRadio(atEnd = false) }
            return
        }
        scope.launch { startRadio() }
        scope.launch { topUpQueue() }
    }

    // TV-only end-of-queue hook (phone never overrode this; default Player.Listener impl is empty). When the
    // queue fully ends without a running-low transition having pre-appended, start/continue the radio.
    override fun onPlaybackStateChanged(playbackState: Int) {
        if (isTv && playbackState == Player.STATE_ENDED) scope.launch { tvDriveRadio(atEnd = true) }
    }
}

