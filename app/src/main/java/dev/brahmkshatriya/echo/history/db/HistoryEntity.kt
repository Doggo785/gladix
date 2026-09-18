package dev.brahmkshatriya.echo.history.db

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.utils.Serializer.toData

@Entity(primaryKeys = ["trackId", "extensionId"])
data class HistoryEntity(
    val trackId: String,
    val extensionId: String,
    val playedAt: Long,
    val trackData: String,
    val contextData: String? = null,
) {
    val track by lazy { trackData.toData<Track>().getOrNull() }
    val context by lazy { contextData?.toData<EchoMediaItem>()?.getOrNull() }
}

// History only needs a track's id (for live re-resolution on tap/play) plus display fields (title,
// artist names + covers, cover, duration) and — for the more-sheet's "Go to Album"/artist nav — a
// slimmed album + slimmed artists. Everything heavy (streamables, extras, description, banners,
// nested album artists) is dropped so a row stays well under the CursorWindow limit that getAll hit.
// ⚠⚠ PATTERN, NOT THREE SEPARATE BUGS: ANY SIGNAL READ FROM `extras` ON A TRACK THAT MAY
// HAVE BEEN SLIMMED IS UNRELIABLE BY CONSTRUCTION. `extras = emptyMap()` below is wholesale - it does not
// know which keys carry identity, capability or availability, so a slimmed track is INDISTINGUISHABLE
// from a track whose extension never set those keys. Every consumer that reads extras and infers
// something about the TRACK is really inferring something about the track's PROVENANCE.
// THREE INSTANCES, ALL FOUND SEPARATELY, ALL THE SAME SHAPE:
//   1. extension_id destroyed -> radio generation broke, loadTrack's failure was masked behind
//      Cached.loadMedia's fallback, and Unified tracking died silently for six weeks.
//   2. TRACK_TOKEN destroyed -> DeezerTrackClient.loadTrack's self-heal gate reads an empty token as
//      "needs re-fetch", which is correct, but a 2026-09-12 investigation then read the SAME emptiness
//      as "this track cannot play". It measured 12/12 against unplayable tracks and was refuted two
//      captures later. See the note at that gate.
//   3. (the general case) any future extras key used as a capability or availability signal.
// ⚠️ AND SELECTIVE SLIMMING ALREADY EXISTS - toSlimContext below keeps Radio extras precisely
// because the radio work depends on them, and is the single source of truth for that. So the precedent is
// PRESERVE WHAT MATTERS, not empty the map; there is no argument that wholesale stripping is required.
// Before adding a fourth consumer of an extras key, ask whether the track could have come through here.
// ⚠⚠ DROPPING `streamables` HERE IS DELIBERATE AND LOAD-BEARING. DO NOT PUT THEM BACK.
// July 2026: History was storing a full serialized Track + context JSON (covers, artists, album
// objects, streamables, tokens, extras) when it needs a fraction of that, and the bulk blew the
// CursorWindow - a crash at row 53. The same slimming fixed a cold-start crash for three users.
// `encodeDefaults` is unset (defaults false), CONFIRMED at the time, so the now-default-valued
// streamables do not serialize at all. Empty streamables in a persisted queue is the INTENDED,
// VERIFIED design, not a loss.
// ⚠️ THE OTHER HALF OF THE CONTRACT IS loadTrack, AND THAT IS WHERE BUGS FROM THIS LIVE.
// Each extension is expected to repopulate streamables in loadTrack (Deezer rebuilds them from
// track.extras["TRACK_TOKEN"]; network extensions get them back by re-fetching). An extension
// whose loadTrack does not is in VIOLATION, and the symptom lands far from here - as a
// TrackUnavailableException out of StreamableLoader.loadServer on a restored queue.
// OfflineExtension.loadTrack was the identity function and did exactly that; see the note there.
// So: a "no playable source" report on a restored queue is a question about that extension's
// loadTrack. It is NOT a reason to revisit this line.
fun Track.toSlim(): Track = copy(
    streamables = emptyList(),
    extras = emptyMap(),
    description = null,
    background = null,
    subtitle = null,
    genres = emptyList(),
    plays = null,
    releaseDate = null,
    playlistAddedDate = null,
    isrc = null,
    artists = artists.map { it.copy(bio = null, extras = emptyMap(), background = null, banners = emptyList()) },
    album = album?.copy(description = null, extras = emptyMap(), background = null, artists = emptyList())
)

// A stored context only needs id/type/title/cover (for the "Go to <context>" button + re-resolution
// by id, which happens with loaded=false). Radio is the exception: its re-resolution reads extras
// (kind + seeds), so keep those; drop everything else's extras/description.
fun EchoMediaItem.toSlimContext(): EchoMediaItem = copyMediaItem(
    description = null,
    subtitle = null,
    extras = if (this is Radio) extras else emptyMap()
)
