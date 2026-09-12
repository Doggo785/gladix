package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toMedia
import dev.brahmkshatriya.echo.common.models.Streamable.Source.Companion.toSource
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.AudioStreamProvider
import dev.brahmkshatriya.echo.extension.DeezerApi
import dev.brahmkshatriya.echo.extension.DeezerExtension
import dev.brahmkshatriya.echo.extension.DeezerParser
import dev.brahmkshatriya.echo.extension.Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

class DeezerTrackClient(private val deezerExtension: DeezerExtension, private val api: DeezerApi, private val parser: DeezerParser) {

    private val client: OkHttpClient get() = api.clientNP

    private fun extractUrlFromJson(json: JsonObject): String? {
        val data = json["data"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val media = data["media"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val source = media["sources"]?.jsonArray?.getOrNull(1)?.jsonObject
            ?: media["sources"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: return null
        return source["url"]?.jsonPrimitive?.content
    }

    private suspend fun createStreamableForQuality(track: Track, quality: String, retry: Boolean = true): Streamable {
        return try {
            val currentTrackId = track.id
            val mediaJson =
                if (quality != "128" && quality != "mp3") api.getMediaUrl(track, quality)
                else api.getMP3MediaUrl(track, quality == "128")
            val mjString = mediaJson.toString()
            if (mjString.contains("License token has no sufficient rights on requested media")) return when (quality) {
                "flac" -> createStreamableForQuality(track, "320", retry)
                "320" -> createStreamableForQuality(track, "128", retry)
                else -> throw Exception("Track not available on server")
            }
            val trackJsonData = mediaJson["data"]?.jsonArray?.firstOrNull()?.jsonObject
            val mediaIsEmpty = trackJsonData?.get("media")?.jsonArray?.isEmpty() == true

            val (finalUrl, fallbackTrack) = when {
                mjString.contains("Track token has no sufficient rights on requested media") || mediaIsEmpty -> {
                    val fallBackId = track.extras["FALLBACK_ID"].orEmpty()
                    if (quality == "128") {
                        val fallbackObject = api.track(fallBackId, "streamable.fallbackId")
                        val resultOj = fallbackObject["results"]?.jsonObject!!
                        val fallBackTrack = parser.run { resultOj.toTrack() }
                        val fbMediaJson = api.getMP3MediaUrl(fallBackTrack, true)
                        val url = extractUrlFromJson(fbMediaJson)!!
                        url to fallBackTrack
                    } else {
                        val fallbackParsed = track.copy(id = fallBackId)
                        val fallbackMediaJson = api.getMediaUrl(fallbackParsed, quality)
                        val url = extractUrlFromJson(fallbackMediaJson)!!
                        url to fallbackParsed
                    }
                }

                mjString.contains("An error occurred while decoding track token") -> {
                    val fallbackObject = api.track(currentTrackId, "streamable.tokenDecode")
                    val resultOj = fallbackObject["results"]?.jsonObject!!
                    val fallBackTrack = parser.run { resultOj.toTrack() }
                    val fbMediaJson = api.getMP3MediaUrl(fallBackTrack, true)
                    val url = extractUrlFromJson(fbMediaJson)!!
                    url to fallBackTrack
                }

                else -> {
                    val url = extractUrlFromJson(mediaJson)!!
                    url to null
                }
            }

            val qualityValue = when (quality) {
                "flac" -> 9
                "320" -> 6
                "128" -> 3
                else -> 0
            }
            val qualityTitle = when (quality) {
                "flac" -> "FLAC"
                "320" -> "320kbps"
                "128" -> "128kbps"
                else -> "UNKNOWN"
            }
            val keySourceId = fallbackTrack?.id ?: currentTrackId

            Streamable.server(
                id = finalUrl,
                quality = qualityValue,
                title = qualityTitle,
                extras = mapOf("key" to Utils.createBlowfishKey(keySourceId))
            )
        } catch (e: Exception) {
            if (e.message?.contains("Song not available") == true) {
                if (retry) {
                    // Deliberate best-effort fallback: the cause is logged above; rethrowing would change
                    // the intended quality-fallback control flow. Suppressing Detekt SwallowedException here.
                    @Suppress("SwallowedException")
                    try {
                        deezerExtension.handleArlExpiration()
                        return createStreamableForQuality(track, quality, false)
                    } catch (retryEx: Exception) {
                        // Best-effort: proceed to quality fallback. Log so a failing ARL-refresh retry
                        // (a token issue) isn't invisible — control flow unchanged.
                        println("GladixDeezer createStreamableForQuality ARL-refresh retry failed id=${track.id} q=$quality: ${retryEx.message}")
                    }
                }
            }

            when (quality) {
                "flac" -> createStreamableForQuality(track, "320", retry)
                "320" -> createStreamableForQuality(track, "128", retry)
                else -> throw Exception("Track not available on server")
            }
        }
    }

    suspend fun loadStreamableMedia(streamable: Streamable): Streamable.Media {
        deezerExtension.handleArlExpiration()
        val resolvedStreamable = if (streamable.id.startsWith(placeholderPrefix)) {
            val info = streamable.id.removePrefix(placeholderPrefix).split(":")
            val trackId = info[0]
            val quality = info.getOrNull(1) ?: "128"
            val newTrack = Track(
                id = trackId,
                title = quality,
                extras = mapOf(
                    "TRACK_TOKEN" to streamable.extras["TRACK_TOKEN"].orEmpty(),
                    "FALLBACK_ID" to streamable.extras["FALLBACK_ID"].orEmpty()
                )
            )
            var resolved: Streamable? = null
            // Last attempt's cause, CHAINED into the throw below. Control flow is unchanged — the loop still
            // swallows per-attempt failures and still retries — this only stops the reason being discarded.
            // Without it every failure mode collapses to one string: the 2026-08-19 breaker trip showed three
            // distinct track ids all reporting "Track not available after retries" with no transport error,
            // and token/session staleness, an account-level limit and a server outage were indistinguishable.
            var lastError: Throwable? = null
            for (attempt in 0..1) {
                if (attempt > 0) delay(2000L)
                // Best-effort retry: the loop continues past a failed attempt and ends in the throw below.
                // The caught exception IS used now (captured as lastError and chained), but the annotation
                // stays because the catch still does not rethrow on the non-final attempt.
                @Suppress("SwallowedException")
                try {
                    resolved = createStreamableForQuality(newTrack, quality)
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    // Kept alongside the chaining, not made redundant by it: only the LAST attempt's cause is
                    // chained, so attempt 0's reason exists nowhere else. The two are complementary.
                    println("GladixDeezer loadStreamableMedia attempt $attempt failed id=$trackId q=$quality: ${e.message}")
                }
            }
            resolved ?: throw Exception("Track not available after retries: $trackId", lastError)
        } else {
            streamable
        }

        return if (resolvedStreamable.quality == 12) {
            resolvedStreamable.id.toSource().toMedia()
        } else {
            Streamable.InputProvider { start, _ ->
                val contentLength = Utils.getContentLength(resolvedStreamable.id, client)
                Pair(
                    AudioStreamProvider.openStream(resolvedStreamable, client, start),
                    contentLength - start
                )
            }.toSource(id = resolvedStreamable.id).toMedia()
        }
    }

    private val qualityOptions = listOf("flac", "320", "128")

    suspend fun loadTrack(original: Track): Track {
        deezerExtension.handleArlExpiration()

        if (original.type == Track.Type.Podcast) {
            return original
        }

        // Self-heal a missing/empty TRACK_TOKEN (e.g. a context-less bare track recovered from an
        // Android Auto cache-miss, or a stale persisted seed): re-fetch the track fresh by id so its
        // streamables carry a valid token — and, for a thin recovered track, full metadata too. Gated
        // on an EMPTY token, so the normal path (token already present) adds no network round-trip. On
        // any failure we fall back to the original track unchanged — the stream-time token-error
        // fallback in createStreamableForQuality still applies — never crashing. CancellationException
        // is rethrown so coroutine cancellation is honoured.
        // ⚠⚠ THIS runCatching ABSORBS THE NEW GATEWAY THROW, AND THAT MAKES THE FLIP A NO-OP
        // ON THE PATH THAT TRIGGERS IT MOST. Measured 2026-09-12: one playlist and the album behind it,
        // containing tracks that will not play, produced TWELVE `GATEWAY-ERROR method=deezer.pageTrack
        // error=REQUEST_ERROR=Wrong parameters` on one screen - every one from the api.track call below.
        // ⚠️ THE CAUSE OF THE UNPLAYABILITY IS NOT KNOWN AND IS NOT ASSERTED. What IS observed:
        // those tracks reach here with an EMPTY TRACK_TOKEN, because that is the gate on the `if` below and
        // it opened for each of them. So each asks the gateway for a record the gateway then refuses.
        // Do not upgrade "empty TRACK_TOKEN" into a catalogue-state explanation - it is a correlation
        // measured once, at 12 of 12, and nothing here establishes why.
        // BEFORE THE FLIP: callApi returned {error, results:{}}; `results` parsed to nothing, this
        // runCatching caught whatever that produced, fresh = null, fall back to `original`.
        // AFTER: callApi throws DeezerGatewayException; the SAME runCatching catches it, the SAME
        // getOrElse yields null, the SAME fallback to `original` runs. Identical screen, identical
        // behaviour - twelve exceptions constructed and immediately absorbed by a handler that already
        // existed for exactly this outcome. ⚠️ NOT A FLOOD AND NOT AN IMPROVEMENT HERE: it is
        // ABSORBED, bounded by construction at one per token-less track. What it does buy is determinism -
        // `fresh` is now reliably null on a refusal instead of depending on how an empty `results` object
        // happens to parse.
        // WHERE THE FLIP ACTUALLY PAYS is createStreamableForQuality's two api.track calls on the FALLBACK
        // branches: those are NOT wrapped, so they surface - and they already failed today, opaquely, on a
        // `!!` or a parse of empty results. They now fail with Deezer's own sentence attached. Same
        // failure, readable cause.
        // The fourth call site, DeezerRadioClient's seed fetch, is runCatching{}.getOrNull() - absorbed
        // like this one.
        // ⚠⚠ THIS GATE FIRES ON PROVENANCE, NOT ON AVAILABILITY - AND IT READS LIKE THE
        // OPPOSITE, WHICH IS WHY IT IS WRITTEN DOWN. An empty TRACK_TOKEN means the track LOST ITS EXTRAS
        // somewhere - restored from a saved queue, recovered from history, or slimmed - because
        // HistoryEntity.toSlim does `extras = emptyMap()` wholesale. It says NOTHING about whether the
        // track can play. Re-fetching in that case is correct and this gate is right; what is wrong is
        // reading the emptiness as a property of the track.
        // ⚠️ MEASURED THE WRONG WAY ONCE, COST TWO CAPTURES: on 2026-09-12 an unplayable-track
        // investigation observed this gate opening 12 times out of 12 on tracks that would not play, and
        // took empty-token as an availability signal. The next capture refuted it outright - the record it
        // caught was an unplayable track WITH a token and every FILESIZE variant at zero, and the
        // no-token slot never fired at all. The correlation was real and the causation was backwards:
        // both the refusals and the empty tokens follow from how those tracks REACHED the player.
        // Do not reuse this condition as an availability test. See the pattern note at HistoryEntity.toSlim.
        val track = if (original.extras["TRACK_TOKEN"].isNullOrEmpty()) {
            val fresh = runCatching {
                api.track(original.id, "loadTrack.selfheal")["results"]?.jsonObject?.let { results ->
                    parser.run { results.toTrack() }
                }
            }.getOrElse { if (it is CancellationException) throw it else null }
            when {
                fresh == null -> original
                // Seed already carries display metadata (e.g. a FALLBACK-grafted playlist track whose
                // top-level TRACK_TOKEN was empty): KEEP the seed's artists/album/cover/background and take
                // ONLY the token/streamable extras from the fresh fetch. Re-fetching by the top-level id
                // returns the substitute's OWN (un-grafted) record, so replacing wholesale would discard the
                // graft and show the wrong/old cover in the player (the fullscreen ViewHolder reads the loaded
                // track). Streaming is unaffected: track.id stays the top-level id and we take fresh's TOKEN.
                original.cover != null || original.artists.isNotEmpty() || original.album != null ->
                    original.copy(extras = original.extras + fresh.extras)
                // Thin recovered track (context-less/bare, e.g. an Android Auto cache-miss): no display
                // metadata to preserve → use the full fresh fetch.
                else -> fresh
            }
        } else original

        val isMp3Misc = track.extras["FILESIZE_MP3_MISC"]?.let { it != "0" } ?: false

        val streamables = if (isMp3Misc) {
            listOf(
                Streamable.server(
                    id = "$placeholderPrefix${track.id}:mp3",
                    quality = 0,
                    title = "MP3",
                    extras = mapOf(
                        "TRACK_TOKEN" to track.extras["TRACK_TOKEN"].orEmpty(),
                        "FALLBACK_ID" to track.extras["FALLBACK_ID"].orEmpty()
                    )
                )
            )
        } else {
            qualityOptions.map { quality ->
                val qualityValue = when (quality) {
                    "flac" -> 9
                    "320" -> 6
                    "128" -> 3
                    else -> 0
                }
                val qualityTitle = when (quality) {
                    "flac" -> "FLAC"
                    "320" -> "320kbps"
                    "128" -> "128kbps"
                    else -> "UNKNOWN"
                }
                Streamable.server(
                    id = "$placeholderPrefix${track.id}:$quality",
                    quality = qualityValue,
                    title = qualityTitle,
                    extras = mapOf(
                        "TRACK_TOKEN" to track.extras["TRACK_TOKEN"].orEmpty(),
                        "FALLBACK_ID" to track.extras["FALLBACK_ID"].orEmpty()
                    )
                )
            }
        }
        return track.copy(
            streamables = streamables
        )
    }

    private val placeholderPrefix = "dzp:"
}