package eu.kanade.tachiyomi.extension.pt.meuhentai

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser

@Source
abstract class MeuHentai : KeiSource() {

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = if (page == 1) "$baseUrl/" else "$baseUrl/page/$page/"
        val response = client.get(url)
        return parseMangaList(response.asJsoup())
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/".toHttpUrl().newBuilder().apply {
            addQueryParameter("s", query)
            if (page > 1) {
                addEncodedPathSegments("page/$page/")
            }
        }.build()

        val response = client.get(url)
        return parseMangaList(response.asJsoup())
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val path = url.encodedPath
        if (path.isBlank() || path == "/") return null

        val document = client.get("$baseUrl$path").asJsoup()
        return parseMangaDetails(document).apply {
            setUrlWithoutDomain(path)
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        val updatedManga = if (fetchDetails) parseMangaDetails(document) else manga
        val updatedChapters = if (fetchChapters) parseChapterList(document, manga.url) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        val ogTitle = document.selectFirst("meta[property=og:title]")?.attr("content")
        val rawTitle = document.selectFirst("h1.entry-title, h1.post-title, h1")?.text() ?: ogTitle ?: ""
        title = Parser.unescapeEntities(cleanTitle(rawTitle), false)

        thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")?.let {
            if (it.startsWith("/")) "$baseUrl$it" else it
        } ?: document.selectFirst(".entry-content img, .post-content img")?.attr("src")?.let {
            if (it.startsWith("/")) "$baseUrl$it" else it
        }

        description = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.selectFirst(".entry-content")?.text()

        val categories = document.select("a[rel='category tag']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        genre = categories.joinToString(", ")
        status = SManga.COMPLETED
    }

    private fun cleanTitle(rawTitle: String): String = rawTitle
        .replace(Regex("""\s*[-–—]\s*(Completo|Hentai|HQ Porno|Quadrinhos Eróticos|HQ|Porno)(\s*[-–—]\s*(Hentai|HQ Porno|Quadrinhos Eróticos|HQ|Porno))*\s*$""", RegexOption.IGNORE_CASE), "")
        .trim()

    private fun parseChapterList(document: Document, mangaUrl: String): List<SChapter> = listOf(
        SChapter.create().apply {
            name = "Capítulo Único"
            setUrlWithoutDomain(mangaUrl)
            chapter_number = 1f
        },
    )

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = "$baseUrl${if (chapter.url.startsWith("/")) chapter.url else "/${chapter.url}"}"
        val pages = mutableListOf<Page>()
        val visitedUrls = mutableSetOf<String>()
        collectPages(chapterUrl, pages, visitedUrls)
        return pages
    }

    private suspend fun collectPages(url: String, pages: MutableList<Page>, visitedUrls: MutableSet<String>) {
        if (url in visitedUrls) return
        visitedUrls.add(url)

        val document = client.get(url).asJsoup()
        val images = document.select(".entry-content img, .post-content img, .reader-area img")
        images.forEach { img ->
            val src = img.attr("data-src").ifBlank { img.attr("data-lazy-src") }.ifBlank { img.attr("src") }
            if (src.isNotBlank()) {
                val fullUrl = if (src.startsWith("/")) "$baseUrl$src" else src
                pages.add(Page(pages.size, imageUrl = fullUrl))
            }
        }

        val nextLink = document.selectFirst("a.botao-r[href*='/pagina/'], a[rel='next']")
            ?: document.selectFirst("a[href*='/pagina/']")?.takeIf { it.text().contains("Próxima", ignoreCase = true) }
        if (nextLink != null) {
            val nextUrl = nextLink.attr("abs:href")
            if (nextUrl.isNotBlank()) {
                collectPages(nextUrl, pages, visitedUrls)
            }
        }
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList()

    private fun parseMangaList(document: Document): MangasPage {
        val mangas = mutableListOf<SManga>()
        val seenUrls = mutableSetOf<String>()

        val elements = document.select(".lista-foto")
        for (element in elements) {
            val link = element.selectFirst("a.intentf") ?: element.selectFirst("a[href]") ?: continue
            val href = link.attr("href").trim()
            if (href.isBlank() || seenUrls.contains(href)) continue
            seenUrls.add(href)

            val title = link.attr("title").trim()
                .ifBlank { element.selectFirst("h2.white")?.text()?.trim().orEmpty() }
                .ifBlank { link.text().trim() }
            if (title.isBlank()) continue

            val img = element.selectFirst("img.thumb")?.attr("src")?.trim()
                ?.let { if (it.startsWith("/")) "$baseUrl$it" else it }

            mangas.add(
                SManga.create().apply {
                    title = Parser.unescapeEntities(title, false)
                    setUrlWithoutDomain(href)
                    thumbnail_url = img
                },
            )
        }

        val hasNextPage = document.selectFirst("link[rel='next']") != null ||
            document.selectFirst(".next, .pagination .next, a[rel='next']") != null

        return MangasPage(mangas, hasNextPage)
    }
}
