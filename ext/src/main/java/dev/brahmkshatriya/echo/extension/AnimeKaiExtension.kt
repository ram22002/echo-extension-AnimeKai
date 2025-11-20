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
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.NetworkRequest.Companion.toGetRequest
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toMedia
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingList
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.Settings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup

class AnimeKaiExtension : ExtensionClient, SearchFeedClient, HomeFeedClient, TrackClient, AlbumClient {

    private lateinit var settings: Settings

    override suspend fun getSettingItems(): List<Setting> {
        return listOf(
            SettingList(
                key = "preferred_domain",
                title = "Preferred Domain",
                entryTitles = listOf(
                    "animekai.to",
                    "animekai.cc",
                    "animekai.ac",
                    "anikai.to"
                ),
                entryValues = listOf("0", "1", "2", "3"),
                defaultEntryIndex = 0
            ),
            SettingList(
                key = "preferred_server",
                title = "Preferred Server",
                entryTitles = listOf(
                    "Auto (First Available)",
                    "Server 1",
                    "Server 2"
                ),
                entryValues = listOf("0", "1", "2"),
                defaultEntryIndex = 0
            ),
            SettingList(
                key = "preferred_type",
                title = "Preferred Language/Type",
                entryTitles = listOf(
                    "Auto (First Available)",
                    "Sub (Hardsub)",
                    "Softsub",
                    "Dub"
                ),
                entryValues = listOf("0", "1", "2", "3"),
                defaultEntryIndex = 0
            ),
            SettingList(
                key = "display_mode",
                title = "List Display Mode",
                entryTitles = listOf(
                    "Grid",
                    "Linear"
                ),
                entryValues = listOf("0", "1"),
                defaultEntryIndex = 0
            ),
            SettingSwitch(
                key = "crop_covers",
                title = "Crop Album Covers",
                summary = "Enable to crop album cover images to fill the space",
                defaultValue = false
            )
        )
    }

    override fun setSettings(settings: Settings) {
        this.settings = settings
        val preferredDomainValue = settings.getString("preferred_domain") ?: "0"
        val domains = listOf("animekai.to", "animekai.cc", "animekai.ac", "anikai.to")
        val domainIndex = preferredDomainValue.toIntOrNull() ?: 0
        baseUrl = "https://${domains.getOrElse(domainIndex) { domains[0] }}"
        println("AnimeKai: Settings updated - Using domain: $baseUrl")
    }

    private val httpClient = OkHttpClient()

    private var baseUrl = "https://animekai.to"

    // Helper function to create ImageHolder with crop setting
    private fun String.toImageHolderWithCrop(crop: Boolean = false): ImageHolder {
        return if (this.isNotEmpty()) {
            ImageHolder.NetworkRequestImageHolder(
                request = this.toGetRequest(),
                crop = crop
            )
        } else {
            ImageHolder.NetworkRequestImageHolder(
                request = "".toGetRequest(),
                crop = false
            )
        }
    }

    // Helper function to get display type from settings
    private fun getDisplayType(): Shelf.Lists.Type {
        val displayModeValue = settings.getString("display_mode") ?: "0"
        val displayModeIndex = displayModeValue.toIntOrNull() ?: 0
        return if (displayModeIndex == 1) Shelf.Lists.Type.Linear else Shelf.Lists.Type.Grid
    }

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

    override suspend fun onInitialize() {
        println("AnimeKai: Extension initialized with domain: $baseUrl")
    }

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        println("AnimeKai: 🔍 loadSearchFeed called - query: $query")
        if (query.isBlank()) return emptyList<Shelf>().toFeed()

