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
import dev.brahmkshatriya.echo.common.models.NetworkRequest.Companion.toGetRequest
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toMedia
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

            jsonText.substring(urlValueStart, urlValueEnd).replace("\\/", "/")
        } catch (e: Exception) {
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
                return domain
            } catch (e: Exception) {
                continue
            }
        }
        return DOMAIN_VALUES.first()
    }

    override suspend fun onInitialize() {
        baseUrl = findWorkingDomain()
    }

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
            emptyList<Shelf>().toFeed()
        }
    }

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
                    continue
                }
            }

            shelves.toFeed()
        } catch (e: Exception) {
            emptyList<Shelf>().toFeed()
        }
    }

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
            album
        }
    }

    override suspend fun loadTracks(album: Album): Feed<Track>? {
        return try {
            val animeId = album.extras?.get("animeId")?.toString()
            if (animeId.isNullOrBlank()) return emptyList<Track>().toFeed()

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

            val htmlContent = parseResultResponse(jsonText) ?: return emptyList<Track>().toFeed()

            val epDoc = Jsoup.parse(htmlContent)
            val episodeElements = epDoc.select("div.eplist a")

            if (episodeElements.isEmpty()) return emptyList<Track>().toFeed()

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
                    duration = 1000 * 60 * 24,
                    extras = mapOf(
                        "token" to token,
                        "episodeNumber" to num,
                        "type" to subdub
                    )
                )
            }

            tracks.reversed().toFeed()

        } catch (e: Exception) {
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

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        val token = track.extras?.get("token")?.toString() ?: track.id

        return try {
            val enc = encDecEndpoints(token)
            val serverListUrl = "$baseUrl/ajax/links/list?token=$token&_=$enc"

            val request = Request.Builder()
                .url(serverListUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "$baseUrl/")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()

            val response = httpClient.newCall(request).await()
            val jsonText = response.body?.string() ?: ""
            response.close()

            val htmlContent = parseResultResponse(jsonText) ?: return track

            val serverDoc = Jsoup.parse(htmlContent)
            val serverElements = serverDoc.select("span.server[data-lid]")

            if (serverElements.isEmpty()) return track

            val streamables = serverElements.mapIndexed { index, serverElement ->
                val serverId = serverElement.attr("data-lid")
                val serverName = serverElement.text()
                val serverType = serverElement.parent()?.attr("data-id") ?: "sub"

                Streamable.server(
                    id = serverId,
                    quality = 720 - (index * 100),
                    title = "$serverName [$serverType]"
                )
            }

            track.copy(streamables = streamables)

        } catch (e: Exception) {
            track
        }
    }

    override suspend fun loadStreamableMedia(streamable: Streamable, isDownload: Boolean): Streamable.Media {
        val serverId = streamable.id

        val serverEnc = encDecEndpoints(serverId)
        val iframeApiUrl = "$baseUrl/ajax/links/view?id=$serverId&_=$serverEnc"

        val iframeRequest = Request.Builder()
            .url(iframeApiUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", "$baseUrl/")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()

        val iframeResponse = httpClient.newCall(iframeRequest).await()
        val iframeJson = iframeResponse.body?.string() ?: ""
        iframeResponse.close()

        val encodedIframe = parseResultResponse(iframeJson)
            ?: throw IllegalStateException("No iframe response")

        val decodedIframeUrl = decodeIframe(encodedIframe)
            ?: throw IllegalStateException("Failed to decode iframe")

        val mediaUrl = decodedIframeUrl.replace("/e/", "/media/").replace("/e2/", "/media/")

        val mediaRequest = Request.Builder()
            .url(mediaUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0")
            .header("Accept", "text/html, */*; q=0.01")
            .header("Referer", "$baseUrl/")
            .build()

        val mediaResponse = httpClient.newCall(mediaRequest).await()
        val mediaJson = mediaResponse.body?.string() ?: ""
        mediaResponse.close()

        val encodedResult = parseResultResponse(mediaJson)
            ?: throw IllegalStateException("No encoded result from media")

        val decodedMedia = decodeMediaResult(encodedResult)
            ?: throw IllegalStateException("Failed to decode media result")

        val videoUrl = extractM3u8FromJson(decodedMedia)
            ?: throw IllegalStateException("No video URL found in decoded media")

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Referer" to decodedIframeUrl
        )

        return Streamable.Source.Http(
            request = videoUrl.toGetRequest(headers),
            type = Streamable.SourceType.HLS,
            quality = streamable.quality,
            title = streamable.title,
            isVideo = true
        ).toMedia()
    }

    private suspend fun decodeMediaResult(encodedText: String): String? {
        return try {
            val jsonBody = """{"text":"$encodedText","agent":"Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0"}"""
            val body = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

            val endpoints = listOf(
                "https://enc-dec.app/api/dec-megaup",
                "https://enc-dec.app/api/dec-mega",
                "https://enc-dec.app/api/decode"
            )

            for (endpoint in endpoints) {
                try {
                    val request = Request.Builder()
                        .url(endpoint)
                        .post(body)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .header("Content-Type", "application/json")
                        .build()

                    val response = httpClient.newCall(request).await()
                    val jsonText = response.body?.string() ?: ""
                    response.close()

                    val result = parseDecodedMediaResponse(jsonText)
                    if (result != null) return result
                } catch (e: Exception) {
                    continue
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun parseDecodedMediaResponse(jsonText: String): String? {
        val resultStart = jsonText.indexOf("\"result\":")
        if (resultStart == -1) return null

        var pos = resultStart + 9
        while (pos < jsonText.length && jsonText[pos].isWhitespace()) pos++

        if (pos >= jsonText.length || jsonText[pos] != '{') return null

        var braceCount = 0
        val resultJson = StringBuilder()

        while (pos < jsonText.length) {
            val char = jsonText[pos]
            resultJson.append(char)

            if (char == '{') braceCount++
            else if (char == '}') {
                braceCount--
                if (braceCount == 0) break
            }
            pos++
        }

        return resultJson.toString()
    }

    private fun extractM3u8FromJson(jsonText: String): String? {
        Regex(""""sources"\s*:\s*\[\s*\{\s*"file"\s*:\s*"([^"]+)"""").find(jsonText)?.let {
            return it.groupValues[1].replace("\\/", "/")
        }

        Regex(""""sources"\s*:\s*\[\s*"([^"]+)"""").find(jsonText)?.let {
            return it.groupValues[1].replace("\\/", "/")
        }

        Regex(""""file"\s*:\s*"([^"]+)"""").find(jsonText)?.let {
            return it.groupValues[1].replace("\\/", "/")
        }

        Regex("""(https?:[^"\\]+\.m3u8[^"\\]*)""").find(jsonText)?.let {
            return it.groupValues[1].replace("\\/", "/")
        }

        return null
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? = null
}