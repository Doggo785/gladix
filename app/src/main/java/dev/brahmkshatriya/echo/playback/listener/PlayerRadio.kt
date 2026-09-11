package dev.brahmkshatriya.echo.playback.listener

import android.content.SharedPreferences
import android.util.Log
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

        // ⚠⚠ MULTI-SEED RE-SEED. N=4, NOT the 8 that DeezerRadioClient uses - SAME PATTERN,
        // DIFFERENT COST CURVE, and copying the constant would have been cargo-culting it.
        // randomTracksFromSongs(parser, 8) draws 8 rows out of an album/playlist tracklist the extension is
        // ALREADY HOLDING, inside ONE extension call: the 8 costs essentially nothing. Here each seed is a
        // separate radio() + loadTracks() pair, i.e. its own NETWORK ROUND TRIP from the app.
        // (That sentence originally read "TWO NETWORK ROUND TRIPS PER SEED" - see the [CORRECTED] block
        // below: for a TRACK seed radio() turns out to be local, so it is one. The contrast that motivates
        // N=4 is unchanged - rows the extension already holds versus a network call per seed.)
        // WHY 4 (inference, no measured per-seed success rate): if each independent seed yields a live
        // station with probability p, N seeds give 1-(1-p)^N. At p~=0.5 that is 87% at N=3, 94% at N=4, 97%
        // at N=5 - the gain flattens while the request cost stays linear.
        // ⚠️ 4 IS ALSO WHERE THE TRANSPORT STOPS BEING FREE, WHICH IS THE HARD CEILING.
        // Traced end to end 2026-09-10; every link was checked because the CONCLUSION SURVIVED A WRONG
        // FIRST REASON and the corrected chain is what makes it checkable:
        //   1. DeezerApi has no Mutex, no Semaphore and no single-thread dispatcher - calls are plain
        //      withContext(Dispatchers.IO). Nothing serialises them at the app layer.
        //   2. ⚠️ [CORRECTED] IT BUILDS THREE OkHttpClients, NOT ONE (client / clientLog /
        //      clientNP), each with its own Dispatcher and therefore its own independent cap. An earlier
        //      version of this note assumed a single client, which would have made the cap below wrong.
        //      IT IS NOT WRONG, BUT ONLY BECAUSE THE THREE ARE CHOSEN BY PURPOSE, NOT ROUND-ROBINED:
        //      callApi does `if (np) clientNP else client` and DeezerRadio.mix/mixArtist/flow never pass
        //      np, so EVERY radio call lands on `client`. The seeds cannot be spread across the three.
        //   3. None of them sets a custom Dispatcher, so OkHttp's defaults hold: maxRequests=64 (never the
        //      binding constraint here) and maxRequestsPerHost=5.
        //   4. Those limits only apply to ASYNC calls, so this turns on enqueue-vs-execute:
        //      ContinuationCallback.await uses enqueue(), so the Dispatcher DOES gate these. Had it
        //      wrapped execute(), the per-host cap would not apply at all and this ceiling would be void.
        //   5. All radio calls go to one host (callApi builds www.deezer.com), so they contend.
        // CONCLUSION: N=4 concurrent seeds sit under the per-host cap, so latency is MAX of the seeds and
        // not SUM. AT N>=6 THE SIXTH QUEUES BEHIND THE FIRST FIVE.
        // ⚠️ AND THE 5 IS SHARED, NOT RESERVED FOR US. Any other gateway call in flight on
        // `client` (metadata loads for the queue, a feed refresh) spends from the same 5, so a fan-out of 4
        // takes most of the budget. The effect of exceeding it is QUEUEING, not failure, and the ~9 minutes
        // of prefetch headroom absorbs it - but "4 of 5" is the honest figure, not "4, comfortably under".
        // Anyone raising this past 5 must set an explicit OkHttp Dispatcher or accept partial serialisation.
        // ⚠️ [CORRECTED] ONE ROUND TRIP PER SEED, NOT TWO. An earlier version of this note said
        // each seed costs a radio() plus a loadTracks() round trip. For a TRACK seed radio() is PURELY
        // LOCAL - DeezerRadioClient.asTrackRadio just builds a Radio out of fields the Track already
        // carries - so the only network call is the page load (song.getSearchTrackMix). The cost estimate
        // that justified N=4 was therefore CONSERVATIVE, not optimistic; the ceiling is unaffected because
        // it is set by concurrency, not by the number of calls.
        private const val RESEED_SEEDS = 4

        // How far back down the queue to walk looking for artist-distinct seeds. Bounded so a long restored
        // queue is not scanned; generous enough to clear a post-fallback bridge (6 tracks) plus a little.
        private const val SEED_SCAN = 12

        // Cap on what one fan-out appends. A single healthy station page is ~100 tracks (measured), so three
        // extra stations uncapped would add ~300 at once - queue bloat for no benefit, since the RETAINED
        // station keeps supplying afterwards. 60 is under one normal page and still far more than the
        // RADIO_PREFETCH_THRESHOLD needs to stop re-triggering.
        private const val RESEED_MAX_APPEND = 60

        // The extras key and value that mark a station as SEED-DETERMINED. Deezer's convention, and the app
        // is not merely a reader of it - PlayerCallback.trackRadio WRITES exactly this pair when it builds
        // an explicit track station, so for that path the app owns both ends. Named constants rather than
        // inline literals because the gate and the writer must not drift apart silently; see the gate note
        // at reseedFanOut for what a missing key costs and why the miss is logged.
        private const val RADIO_KIND_EXTRA = "radio"
        private const val RADIO_KIND_TRACK = "track"

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
        // What play() appended and whether the station is spent. Returned rather than logged so the
        // re-seed escalation in loadPlaylist can gate on the SAME predicate play() uses internally -
        // see isThin.
        data class PlayResult(
            val appended: Int,
            val exhausted: Boolean,
            // The page load itself failed (extension threw, already reported). Distinct from "returned
            // nothing": a transient failure must NOT escalate, because play() has restored the prior
            // Loaded state for the next transition to retry and a fan-out would overwrite that.
            val failed: Boolean = false,
        )

        // ⚠⚠ THE SINGLE DEFINITION OF "THIS STATION GAVE US NOTHING USEFUL". Used by play() for the
        // Last.fm bridge and by loadPlaylist for the multi-seed escalation. Two call sites, one predicate,
        // deliberately - they must fire on the same condition or the two rescues disagree about whether a
        // station is dead.
        // Deliberately NOT "fewer than RADIO_PREFETCH_THRESHOLD": a healthy station legitimately appends
        // small pages WITH a continuation, and treating that as failure would fire both rescues constantly.
        fun isThin(r: PlayResult) =
            !r.failed && (r.appended == 0 || (r.appended < 2 && r.exhausted))

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

        // ⚠⚠ THE ONE PLACE THE QUEUE-TAIL DEDUP LIVES. Extracted from play() on 2026-09-10 when the
        // multi-seed re-seed became a SECOND caller that appends station tracks. Two copies of a
        // windowed dedup that must agree is precisely the drift this file has already been bitten by
        // once (see the [CORRECTED] note below on 'radio already has its own dedup'), so the second
        // caller got an extraction rather than a paste.
        // `entries` is clientId-to-track because the multi-seed caller merges tracks from SEVERAL
        // stations that may belong to DIFFERENT extensions under Unified - each track must keep the
        // id that will resolve it. play() passes the same clientId for every entry, which is the
        // degenerate case of the same rule.
        suspend fun appendDeduped(
            player: Player,
            downloadFlow: StateFlow<List<Downloader.Info>>,
            app: App,
            entries: List<Pair<String, Track>>,
            context: EchoMediaItem,
        ): Int = withContext(Dispatchers.Main) {
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
            val items = entries
                // add() returns false when the key is already present, so this also removes duplicates
                // WITHIN a single batch, not just against what is already queued.
                .filter { (_, t) -> t.dedupKey()?.let { existing.add(it) } ?: true }
                .map { (clientId, t) ->
                    MediaItemUtils.build(
                        app, downloadFlow.value, MediaState.Unloaded(clientId, t), context
                    )
                }
            player.addMediaItems(items)
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            items.size
        }
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
        ): PlayResult {
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
                return PlayResult(0, exhausted = false, failed = true)
            }

            stateFlow.value = if (tracks.continuation == null) PlayerState.Radio.Empty
            else loaded.copy(cont = tracks.continuation)

            val appended = appendDeduped(
                player, downloadFlow, app,
                tracks.data.map { loaded.clientId to it }, loaded.context
            )
            // Post-fallback total, which is what loadPlaylist gates the multi-seed escalation on. The
            // `appended` val above stays the PRE-fallback page count the Last.fm gate below asks about,
            // so a successful bridge raises the total and suppresses the fan-out without changing whether
            // the bridge itself fires.
            var totalAppended = appended
            run {
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
                // ⚠️ [MEASURED 2026-09-10] `thin` FIRES FROM FILTERING, NOT FROM A STINGY API -
                // AND THAT CLOSES A PARKED PROPOSAL. It had been suggested that api.mix might return too few
                // tracks for some seeds, which would have argued for a multi-seed RadioKind.TRACK inside
                // DeezerRadioClient. Hand-counted on device: Link Wray "Fire and Brimstone" and The Brooklyn
                // Bridge "Worst That Could Happen" BOTH returned ~100 tracks, with no LASTFM line logged.
                // THERE IS NO THIN CASE AT THE API. That proposal is dropped, not parked.
                // What remains true is that the seed strip can empty a HEALTHY page - see DeezerRadioClient's
                // TRACK branch on the two-album-id case - which is why this is measured post-dedup.
                // Pre-fallback counts on purpose: this asks whether the STATION is spent, which is what
                // the Last.fm bridge exists to answer. The value play() RETURNS is post-fallback, so a
                // successful bridge stops the multi-seed escalation from also firing - see loadPlaylist.
                val thin = isThin(PlayResult(appended, tracks.continuation == null))
                val radioId = loaded.context.id
                if (thin && extension != null && fallbackTriedForRadioId != radioId) {
                    val seed = withContext(Dispatchers.Main) { player.currentMediaItem?.track }
                    if (seed != null) {
                        // ⚠⚠ THE LATCH BURNS HERE - AFTER THE SEED READ, BEFORE THE LOOKUP. BOTH HALVES
                        // OF THAT POSITION ARE A FIX FOR A DIFFERENT FAILURE; DO NOT MOVE IT EITHER WAY.
                        //
                        // IT USED TO BE SET ABOVE, BEFORE the seed read, AND THAT WAS A REAL BUG (found
                        // 2026-09-10). When PlayerCallback.radio served a track station it cleared the
                        // queue without queueing a seed, so currentMediaItem was null, `seed != null`
                        // failed - and the latch had ALREADY been set for a lookup that never happened.
                        // Every later topUpQueue on that station was then locked out. The station was
                        // silent AND permanently un-retryable, and because the skip has no branch and no
                        // log, nothing recorded it. (The null-seed cause is fixed at
                        // PlayerViewModel.radio, but the latch was independently wrong and stays fixed
                        // here: any future path that reaches this with no current item must not burn it.)
                        //
                        // AND NOT AFTER similarTracks EITHER. The point of the latch is ONE ATTEMPT PER
                        // STATION - counting attempts, not successes. Latching on the far side would let
                        // a lookup that throws, times out, or returns empty re-fire on every single
                        // topUpQueue for the rest of the context: an unbounded network retry loop driven
                        // by track transitions. Set before the call, so a thrown lookup is spent.
                        fallbackTriedForRadioId = radioId
                        val extra = RadioFallback.similarTracks(extension, seed)
                        totalAppended += extra.size
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
            return PlayResult(totalAppended, tracks.continuation == null)
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
            // ⚠⚠ ESCALATION GATE - ONE SEED FIRST, THEN THE OTHER THREE ONLY IF THAT FAILED.
            // The common case is unchanged and costs nothing extra: one seed, one station, no fan-out.
            // A re-seed that comes back thin is the ONLY thing that triggers the remaining seeds, which is
            // why this needs no mode flag and no new state - the condition is measured, not remembered.
            // It also catches a case neither "always multi-seed" nor "only after a Last.fm bridge" would:
            // an ORDINARY station whose re-seed happens to land on a dead track.
            // ⚠️ A KNOWN ORDERING, NOT A DECISION - recorded because it was DISCOVERED while
            // building this, not chosen. The Last.fm bridge
            // lives INSIDE play(), so it runs BEFORE this gate is even evaluated, and its appends count
            // toward the returned total - so a successful bridge suppresses the fan-out. That ordering is a
            // consequence of where the two rescues sit, not a judgement that Last.fm should go first;
            // multi-seed is the cheaper and more native of the two (3 radio calls vs up to 15 catalogue
            // searches) and would ideally be tried first. Reversing them means lifting the bridge out of
            // play(), which is a larger change than this one and was deliberately not taken.
            val result = play(player, downloadFlow, app, stateFlow, loaded, extension)
            if (isThin(result)) reseedFanOut(itemContext)
        }
    }

    // Seeds for the fan-out: walk BACKWARDS from the playhead taking one track per distinct artist.
    // ⚠⚠ ARTIST-DISTINCTNESS IS THE WHOLE POINT, NOT A TIDINESS RULE. Four tracks by the same
    // artist are four seeds that return the same neighbourhood, which collapses N back to 1 and buys
    // nothing for 3x the requests. What makes a seed set worth fanning out over is INDEPENDENCE.
    // That is also why this reads the queue TAIL rather than the station: after a Last.fm bridge the tail
    // holds six tracks by six different artists that Last.fm proposed and the catalogue confirmed it
    // carries - independently sourced, not drawn from the station that just died.
    // An empty artist name means "cannot compare" and the track is KEPT, matching dedupKey's rule: a false
    // merge silently costs a seed, a false split costs nothing.
    private suspend fun collectSeeds(max: Int): List<Pair<String, Track>> =
        withContext(Dispatchers.Main) {
            val out = ArrayList<Pair<String, Track>>()
            val artists = HashSet<String>()
            val from = player.currentMediaItemIndex
            val floor = (from - SEED_SCAN + 1).coerceAtLeast(0)
            var i = from
            while (i >= floor && out.size < max) {
                val mediaItem = runCatching { player.getMediaItemAt(i) }.getOrNull()
                i--
                val track = mediaItem?.track ?: continue
                val artist = track.artists.firstOrNull()?.name?.trim()?.lowercase().orEmpty()
                if (artist.isNotEmpty() && !artists.add(artist)) continue
                out.add(mediaItem.extensionId to track)
            }
            out
        }

    // ⚠⚠ FAN OUT ONCE, MERGE, RETAIN ONE STATION. Reached only from loadPlaylist's escalation
    // gate, i.e. only after a single-seed re-seed already came back thin.
    //
    // ⚠️ WHAT Radio.Loaded MEANS AFTERWARDS IS A DELIBERATE SIMPLIFICATION - READ THIS BEFORE
    // TRUSTING IT. The user is hearing tracks merged from up to four stations, and Loaded names exactly
    // ONE of them: the highest-yielding. The other stations' continuations are DISCARDED after their first
    // page. So the NEXT re-seed is single-seed roulette again - which is fine, because the escalation gate
    // catches it again if it comes back thin. The alternative (Loaded carrying a list, topUpQueue
    // round-robining continuations) changes PlayerState's shape and every consumer of it, for a benefit
    // that only shows up on a station that was already rescued once.
    // The merged tracks are stamped with the RETAINED station's context, so the "Playing from ..." header
    // follows the station that will actually continue. It will change once, at the fan-out.
    // ⚠⚠ THE FAN-OUT ONLY RUNS FOR SEED-DETERMINED STATIONS. THE GATE IS NOT A PREFERENCE,
    // IT IS A CORRECTNESS CONDITION - fanning out over a context-determined station is definitionally a
    // no-op that costs three extra round trips.
    //     gate:  itemContext == null || itemContext.extras["radio"] == "track"
    //
    // THE STRUCTURAL REASON, AND IT IS DESIGN RATHER THAN ACCIDENT. In DeezerRadioClient.radio's Track
    // branch the non-TRACK kinds IGNORE THE SEED TRACK ENTIRELY:
    //     RadioKind.ARTIST   -> Radio(id = context.id, ...)
    //     RadioKind.PLAYLIST -> context
    //     RadioKind.ALBUM    -> context
    // Four different seeds under an artist context therefore resolve to FOUR COPIES OF ONE STATION.
    // ⚠️ AND THE FORWARDING IS DELIBERATE, NOT AN OVERSIGHT TO BE FIXED: PLAYLIST and ALBUM were
    // CHANGED to forward the existing Radio object directly, specifically to preserve source_id,
    // include_seeds and the rest of the seed state across the whole radio session. So the collapse is that
    // branch working AS DESIGNED. Anyone tempted to "fix" the collapse by making those kinds seed-sensitive
    // would be undoing that, and would break seed-state continuity to gain a fan-out nobody asked for.
    //
    // THIRD INSTANCE OF TRACK BEING THE ODD KIND OUT, which is what makes this gate consistent with the
    // codebase rather than a new distinction invented here:
    //   1. The seed filter in loadTracks is RadioKind.TRACK-ONLY - and for ARTIST it could not work anyway,
    //      because it compares `it.id != radio.id` where radio.id is the ARTIST id, so it never matches a
    //      track id. (See the dedup note in play(), which was written when that gap surfaced as a loop.)
    //   2. Generation itself: TRACK and a null context consult the seed via asTrackRadio; every other kind
    //      forwards or rebuilds from the context.
    //   3. This gate.
    //
    // ⚠️ A REAL NARROWING, BY OMISSION NOT BY DECISION - RECORDED SO IT IS VISIBLE RATHER THAN
    // DISCOVERED. No extension other than Deezer writes extras["radio"], so a NON-DEEZER EXPLICIT TRACK
    // STATION NEVER FANS OUT. Those extensions get the fan-out only through the null-context clause, which
    // covers the bare-track and History seeds - the common path - but not a station started deliberately
    // from a track. Accepted knowingly: Deezer is the primary extension, the null clause covers the common
    // path for everyone, and the portable alternative (fan out, then discard stations whose Radio.id
    // matches each other's) pays three requests to learn what this test knows for free.
    //
    // ⚠⚠ THE LOG LINE BELOW IS MANDATORY, NOT DIAGNOSTIC GARNISH. extras["radio"] ALREADY HAS
    // ONE SILENT FAILURE ON RECORD: Radio.kind() defaults to FLOW when the key is missing or altered - it
    // does not error, it falls through to the user's personal Flow (getUserRadio), returning a different,
    // wrong-seeded, often tiny set. This gate makes a missing key ALSO disable the fan-out, i.e. a second
    // silent consequence for one missing key, in a second subsystem.
    // The POLARITY is opposite and that is the whole safety argument: kind()'s failure ACTS WRONGLY
    // (commission), this one DOES NOTHING EXTRA (omission - you simply get the single-seed behaviour that
    // predates the fan-out). But severity was never what made the first bug expensive - DETECTABILITY was,
    // and that argument does not transfer for free. The log line is what buys it: a missing key becomes
    // OBSERVABLE at the moment it changes behaviour here, even though it stays silent at kind().
    // DO NOT REMOVE IT AS NOISE. It is the only thing standing between this key and a second slow hunt.
    //
    // ⚠️ LABEL_ONLY_RADIO IS NOT THIS TEST, THOUGH IT WILL LOOK LIKE THE ANSWER TO THE NEXT
    // READER. It marks a placeholder context standing in for NO context
    // (MediaItemUtils.trackRadioPlaceholder, which by its own comment "only ever labels the header and
    // never alters which radio is generated"). That is PLACEHOLDER-VS-REAL; this gate needs
    // SEED-DETERMINED-VS-CONTEXT-DETERMINED, and the two do not partition the same way:
    //     bare-track / History seed      marked   -> stripped to null   -> seed-determined
    //     explicit station from trackRadio  UNMARKED (a real track-kind Radio) -> seed-determined
    //     album completed -> artist radio   unmarked                    -> context-determined
    // It is TRUE for one seed-determined case and FALSE for the other, so gating on it would exclude the
    // explicit track station - THE EXACT CASE THIS FAN-OUT WAS BUILT FOR. Wrong in the direction that
    // matters. What the marker does contribute is the null half for free: loadPlaylist strips it, so
    // `itemContext == null` is already a sound sufficient test. It cannot supply the track-kind half.
    //
    // WHY THE SEEDS THEMSELVES STILL PASS A NULL CONTEXT (below), given all of the above: that is what
    // makes them produce DISTINCT stations. Forwarding itemContext into each seed would preserve the
    // station's label and destroy the fan-out in the same stroke - see the collapse above. The gate is what
    // makes the null safe, by ensuring we only reach it when the context was not carrying the station's
    // identity in the first place.
    private suspend fun reseedFanOut(itemContext: EchoMediaItem?) {
        if (itemContext != null) {
            val kind = (itemContext as? Radio)?.extras?.get(RADIO_KIND_EXTRA)
            if (kind != RADIO_KIND_TRACK) {
                // Mandatory - see the gate note above. `kind` is reported verbatim so an ABSENT key is
                // distinguishable from a present-but-different one, which is the whole point: absent means
                // the key was dropped somewhere upstream and Radio.kind() is simultaneously treating this
                // station as FLOW, silently.
                val observed = when {
                    itemContext !is Radio -> "not-a-radio:${itemContext::class.simpleName}"
                    kind == null -> "<absent>"
                    else -> kind
                }
                Log.d("GladixRadio", "RESEED reason=not_seed_determined kind=$observed")
                return
            }
        }
        // Seed 1 is the currently playing track - loadPlaylist already tried it, that is what came back
        // thin. Only the remainder are new work.
        val seeds = collectSeeds(RESEED_SEEDS)
        val extra = seeds.drop(1)
        val started = System.currentTimeMillis()
        if (extra.isEmpty()) {
            Log.d("GladixRadio", "RESEED reason=no_distinct_seeds scanned=$SEED_SCAN")
            return
        }

        // ⚠⚠ Loading BEFORE THE FAN-OUT, NOT AFTER THE FIRST RESULT. play() has already left
        // stateFlow at Empty or Loaded by now, and topUpQueue's only re-entrancy guard is
        // `stateFlow.value is Loading`. Without this line a track transition during the fan-out re-enters
        // loadPlaylist and starts a SECOND concurrent fan-out against the same queue.
        val prior = stateFlow.value
        stateFlow.value = PlayerState.Radio.Loading

        val stations = coroutineScope {
            extra.map { (clientId, seed) ->
                async {
                    // ⚠️ PER-SEED ISOLATION: THREE OF FOUR SUCCEEDING MUST STILL APPEND. One seed
                    // whose extension is gone, or whose radio() throws, cannot take the fan-out down with
                    // it - that would make multi-seed LESS reliable than the single seed it replaces.
                    // CancellationException is rethrown, never swallowed: the scope dying must propagate.
                    // start() already routes extension errors through getOrThrow(throwFlow) and returns
                    // null, so this catch is a backstop, and it logs rather than emitting to throwFlow -
                    // a supplementary seed failing is not something to put a snackbar in front of the user
                    // for, but it must not be invisible either.
                    try {
                        val ext = extensionList.getExtension(clientId) ?: return@async null
                        val station = start(throwFlow, ext, seed, null) ?: return@async null
                        val page = station.tracks(null) ?: return@async null
                        station to page
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Log.d(
                            "GladixRadio",
                            "RESEED seed_failed client=$clientId err=${e.javaClass.simpleName}"
                        )
                        null
                    }
                }
            }.awaitAll()
        }.filterNotNull()

        if (stations.isEmpty()) {
            stateFlow.value = prior
            Log.d(
                "GladixRadio",
                "RESEED reason=all_seeds_failed seeds=${extra.size} " +
                    "ms=${System.currentTimeMillis() - started}"
            )
            return
        }

        // Retain the station that can actually CONTINUE, then the biggest page. A station with no
        // continuation is spent the moment its page is consumed, so continuation outranks any page size -
        // hence the constant, which is far above any real page (~100).
        val retained = stations.maxByOrNull { (_, page) ->
            (if (page.continuation != null) 1_000_000 else 0) + page.data.size
        }!!

        // ⚠⚠ CROSS-STATION DEDUP HAPPENS HERE, BEFORE appendDeduped's QUEUE-TAIL WINDOW.
        // Four seeds picked for artist-distinctness still land in overlapping neighbourhoods - six surf
        // instrumentals share plenty of neighbours - so the stations' pages duplicate EACH OTHER, not just
        // the queue. appendDeduped's window cannot see that: it compares against what is already queued.
        // Without this pass the merged pool is mostly repeats, the append count collapses, and the thin
        // predicate then misreads a fan-out that actually SUCCEEDED as another failure.
        // Round-robin rather than concatenation so the result is a blend, not four contiguous blocks of
        // one station each.
        val seen = HashSet<String>()
        val merged = ArrayList<Pair<String, Track>>()
        var depth = 0
        outer@ while (merged.size < RESEED_MAX_APPEND) {
            var any = false
            for ((station, page) in stations) {
                val track = page.data.getOrNull(depth) ?: continue
                any = true
                if (track.dedupKey()?.let { seen.add(it) } != false) {
                    merged.add(station.clientId to track)
                    if (merged.size >= RESEED_MAX_APPEND) break@outer
                }
            }
            if (!any) break
            depth++
        }

        val appended = appendDeduped(player, downloadFlow, app, merged, retained.first.context)
        stateFlow.value =
            if (retained.second.continuation != null)
                retained.first.copy(cont = retained.second.continuation)
            else PlayerState.Radio.Empty

        // ⚠️ STATE THE NUMBERS, NOT THE SYMPTOM. stations<extra means seeds failed;
        // merged<<sum(pages) means the stations overlapped heavily; appended<merged means the queue tail
        // already held them. Each gap points at a different one of the risks this was built against.
        Log.d(
            "GladixRadio",
            "RESEED seeds=${extra.size} stations=${stations.size} merged=${merged.size} " +
                "appended=$appended cont=${retained.second.continuation != null} " +
                "ms=${System.currentTimeMillis() - started}"
        )
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
            // extension is explicitly not a goal here. Wiring it is one argument, the same as topUpQueue's Loaded branch.
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

