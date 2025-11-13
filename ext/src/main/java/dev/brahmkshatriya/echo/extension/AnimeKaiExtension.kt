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
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings
import okhttp3.OkHttpClient
import okhttp3.Request
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

    // Manual JSON parser for simple {"status":true,"result":"html"} format
    private fun extractJsonResult(jsonString: String): String? {
        try {
            // Find the "result" field
            val resultIndex = jsonString.indexOf("\"result\"")
            if (resultIndex == -1) {
                println("AnimeKai: No 'result' field found in JSON")
                return null
            }

            // Find the opening quote of the result value
            val valueStart = jsonString.indexOf("\"", resultIndex + 8)
            if (valueStart == -1) return null

            // Extract the string value, handling escaped characters
            val chars = jsonString.toCharArray()
            val result = StringBuilder()
            var i = valueStart + 1
            var escaped = false

            while (i < chars.size) {
                val char = chars[i]
                when {
                    escaped -> {
                        // Handle escape sequences
                        when (char) {
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            '"' -> result.append('"')
                            '\\' -> result.append('\\')
                            '/' -> result.append('/')
                            else -> {
                                result.append('\\')
                                result.append(char)
                            }
                        }
                        escaped = false
                    }
                    char == '\\' -> escaped = true
                    char == '"' -> break // End of string value
                    else -> result.append(char)
                }
                i++
            }

            return result.toString()
        } catch (e: Exception) {
            println("AnimeKai: JSON parse error: ${e.message}")
            return null
        }
    }

    private suspend fun decode(text: String?): String {
        // Simple timestamp for now - replace with actual decode service if needed
        return System.currentTimeMillis().toString()
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
                println("AnimeKai: Domain $domain failed: ${e.message}")
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
            val searchUrl = "$baseUrl/browser?keyword=$query&page=1"

            val request = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", baseUrl)
                .build()

            val response = httpClient.newCall(request).await()
            val html = response.body?.string() ?: ""
            response.close()

            val document = Jsoup.parse(html)
            val animeAlbums = document.select("div.aitem-wrapper div.aitem").mapNotNull { element ->
                try {
                    val title = element.selectFirst("a.title")?.text()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val href = element.selectFirst("a.poster")?.attr("href")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val posterUrl = element.selectFirst("a.poster img")?.attr("data-src")
                        ?: element.selectFirst("a.poster img")?.attr("src") ?: ""

                    val subSpan = element.selectFirst("div.info span.sub")?.text()
                    val dubSpan = element.selectFirst("div.info span.dub")?.text()

                    Album(
                        id = href,
                        title = title,
                        cover = posterUrl.toImageHolder(),
                        subtitle = buildString {
                            if (subSpan != null) append("Sub: $subSpan ")
                            if (dubSpan != null) append("Dub: $dubSpan")
                        }.trim().takeIf { it.isNotEmpty() },
                        extras = mapOf("animeUrl" to href, "posterUrl" to posterUrl)
                    )
                } catch (e: Exception) {
                    null
                }
            }

            listOf(
                Shelf.Lists.Items(
                    id = "search",
                    title = "Results (${animeAlbums.size})",
                    list = animeAlbums,
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
                "Trending" to "$baseUrl/browser?keyword=&status[]=releasing&sort=trending",
                "Latest" to "$baseUrl/browser?keyword=&status[]=releasing&sort=updated_date"
            )

            for ((categoryName, categoryUrl) in categories) {
                try {
                    val request = Request.Builder()
                        .url(categoryUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .header("Referer", baseUrl)
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

                            Album(
                                id = href,
                                title = title,
                                cover = poster.toImageHolder(),
                                extras = mapOf("animeUrl" to href, "posterUrl" to poster)
                            )
                        } catch (e: Exception) {
                            null
                        }
                    }

                    if (items.isNotEmpty()) {
                        shelves.add(
                            Shelf.Lists.Items(
                                id = categoryName.lowercase(),
                                title = categoryName,
                                list = items,
                                type = Shelf.Lists.Type.Grid
                            )
                        )
                    }
                } catch (e: Exception) {
                    println("AnimeKai: Error loading $categoryName")
                }
            }

            shelves.toFeed()
        } catch (e: Exception) {
            println("AnimeKai: Home error: ${e.message}")
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
                .header("Referer", baseUrl)
                .build()

            val response = httpClient.newCall(request).await()
            val html = response.body?.string() ?: ""
            response.close()

            val document = Jsoup.parse(html)

            val description = document.selectFirst("div.desc")?.text() ?: ""
            val animeId = document.selectFirst("div.rate-box")?.attr("data-id") ?: ""

            val subCount = document.selectFirst("#main-entity div.info span.sub")?.text()?.toIntOrNull() ?: 0
            val dubCount = document.selectFirst("#main-entity div.info span.dub")?.text()?.toIntOrNull() ?: 0
            val totalEpisodes = if (subCount > dubCount) subCount else dubCount

            val posterUrl = album.extras?.get("posterUrl")?.toString() ?: ""

            println("AnimeKai: Album loaded - ID: $animeId, Episodes: $totalEpisodes (Sub: $subCount, Dub: $dubCount)")

            Album(
                id = album.id,
                title = album.title,
                cover = posterUrl.toImageHolder(),
                subtitle = album.subtitle,
                description = description,
                trackCount = totalEpisodes.toLong(),
                extras = mapOf(
                    "animeUrl" to animeUrl,
                    "posterUrl" to posterUrl,
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

            val decoded = decode(animeId)
            val ajaxUrl = "$baseUrl/ajax/episodes/list?ani_id=$animeId&_=$decoded"

            println("AnimeKai: Fetching episodes from: $ajaxUrl")

            val request = Request.Builder()
                .url(ajaxUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", baseUrl)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Accept", "application/json")
                .build()

            val response = httpClient.newCall(request).await()
            val jsonText = response.body?.string() ?: ""
            response.close()

            println("AnimeKai: Response: ${jsonText.take(100)}...")

            // Extract HTML from JSON
            val htmlContent = extractJsonResult(jsonText)
            if (htmlContent == null) {
                println("AnimeKai: Failed to extract JSON result")
                return emptyList<Track>().toFeed()
            }

            println("AnimeKai: Extracted HTML (${htmlContent.length} chars)")

            // Parse episodes from HTML
            val epDoc = Jsoup.parse(htmlContent)
            val episodeElements = epDoc.select("div.eplist a")

            println("AnimeKai: Found ${episodeElements.size} episodes")

            if (episodeElements.isEmpty()) {
                println("AnimeKai: No episodes found!")
                println("AnimeKai: HTML content: ${htmlContent.take(500)}")
                return emptyList<Track>().toFeed()
            }

            val subCount = album.extras?.get("subCount")?.toString()?.toIntOrNull() ?: 0

            val tracks = episodeElements.mapIndexed { index, element ->
                try {
                    val episodeNum = index + 1
                    val token = element.attr("token")
                    val num = element.attr("num")
                    val title = element.selectFirst("span")?.text()
                        ?: element.attr("title").takeIf { it.isNotBlank() }
                        ?: "Episode $episodeNum"

                    val source = if (index < subCount) "sub" else "dub"

                    Track(
                        id = "$source|$token",
                        title = title,
                        subtitle = "Ep $episodeNum (${source.uppercase()})",
                        album = album,
                        albumOrderNumber = episodeNum.toLong(),
                        cover = album.cover,
                        extras = mapOf(
                            "token" to token,
                            "source" to source,
                            "episodeNumber" to episodeNum.toString()
                        )
                    )
                } catch (e: Exception) {
                    println("AnimeKai: Episode parse error: ${e.message}")
                    null
                }
            }.filterNotNull()

            println("AnimeKai: Loaded ${tracks.size} tracks")
            tracks.toFeed()

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

            println("AnimeKai: Loading related anime from: $fullUrl")

            val request = Request.Builder()
                .url(fullUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", baseUrl)
                .build()

            val response = httpClient.newCall(request).await()
            val html = response.body?.string() ?: ""
            response.close()

            val document = Jsoup.parse(html)

            // Get related anime - selector from CloudStream/Aniyomi
            val relatedElements = document.select("div.aitem-col a")
            println("AnimeKai: Found ${relatedElements.size} related anime elements")

            val relatedAnime = relatedElements.mapNotNull { link ->
                try {
                    val title = link.selectFirst("div.title")?.text() ?: return@mapNotNull null
                    val href = link.attr("href").takeIf { it.isNotEmpty() } ?: return@mapNotNull null

                    // Extract poster from style attribute: background-image:url('...')
                    val styleAttr = link.attr("style")
                    val posterUrl = if (styleAttr.contains("url(")) {
                        styleAttr.substringAfter("url('").substringBefore("')")
                    } else {
                        ""
                    }

                    println("AnimeKai: Related - $title -> $href")

                    Album(
                        id = href,
                        title = title,
                        cover = posterUrl.toImageHolder(),
                        extras = mapOf(
                            "animeUrl" to href,
                            "posterUrl" to posterUrl
                        )
                    )
                } catch (e: Exception) {
                    println("AnimeKai: Related parse error: ${e.message}")
                    null
                }
            }

            println("AnimeKai: Parsed ${relatedAnime.size} related anime")

            if (relatedAnime.isEmpty()) {
                println("AnimeKai: No related anime found")
                return null
            }

            val shelf = Shelf.Lists.Items(
                id = "related",
                title = "Related Anime",
                list = relatedAnime.take(10),
                type = Shelf.Lists.Type.Grid
            )

            listOf(shelf).toFeed()
        } catch (e: Exception) {
            println("AnimeKai: Related error: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    // ===== TrackClient =====
    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track = track

    override suspend fun loadStreamableMedia(
        streamable: Streamable,
        isDownload: Boolean
    ): Streamable.Media {
        throw IllegalStateException("Video playback not implemented yet")
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? = null
}