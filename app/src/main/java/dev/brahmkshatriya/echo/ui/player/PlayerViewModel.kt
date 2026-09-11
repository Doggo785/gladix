package dev.brahmkshatriya.echo.ui.player

import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.ThumbRating
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.session.MediaController
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.clients.LikeClient
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Message
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.download.Downloader
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getExtension
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.isClient
import dev.brahmkshatriya.echo.extensions.MediaState
import dev.brahmkshatriya.echo.playback.MediaItemUtils
import dev.brahmkshatriya.echo.playback.MediaItemUtils.serverWithDownloads
import dev.brahmkshatriya.echo.playback.MediaItemUtils.sourceIndex
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.playback.PlayerCommands.addToNextCommand
import dev.brahmkshatriya.echo.playback.PlayerCommands.addToQueueCommand
import dev.brahmkshatriya.echo.playback.PlayerCommands.backfillCommand
import dev.brahmkshatriya.echo.playback.PlayerCommands.playCommand
import dev.brahmkshatriya.echo.playback.PlayerCommands.radioCommand
import dev.brahmkshatriya.echo.playback.PlayerCommands.trackRadioCommand
import dev.brahmkshatriya.echo.playback.PlayerCommands.seekToFullCommand
import dev.brahmkshatriya.echo.playback.PlayerCommands.sleepTimer
import dev.brahmkshatriya.echo.playback.PlayerCommands.syncShuffleFlagCommand
import dev.brahmkshatriya.echo.playback.PlayerService.Companion.getController
import dev.brahmkshatriya.echo.playback.PlayerState
import dev.brahmkshatriya.echo.utils.ContextUtils.listenFuture
import dev.brahmkshatriya.echo.utils.Serializer.putSerialized
import kotlinx.coroutines.Dispatchers
import dev.brahmkshatriya.echo.history.HistoryRepository
import dev.brahmkshatriya.echo.history.db.HistoryEntity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

