package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup

class AnimeKaiExtension : ExtensionClient, SearchFeedClient, HomeFeedClient, TrackClient, AlbumClient {

    override suspend fun getSettingItems(): List<Setting> = emptyList()

    private lateinit var setting: Settings
    override fun setSettings(settings: Settings) {
        setting = settings
    }

    private val httpClient = OkHttpClient()

    private val DOMAIN_VALUES = listOf(
        "https://animekai.to",
        "https://animekai.cc",
        "https://animekai.ac",
        "https://anikai.to"
    )
    private var baseUrl = DOMAIN_VALUES.first()

    data class EpisodeData(
        val token: String,
        val num: String,
        val title: String,
        val subdub: String
    )

    private fun parseResultResponse(jsonString: String): String? {
        return try {
            val resultIndex = jsonString.indexOf("\"result\":")
            if (resultIndex == -1) return null

            val valueStart = jsonString.indexOf("\"", resultIndex + 9)
            if (valueStart == -1) return null

            val result = StringBuilder()
            var i = valueStart + 1
            var escaped = false

            while (i < jsonString.length) {
                val char = jsonString[i]
                when {
                    escaped -> {
                        when (char) {
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            '"' -> result.append('"')
                            '\\' -> result.append('\\')
                            '/' -> result.append('/')
                            'u' -> {
                                // Unicode escape sequence \uXXXX
                                if (i + 4 < jsonString.length) {
                                    try {
                                        val unicode = jsonString.substring(i + 1, i + 5)
                                        val charCode = unicode.toInt(16)
                                        result.append(charCode.toChar())
                                        i += 4
                                    } catch (e: Exception) {
                                        result.append('u')
                                    }
                                }
                            }
                            else -> {
                                result.append('\\')
                                result.append(char)
                            }
                        }
                        escaped = false
                    }
                    char == '\\' -> escaped = true
                    char == '"' -> break
                    else -> result.append(char)
                }
                i++
            }

            result.toString()
        } catch (e: Exception) {
            println("AnimeKai: JSON parse error: ${e.message}")
            null
        }
    }

    private suspend fun encDecEndpoints(text: String): String {
        return try {
            val url = "https://enc-dec.app/api/enc-kai?text=$text"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val response = httpClient.newCall(request).await()
            val jsonText = response.body?.string() ?: ""
            response.close()

            parseResultResponse(jsonText) ?: System.currentTimeMillis().toString()
        } catch (e: Exception) {
            println("AnimeKai: Encode error: ${e.message}")
            System.currentTimeMillis().toString()
        }
    }

    private suspend fun decodeIframe(encodedText: String): String? {
        return try {
            val jsonBody = """{"text":"$encodedText"}"""
            val body = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url("https://enc-dec.app/api/dec-kai")
                .post(body)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Content-Type", "application/json")
                .build()

            val response = httpClient.newCall(request).await()
            val jsonText = response.body?.string() ?: ""
            response.close()

            val urlStart = jsonText.indexOf("\"url\":\"")
            if (urlStart == -1) return null

            val urlValueStart = urlStart + 7
            val urlValueEnd = jsonText.indexOf("\"", urlValueStart)
            if (urlValueEnd == -1) return null

            jsonText.substring(urlValueStart, urlValueEnd)
                .replace("\\/", "/")
        } catch (e: Exception) {
            println("AnimeKai: Decode error: ${e.message}")
            null
        }
    }

    private suspend fun findWorkingDomain(): String {
        for (domain in DOMAIN_VALUES) {
            try {
                val request = Request.Builder()
                    .url(domain)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                val response = httpClient.newCall(request).await()
                response.close()
                println("AnimeKai: Using domain: $domain")
                return domain
            } catch (e: Exception) {
                println("AnimeKai: Domain $domain failed")
            }
        }
        return DOMAIN_VALUES.first()
    }

    override suspend fun onInitialize() {
        baseUrl = findWorkingDomain()
    }

    // ===== SearchFeedClient =====
    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        if (query.isBlank()) return emptyList<Shelf>().toFeed()

        return try {
            val request = Request.Builder()
                .url("$baseUrl/browser?keyword=$query&page=1")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "$baseUrl/")
                .build()

            val response = httpClient.newCall(request).await()
            val html = response.body?.string() ?: ""
            response.close()

