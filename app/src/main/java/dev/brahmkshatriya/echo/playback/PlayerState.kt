package dev.brahmkshatriya.echo.playback

import androidx.media3.common.MediaItem
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.playback.MediaItemUtils.context
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicInteger

data class PlayerState(
    val current: MutableStateFlow<Current?> = MutableStateFlow(null),
    val radio: MutableStateFlow<Radio> = MutableStateFlow(Radio.Empty),
    val session: MutableStateFlow<Int> = MutableStateFlow(0)
) {

    val servers: MutableMap<String, Result<Streamable.Media.Server>> =
        Collections.synchronizedMap(LinkedHashMap())
    val serverChanged = MutableSharedFlow<Unit>(replay = 1)
    val activeLoadCount = AtomicInteger(0)

    // Single cold-start restore: the queue is read from disk ONCE at service creation
    // (PlayerService.onCreate) into this Deferred, and shared by every consumer — so no path runs its own
    // recoverPlaylist and races another. A null payload means the disk was empty.
    //
    // ⚠️ THE CONSUMERS, VERIFIED AT HEAD 2026-09-07 — THERE ARE EXACTLY THREE. Grep `restoreDeferred`
    // before trusting any other list, including this one:
    //   1. PlayerCallback.applyRestoreIfCold   — the app-open apply, at service create.
    //   2. PlayerCallback.onPlaybackResumption — a media button (isForPlayback = true).
    //   3. PlayerViewModel (getController)     — the UI's at-rest seed for `current` and the scrubber.
    // This comment previously read "applyRestoreIfCold, resume(), and onPlaybackResumption": it named a
    // `resume()` consumer that does not exist anywhere at HEAD, and OMITTED consumer 3 — which is the one
    // that turned out to bind the restore-snapshot release gate (see
    // PlayerService.scheduleRestoreSnapshotRelease), because it awaits AFTER the timeline is populated by
    // design. A stale list here is how a "four consumers" count went on being quoted; the count is three
    // and the names are above.
    var restoreDeferred: Deferred<RestoreData?>? = null

    // Cache of the last built restore, keyed on ResumptionUtils.queueGeneration. Survives PlayerService
    // death because this class is a Koin singleton, while the service (and its ExoPlayer) is not.
    //
    // WHY THIS EXISTS — it is NOT "restore once per process". Media3's MediaSessionService.onStartCommand
    // has a stale-start-intent branch ("Terminating service that was started by a stale start intent")
    // that STOPS an instance it has already created, and onCreate has fully run by then. So the service
    // can be created and destroyed in a tight loop, and every creation re-ran recoverPlaylist. On build
    // 1039 that was ~1050 creations in ~60s x 81 items = ~85,000 MediaItem builds, which took the heap
    // from ~20MB to 255MB and OOM'd the process — the loop caused the OOM, not the reverse.
    //
    // The APPLY still happens on every creation: a new service means a new ExoPlayer with an empty
    // timeline, so the queue must be re-applied or playback cannot resume. Only the disk read and the
    // item construction are skipped. That turns the loop from fatal into merely wasteful, which is the
    // failure mode we can observe (service_create_count + age_s_svc_first) instead of dying blind.
    //
    // Accepted staleness: build() bakes in download state, the show-background setting and the
    // quality-derived serverIndex. All three are user actions that cannot meaningfully occur inside a
    // sub-minute service-recreation storm, and any real queue write bumps the generation and evicts this.
    // NOTHING time-sensitive or credentialed is cached — stream URLs are resolved later, at playback.
    @Volatile
    var restoreCache: Pair<Long, RestoreData?>? = null

    // Set true SYNCHRONOUSLY when onPlaybackResumption is invoked (media-button / system resume) so the
    // app-open applyRestoreIfCold defers while the framework is about to apply the same queue — a second
    // setMediaItems tears the timeline down and re-prepares. Plain var, NOT AtomicBoolean: read and
    // written ONLY on the player's application looper — which is Main, because the player is built on Main
    // in PlayerService.onCreate (ExoPlayer.Builder defaults its looper to the current thread). It is set
    // in onPlaybackResumption's synchronous body (Media3 invokes that callback on that looper), read in
    // applyRestoreIfCold's withContext(Main), and cleared on Main by the timeline listener (success) and
    // a withContext(Main) in the non-return paths (failure). If the player is ever built off-Main this
    // invariant breaks and this must become atomic.
    var resumptionApplying = false

    // Cold-start re-seek latch. Media3 loses the restored startPositionMs when prepare() resolves the
    // deferred StreamableMediaSource's placeholder->real timeline to the default position (0) — traced in
    // ExoPlayerImplInternal.resolvePositionForPlaylistChange. Armed at the restore-apply sites
    // (applyRestoreIfCold / onPlaybackResumption) with the restored current item's (mediaId, savedPositionMs),
    // consumed at the FIRST STATE_READY in PlayerEventListener, which re-seeks now that the real timeline
    // exists. mediaId-guarded so a track the user plays during the restore's buffering window can't be seeked
    // to the stale position. Main-only (same application-looper invariant as resumptionApplying above).
    // ⚠⚠ [FIXED 2026-09-12] EPOCH-KEYED. THE MECHANISM, WHICH IS A CLASS AND NOT AN INSTANCE:
    // ARMED AT COLD START, CONSUMED BY AN UNRELATED EVENT. There are three writes to this latch in the
    // whole tree - two arms (applyRestoreIfCold, onPlaybackResumption) and ONE clear, inside
    // consumeRestoreSeek. NOTHING clears it on a user action, a queue replacement, or a timeout. So if the
    // restored queue never reaches STATE_READY - a cold start where the user does not press play, which is
    // exactly the reproduction - the latch simply waits, and THE FIRST STATE_READY OF THE SESSION BELONGS
    // TO WHATEVER THE USER TAPS NEXT. Reported symptom: tapping a track in search results after a cold
    // start starts it mid-song; without a cold start the same tap correctly restarts.
    // ⚠⚠ AND BOTH EXISTING GUARDS ARE SATISFIED BY THE THING THEY WERE MEANT TO EXCLUDE - THE
    // GUARD'S SUCCESS CONDITION IS THE BUG'S PRECONDITION. The mediaId check was written so "a track the
    // user plays during the restore's buffering window can't be seeked to the stale position": it rejects a
    // DIFFERENT track and permits the SAME one. Tapping the already-restored track satisfies it BY BEING
    // IDENTICAL. The RESTORE_SEEK_BELT_MS belt passes too, because a freshly tapped track sits at 0 - the
    // belt was meant to detect "the user already seeked", and a fresh start looks exactly like "untouched".
    // Fourth marker this session whose check looks like a guard and answers a different question; see also
    // DeezerTrackClient's TRACK_TOKEN self-heal gate, which reads as availability and answers provenance.
    // ⚠️ THE EPOCH IS NULLABLE, AND null IS A REAL STATE - "THIS ARM COULD NOT CAPTURE A VALID
    // EPOCH", NOT "UNKNOWN". Read the note at each arming site before changing either. A nullable field
    // rather than a magic number precisely so nobody later reads a sentinel as a real epoch.
    data class RestoreSeek(val mediaId: String, val positionMs: Long, val epoch: Long?)

    var pendingRestoreSeek: RestoreSeek? = null

    // Route-state gate for the BT/car/AA "phantom PLAY" fix. True when there is NO external audio route
    // AND Android Auto is not connected — i.e. we are "post-disconnect". Written by PlayerService from
    // three signals (AudioDeviceCallback add/remove, the CarConnection observer, and an onCreate
    // getDevices probe that seeds it for the cold-open case after the service was killed), and read in
    // PlayerCallback.onMediaButtonEvent to swallow a phantom hardware KEYCODE_MEDIA_PLAY that a head unit
    // emits around disconnect. @Volatile: all reads/writes happen on the application looper today (the
    // AudioDeviceCallback is registered with a Main handler, the observer runs on Main, onMediaButtonEvent
    // is invoked on the app looper), so it is defensive insurance rather than strictly required.
    @Volatile
    var isPostDisconnect: Boolean = false

    data class Current(
        val index: Int,
        val mediaItem: MediaItem,
        val isLoaded: Boolean,
        val isPlaying: Boolean,
        val isPlaceholder: Boolean = false,
    ) {

        val context by lazy { mediaItem.context }
        val track by lazy { mediaItem.track }
        fun isPlaying(id: String?): Boolean {
            val same = mediaItem.mediaId == id
                    || context?.id == id
                    || track.album?.id == id
                    || track.artists.any { it.id == id }
            return isPlaying && same
        }

        companion object {
            fun Current?.isPlaying(id: String?): Boolean = this?.isPlaying(id) ?: false
        }
    }

    sealed class Radio {
        data object Empty : Radio()
        data object Loading : Radio()
        data class Loaded(
            val clientId: String,
            val context: EchoMediaItem,
            val cont: String?,
            // ⚠⚠ WHICH LIVE QUEUE THIS STATION WAS BUILT FOR - ShufflePlayer.queueEpoch at the
            // moment it was PUBLISHED. A Loaded whose epoch no longer matches belongs to a queue that has
            // since been replaced, and PlayerRadio treats it as Empty rather than continuing it.
            //
            // ⚠️ THE DEFECT THIS CLOSES, OBSERVED 2026-09-11: nothing reset this flow when a new
            // queue was set, so a Loaded from a previous station survived into the next queue and
            // startRadio extended THAT station - appending the previous extension's tracks. In composition
            // with a second silent defect it produced a station that stopped with a completely empty log.
            //
            // ⚠️ THE PATTERN IS ALREADY IN THIS FILE, WHICH IS WHY IT LIVES ON THE MODEL RATHER
            // THAN BESIDE IT: restoreCache is `Pair<Long, RestoreData?>` keyed on
            // ResumptionUtils.queueGeneration for exactly the same reason - "which queue generation was
            // this built for". Carrying the key WITH the value is what makes it impossible to desync; a
            // parallel var next to the flow would have to be kept in step by hand at four publication
            // sites, which is the class of pairing this codebase keeps getting bitten by.
            // ⚠️ THOSE TWO COUNTERS ARE NOT THE SAME AND MUST NOT BE UNIFIED - see the note at
            // ResumptionUtils.queueGeneration.
            //
            // ⚠️ DEFAULT -1L, NOT 0L, DELIBERATELY. queueEpoch starts at 0, so a 0L default would
            // make an UNSTAMPED Loaded match a never-replaced queue. -1L can never match, so anything that
            // reaches a consumer unstamped reads as stale and is regenerated - FAIL-SAFE BY CONSTRUCTION
            // rather than by argument. The asymmetry justifies it: a spurious regeneration costs one radio
            // request and is invisible (see below), a missed one is the defect above.
            //
            // ⚠⚠ THIS ONLY WORKS BECAUSE state.radio HAS NO UI CONSUMER. Checked 2026-09-11:
            // there is no collect() on it anywhere - the only references are PlayerCallback (which writes
            // it and hands it to PlayerRadio) and PlayerService's PlayerRadio construction. Its consumers
            // ask one question, "is this station still the one to continue", which is an IDENTITY question
            // and exactly what an epoch answers. Had anything been rendering station state, an epoch could
            // not have supplied it and this would have needed a real reset instead. That is why the answer
            // here differs from what it would be for PlayerState.current.
            val epoch: Long = -1L,
            val tracks: suspend (String?) -> Page<Track>?
        ) : Radio()
    }
}

// The shared cold-start restore snapshot (see PlayerState.restoreDeferred). One IO read fills it; the
// app-open apply uses items raw, onPlaybackResumption maps them through withUnloaded for the framework.
data class RestoreData(
    val items: List<MediaItem>,
    val index: Int,
    val pos: Long,
    val shuffle: Boolean,
    val repeat: Int,
)
