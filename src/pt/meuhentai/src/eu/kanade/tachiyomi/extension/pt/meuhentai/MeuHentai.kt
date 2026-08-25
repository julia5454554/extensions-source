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
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import eu.kanade.tachiyomi.network.await
import java.net.URLEncoder

@Source
abstract class MeuHentai : KeiSource() {

    // Função para realizar GET com headers anti-hotlink
    private suspend fun fetchWithHeaders(url: String): Response {
        val request = Request.Builder()
            .url(url)
            .header("Referer", baseUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36")
            .build()
        return client.newCall(request).await()
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = if (page == 1) "$baseUrl/" else "$baseUrl/page/$page/"
        val response = fetchWithHeaders(url)
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
        val response = fetchWithHeaders(url.toString())
        return parseMangaList(response.asJsoup())
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val path = url.encodedPath
        if (path.isBlank() || path == "/") return null
        val document = fetchWithHeaders("$baseUrl$path").asJsoup()
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
        val document = fetchWithHeaders(getMangaUrl(manga)).asJsoup()
        val updatedManga = if (fetchDetails) parseMangaDetails(document) else manga
        val updatedChapters = if (fetchChapters) parseChapterList(document, manga.url) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        val ogTitle = document.selectFirst("meta[property=og:title]")?.attr("content")
        val rawTitle = document.selectFirst("h1.entry-title, h1.post-title, h1")?.text() ?: ogTitle ?: ""
        title = Parser.unescapeEntities(rawTitle, false)

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

    private fun parseChapterList(document: Document, mangaUrl: String): List<SChapter> = listOf(
        SChapter.create().apply {
            name = "Capítulo Único"
            setUrlWithoutDomain(mangaUrl)
            chapter_number = 1f
        },
    )

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = if (chapter.url.startsWith("http")) chapter.url else {
            "$baseUrl${if (chapter.url.startsWith("/")) chapter.url else "/${chapter.url}"}"
        }
        val pages = mutableListOf<Page>()
        val visited = mutableSetOf<String>()
        collectPages(chapterUrl, pages, visited)
        return pages
    }

    private suspend fun collectPages(url: String, pages: MutableList<Page>, visited: MutableSet<String>) {
        if (url in visited) return
        visited.add(url)

        val document = fetchWithHeaders(url).asJsoup()

        // Extrai TODAS as imagens de páginas (nome contém "pagina-")
        val images = document.select("img[src*='/wp-content/uploads/']")
        for (img in images) {
            if (img.hasClass("thumb") || img.parents().any { it.hasClass("thumb") }) continue

            val imageUrl = extractPageImageUrl(img)
            if (imageUrl != null) {
                pages.add(Page(pages.size, imageUrl = imageUrl))
            }
        }

        // Fallback para #img_gallery_big caso não tenhamos encontrado nada
        if (pages.isEmpty()) {
            val mainImage = document.selectFirst("#img_gallery_big")
            if (mainImage != null) {
                val imageUrl = extractPageImageUrl(mainImage)
                if (imageUrl != null) {
                    pages.add(Page(pages.size, imageUrl = imageUrl))
                }
            }
        }

        // Procura o link da próxima página (para mangás com paginação)
        val nextLink = document.selectFirst("a.botao-r[href*='/pagina/'], a[rel='next']")
            ?: document.selectFirst("a[href*='/pagina/']")?.takeIf { it.text().contains("Próxima", ignoreCase = true) }
        if (nextLink != null) {
            val nextUrl = nextLink.attr("abs:href")
            if (nextUrl.isNotBlank() && nextUrl != url) {
                collectPages(nextUrl, pages, visited)
            }
        }
    }

    private fun extractPageImageUrl(img: org.jsoup.nodes.Element): String? {
        val attrs = listOf("data-full-url", "data-original", "data-src", "data-lazy-src", "src")
        for (attr in attrs) {
            val value = img.attr(attr).trim()
            if (value.isNotBlank() && isPageImageUrl(value)) {
                val fullUrl = if (value.startsWith("/")) "$baseUrl$value" else value
                if (fullUrl.startsWith("$baseUrl/wp-content/uploads/")) {
                    // Usa proxy para burlar hotlink
                    return "https://wsrv.nl/?url=${URLEncoder.encode(fullUrl, "UTF-8")}&output=webp"
                }
            }
        }

        val srcset = img.attr("srcset")
        if (srcset.isNotBlank()) {
            val srcsetUrls = srcset.split(",").map { it.trim().substringBefore(" ") }
            for (url in srcsetUrls.reversed()) {
                if (url.isNotBlank() && isPageImageUrl(url)) {
                    val fullUrl = if (url.startsWith("/")) "$baseUrl$url" else url
                    if (fullUrl.startsWith("$baseUrl/wp-content/uploads/")) {
                        return "https://wsrv.nl/?url=${URLEncoder.encode(fullUrl, "UTF-8")}&output=webp"
                    }
                }
            }
        }

        return null
    }

    private fun isPageImageUrl(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#')
        return path.matches(Regex(""".*pagina-.*\.(jpg|jpeg|png|webp)$""", RegexOption.IGNORE_CASE))
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList()

    private fun parseMangaList(document: Document): MangasPage {
        val mangas = mutableListOf<SManga>()
        val seenUrls = mutableSetOf<String>()
        val elements = document.select(".lista-foto")
        for (element in elements) {
            val link = element.selectFirst("a.intentf") ?: element.selectFirst("a[href]") ?: continue
            val href = link.attr("href").trim()
            if (href.isBlank()) continue

            val mangaPath = href.substringAfter(baseUrl).ifBlank { href }
            if (seenUrls.contains(mangaPath)) continue
            seenUrls.add(mangaPath)

            val mangaTitle = link.attr("title").trim()
                .ifBlank { element.selectFirst("h2.white")?.text()?.trim().orEmpty() }
                .ifBlank { link.text().trim() }
            if (mangaTitle.isBlank()) continue

            val img = element.selectFirst("img.thumb")?.attr("src")?.trim()
                ?.let { if (it.startsWith("/")) "$baseUrl$it" else it }

            mangas.add(
                SManga.create().apply {
                    title = Parser.unescapeEntities(mangaTitle, false)
                    setUrlWithoutDomain(mangaPath)
                    thumbnail_url = img
                },
            )
        }
        val hasNextPage = document.selectFirst("link[rel='next']") != null ||
            document.selectFirst(".next, .pagination .next, a[rel='next']") != null
        return MangasPage(mangas, hasNextPage)
    }
}