            val document = Jsoup.parse(html)
            val albums = document.select("div.aitem-wrapper div.aitem").mapNotNull { el ->
                try {
                    val title = el.selectFirst("a.title")?.text() ?: return@mapNotNull null
                    val href = el.selectFirst("a.poster")?.attr("href") ?: return@mapNotNull null
                    val poster = el.selectFirst("a.poster img")?.attr("data-src")
                        ?: el.selectFirst("a.poster img")?.attr("src") ?: ""

                    val subSpan = el.selectFirst("div.info span.sub")?.text()
                    val dubSpan = el.selectFirst("div.info span.dub")?.text()

                    Album(
                        id = href,
                        title = title,
                        cover = poster.toImageHolder(),
                        subtitle = buildString {
                            if (subSpan != null) append("Sub: $subSpan ")
                            if (dubSpan != null) append("Dub: $dubSpan")
                        }.trim().takeIf { it.isNotEmpty() },
                        extras = mapOf("animeUrl" to href)
                    )
                } catch (e: Exception) {
                    null
                }
            }

            listOf(
                Shelf.Lists.Items(
                    id = "search",
                    title = "Results (${albums.size})",
                    list = albums,
                    type = Shelf.Lists.Type.Grid
                )
            ).toFeed()
        } catch (e: Exception) {
            println("AnimeKai: Search error: ${e.message}")
            emptyList<Shelf>().toFeed()
        }
    }

    // ===== HomeFeedClient =====
    override suspend fun loadHomeFeed(): Feed<Shelf> {
        return try {
            val shelves = mutableListOf<Shelf>()

            val categories = listOf(
                "Trending" to "$baseUrl/trending",
                "Latest" to "$baseUrl/updates"
            )

            for ((name, url) in categories) {
                try {
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .header("Referer", "$baseUrl/")
                        .build()

                    val response = httpClient.newCall(request).await()
                    val html = response.body?.string() ?: ""
                    response.close()

                    val document = Jsoup.parse(html)
                    val items = document.select("div.aitem-wrapper div.aitem").take(10).mapNotNull { el ->
                        try {
                            val title = el.selectFirst("a.title")?.text() ?: return@mapNotNull null
                            val href = el.selectFirst("a.poster")?.attr("href") ?: return@mapNotNull null
                            val poster = el.selectFirst("a.poster img")?.attr("data-src")
                                ?: el.selectFirst("a.poster img")?.attr("src") ?: ""

                            Album(id = href, title = title, cover = poster.toImageHolder(), extras = mapOf("animeUrl" to href))
                        } catch (e: Exception) {
                            null
                        }
                    }

                    if (items.isNotEmpty()) {
                        shelves.add(
                            Shelf.Lists.Items(
                                id = name.lowercase(),
                                title = name,
                                list = items,
                                type = Shelf.Lists.Type.Grid
                            )
                        )
                    }
                } catch (e: Exception) {
                    println("AnimeKai: Error $name")
                }
            }

            shelves.toFeed()
        } catch (e: Exception) {
            emptyList<Shelf>().toFeed()
        }
    }

    // ===== AlbumClient =====
    override suspend fun loadAlbum(album: Album): Album {
        return try {
            val animeUrl = album.extras?.get("animeUrl")?.toString() ?: album.id
            val fullUrl = if (animeUrl.startsWith("http")) animeUrl else "$baseUrl$animeUrl"

            val request = Request.Builder()
                .url(fullUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "$baseUrl/")
                .build()

            val response = httpClient.newCall(request).await()
            val html = response.body?.string() ?: ""
            response.close()

            val document = Jsoup.parse(html)
            val description = document.selectFirst("div.desc")?.text() ?: ""
            val animeId = document.selectFirst("div[data-id]")?.attr("data-id") ?: ""
            val poster = document.selectFirst(".poster img")?.attr("src") ?: ""

            val subCount = document.selectFirst("#main-entity div.info span.sub")?.text()?.toIntOrNull() ?: 0
            val dubCount = document.selectFirst("#main-entity div.info span.dub")?.text()?.toIntOrNull() ?: 0
            val totalEpisodes = if (subCount > dubCount) subCount else dubCount

            println("AnimeKai: Album ID: $animeId, Sub: $subCount, Dub: $dubCount")

            Album(
                id = album.id,
                title = album.title,
                cover = poster.toImageHolder(),
                subtitle = buildString {
                    if (subCount > 0) append("Sub: $subCount ")
                    if (dubCount > 0) append("Dub: $dubCount")
                }.trim().takeIf { it.isNotEmpty() },
                description = description,
                trackCount = totalEpisodes.toLong(),
                extras = mapOf(
                    "animeUrl" to animeUrl,
                    "animeId" to animeId,
                    "subCount" to subCount.toString(),
                    "dubCount" to dubCount.toString()
                )
            )
        } catch (e: Exception) {
            println("AnimeKai: Album error: ${e.message}")
            album
        }
    }

    override suspend fun loadTracks(album: Album): Feed<Track>? {
        return try {
            val animeId = album.extras?.get("animeId")?.toString()
            if (animeId.isNullOrBlank()) {
                println("AnimeKai: No anime ID")
                return emptyList<Track>().toFeed()
            }

            val enc = encDecEndpoints(animeId)
            val ajaxUrl = "$baseUrl/ajax/episodes/list?ani_id=$animeId&_=$enc"

            val request = Request.Builder()
                .url(ajaxUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "$baseUrl/")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()

            val response = httpClient.newCall(request).await()
            val jsonText = response.body?.string() ?: ""
            response.close()

            val htmlContent = parseResultResponse(jsonText)
            if (htmlContent == null) {
                println("AnimeKai: Failed to parse JSON")
                return emptyList<Track>().toFeed()
            }

            val epDoc = Jsoup.parse(htmlContent)
            val episodeElements = epDoc.select("div.eplist a")

            println("AnimeKai: Found ${episodeElements.size} episodes")

            if (episodeElements.isEmpty()) {
                return emptyList<Track>().toFeed()
            }

            val tracks = episodeElements.mapIndexed { index, el ->
                val token = el.attr("token")
                val num = el.attr("num")
                val langs = el.attr("langs").toIntOrNull() ?: 0

                val title = el.selectFirst("span")?.text()
                    ?: el.attr("title").takeIf { it.isNotBlank() }
                    ?: "Episode $num"

                val subdub = when (langs) {
                    1 -> "Sub"
                    3 -> "Dub & Sub"
                    else -> ""
                }

                Track(
                    id = token,
                    title = "Episode $num",
                    subtitle = title.takeIf { it != "Episode $num" },
                    album = album,
                    albumOrderNumber = num.toFloatOrNull()?.toLong() ?: (index + 1).toLong(),
                    cover = album.cover,
                    duration = 1000 * 60 * 24, // CRITICAL: Duration must be set for playability!
                    extras = mapOf(
                        "token" to token,
                        "episodeNumber" to num,
                        "type" to subdub
                    )
                )
            }

            println("AnimeKai: Loaded ${tracks.size} tracks")

            tracks.reversed().toFeed()

        } catch (e: Exception) {
            println("AnimeKai: Tracks error: ${e.message}")
            e.printStackTrace()
            emptyList<Track>().toFeed()
        }
    }

    override suspend fun loadFeed(album: Album): Feed<Shelf>? {
        return try {
            val animeUrl = album.extras?.get("animeUrl")?.toString() ?: album.id
            val fullUrl = if (animeUrl.startsWith("http")) animeUrl else "$baseUrl$animeUrl"

            val request = Request.Builder()
                .url(fullUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "$baseUrl/")
                .build()

            val response = httpClient.newCall(request).await()
            val html = response.body?.string() ?: ""
            response.close()

            val document = Jsoup.parse(html)
            val relatedElements = document.select("div.aitem-col a.aitem")

            val related = relatedElements.mapNotNull { link ->
                try {
                    val title = link.selectFirst("div.title")?.text() ?: return@mapNotNull null
                    val href = link.attr("href").takeIf { it.isNotEmpty() } ?: return@mapNotNull null

                    val style = link.attr("style")
                    val poster = when {
                        style.contains("url('") -> style.substringAfter("url('").substringBefore("')")
                        style.contains("url(\"") -> style.substringAfter("url(\"").substringBefore("\")")
                        style.contains("url(") -> style.substringAfter("url(").substringBefore(")")
                        else -> ""
                    }

                    Album(id = href, title = title, cover = poster.toImageHolder(), extras = mapOf("animeUrl" to href))
                } catch (e: Exception) {
                    null
                }
            }

            if (related.isEmpty()) return null

            listOf(
                Shelf.Lists.Items(
                    id = "related",
                    title = "Related Anime",
                    list = related.take(10),
                    type = Shelf.Lists.Type.Grid
                )
            ).toFeed()
        } catch (e: Exception) {
            null
        }
    }

    // ===== TrackClient =====
    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        println("AnimeKai: loadTrack called for: ${track.title}")
        return track
    }

    override suspend fun loadStreamableMedia(streamable: Streamable, isDownload: Boolean): Streamable.Media {
        println("AnimeKai: !!!! loadStreamableMedia CALLED !!!!")
        println("AnimeKai: Streamable ID: ${streamable.id}")

        val token = streamable.id

        try {
            val enc = encDecEndpoints(token)
            val serverListUrl = "$baseUrl/ajax/links/list?token=$token&_=$enc"

            println("AnimeKai: Fetching servers from: $serverListUrl")

            val request = Request.Builder()
                .url(serverListUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "$baseUrl/")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()

            val response = httpClient.newCall(request).await()
            val jsonText = response.body?.string() ?: ""
            response.close()

            val htmlContent = parseResultResponse(jsonText)
            if (htmlContent == null) {
                throw IllegalStateException("Failed to get server list")
            }

            val serverDoc = Jsoup.parse(htmlContent)

            // Get first server with data-lid attribute
            val firstServerElement = serverDoc.select("span.server[data-lid]").firstOrNull()
            if (firstServerElement == null) {
                throw IllegalStateException("No servers found in HTML")
            }

            val serverId = firstServerElement.attr("data-lid")
            val serverName = firstServerElement.text()
            val serverType = firstServerElement.parent()?.attr("data-id") ?: "sub"

            println("AnimeKai: Using server: $serverName [$serverType] (lid: $serverId)")

            val serverEnc = encDecEndpoints(serverId)
            val iframeApiUrl = "$baseUrl/ajax/links/view?id=$serverId&_=$serverEnc"

            println("AnimeKai: Fetching iframe from: $iframeApiUrl")

            val iframeRequest = Request.Builder()
                .url(iframeApiUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "$baseUrl/")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()

            val iframeResponse = httpClient.newCall(iframeRequest).await()
            val iframeJson = iframeResponse.body?.string() ?: ""
            iframeResponse.close()

            println("AnimeKai: Iframe JSON response: ${iframeJson.take(200)}...")

            val encodedIframe = parseResultResponse(iframeJson)
                ?: throw IllegalStateException("No iframe response")

            val decodedIframeUrl = decodeIframe(encodedIframe)
                ?: throw IllegalStateException("Failed to decode iframe")

            println("AnimeKai: Decoded iframe URL: $decodedIframeUrl")

            // Transform /e/ or /e2/ to /media/ endpoint
            val mediaUrl = decodedIframeUrl.replace("/e/", "/media/").replace("/e2/", "/media/")

            println("AnimeKai: Fetching from media URL: $mediaUrl")

            val mediaRequest = Request.Builder()
                .url(mediaUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", decodedIframeUrl) // Use iframe URL as referer
                .build()

            val mediaResponse = httpClient.newCall(mediaRequest).await()
            val mediaJson = mediaResponse.body?.string() ?: ""
            mediaResponse.close()

            println("AnimeKai: Media JSON: ${mediaJson.take(200)}...")

            // Parse the media response
            val mediaResult = parseResultResponse(mediaJson)

            println("AnimeKai: Media result: ${mediaResult?.take(200)}...")

            // Decode the media result to get actual m3u8 URL
            val finalVideoUrl = if (mediaResult != null) {
                // Decode again using dec-kai
                val decoded = decodeIframe(mediaResult)
                println("AnimeKai: Decoded media: ${decoded?.take(200)}...")

                // Try to extract URL from JSON
                if (decoded != null && decoded.contains("\"url\"")) {
                    decoded.substringAfter("\"url\":\"").substringBefore("\"").replace("\\/", "/")
                } else {
                    decoded ?: decodedIframeUrl
                }
            } else {
                decodedIframeUrl
            }

            println("AnimeKai: Final video URL: $finalVideoUrl")

            // Create network request with proper headers
            val networkRequest = NetworkRequest(
                url = finalVideoUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to decodedIframeUrl
                )
            )

            // Determine source type
            val sourceType = when {
                finalVideoUrl.contains(".m3u8") || finalVideoUrl.contains("playlist") -> Streamable.SourceType.HLS
                finalVideoUrl.contains(".mpd") -> Streamable.SourceType.DASH
                else -> Streamable.SourceType.Progressive
            }

            val source = Streamable.Source.Http(
                request = networkRequest,
                type = sourceType,
                quality = 720,
                title = "$serverName [$serverType]",
                isVideo = true
            )

            println("AnimeKai: ✅ Created source: ${source.title} (Type: $sourceType)")

            return Streamable.Media.Server(
                sources = listOf(source),
                merged = false
            )

        } catch (e: Exception) {
            println("AnimeKai: ❌ Error: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? = null
}