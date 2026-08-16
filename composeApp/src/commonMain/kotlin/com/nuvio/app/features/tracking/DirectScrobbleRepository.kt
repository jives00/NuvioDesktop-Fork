package com.nuvio.app.features.tracking

import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.AppVersionPolicy
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.trakt.TraktEpisodeMappingService
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// [FORK] Direct scrobble to the self-hosted Trakt clone — no Trakt.tv account required.
//
// Deliberately NOT registered via TrackingProviderRegistry.registerScrobbler: TrackingProviderId
// is a closed enum that drives the tracking settings UI, and this endpoint is always-on rather
// than something the user connects. TrackingScrobbleCoordinator invokes it alongside the
// registered providers instead.
object DirectScrobbleRepository {
    private val log = Logger.withTag("DirectScrobble")
    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
    }

    private val isEnabled: Boolean
        get() = ScrobbleConfig.API_URL.isNotBlank()

    suspend fun scrobble(action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
        if (!isEnabled) return

        // The server models a pause as a stop that keeps now_playing alive, so a real stop and a
        // user pause differ only by this flag. Getting it wrong leaves now_playing stuck for hours.
        val (endpoint, paused) = when (action) {
            TrackingScrobbleAction.START -> "start" to false
            TrackingScrobbleAction.PAUSE -> "stop" to true
            TrackingScrobbleAction.STOP -> "stop" to false
        }

        val body = buildRequestBody(
            media = event.media,
            progressPercent = event.progressPercent.toFloat().coerceIn(0f, 100f),
            paused = paused,
        ) ?: return

        val url = ScrobbleConfig.API_URL.trimEnd('/') + "/" + endpoint
        val response = runCatching {
            httpRequestRaw(
                method = "POST",
                url = url,
                headers = mapOf(
                    "Accept" to "application/json",
                    "Content-Type" to "application/json",
                    "X-Api-Key" to ScrobbleConfig.API_KEY,
                ),
                body = json.encodeToString(body),
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.w(error) { "Direct scrobble $endpoint transport failure" }
        }.getOrNull() ?: return

        if (response.status !in 200..299) {
            log.w { "Direct scrobble $endpoint failed: HTTP ${response.status} ${response.body.take(200)}" }
        }
    }

    // Mirrors TraktScrobbleRepository.buildItem so the direct endpoint receives the same
    // season/episode numbers (post absolute-numbering mapping) that Trakt.tv would.
    private suspend fun buildRequestBody(
        media: TrackingMediaReference,
        progressPercent: Float,
        paused: Boolean,
    ): DirectScrobbleRequest? {
        val ids = media.ids.toDirectIds() ?: return null

        if (media.kind == TrackingMediaKind.MOVIE) {
            return DirectScrobbleRequest(
                movie = DirectMediaBody(title = media.title, year = media.year, ids = ids),
                progress = progressPercent,
                appVersion = AppVersionPolicy.displayVersionName,
                paused = paused,
            )
        }

        val episode = media.episode ?: return null
        val season = episode.season ?: return null
        val contentId = media.catalog?.contentId?.takeIf(String::isNotBlank)
            ?: media.ids.imdb?.takeIf(String::isNotBlank)
            ?: media.ids.tmdb?.let { value -> "tmdb:$value" }
            ?: media.ids.trakt?.let { value -> "trakt:$value" }
            ?: return null
        val mapped = TraktEpisodeMappingService.resolveEpisodeMapping(
            contentId = contentId,
            contentType = media.catalog?.contentType?.takeIf(String::isNotBlank) ?: "series",
            videoId = media.catalog?.videoId?.takeIf(String::isNotBlank),
            season = season,
            episode = episode.number,
            episodeTitle = episode.title,
        )

        return DirectScrobbleRequest(
            show = DirectMediaBody(title = media.title, year = media.year, ids = ids),
            episode = DirectEpisodeBody(
                title = episode.title,
                season = mapped?.season ?: season,
                number = mapped?.episode ?: episode.number,
            ),
            progress = progressPercent,
            appVersion = AppVersionPolicy.displayVersionName,
            paused = paused,
        )
    }

    // The server resolves a media row from any one of these, so a reference carrying none of
    // them would only match by fuzzy title — skip it rather than risk scrobbling the wrong show.
    private fun TrackingExternalIds.toDirectIds(): DirectIdsBody? {
        val body = DirectIdsBody(
            trakt = trakt,
            imdb = imdb?.takeIf(String::isNotBlank),
            tmdb = tmdb,
            tvdb = tvdb?.toIntOrNull(),
        )
        val hasAny = body.trakt != null || !body.imdb.isNullOrBlank() || body.tmdb != null || body.tvdb != null
        return body.takeIf { hasAny }
    }
}

@Serializable
private data class DirectScrobbleRequest(
    @SerialName("movie") val movie: DirectMediaBody? = null,
    @SerialName("show") val show: DirectMediaBody? = null,
    @SerialName("episode") val episode: DirectEpisodeBody? = null,
    @SerialName("progress") val progress: Float,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("paused") val paused: Boolean = false,
)

@Serializable
private data class DirectMediaBody(
    @SerialName("title") val title: String? = null,
    @SerialName("year") val year: Int? = null,
    @SerialName("ids") val ids: DirectIdsBody,
)

@Serializable
private data class DirectEpisodeBody(
    @SerialName("title") val title: String? = null,
    @SerialName("season") val season: Int,
    @SerialName("number") val number: Int,
)

@Serializable
private data class DirectIdsBody(
    @SerialName("trakt") val trakt: Long? = null,
    @SerialName("imdb") val imdb: String? = null,
    @SerialName("tmdb") val tmdb: Long? = null,
    @SerialName("tvdb") val tvdb: Int? = null,
)
