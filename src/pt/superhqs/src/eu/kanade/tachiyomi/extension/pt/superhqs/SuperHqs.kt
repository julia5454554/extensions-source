package eu.kanade.tachiyomi.extension.pt.superhqs

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
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

@Source
abstract class SuperHqs : KeiSource() {

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

    // Detalhes do mangá
    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        val ogTitle = document.selectFirst("meta[property=og:title]")?.attr("content")
        val rawTitle = document.selectFirst("h1.title")?.text() ?: ogTitle ?: ""
        title = Parser.unescapeEntities(rawTitle, false)

        thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")?.let {
            if (it.startsWith("/")) "$baseUrl$it" else it
        } ?: document.selectFirst(".entry-content img")?.attr("src")?.let {
            if (it.startsWith("/")) "$baseUrl$it" else it
        }

        description = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.selectFirst(".entry-content p")?.text()
            ?: ""

        val categories = document.select(".category a, a[rel='category tag']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        genre = categories.joinToString(", ")
        status = SManga.COMPLETED
    }

    // Capítulo único: aponta para a URL do mangá
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

        // Extrai o slug do mangá, removendo segmento /pagina/ se existir
        val rawSlug = chapterUrl.split("/pagina/").first()
            .trimEnd('/')
            .substringAfterLast('/')
            .lowercase()

        // Remove sufixo numérico (ex.: "-1", "-22") para obter o nome base
        val mangaNameBase = rawSlug.replace(Regex("-\\d+$"), "")

        val pages = mutableListOf<Page>()
        val visited = mutableSetOf<String>()
        collectPages(chapterUrl, mangaNameBase, pages, visited)
        return pages
    }

    private suspend fun collectPages(
        url: String,
        mangaNameBase: String,
        pages: MutableList<Page>,
        visited: MutableSet<String>,
    ) {
        if (url in visited) return
        visited.add(url)

        val document = client.get(url).asJsoup()
        val images = document.select("img[src*='/wp-content/uploads/']")

        var foundByName = false

        // Tenta primeiro pelo nome base do mangá
        for (img in images) {
            if (img.hasClass("thumb") || img.parents().any { it.hasClass("thumb") }) continue

            val imageUrl = extractImageUrlWithName(img, mangaNameBase)
            if (imageUrl != null) {
                pages.add(Page(pages.size, imageUrl = imageUrl))
                foundByName = true
            }
        }

        // Se NÃO encontrou nada pelo nome, tenta o padrão numérico
        if (!foundByName) {
            for (img in images) {
                if (img.hasClass("thumb") || img.parents().any { it.hasClass("thumb") }) continue

                val imageUrl = extractImageUrlWithNumericPattern(img)
                if (imageUrl != null) {
                    pages.add(Page(pages.size, imageUrl = imageUrl))
                }
            }
        }

        // Fallback para #img_gallery_big (se houver)
        if (pages.isEmpty()) {
            val mainImage = document.selectFirst("#img_gallery_big")
            if (mainImage != null) {
                val imageUrl = extractImageUrlWithName(mainImage, mangaNameBase)
                    ?: extractImageUrlWithNumericPattern(mainImage)
                if (imageUrl != null) {
                    pages.add(Page(pages.size, imageUrl = imageUrl))
                }
            }
        }

        // Procura link de próxima página (se houver)
        val nextLink = document.selectFirst("a.botao-r[href*='/pagina/'], a[rel='next']")
            ?: document.selectFirst("a[href*='/pagina/']")?.takeIf { it.text().contains("Próxima", ignoreCase = true) }
        if (nextLink != null) {
            val nextUrl = nextLink.attr("abs:href")
            if (nextUrl.isNotBlank() && nextUrl != url) {
                collectPages(nextUrl, mangaNameBase, pages, visited)
            }
        }
    }

    // Extrai imagem filtrando pelo nome base do mangá
    private fun extractImageUrlWithName(img: Element, mangaNameBase: String): String? {
        val attrs = listOf("data-full-url", "data-original", "data-src", "data-lazy-src", "src")
        for (attr in attrs) {
            val value = img.attr(attr).trim()
            if (value.isNotBlank() && isImageUrl(value)) {
                val fullUrl = if (value.startsWith("/")) "$baseUrl$value" else value
                if (fullUrl.startsWith("$baseUrl/wp-content/uploads/")) {
                    val fileName = fullUrl.substringAfterLast('/').lowercase()
                    if (fileName.contains(mangaNameBase)) {
                        return fullUrl
                    }
                }
            }
        }

        val srcset = img.attr("srcset")
        if (srcset.isNotBlank()) {
            val srcsetUrls = srcset.split(",").map { it.trim().substringBefore(" ") }
            for (url in srcsetUrls.reversed()) {
                if (url.isNotBlank() && isImageUrl(url)) {
                    val fullUrl = if (url.startsWith("/")) "$baseUrl$url" else url
                    if (fullUrl.startsWith("$baseUrl/wp-content/uploads/")) {
                        val fileName = fullUrl.substringAfterLast('/').lowercase()
                        if (fileName.contains(mangaNameBase)) {
                            return fullUrl
                        }
                    }
                }
            }
        }

        return null
    }

    // Extrai imagem que tenha padrão numérico no final do nome (ex.: 01-84.jpg ou 01.jpg)
    private fun extractImageUrlWithNumericPattern(img: Element): String? {
        val attrs = listOf("data-full-url", "data-original", "data-src", "data-lazy-src", "src")
        for (attr in attrs) {
            val value = img.attr(attr).trim()
            if (value.isNotBlank() && isImageUrl(value)) {
                val fullUrl = if (value.startsWith("/")) "$baseUrl$value" else value
                if (fullUrl.startsWith("$baseUrl/wp-content/uploads/")) {
                    val fileName = fullUrl.substringAfterLast('/').lowercase()
                    // Aceita padrões como: nome-1-2.jpg, nome-01-02.jpg, nome-1.jpg, nome-01.jpg
                    if (fileName.matches(Regex(""".*-\d+-\d+\.(jpg|jpeg|png|webp)$""")) ||
                        fileName.matches(Regex(""".*-\d+\.(jpg|jpeg|png|webp)$"""))) {
                        return fullUrl
                    }
                }
            }
        }

        val srcset = img.attr("srcset")
        if (srcset.isNotBlank()) {
            val srcsetUrls = srcset.split(",").map { it.trim().substringBefore(" ") }
            for (url in srcsetUrls.reversed()) {
                if (url.isNotBlank() && isImageUrl(url)) {
                    val fullUrl = if (url.startsWith("/")) "$baseUrl$url" else url
                    if (fullUrl.startsWith("$baseUrl/wp-content/uploads/")) {
                        val fileName = fullUrl.substringAfterLast('/').lowercase()
                        if (fileName.matches(Regex(""".*-\d+-\d+\.(jpg|jpeg|png|webp)$""")) ||
                            fileName.matches(Regex(""".*-\d+\.(jpg|jpeg|png|webp)$"""))) {
                            return fullUrl
                        }
                    }
                }
            }
        }

        return null
    }

    private fun isImageUrl(url: String): Boolean {
        return url.substringBefore('?').substringBefore('#').matches(
            Regex(""".*\.(jpg|jpeg|png|webp)$""", RegexOption.IGNORE_CASE)
        )
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList()

    private fun parseMangaList(document: Document): MangasPage {
        val mangas = mutableListOf<SManga>()
        val seenUrls = mutableSetOf<String>()
        val elements = document.select("article.entry-item")
        for (element in elements) {
            val link = element.selectFirst("header a[rel='bookmark']") ?: continue
            val href = link.attr("href").trim()
            if (href.isBlank() || seenUrls.contains(href)) continue
            seenUrls.add(href)

            val mangaTitle = link.selectFirst("h3.entry-title")?.text()?.trim()
                ?: link.attr("title")?.trim()
                ?: link.text().trim()
            if (mangaTitle.isBlank()) continue

            val img = element.selectFirst("img.wp-post-image")?.let {
                it.attr("data-src").ifBlank { it.attr("src") }
            }?.let { if (it.startsWith("/")) "$baseUrl$it" else it }

            mangas.add(
                SManga.create().apply {
                    title = Parser.unescapeEntities(mangaTitle, false)
                    setUrlWithoutDomain(href)
                    thumbnail_url = img
                },
            )
        }

        val hasNextPage = document.selectFirst("link[rel='next'], a.next.page-numbers, .next, .pagination .next, a[rel='next']") != null
        return MangasPage(mangas, hasNextPage)
    }
}