@OptIn(UnstableApi::class)
class PlayerViewModel(
    val app: App,
    val playerState: PlayerState,
    val settings: SharedPreferences,
    val cache: SimpleCache,
    val extensions: ExtensionLoader,
    val historyRepository: HistoryRepository,
    downloader: Downloader,
    fullQueueFlow: MutableStateFlow<List<MediaItem>>,
) : ViewModel() {
    private val downloadFlow = downloader.flow

    val browser = MutableStateFlow<MediaController?>(null)

    var queue: List<MediaItem> = emptyList()

    // ⚠️ replay = 1 WAS TRIED HERE AND REVERTED (2026-08-28). Do not re-apply it without reading this.
    // It was added to fix the dropped-emission problem described below — a problem found while tracing
    // something else, never observed by the user, and cosmetic when it does occur. The build carrying it
    // hung on a cold start: restored queue, session state=NONE, position 0, spinner, nothing logged for
    // 19 minutes. It could not be cheaply ruled out as the cause, so it went rather than being carried.
    // Why it is not obviously safe, despite both consumers being pure UI: replay changes whether an
    // emission made BEFORE PlayerFragment subscribes is delivered at subscribe time. Whether one exists is
    // a RACE between this ViewModel's restore block emitting (below) and onViewCreated subscribing, so the
    // change is intermittent by construction and shows up exactly when cold-start disk I/O is slow — the
    // same timing window every cold-start bug in this project has lived in. An earlier analysis called it
    // "deterministic on cold start, therefore ruled out"; that was wrong.
    // To clear it you need either a cold-start reproduction on replay=0 (proving it wasn't the cause) or
    // enough clean launches to bound an unknown failure rate. Neither is cheap. The dropped emission is
    // not worth that; drive new work from the `current` collector instead.
    //
    // The problem it was addressing, which is real and still present:
    // This flow does not deliver while the screen is off: it is a
    // zero-buffer MutableSharedFlow, and BOTH its collectors go through ContextUtils.observe ->
    // flowWithLifecycle(lifecycle), whose default minActiveState is STARTED. A stopped Activity has NO
    // subscriber, and a MutableSharedFlow with no subscriber and no replay drops the emission outright —
    // gone for good, not deferred. Measured 2026-08-24 across eight consecutive screen-off auto-advances:
    // subscriptionCount was 0 at every emit and neither collector ran.
    //
    // The two collectors, and they are NOT equivalent:
    //   PlayerFragment.configurePlayerControls -> submit(), the full-screen ViewPager pages
    //   QueueFragment.onViewCreated             -> submit(), the Up Next list and scroll-to-current
    // Both are backed by a sibling `playerState.current` collector that rebuilds from `queue`, but by
    // different mechanisms, and the asymmetry is the whole point:
    //   QueueFragment's sibling (:120) is ALSO gated, so flowWithLifecycle re-subscribes at ON_START and
    //     `current` — a StateFlow — replays its latest value. It self-heals on every wake.
    //   PlayerFragment's sibling (:534) is a raw lifecycleScope.launch that subscribes ONCE for the life
    //     of the fragment. It never re-subscribes, so it never gets a replay. The ungated collector that
    //     rescues the album art is precisely the one with NO resume-time recovery.
    //
    // The case that made this real rather than theoretical: a queue mutation with a STATIONARY current —
    // a radio top-up while the screen is off. PlayerState.Current is a data class and StateFlow conflates
    // equal values, so `current` does not emit; only this flow does. Before replay=1 the appended tracks
    // were missing as ViewPager pages until the next track change, while QueueFragment showed them
    // correctly. That divergence is the signature of this bug if it ever returns.
    //
    // Why replay is safe HERE specifically: the flow carries Unit, so a replayed emission can never
    // deliver stale data — it only says "re-read", and submit() reads `queue` and `current` together at
    // call time. A flow carrying the queue itself would not have that property.
    //
    // `queue` itself IS kept current while stopped (the collector that writes it lives in viewModelScope
    // and is not lifecycle-gated), so the damage was always confined to the SIGNAL, not the data.
    val queueFlow = MutableSharedFlow<Unit>()

    init {
        // The cold-start current is seeded ONCE, in the getController callback below, from the shared
        // restore read (restoreDeferred) with a history fallback — NOT here. The old eager history
        // placeholder that used to live in this init was the one current source the wrong-track repair never
        // reconciled (history-latest = last COMPLETED track, not the restored CURRENT_ID), which is what made
        // the mini bar render a stale/consumed track while the queue-validated full player showed the real
        // one. See the seed block in getController for the single-writer replacement.
        viewModelScope.launch {
            fullQueueFlow.collect { items ->
                val showingPlaceholder = playerState.current.value?.isPlaceholder == true && items.isEmpty()
                if (items.isNotEmpty() || !showingPlaceholder) {
                    queue = items
                    queueFlow.emit(Unit)
                }
            }
        }
    }

    // ⚠⚠ EVERY SessionResult FROM HERE IS DISCARDED - AND THE FIX IS NOT HERE. `block` returns
    // Unit and the ListenableFuture that sendCustomCommand hands back is never awaited or inspected, so a
    // handler that fails is INVISIBLE BY CONSTRUCTION - not unreported by oversight, but unreportable
    // through this call shape. Eleven sendCustomCommand sites dispatch through this function.
    //
    // ⚠️ [CORRECTED 2026-09-10] THIS DID NOT CAUSE THE "a message, then nothing" REPORT, THOUGH
    // THIS NOTE ASSERTED THAT IT DID. The claim was: PlayerViewModel.radio emits its snackbar before
    // dispatching, so the user gets the optimistic half and never the failure half. Re-derived against
    // source: on the Frogmen station start() SUCCEEDED (asTrackRadio is local construction off fields the
    // Track already carries, and cannot fail), play() appended zero after DeezerRadioClient's TRACK filter
    // stripped both copies of the recording, and radio() returned RESULT_SUCCESS. NO return@future error
    // EVER RAN. The silence was an empty append treated as success plus play() no-opping on an emptied
    // queue, fixed by the seed routing at PlayerViewModel.radio.
    // Kept rather than deleted because of HOW it got believed: a plausible, real, but UNEXERCISED mechanism
    // sitting next to a real symptom was written down as the cause while the actual bug was still being
    // traced, and was then quoted back as established before anyone re-derived it.
    //
    // ⚠️ [RETARGETED 2026-09-10] THE FIX IS IN THE HANDLERS, NOT HERE. This note previously
    // proposed having block return the SessionResult and emitting on a non-success code. Wrong layer:
    //   - SessionResult carries an ERROR CODE AND NO MESSAGE, so the most this side could ever say is
    //     "something failed" - strictly less than the handler already knows.
    //   - The service and the UI SHARE A PROCESS, and app.messageFlow already crosses that boundary, so
    //     the SessionResult round trip is redundant as a channel, not merely lossy.
    //   - The precedent is already in PlayerCallback: playItem emits app.messageFlow / throwableFlow FROM
    //     INSIDE THE HANDLER (its list_is_empty guard), and trackRadio's catch does the same for its
    //     generation phase. Neither touches SessionResult.
    // So withBrowser SHOULD NOT CHANGE. The work is one emit per silent guard, inside PlayerCallback.
    // ⚠️ SAME RULE, ONE SUBSYSTEM OVER: see CALLED vs LAUNCHED at App.exceptionHandler. THE CODE
    // THAT KNOWS ABOUT THE FAILURE REPORTS IT - there it is why a CALLED extension method is attributed and
    // a LAUNCHED coroutine is not; here it is why the handler reports and the dispatch helper cannot.
    // Cross-referenced deliberately, so it is a project-wide rule rather than the same judgement reinvented
    // at each site.
    //
    // ⚠⚠ PARKED 2026-09-10, AND THE REACHABILITY IS WHAT MAKES THAT A DECISION RATHER THAN A
    // BACKLOG ITEM: FIVE THEORETICAL GUARDS, ZERO EVER OBSERVED TO FIRE.
    //   radio()      - 3 silent: extId null (the VM always writes it), item undeserialisable
    //                  (putSerialized<EchoMediaItem> writes the discriminator correctly today), extension
    //                  unknown (needs an extension disabled mid-session).
    //   trackRadio() - 2 silent, and BOTH SIT BEFORE ANY PLAYBACK, so either firing means a tap that does
    //                  nothing with no log and no message - the shape already documented at playTrackRadio.
    // AND THE MOST REACHABLE FAILURE ON THIS PATH ALREADY REPORTS. radio()'s fourth return SPLITS:
    // PlayerRadio.start ends in getOrThrow(throwableFlow), so when DeezerRadioClient throws "No Radio"
    // (its Album/Playlist branches, reachable on an ordinary network failure or an empty tracklist) the
    // user IS told. It is silent only for !isRadioSupported or a non-RadioClient extension.
    // WHEN TAKEN, IT IS ONE PASS ACROSS EVERY HANDLER - eleven sendCustomCommand sites, ~eight command
    // handlers unaudited. Doing two of eight is the reason this is parked rather than half-done: a
    // PARTIALLY-reporting command surface is harder to reason about than a uniformly silent one, because
    // "no message" stops being evidence of anything.
    private fun withBrowser(block: suspend (MediaController) -> Unit) {
        viewModelScope.launch {
            val browser = browser.first { it != null }!!
            block(browser)
        }
    }

    private val context = app.context
    val controllerFutureRelease = getController(app) { player ->
        browser.value = player
        player.addListener(PlayerUiListener(player, this))
        // At-rest position seed (display half of the cold-start position fix). The controller reports
        // currentPosition=0 before play — the queue isn't applied yet and the masked position is never
        // surfaced to the controller (proven by GladixProgress). Seed progress from the in-memory
        // RestoreData.pos (shared PlayerState.restoreDeferred, not disk) so the scrubber shows the saved
        // position at rest; updateProgress holds it until a real tick or a user seek. Skipped if playback has
        // already started (currentPosition > 0). Independent of the service re-seek: that fixes PLAYBACK,
        // this fixes the at-rest DISPLAY, and they share no state.
        viewModelScope.launch {
            val data = playerState.restoreDeferred?.await()
            // SOLE initial-current writer (reuses the ONE restore read above — no second recoverPlaylist).
            // A restorable queue seeds the CURRENT_ID-aligned current: data.items[data.index] is the SAME
            // element applyRestoreIfCold applies and updateCurrentFlow later reflects, so the mini bar and the
            // full player cannot show different tracks. No restorable queue falls back to the last-played
            // history track (there is no CURRENT_ID to align to, and no queue to diverge from). The
            // current == null guard yields to updateCurrentFlow if the service already applied the queue;
            // both orders are correct. current is only ever REPLACED here, never nulled, so the bar cannot
            // flicker hidden — and updateCurrentFlow's later replacement carries the same mediaId.
            if (playerState.current.value == null) {
                if (data != null) {
                    val item = data.items.getOrNull(data.index) ?: data.items.firstOrNull()
                    if (item != null) {
                        playerState.current.value = PlayerState.Current(
                            index = data.index,
                            mediaItem = item,
                            isLoaded = false,
                            isPlaying = false,
                            isPlaceholder = true
                        )
                        queue = data.items
                        queueFlow.emit(Unit)
                    }
                } else {
                    val entity = historyRepository.getLatest().first()
                    val track = entity?.track
                    if (track != null) {
                        val mediaItem = MediaItemUtils.build(
                            app,
                            downloadFlow.value,
                            MediaState.Unloaded(entity.extensionId, track),
                            null
                        )
                        playerState.current.value = PlayerState.Current(
                            index = 0,
                            mediaItem = mediaItem,
                            isLoaded = false,
                            isPlaying = false,
                            isPlaceholder = true
                        )
                        queue = listOf(mediaItem)
                        queueFlow.emit(Unit)
                    }
                }
            }
            // At-rest position seed (display half of the cold-start position fix): only meaningful with a
            // restore, so it stays gated on data != null.
            if (data != null && data.pos > 0 && player.currentPosition <= 0L) {
                restoreSeedMs = data.pos
                progress.value = data.pos to 0L
            }
        }
        // No cold-start resume() here: PlayerService.onCreate is the sole app-open restorer. Sending
        // resumeCommand on connect raced onCreate's in-flight recoverPlaylist — both compareAndSet
        // userQueueSet, but onCreate only AFTER its slow disk read, so resume() could claim first and run
        // a second restore (two recoverPlaylist calls), or claim-then-bail on activeLoadCount leaving
        // nobody to restore (the bar-flash). KEEP_QUEUE is now honored inside onCreate. (resumeCommand is
        // still used by the widget/notification resume actions, which are explicit user intents.)
    }

    override fun onCleared() {
        super.onCleared()
        controllerFutureRelease()
    }

    // `position` is a FULL-queue index (the tap indexes fullQueueFlow). Seek by full index via a
    // custom command handled service-side by ShufflePlayer.seekToFullIndex, which seeks the real
    // player directly — bypassing the windowed controller seekTo() whose range check crashed on
    // taps outside the serialized window. Do NOT reconstruct a windowed index here (that desync-prone
    // math was the crash source); the service owns the window.
    fun play(position: Int) {
        withBrowser {
            it.sendCustomCommand(seekToFullCommand, Bundle().apply {
                putInt("index", position)
                putBoolean("play", true)
            })
        }
    }

    fun seek(position: Int) {
        withBrowser {
            it.sendCustomCommand(seekToFullCommand, Bundle().apply {
                putInt("index", position)
                putBoolean("play", false)
            })
        }
    }

    fun removeQueueItem(position: Int) {
        withBrowser { it.removeMediaItem(position) }
    }

    fun moveQueueItems(fromPos: Int, toPos: Int) {
        withBrowser { it.moveMediaItem(fromPos, toPos) }
    }

    fun seekTo(pos: Long) {
        withBrowser { it.seekTo(pos) }
    }

    fun seekToAdd(position: Int) {
        withBrowser { it.seekTo(max(0, it.currentPosition + position)) }
    }

    fun setPlaying(isPlaying: Boolean) {
        withBrowser {
            Log.d("GladixAudio", "setPlaying: isPlaying=$isPlaying playbackState=${it.playbackState} playWhenReady=${it.playWhenReady}")
            it.playWhenReady = isPlaying
        }
    }

    fun next() {
        withBrowser { it.seekToNextMediaItem() }
    }

    fun previous() {
        withBrowser { it.seekToPrevious() }
    }

    fun setShuffle(isShuffled: Boolean, changeCurrent: Boolean = false) {
        withBrowser {
            it.shuffleModeEnabled = isShuffled
            if (changeCurrent) it.seekTo(0, 0)
        }
    }

    fun setRepeat(repeatMode: Int) {
        withBrowser { it.repeatMode = repeatMode }
    }

    // Icon of the PLAYING extension, for the full-screen player's top-right slot. Takes an explicit id
    // (from the current MediaItem) rather than reading extensionLoader.current, which is the BROWSING
    // extension and would change while a track from a different extension kept playing.
    // Null when the id is unknown or the extension ships no icon; the view keeps its ic_extension_32dp.
    suspend fun getExtensionIcon(extensionId: String?): ImageHolder? = withContext(Dispatchers.IO) {
        extensionId ?: return@withContext null
        extensions.music.getExtension(extensionId)?.metadata?.icon
    }

    suspend fun isLikeClient(extensionId: String): Boolean = withContext(Dispatchers.IO) {
        extensions.music.getExtension(extensionId)?.isClient<LikeClient>() ?: false
    }

    private fun createException(throwable: Throwable) {
        viewModelScope.launch { app.throwFlow.emit(throwable) }
    }

    fun likeCurrent(isLiked: Boolean) = withBrowser { controller ->
        val future = controller.setRating(ThumbRating(isLiked))
        app.context.listenFuture(future) { sessionResult ->
            sessionResult.getOrElse { createException(it) }
        }
    }

    // mediaId-scoped like, for the player's overflow sheet. Same transport as likeCurrent above, but it
    // targets a NAMED track and surfaces the result CODE, because RESULT_ERROR_BAD_VALUE ("that id is not
    // in the timeline") is the caller's signal to fall back to the extension-only path. See the note on
    // PlayerCallback.onSetRating(mediaId) for why that code is unambiguous.
    fun likeById(mediaId: String, isLiked: Boolean, onResult: (Int) -> Unit) = withBrowser { controller ->
        val future = controller.setRating(mediaId, ThumbRating(isLiked))
        app.context.listenFuture(future) { sessionResult ->
            onResult(
                sessionResult.getOrElse {
                    createException(it)
                    SessionResult(SessionError.ERROR_UNKNOWN)
                }.resultCode
            )
        }
    }

    fun setSleepTimer(timer: Long) {
        withBrowser { it.sendCustomCommand(sleepTimer, Bundle().apply { putLong("ms", timer) }) }
    }

    fun changeTrackSelection(trackGroup: TrackGroup, index: Int) {
        withBrowser {
            it.trackSelectionParameters = it.trackSelectionParameters
                .buildUpon()
                .clearOverride(trackGroup)
                .addOverride(TrackSelectionOverride(trackGroup, index))
                .build()
        }
    }

    private fun changeCurrent(newItem: MediaItem) {
        withBrowser { player ->
            // player is the MediaController: its currentMediaItemIndex is windowed, but the session
            // applies replaceMediaItem to the full inner timeline. Resolve the current item's FULL
            // index by mediaId so we replace the actual current track, not the wrong one, in queues > 50.
            val fullIndex =
                queue.indexOfFirst { it.mediaId == playerState.current.value?.mediaItem?.mediaId }
            if (fullIndex < 0) return@withBrowser
            val oldPosition = player.currentPosition
            player.replaceMediaItem(fullIndex, newItem)
            player.prepare()
            player.seekTo(oldPosition)
        }
    }

    fun changeServer(server: Streamable) {
        val item = playerState.current.value?.mediaItem ?: return
        val index = item.serverWithDownloads(app.context).indexOf(server).takeIf { it != -1 }
            ?: return
        changeCurrent(MediaItemUtils.buildServer(item, index))
    }

    fun changeBackground(background: Streamable?) {
        val item = playerState.current.value?.mediaItem ?: return
        val index = item.track.backgrounds.indexOf(background)
        changeCurrent(MediaItemUtils.buildBackground(item, index))
    }

    fun changeSubtitle(subtitle: Streamable?) {
        val item = playerState.current.value?.mediaItem ?: return
        val index = item.track.subtitles.indexOf(subtitle)
        changeCurrent(MediaItemUtils.buildSubtitle(item, index))
    }

    fun changeCurrentSource(index: Int) {
        val item = playerState.current.value?.mediaItem ?: return
        changeCurrent(MediaItemUtils.buildSource(item, index))
    }

    fun setQueue(id: String, list: List<Track>, index: Int, context: EchoMediaItem?) {
        withBrowser { controller ->
            if (list.isEmpty()) return@withBrowser
            // P2 — current+upcoming: start at the tapped track (index 0) and drop the tracks before it,
            // so it lands at index 0 with nothing stranded above and a zero persisted index — matching
            // playItem and freshContextUpcoming. `index` locates the tapped track within `list`.
            val start = index.coerceIn(0, list.size - 1)
            val upcoming = list.subList(start, list.size)
            val mediaItems = upcoming.map {
                MediaItemUtils.build(
                    app,
                    downloadFlow.value,
                    MediaState.Unloaded(id, it),
                    context
                )
            }
            controller.setMediaItems(mediaItems, 0, upcoming.first().playedDuration ?: 0)
            controller.prepare()
            // In-order queue set (track tap / History) — sync the shuffle flag/icon OFF on the service player
            // WITHOUT changeQueue (pure primitive), so the icon can't stay stale-ON from prior playback.
            // `original` is already the in-order queue from setMediaItems, so this is cosmetic-only. FIFO after
            // setMediaItems, and idempotent regardless of arrival order. (The feed Play/Shuffle buttons call
            // setShuffle(...) AFTER this, which correctly overrides the flag for the Shuffle-button case.)
            controller.sendCustomCommand(
                syncShuffleFlagCommand, Bundle().apply { putBoolean("enabled", false) }
            )
        }
    }

    fun backfillQueue(
        extensionId: String, item: EchoMediaItem, loaded: Boolean, startTrackId: String,
    ) = viewModelScope.launch {
        withBrowser {
            it.sendCustomCommand(backfillCommand, Bundle().apply {
                putString("extId", extensionId)
                putSerialized("item", item)
                putBoolean("loaded", loaded)
                putString("startTrackId", startTrackId)
            })
        }
    }

    // ⚠⚠ TRACKS DO NOT GO THROUGH radioCommand. THE SPLIT IS BY ITEM TYPE AND IT IS LOAD-BEARING.
    // A Track IS a radio seed by nature, so a track station must be SEED-FIRST: the tapped track queued at
    // index 0 and played, then the generated mix appended behind it. Everything else (Album, Artist,
    // Playlist, Radio) has no seed to preserve - PlayerCallback.radio clears the queue and plays the mix,
    // which is CORRECT for those and only for those.
    //
    // ⚠️ WHAT THIS FIXES, MEASURED ON DEVICE 2026-09-10. Long-press -> Radio on a search result reached
    // PlayerCallback.radio, whose clearMediaItems() runs with no seed queued. THREE SYMPTOMS, ONE CAUSE:
    //   1. "Worst That Could Happen" (The Brooklyn Bridge) - ~100 tracks queued, playback started on the
    //      wrong one, because the tapped track was never at index 0 and DeezerRadioClient's TRACK branch
    //      had stripped it from the mix (see the coupling note there).
    //   2. "Underwater" (The Frogmen) - Deezer serves that recording under two album ids, the TRACK branch
    //      stripped BOTH, the append was empty, and play() ran on an emptied queue. Total silence.
    //   3. The endless-queue fallback never fired: PlayerRadio.play reads its seed from
    //      player.currentMediaItem, which is null once the queue is cleared and nothing is appended.
    //      Confirmed by an EMPTY `adb logcat -s GladixRadio` across both attempts - RadioFallback logs on
    //      every outcome, so zero lines means it never ran.
    //
    // ⚠️ DO NOT "FIX" THIS BY QUEUEING THE SEED HERE AND THEN CALLING radioCommand. ATOMIC PACKAGING IS
    // WHY trackRadio EXISTS: as two separate async commands the append can read a STALE currentMediaItem
    // between them. trackRadio does both halves inside one command, on one thread, in order.
    //
    // THE CHOICE LIVES HERE, AT THE CALLER, DELIBERATELY. Both UI entry points - MediaMoreBottomSheet's
    // radio button and MediaHeaderAdapter's onRadioClicked - funnel through this one function, so one
    // branch covers both and PlayerCallback.radio stays untouched and correct for its remaining callers.
    // Branching INSIDE PlayerCallback.radio was rejected: it would make the service handler mean two
    // different things depending on payload type, and the service is the harder place to see it from.
    //
    // ⚠⚠ THIS REROUTE POINTS THE MENU AT A HANDLER BUILT FOR TILE TAPS, AND THAT EXACT SHAPE
    // HAS REGRESSED BEFORE. A previous reroute of the single-track branch was believed TV-only, was relayed
    // as "phone unchanged" when the truth was "phone reaches the same result through DIFFERENT CODE", and
    // phone single-track tiles and search results then played NOTHING. Different code is different failure
    // modes. So the inputs were compared rather than assumed - checked 2026-09-10:
    //   extId        - tile tap passes the feed item's extensionId, the menu passes the sheet's. Both
    //                  non-null String; trackRadio only needs it to stamp MediaState.Unloaded.
    //   item         - both go through putSerialized<EchoMediaItem>(...) on THIS function, so the
    //                  polymorphic discriminator trackRadio's getSerialized<EchoMediaItem> needs is written
    //                  by construction. (Serialising as the concrete Track omits it - that is the
    //                  "tapping does nothing" regression recorded at playTrackRadio.)
    //   context      - NOT a difference: trackRadio builds its own "<title> Radio" Radio from the seed and
    //                  ignores whatever the caller had. SearchFragment's override passes null for exactly
    //                  this reason; the menu never supplied one at all.
    //   loaded       - THE ONE REAL DIFFERENCE. Tile taps pass an UNLOADED feed track; the menu passes a
    //                  LOADED one. trackRadio wraps either in MediaState.Unloaded and lets the normal
    //                  pipeline resolve it, so both work - the loaded track just carries more extras
    //                  through the Binder. The old menu path called loadItem() first; dropping that is not
    //                  a loss, because the menu already passed loaded=true so it was a no-op there.
    // ⚠️ ONE BEHAVIOUR DIFFERENCE AT THE EXTENSION, VERIFIED FOR DEEZER AND ONLY DEEZER. The old
    // path called RadioClient.radio(item, null); trackRadio calls it with the Radio it just built. In
    // DeezerRadioClient the Track branch sends BOTH to the same place - `null -> item.asTrackRadio()` and
    // `is Radio -> RadioKind.TRACK -> item.asTrackRadio()` - so the generated station is IDENTICAL. That
    // equivalence is NOT guaranteed for other extensions: any RadioClient that treats a non-null Radio
    // context differently from null will now see a context where it used to see null. If a non-Deezer
    // track radio behaves oddly after this change, that is the first place to look.
    //
    // NOTE ON `loaded`: intentionally unused on the Track path. trackRadio builds the seed as
    // MediaState.Unloaded and lets the normal resolution pipeline load it, which is what the already-shipped
    // tile path (FeedClickListener -> playTrackRadio) does with unloaded feed tracks.
    fun radio(id: String, item: EchoMediaItem, loaded: Boolean) = viewModelScope.launch {
        if (item is Track) {
            // No snackbar on this path: the seed starts playing immediately, so "Loading radio for X"
            // followed by instant audio reads as a stutter. The message below exists because the non-seed
            // path genuinely has nothing to show until the whole mix resolves.
            playTrackRadio(id, item)
            return@launch
        }
        app.messageFlow.emit(
            Message(app.context.getString(R.string.loading_radio_for_x, item.title))
        )
        withBrowser {
            it.sendCustomCommand(radioCommand, Bundle().apply {
                putString("extId", id)
                putSerialized("item", item)
                putBoolean("loaded", loaded)
            })
        }
    }

    // Single-track "radio" tiles (Home "Mixes inspired by", search): play the seed first, then append the
    // generated radio — service-side (PlayerCallback.trackRadio) so it works on TV without relying on
    // auto-radio. No loading snackbar (the seed plays immediately), matching the old setQueue path.
    fun playTrackRadio(id: String, track: Track) = viewModelScope.launch {
        withBrowser {
            it.sendCustomCommand(trackRadioCommand, Bundle().apply {
                putString("extId", id)
                // Serialize as EchoMediaItem (not Track) so the polymorphic "mediaItemType" discriminator is
                // written and the handler's getSerialized<EchoMediaItem> can round-trip it. Serializing as
                // the concrete Track omits the discriminator, decoding fails, and the seed guard silently
                // bails before any playback — the "tapping does nothing" regression.
                putSerialized<EchoMediaItem>("item", track)
            })
        }
    }

    fun play(id: String, item: EchoMediaItem, loaded: Boolean, startTrackId: String? = null) = viewModelScope.launch {
        if (item !is Track) app.messageFlow.emit(
            Message(app.context.getString(R.string.playing_x, item.title))
        )
        withBrowser {
            it.sendCustomCommand(playCommand, Bundle().apply {
                putString("extId", id)
                putSerialized("item", item)
                putBoolean("loaded", loaded)
                putBoolean("shuffle", false)
                if (startTrackId != null) putString("startTrackId", startTrackId)
            })
        }
    }

    fun shuffle(id: String, item: EchoMediaItem, loaded: Boolean) = viewModelScope.launch {
        if (item !is Track) app.messageFlow.emit(
            Message(app.context.getString(R.string.shuffling_x, item.title))
        )
        withBrowser {
            it.sendCustomCommand(playCommand, Bundle().apply {
                putString("extId", id)
                putSerialized("item", item)
                putBoolean("loaded", loaded)
                putBoolean("shuffle", true)
            })
        }
    }


    fun addToQueue(id: String, item: EchoMediaItem, loaded: Boolean) = viewModelScope.launch {
        if (item !is Track) app.messageFlow.emit(
            Message(app.context.getString(R.string.adding_x_to_queue, item.title))
        )
        withBrowser {
            it.sendCustomCommand(addToQueueCommand, Bundle().apply {
                putString("extId", id)
                putSerialized("item", item)
                putBoolean("loaded", loaded)
            })
        }
    }

    fun addToNext(id: String, item: EchoMediaItem, loaded: Boolean) = viewModelScope.launch {
        if (!(browser.value?.mediaItemCount == 0 && item is Track)) app.messageFlow.emit(
            Message(app.context.getString(R.string.adding_x_to_next, item.title))
        )
        withBrowser {
            it.sendCustomCommand(addToNextCommand, Bundle().apply {
                putString("extId", id)
                putSerialized("item", item)
                putBoolean("loaded", loaded)
            })
        }
    }

    val progress = MutableStateFlow(0L to 0L)

    // At-rest position seed hold (display half of the cold-start position fix). Non-null = the saved restore
    // position is being shown while the controller still reports currentPosition=0. PlayerUiListener.updateProgress
    // emits it in place of 0 and releases it (nulls) on the first real tick; a user seek nulls it too. Main-only.
    var restoreSeedMs: Long? = null
    val discontinuity = MutableStateFlow(0L)
    val totalDuration = MutableStateFlow<Long?>(null)

    val buffering = MutableStateFlow(false)
    val isPlaying = MutableStateFlow(false)
    val playWhenReady = MutableStateFlow(false)
    val nextEnabled = MutableStateFlow(false)
    val previousEnabled = MutableStateFlow(false)
    val repeatMode = MutableStateFlow(0)
    val shuffleMode = MutableStateFlow(false)

    // Tracks STAMPED with the mediaId they belong to. Written only by PlayerUiListener, which reads both
    // halves in one expression. The stamp turns "are these tracks for the item on screen?" from a timing
    // assumption into a checkable one, which is the point: the previous shape emitted the PREVIOUS track's
    // formats for the whole gap between an item transition and its onTracksChanged, because `current`
    // emits immediately while tracksFlow still holds the old value. On an all-320 queue that is invisible;
    // the moment quality varies it silently mislabels the new track for 2.4-3.8s (measured resolve window).
    val tracksFlow = MutableStateFlow<Pair<String?, Tracks?>>(null to null)
    val serverAndTracks = tracksFlow.combine(playerState.serverChanged) { stamped, _ -> stamped }
        .combine(playerState.current) { (stampedId, tracks), current ->
            val currentId = current?.mediaItem?.mediaId
            val server = playerState.servers[currentId]?.getOrNull()
            val index = current?.mediaItem?.sourceIndex
            // null unless the stamp matches. Consumers already null-guard `tracks`, so a mismatch renders
            // as "not known yet" rather than as the wrong track's format. `server` and `index` are NOT
            // gated: they come from `current` itself and are always about the right item, which is what
            // keeps the quality sheet's source chips populated during the gap.
            //
            // FAILURE MODE, AND HOW TO RECOGNISE IT. If a stamp never matches - a transition race, or a
            // reconnect that re-seeds with an id the UI has moved off - the pill hides PERMANENTLY rather
            // than showing something wrong. That is the safe direction, but it is silent, so know the
            // signature: a track that genuinely has no readable format hides the pill for THAT TRACK ONLY,
            // while a stamp mismatch hides it for EVERY track. The quality sheet is the discriminator -
            // its source chips come from `server`, which is never gated, so "chips present, details line
            // absent, on every track" means the stamp is not matching and this gate is the place to look.
            // "Chips present, details absent, on one track" is the legitimate case.
            Triple(if (stampedId != null && stampedId == currentId) tracks else null, server, index)
        }.stateIn(viewModelScope, SharingStarted.Lazily, Triple(null, null, null))

    companion object {
        const val KEEP_QUEUE = "keep_queue"
    }
}