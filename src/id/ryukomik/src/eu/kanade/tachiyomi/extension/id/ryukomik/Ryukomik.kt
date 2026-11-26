package eu.kanade.tachiyomi.extension.id.ryukomik

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.source.SMangaUpdate
import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParseDate
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar

@Source
abstract class Ryukomik : KeiSource() {

    private val apiUrl = "https://api.ryukomik.com"

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(3)

    override fun Headers.Builder.configureHeaders(): Headers.Builder = set("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")

    // ============================== Popular ===============================
    // Full catalog via the komiku list endpoint (the only /list endpoint);
    // 50 titles per page, hasNextPage from meta.totalPages.
    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$apiUrl/komiku/list".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .build()

        val res = client.get(url, Fetch.XHR).parseAs<KomikuListResponse>()
        return MangasPage(res.data.map { it.toSManga() }, res.meta.currentPage < res.meta.totalPages)
    }

    // =============================== Latest ===============================
    // Merged "terbaru" from both home endpoints; a single page.
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)

        val komiku = client.get("$apiUrl/komiku/home", Fetch.XHR).parseAs<KomikuHomeResponse>()
        val kiryuu = client.get("$apiUrl/kiryuu/home", Fetch.XHR).parseAs<KiryuuHomeResponse>()

        val mangas = komiku.data.terbaru.map { it.toSManga() } +
            kiryuu.data.terbaru.map { it.toSManga() }
        return MangasPage(mangas, false)
    }

    // =============================== Search ===============================
    // Merged API searches; the same title can appear twice (once per source)
    // with different detail URLs, so no dedup is applied.
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        if (query.isBlank()) return getPopularManga(1)

        val komiku = client.get(searchUrl("komiku", query), Fetch.XHR).parseAs<SearchResponse>()
        val kiryuu = client.get(searchUrl("kiryuu", query), Fetch.XHR).parseAs<SearchResponse>()

        val mangas = komiku.data.map { it.toSManga("komiku") } +
            kiryuu.data.map { it.toSManga("kiryuu") }
        return MangasPage(mangas, false)
    }

    private fun searchUrl(source: String, query: String) = "$apiUrl/$source/search".toHttpUrl().newBuilder()
        .addQueryParameter("q", query)
        .build()

    // ======================= Details and Chapters ==========================
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val html = client.get(baseUrl + manga.url, Fetch.DOCUMENT).bodyString()
        return SMangaUpdate(
            manga = parseDetails(html, manga),
            chapters = parseChapters(html, manga.url),
        )
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        if (url.pathSegments.firstOrNull() != "komik") return null
        val source = url.pathSegments.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
        val slug = url.pathSegments.getOrNull(2)?.takeIf { it.isNotEmpty() } ?: return null
        val targetUrl = "$baseUrl/komik/$source/$slug"
        val html = client.get(targetUrl, Fetch.DOCUMENT).bodyString()
        val manga = SManga.create().apply { setUrlWithoutDomain(targetUrl) }
        return parseDetails(html, manga)
    }

    // The detail page is Next.js: title/status/genres/synopsis/chapters ride in
    // a flight-data JSON object ("data":{"title":...,"chapters":[...]}). The
    // HTML is read as a raw string and unescaped first; if that object ever
    // disappears, plain HTML selectors are the fallback.
    private fun parseDetails(html: String, manga: SManga): SManga {
        val unescaped = html.replace("\\\"", "\"")
        val start = unescaped.indexOf("\"data\":{\"title\":")
        if (start < 0) return parseDetailsFallback(html, manga)

        val data = unescaped.substring(start)
        return manga.apply {
            title = data.stringField("title") ?: title
            thumbnail_url = data.stringField("thumbnail")
            author = data.stringField("Pengarang")?.takeIf { it != "-" }
            status = parseStatus(data.stringField("status"))
            genre = Regex("\"genres\":\\[([^\\]]*)\\]").find(data)
                ?.groupValues?.get(1)
                ?.let { Regex("\"([^\"]+)\"").findAll(it).map { m -> m.groupValues[1] }.joinToString() }
                ?.takeIf { it.isNotEmpty() }
            description = data.stringField("synopsis")?.unescapeJson()
        }
    }

    private fun parseDetailsFallback(html: String, manga: SManga): SManga {
        val document = Jsoup.parse(html, baseUrl)
        return manga.apply {
            title = document.selectFirst("h1")?.text() ?: title
            genre = document.select("a[href^=\"/genre/\"]").eachText().joinToString().takeIf { it.isNotEmpty() }
        }
    }

    private fun parseChapters(html: String, mangaUrl: String): List<SChapter> {
        val source = mangaUrl.split("/").getOrNull(2) ?: return emptyList()
        val unescaped = html.replace("\\\"", "\"")
        val start = unescaped.indexOf("\"chapters\":[")
        if (start < 0) return parseChaptersFallback(html)

        val chapterRegex = Regex(
            "\\{\"title\":\"([^\"]*)\"," +
                "(?:\"slug\":\"([^\"]*)\",\"link\":\"([^\"]*)\"|\"link\":\"([^\"]*)\",\"slug\":\"([^\"]*)\")," +
                "\"date\":\"([^\"]*)\"\\}",
        )
        return chapterRegex.findAll(unescaped.substring(start)).map { match ->
            val slug = match.groupValues[2].ifEmpty { match.groupValues[5] }
            SChapter.create().apply {
                setUrlWithoutDomain("/chapter/$source/$slug")
                name = match.groupValues[1]
                date_upload = parseChapterDate(match.groupValues[6])
            }
        }.distinctBy { it.url }.toList()
    }

    private fun parseChaptersFallback(html: String): List<SChapter> {
        val document = Jsoup.parse(html, baseUrl)
        return document.select("a[href^=\"/chapter/\"]").map { element ->
            SChapter.create().apply {
                setUrlWithoutDomain(element.absUrl("href"))
                name = element.text().ifEmpty { element.attr("href").substringAfterLast("/").replace("-", " ") }
            }
        }.distinctBy { it.url }
    }

    private fun parseStatus(status: String?): Int {
        val s = status?.lowercase() ?: return SManga.UNKNOWN
        return when {
            "ongoing" in s || "on going" in s -> SManga.ONGOING
            "completed" in s || "tamat" in s || "selesai" in s -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    private fun parseChapterDate(date: String): Long {
        val trimmed = date.trim()
        return if (trimmed.contains("lalu") || trimmed.contains("ago")) {
            parseRelativeDate(trimmed)
        } else {
            dateFormat.tryParseDate(trimmed, ZoneId.of("Asia/Jakarta"))
        }
    }

    private val dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    // Chapter dates come in two shapes: kiryuu "33 menit lalu", komiku
    // "21/07/2026". The relative form also appears in English ("42 minutes
    // ago") in the home API's info field.
    private fun parseRelativeDate(date: String): Long {
        val trimmedDate = date.substringBefore(" lalu").substringBefore(" ago").trim().split(" ")
        if (trimmedDate.size < 2) return 0L
        val amount = trimmedDate[0].toIntOrNull() ?: return 0L

        val calendar = Calendar.getInstance()
        when (trimmedDate[1].lowercase()) {
            "detik", "second", "seconds" -> calendar.add(Calendar.SECOND, -amount)
            "menit", "minute", "minutes" -> calendar.add(Calendar.MINUTE, -amount)
            "jam", "hour", "hours" -> calendar.add(Calendar.HOUR_OF_DAY, -amount)
            "hari", "day", "days" -> calendar.add(Calendar.DAY_OF_YEAR, -amount)
            "minggu", "week", "weeks" -> calendar.add(Calendar.WEEK_OF_YEAR, -amount)
            "bulan", "month", "months" -> calendar.add(Calendar.MONTH, -amount)
            "tahun", "year", "years" -> calendar.add(Calendar.YEAR, -amount)
        }

        return calendar.timeInMillis
    }

    // =============================== Pages ================================
    // Chapter pages embed the image list as a JSON array in the flight data:
    // "images":["https://...","https://..."].
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = baseUrl + chapter.url
        val html = client.get(chapterUrl, Fetch.DOCUMENT).bodyString().replace("\\\"", "\"")
        val start = html.indexOf("\"images\":[")
        if (start < 0) return emptyList()
        val end = html.indexOf("]", start)
        val urls = Regex("\"(https?://[^\"]+)\"")
            .findAll(html.substring(start, end))
            .map { it.groupValues[1] }
            .distinct()
            .toList()
        return urls.mapIndexed { i, url -> Page(i, chapterUrl, imageUrl = url) }
    }

    override fun getImageRequest(page: Page): Request {
        val headers = headersBuilder()
            .set("Referer", page.url)
            .build()
        return GET(page.imageUrl!!, headers)
    }

    // ============================= Utilities ==============================
    private fun String.stringField(key: String): String? = Regex("\"$key\":\"([^\"*)\"").find(this)?.groupValues?.get(1)

    private fun String.unescapeJson(): String = replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")

    private fun KomikuHomeItem.toSManga(): SManga = SManga.create().apply {
        title = this@toSManga.title
        setUrlWithoutDomain("/komik/komiku/$slug")
        thumbnail_url = image
    }

    private fun KiryuuHomeItem.toSManga(): SManga = SManga.create().apply {
        title = this@toSManga.title
        setUrlWithoutDomain("/komik/kiryuu/$slug")
        thumbnail_url = image
        genre = typeGenre
    }

    private fun KomikuListItem.toSManga(): SManga = SManga.create().apply {
        title = this@toSManga.title
        // link is the upstream komiku.org URL: /manga/<slug>/ -> /komik/komiku/<slug>
        setUrlWithoutDomain("/komik/komiku/" + link.trimEnd('/').substringAfterLast('/'))
        thumbnail_url = image
        genre = this@toSManga.genre
        status = parseStatus(this@toSManga.status)
    }

    private fun SearchItem.toSManga(source: String): SManga = SManga.create().apply {
        title = this@toSManga.title
        // detail_link is the upstream URL (komiku.org/manga/<slug>/ or
        // v7.kiryuu.to/manga/<slug>/); the slug is its last path segment.
        setUrlWithoutDomain("/komik/$source/" + detailLink.trimEnd('/').substringAfterLast('/'))
        thumbnail_url = image
    }
}
