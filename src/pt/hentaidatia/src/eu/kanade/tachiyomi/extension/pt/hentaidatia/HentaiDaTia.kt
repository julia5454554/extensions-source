package eu.kanade.tachiyomi.extension.pt.hentaidatia

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

@Source
class HentaiDaTia(
    override val lang: String = "pt-BR",
    override val id: Long = 0L,
) : HttpSource() {

    override val name = "HentaiDaTia"
    override val baseUrl = "https://hentaidatia.com"
    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
        .add("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
        .add("Referer", "$baseUrl/")

    // Populares
    override fun popularMangaRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/" else "$baseUrl/page/$page/"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        return parseMangaList(response.asJsoup())
    }

    // Mais Recentes
    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)
    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // Busca
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = if (page == 1) {
            "$baseUrl/?s=$encodedQuery"
        } else {
            "$baseUrl/page/$page/?s=$encodedQuery"
        }
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    private fun parseMangaList(document: Document): MangasPage {
        // Detecta se o Cloudflare bloqueou a requisição HTTP em background
        val docTitle = document.title().lowercase()
        if (docTitle.contains("just a moment") || docTitle.contains("attention required") || document.selectFirst("#challenge-running, #challenge-form") != null) {
            throw Exception("Cloudflare ativo: abra na WebView, resolva o desafio e puxe a tela para atualizar.")
        }

        // Tenta capturar os posts usando tags de artigos ou classes alternativas de temas WordPress
        var elements = document.select("article")
        if (elements.isEmpty()) {
            elements = document.select(".type-post, div.post, div.post-item, div.box-single, div.episodes, .manga-item")
        }

        val mangas = elements.mapNotNull { element ->
            val link = element.selectFirst("h1 a, h2 a, h3 a, .entry-title a, a.post-thumbnail, a[rel='bookmark']")
                ?: element.selectFirst("a")
                ?: return@mapNotNull null

            val href = link.attr("abs:href").ifEmpty { link.attr("href") }

            if (href.isEmpty() || href == "$baseUrl/" || href == "$baseUrl" ||
                href.contains("/category/") || href.contains("/tag/") ||
                href.contains("/page/") || href.contains("#")
            ) {
                return@mapNotNull null
            }

            val titleText = element.selectFirst("h1, h2, h3, .entry-title")?.text()
                ?.ifEmpty { link.attr("title") }
                ?.ifEmpty { link.text() }
                ?: return@mapNotNull null

            if (titleText.isBlank()) return@mapNotNull null

            val img = element.selectFirst("img") ?: link.selectFirst("img")
            val thumb = img?.let { extractImageUrl(it) } ?: ""

            SManga.create().apply {
                setUrlWithoutDomain(href)
                title = titleText.trim()
                thumbnail_url = thumb
            }
        }.distinctBy { it.url }

        val hasNextPage = document.selectFirst("a.next, .nav-previous a, .pagination a:contains(›), .page-numbers.next") != null
        return MangasPage(mangas, hasNextPage)
    }

    // Detalhes
    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("h1.entry-title, h1.post-title, h1")?.text()?.trim() ?: ""

            val genres = document.select(".entry-categories a, .entry-tags a, .post-tags a, a[rel='tag']")
                .map { it.text() }
                .distinct()
            genre = genres.joinToString(", ")

            description = document.select(".entry-content p:not(:has(img))")
                .text()
                .ifEmpty { document.select(".entry-content").text() }

            status = SManga.COMPLETED

            thumbnail_url = document.selectFirst(".entry-content img, .post-thumbnail img, article img")?.let { extractImageUrl(it) }
        }
    }

    // Capítulos
    override fun chapterListParse(response: Response): List<SChapter> {
        return listOf(
            SChapter.create().apply {
                setUrlWithoutDomain(response.request.url.encodedPath)
                name = "Capítulo Único"
            },
        )
    }

    // Páginas de Leitura
    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val pages = mutableListOf<Page>()
        val imgs = document.select(".entry-content img, .page-break img, article img")

        var index = 0
        imgs.forEach { img ->
            val url = extractImageUrl(img)

            if (url.isNotEmpty() && isValidImage(url)) {
                pages.add(Page(index++, "", url))
            }
        }
        return pages
    }

    private fun isValidImage(url: String): Boolean {
        val lowerUrl = url.lowercase()
        return !lowerUrl.contains("logo") &&
            !lowerUrl.contains("banner") &&
            !lowerUrl.contains("discord") &&
            !lowerUrl.contains("anuncio") &&
            !lowerUrl.endsWith(".gif")
    }

    private fun extractImageUrl(element: Element): String {
        return element.attr("abs:data-src")
            .ifEmpty { element.attr("abs:data-lazy-src") }
            .ifEmpty { element.attr("abs:data-cfsrc") }
            .ifEmpty { element.attr("abs:data-orig-file") }
            .ifEmpty { element.attr("abs:src") }
    }

    override fun imageUrlParse(response: Response): String = ""
}