        return try {
            val cropCovers = settings.getBoolean("crop_covers") ?: false
            val displayType = getDisplayType()

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
                        cover = poster.toImageHolderWithCrop(cropCovers),
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

            println("AnimeKai: ✅ Search found ${albums.size} results")

            listOf(
                Shelf.Lists.Items(
                    id = "search",
                    title = "Results (${albums.size})",
                    list = albums,
                    type = displayType
                )
            ).toFeed()
        } catch (e: Exception) {
            println("AnimeKai: ❌ Search error: ${e.message}")
            emptyList<Shelf>().toFeed()
        }
    }

    override suspend fun loadHomeFeed(): Feed<Shelf> {
        println("AnimeKai: 🏠 loadHomeFeed called")
        return try {
            val cropCovers = settings.getBoolean("crop_covers") ?: false
            val displayType = getDisplayType()
            val shelves = mutableListOf<Shelf>()

            val categories = listOf(
                "Trending" to "$baseUrl/trending",
                "Latest" to "$baseUrl/updates",
                "Recent" to "$baseUrl/recent",
                "Completed" to "$baseUrl/completed",
            )

            for ((name, url) in categories) {
                try {
                    println("AnimeKai: Loading $name from $url")
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

                            // Extract sub/dub counts from home feed items
                            val subSpan = el.selectFirst("div.info span.sub")?.text()
                            val dubSpan = el.selectFirst("div.info span.dub")?.text()

                            Album(
                                id = href,
                                title = title,
                                cover = poster.toImageHolderWithCrop(cropCovers),
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

                    if (items.isNotEmpty()) {
                        shelves.add(
                            Shelf.Lists.Items(
                                id = name.lowercase(),
                                title = name,
                                list = items,
                                type = displayType
                            )
                        )
                        println("AnimeKai: ✅ Loaded $name - ${items.size} items")
                    }
                } catch (e: Exception) {
                    println("AnimeKai: ❌ Error loading $name: ${e.message}")
                    continue
                }
            }

            println("AnimeKai: ✅ Home feed loaded - ${shelves.size} shelves")
            shelves.toFeed()
        } catch (e: Exception) {
            println("AnimeKai: ❌ Home feed error: ${e.message}")
            emptyList<Shelf>().toFeed()
        }
    }

    override suspend fun loadAlbum(album: Album): Album {
        println("AnimeKai: 📀 loadAlbum called - ${album.title}")
        return try {
            val animeUrl = album.extras?.get("animeUrl")?.toString() ?: album.id
            val fullUrl = if (animeUrl.startsWith("http")) animeUrl else "$baseUrl$animeUrl"

            println("AnimeKai: Fetching album from: $fullUrl")

            val request = Request.Builder()
                .url(fullUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "$baseUrl/")
                .build()

            val response = httpClient.newCall(request).await()
            val html = response.body?.string() ?: ""
            response.close()

            val document = Jsoup.parse(html)

            val poster = document.selectFirst(".poster img")?.attr("src") ?: ""
            val animeId = document.selectFirst("div[data-id]")?.attr("data-id") ?: ""

            val mainEntity = document.selectFirst("div#main-entity")
            val detail = mainEntity?.selectFirst("div.detail")

            // Get episode counts
            val subCount = mainEntity?.selectFirst("div.info span.sub")?.text()?.toIntOrNull() ?: 0
            val dubCount = mainEntity?.selectFirst("div.info span.dub")?.text()?.toIntOrNull() ?: 0
            val totalEpisodes = if (subCount > dubCount) subCount else dubCount

            println("AnimeKai: ✅ Album loaded - ID: $animeId, Sub: $subCount, Dub: $dubCount")

            // Get synopsis
            val synopsis = mainEntity?.selectFirst(".desc")?.text()?.trim() ?: ""

            // Get title and alternative titles
            val titleElement = mainEntity?.selectFirst("h1.title")
            val mainTitle = titleElement?.text()?.trim() ?: album.title
            val altTitle = mainEntity?.selectFirst(".al-title")?.text()?.trim() ?: ""

            // Get metadata from info items
            val infoItems = detail?.select("div.item") ?: emptyList()
            val metadata = mutableMapOf<String, String>()

            infoItems.forEach { item ->
                val label = item.selectFirst("div.name")?.text()?.trim() ?: ""
                val value = item.selectFirst("div.value")?.let { valueDiv ->
                    val links = valueDiv.select("a")
                    if (links.isNotEmpty()) {
                        links.joinToString(", ") { it.text().trim() }
                    } else {
                        valueDiv.text().trim()
                    }
                } ?: ""

                if (label.isNotEmpty() && value.isNotEmpty()) {
                    metadata[label] = value
                }
            }

            // Get rating
            val rating = mainEntity?.selectFirst(".rating")?.text()?.trim() ?: ""

            // Get external links
            val externalLinks = detail?.select("div div div:contains(Links:) a")?.joinToString("\n") { link ->
                "  [${link.text()}](${link.attr("href")})"
            } ?: ""

            // Build enhanced description with sub/dub count at top
            val enhancedDescription = buildString {
                // Episode availability at the very top
                append("📺 **Episodes Available:**\n")
                if (subCount > 0) append("  • Subtitled: $subCount episodes\n")
                if (dubCount > 0) append("  • Dubbed: $dubCount episodes\n")
                append("\n")

                // Synopsis
                if (synopsis.isNotEmpty()) {
                    append("**Synopsis:**\n")
                    append(synopsis)
                    append("\n\n")
                }

                // Metadata
                metadata["Country:"]?.let { append("**Country:** $it\n") }
                metadata["Premiered:"]?.let { append("**Premiered:** $it\n") }
                metadata["Date aired:"]?.let { append("**Date aired:** $it\n") }
                metadata["Broadcast:"]?.let { append("**Broadcast:** $it\n") }
                metadata["Duration:"]?.let { append("**Duration:** $it\n") }
                metadata["Studios:"]?.let { append("**Studios:** $it\n") }
                metadata["Producers:"]?.let { append("**Producers:** $it\n") }
                metadata["Genres:"]?.let { append("**Genres:** $it\n") }
                metadata["Status:"]?.let { append("**Status:** $it\n") }
                if (rating.isNotEmpty()) append("**Rating:** $rating\n")
                metadata["MAL Score:"]?.let { append("**MAL:** $it\n") }

                // Alternative title
                if (altTitle.isNotEmpty()) {
                    append("**Alternative Title:** $altTitle\n")
                }

                // External links
                if (externalLinks.isNotEmpty()) {
                    append("\n**Links:**\n")
                    append(externalLinks)
                }

                // Cover image
                if (poster.isNotEmpty()) {
                    append("\n\n![Cover]($poster)")
                }
            }

            // Get crop setting
            val cropCovers = settings.getBoolean("crop_covers") ?: false

            Album(
                id = album.id,
                title = mainTitle,
                cover = poster.toImageHolderWithCrop(cropCovers),
                subtitle = buildString {
                    metadata["Status:"]?.let { append("$it • ") }
                    if (subCount > 0) append("Sub: $subCount ")
                    if (dubCount > 0) append("Dub: $dubCount")
                }.trim().takeIf { it.isNotEmpty() },
                description = enhancedDescription,
                trackCount = totalEpisodes.toLong(),
                extras = mapOf(
                    "animeUrl" to animeUrl,
                    "animeId" to animeId,
                    "subCount" to subCount.toString(),
                    "dubCount" to dubCount.toString()
                )
            )
        } catch (e: Exception) {
            println("AnimeKai: ❌ Album error: ${e.message}")
            e.printStackTrace()
            album
        }
    }

    override suspend fun loadTracks(album: Album): Feed<Track>? {
        println("AnimeKai: 🎬 loadTracks called - ${album.title}")
        return try {
            val animeId = album.extras?.get("animeId")?.toString()
            if (animeId.isNullOrBlank()) {
                println("AnimeKai: ❌ No anime ID found")
                return emptyList<Track>().toFeed()
            }

            val enc = encDecEndpoints(animeId)
            val ajaxUrl = "$baseUrl/ajax/episodes/list?ani_id=$animeId&_=$enc"

            println("AnimeKai: Fetching episodes from: $ajaxUrl")

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

            println("AnimeKai: ✅ Found ${episodeElements.size} episodes")

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

            println("AnimeKai: ✅ Loaded ${tracks.size} tracks")
            tracks.toFeed()

        } catch (e: Exception) {
            println("AnimeKai: ❌ Tracks error: ${e.message}")
            emptyList<Track>().toFeed()
        }
    }

    override suspend fun loadFeed(album: Album): Feed<Shelf>? {
        return try {
            val cropCovers = settings.getBoolean("crop_covers") ?: false
            val displayType = getDisplayType()
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

                    Album(
                        id = href,
                        title = title,
                        cover = poster.toImageHolderWithCrop(cropCovers),
                        extras = mapOf("animeUrl" to href)
                    )
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
                    type = displayType
                )
            ).toFeed()
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        println("AnimeKai: ▶️ loadTrack called - ${track.title}")
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

            if (serverElements.isEmpty()) {
                println("AnimeKai: ❌ No servers found")
                return track
            }

            println("AnimeKai: Found ${serverElements.size} servers")

            // Get user preferences
            val preferredServerValue = settings.getString("preferred_server") ?: "0"
            val preferredTypeValue = settings.getString("preferred_type") ?: "0"

            val serverOptions = listOf("Auto (First Available)", "Server 1", "Server 2")
            val typeOptions = listOf("Auto (First Available)", "Sub (Hardsub)", "Softsub", "Dub")

            val preferredServerIndex = preferredServerValue.toIntOrNull() ?: 0
            val preferredTypeIndex = preferredTypeValue.toIntOrNull() ?: 0

            val preferredServer = serverOptions.getOrElse(preferredServerIndex) { serverOptions[0] }
            val preferredType = typeOptions.getOrElse(preferredTypeIndex) { typeOptions[0] }

            println("AnimeKai: User preferences - Server: $preferredServer, Type: $preferredType")

            // Filter servers based on user preferences
            var filteredServers = serverElements.toList()

            // Filter by type/language preference
            if (preferredType != "Auto (First Available)") {
                filteredServers = filteredServers.filter { serverElement ->
                    val serverType = serverElement.parent()?.attr("data-id") ?: ""
                    when (preferredType) {
                        "Sub (Hardsub)" -> serverType.equals("sub", ignoreCase = true)
                        "Softsub" -> serverType.equals("softsub", ignoreCase = true)
                        "Dub" -> serverType.equals("dub", ignoreCase = true)
                        else -> true
                    }
                }
            }

            // Filter by server preference
            if (preferredServer != "Auto (First Available)" && filteredServers.isNotEmpty()) {
                val serverName = preferredServer.replace("Server ", "")
                filteredServers = filteredServers.filter { serverElement ->
                    val name = serverElement.text()
                    name.contains(serverName, ignoreCase = true)
                }.ifEmpty { filteredServers }
            }

            // If no servers match preferences, use all servers
            if (filteredServers.isEmpty()) {
                filteredServers = serverElements.toList()
            }

            println("AnimeKai: After filtering: ${filteredServers.size} servers available")

            val streamables = filteredServers.mapIndexed { index, serverElement ->
                val serverId = serverElement.attr("data-lid")
                val serverName = serverElement.text()
                val serverType = serverElement.parent()?.attr("data-id") ?: "sub"

                Streamable.server(
                    id = serverId,
                    quality = 720 - (index * 100),
                    title = "$serverName [$serverType]"
                )
            }

            println("AnimeKai: ✅ Created ${streamables.size} streamables")
            track.copy(streamables = streamables)

        } catch (e: Exception) {
            println("AnimeKai: ❌ loadTrack error: ${e.message}")
            track
        }
    }

    override suspend fun loadStreamableMedia(streamable: Streamable, isDownload: Boolean): Streamable.Media {
        println("AnimeKai: 🎥 loadStreamableMedia called - ${streamable.title}")
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

        println("AnimeKai: Iframe URL: $decodedIframeUrl")

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

        println("AnimeKai: ✅ Video URL: $videoUrl")

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