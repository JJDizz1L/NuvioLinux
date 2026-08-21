package com.nuviolinux.app.features.trailer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** API key + visitor session token extracted from a YouTube watch page. */
internal data class WatchConfig(
    val apiKey: String?,
    val visitorData: String?,
)

internal object TrailerExtractionPlatform {
    val diagnosticsEnabled: Boolean = System.getenv("NUVIO_TRAILER_DEBUG")
        ?.trim()
        ?.lowercase()
        .let { it == "1" || it == "true" || it == "yes" || it == "on" }

    val defaultHeaders: Map<String, String> = mapOf(
        "accept-language" to "en-US,en;q=0.9",
        "user-agent" to
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36",
    )

    /* YouTube rate-limits headless watch-page fetches (HTTP 429) when they lack
     * cookies / arrive in quick succession. Keep cookies between requests and
     * seed the GDPR consent cookie so subsequent player-API calls (which need
     * the visitor session token) don't get flagged as a bot. */
    private val cookieJar = object : CookieJar {
        private val cookies = mutableMapOf<String, List<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            synchronized(this) {
                /* RFC 6265 semantics: a cookie is identified by name+path, so
                 * an incoming cookie REPLACES the stored one instead of being
                 * appended (the old append-merge accumulated stale duplicates
                 * for the process lifetime). */
                val retained = this.cookies[url.host].orEmpty()
                    .filter { old -> cookies.none { it.name == old.name && it.path == old.path } }
                this.cookies[url.host] = retained + cookies
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            val stored = synchronized(this) {
                this.cookies[url.host].orEmpty().filter { it.expiresAt > now }
            }
            val consent = Cookie.Builder()
                .name("SOCS")
                .value("CAI")
                .domain("youtube.com")
                .path("/")
                .build()
            return (stored + consent).filter { it.matches(url) }
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(TRAILER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TRAILER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(TRAILER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .cookieJar(cookieJar)
        .build()

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /* The INNERTUBE_API_KEY + VISITOR_DATA are session-wide, not video-specific.
     * Cache them so a watch page is fetched at most once per session — repeated
     * fetches (hover preview + hero + popup) are what trigger YouTube's 429. */
    private val watchConfigRef = AtomicReference<WatchConfig?>(null)

    fun cachedWatchConfig(): WatchConfig? = watchConfigRef.get()

    fun cacheWatchConfig(config: WatchConfig) {
        if (config.apiKey.isNullOrBlank()) return
        watchConfigRef.compareAndSet(null, config)
    }

    fun supportsSeparateVideo(candidate: StreamCandidate): Boolean = candidate.ext == "mp4"

    fun supportsSeparateAudio(candidate: StreamCandidate): Boolean = candidate.ext == "m4a"

    fun diagnostic(message: String) {
        if (diagnosticsEnabled) {
            println("[TrailerDebug] $message")
        }
    }

    fun describeUrl(url: String): String {
        val parsed = url.toHttpUrlOrNull()
        return "host=${parsed?.host ?: "unknown"} itag=${parsed?.queryParameter("itag") ?: "unknown"}"
    }

    /* Derived clients per non-default timeout (shared pools); avoids building
     * a fresh OkHttpClient on every request. */
    private val timeoutClients = java.util.concurrent.ConcurrentHashMap<Long, OkHttpClient>()

    private fun clientForTimeout(timeoutMillis: Long): OkHttpClient =
        if (timeoutMillis == TRAILER_REQUEST_TIMEOUT_MS) {
            httpClient
        } else {
            timeoutClients.getOrPut(timeoutMillis) {
                httpClient.newBuilder()
                    .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                    .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                    .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                    .build()
            }
        }

    suspend fun performRequest(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        timeoutMillis: Long,
    ): TrailerRequestResponse = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder()
            .url(url)
            .headers(buildHeaders(headers))

        when (method.uppercase()) {
            "POST" -> requestBuilder.post((body ?: "").toRequestBody())
            "PUT" -> requestBuilder.put((body ?: "").toRequestBody())
            "DELETE" -> requestBuilder.delete()
            else -> requestBuilder.get()
        }

        clientForTimeout(timeoutMillis)
            .newCall(requestBuilder.build())
            .execute().use { response ->
                TrailerRequestResponse(
                    ok = response.isSuccessful,
                    status = response.code,
                    statusText = response.message,
                    url = response.request.url.toString(),
                    body = response.body?.string().orEmpty(),
                )
            }
    }

    suspend fun buildPlaybackSource(
        bestManifest: ManifestCandidate?,
        progressiveCandidates: List<StreamCandidate>,
        videoCandidates: List<StreamCandidate>,
        audioCandidates: List<StreamCandidate>,
    ): TrailerPlaybackSource? = withContext(Dispatchers.IO) {
        /* Preference order, tuned for mpv/ffmpeg reliability (REVIEW-NOTES T1):
         * 1. adaptive_separate — H.264 fMP4 video + AAC audio (up to 1080p);
         *    clean demux, and the mpv bridge plays separate audio natively.
         * 2. progressive       — single muxed MP4 (YouTube caps it at 720p).
         * 3. hls               — DVR variant master, LAST RESORT only: its
         *    VP9-in-MPEGTS renditions cause mpegts corruption storms in
         *    ffmpeg's demuxer and per-frame VAAPI import failures.
         * Candidate lists arrive ordered preferred-client-first; each category
         * walks down its chain until a URL probes reachable. */
        var chosenVideo: StreamCandidate? = null
        var separatedVideoUrl: String? = null
        for (candidate in videoCandidates) {
            val url = resolveReachableUrlOrNull(candidate.url)
            if (url != null) {
                chosenVideo = candidate
                separatedVideoUrl = url
                break
            }
            diagnostic("blocked stage=video_probe candidate=${candidate.diagnosticSummary()}")
        }
        var separatedAudioUrl: String? = null
        if (separatedVideoUrl != null) {
            for (candidate in audioCandidates) {
                val url = resolveReachableUrlOrNull(candidate.url)
                if (url != null) {
                    separatedAudioUrl = url
                    break
                }
                diagnostic("blocked stage=audio_probe candidate=${candidate.diagnosticSummary()}")
            }
        }
        val useSeparatedStreams = separatedVideoUrl != null && separatedAudioUrl != null

        var chosenProgressive: StreamCandidate? = null
        var progressiveUrl: String? = null
        if (!useSeparatedStreams) {
            for (candidate in progressiveCandidates) {
                val url = resolveReachableUrlOrNull(candidate.url)
                if (url != null) {
                    chosenProgressive = candidate
                    progressiveUrl = url
                    break
                }
                diagnostic("blocked stage=progressive_probe candidate=${candidate.diagnosticSummary()}")
            }
        }

        var manifestResolvedUrl: String? = null
        if (!useSeparatedStreams && progressiveUrl == null && bestManifest != null) {
            manifestResolvedUrl = resolveReachableUrlOrNull(bestManifest.manifestUrl)
            if (manifestResolvedUrl == null) {
                diagnostic("blocked stage=hls_probe candidate=${bestManifest.diagnosticSummary()}")
            }
        }

        /* Full separate-audio playback wins; otherwise prefer SOUND over
         * silence: progressive (720p+muxed audio) then HLS, and only then a
         * muted higher-resolution video-only stream as degenerate fallback. */
        val videoUrl = if (useSeparatedStreams) {
            separatedVideoUrl
        } else {
            progressiveUrl ?: manifestResolvedUrl ?: separatedVideoUrl
        }
        if (videoUrl == null) {
            diagnostic("blocked stage=source reason=no_reachable_video")
            return@withContext null
        }
        val audioUrl = separatedAudioUrl.takeIf { useSeparatedStreams }
        val mode = when {
            useSeparatedStreams -> "adaptive_separate"
            progressiveUrl != null -> "progressive"
            manifestResolvedUrl != null -> "hls_last_resort"
            else -> "adaptive_video_only"
        }
        val videoSummary = when {
            useSeparatedStreams -> chosenVideo?.diagnosticSummary()
            progressiveUrl != null -> chosenProgressive?.diagnosticSummary()
            manifestResolvedUrl != null -> bestManifest.diagnosticSummary()
            else -> chosenVideo?.diagnosticSummary()
        }.orEmpty()
        diagnostic(
            "selected mode=$mode video=[$videoSummary] audio=[${bestAudioForDiag(audioCandidates, audioUrl)}]",
        )
        diagnostic("source videoUrl=$videoUrl")
        diagnostic("source audioUrl=${audioUrl ?: "none"}")
        TrailerPlaybackSource(
            videoUrl = videoUrl,
            audioUrl = audioUrl,
        )
    }

    private fun bestAudioForDiag(candidates: List<StreamCandidate>, resolved: String?): String =
        if (resolved == null) {
            "none"
        } else {
            candidates.firstOrNull()?.diagnosticSummary().orEmpty()
        }

    private suspend fun resolveReachableUrlOrNull(url: String): String? {
        if (!url.contains("googlevideo.com")) {
            diagnostic("probe skipped ${describeUrl(url)} reason=non_googlevideo")
            return url
        }
        val parsedUrl = url.toHttpUrlOrNull()
        if (parsedUrl == null) {
            diagnostic("probe failed host=unknown reason=invalid_url")
            return null
        }
        val servers = parsedUrl.queryParameter("mn")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val host = parsedUrl.host
        val candidates = buildList {
            add(url)
            servers.forEachIndexed { index, server ->
                val alternateHost = host
                    .replaceFirst(Regex("^rr\\d+---"), "rr${index + 1}---")
                    .replaceFirst(Regex("sn-[a-z0-9]+-[a-z0-9]+"), server)
                if (alternateHost != host) {
                    add(parsedUrl.newBuilder().host(alternateHost).build().toString())
                }
            }
        }.distinct()

        if (candidates.size == 1) {
            val selected = candidates.first().takeIf(::isUrlReachable)
            diagnostic(
                "probe ${if (selected != null) "ok" else "failed"} ${describeUrl(url)} candidates=1",
            )
            return selected
        }

        val result = CompletableDeferred<String>()
        val probeScope = CoroutineScope(Dispatchers.IO)
        candidates.forEach { candidate ->
            probeScope.launch {
                if (isUrlReachable(candidate)) {
                    result.complete(candidate)
                }
            }
        }

        return try {
            val selected = withTimeoutOrNull(4_000L) { result.await() }
            diagnostic(
                "probe ${if (selected != null) "ok" else "failed"} ${describeUrl(url)} candidates=${candidates.size}" +
                    selected?.let { " selectedHost=${it.toHttpUrlOrNull()?.host ?: "unknown"}" }.orEmpty(),
            )
            selected
        } finally {
            probeScope.cancel()
        }
    }

    private fun isUrlReachable(url: String): Boolean = runCatching {
        val parsedUrl = url.toHttpUrlOrNull()
        val sourceSize = parsedUrl?.queryParameter("clen")?.toLongOrNull()?.takeIf { it > 0L }
        val ranges = sourceSize?.let { size ->
            listOf(
                0L to 65_535L.coerceAtMost(size - 1L),
                (size - 65_536L).coerceAtLeast(0L) to size - 1L,
            ).distinct()
        } ?: listOf(0L to 0L)

        ranges.all { (rangeStart, rangeEnd) ->
            val request = Request.Builder()
                .url(url)
                .headers(buildHeaders(defaultHeaders))
                .header("Range", "bytes=$rangeStart-$rangeEnd")
                .get()
                .build()

            probeClient.newCall(request).execute().use { response ->
                val reachable = response.code == 206 ||
                    (sourceSize == null && rangeStart == 0L && response.code in 200..299)
                if (!reachable) {
                    diagnostic(
                        "probe range rejected ${describeUrl(url)} requested=$rangeStart-$rangeEnd status=${response.code}",
                    )
                }
                reachable
            }
        }
    }.getOrDefault(false)

    private fun buildHeaders(source: Map<String, String>): Headers {
        val headers = Headers.Builder()
        source.forEach { (name, value) ->
            if (!name.equals("Accept-Encoding", ignoreCase = true)) {
                headers.add(name, value)
            }
        }
        if (source.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            headers.add("User-Agent", defaultHeaders.getValue("user-agent"))
        }
        return headers.build()
    }
}
