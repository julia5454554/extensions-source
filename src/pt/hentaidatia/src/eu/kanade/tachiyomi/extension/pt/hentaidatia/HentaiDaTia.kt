package eu.kanade.tachiyomi.extension.pt.hentaidatia

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class HentaiDaTia : ParsedHttpSource() {

    override val name = "HentaiDaTia"
    override val baseUrl = "https://hentaidatia.com"
    override val lang = "pt-BR"
    override val supportsLatest = true

    // Populares
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/page/$page/", headers)
    override fun popularMangaSelector(): String = "article, div.post-item, .manga-item"
    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")?.attr("href") ?: "")
        title = element.selectFirst("h2, h3, .entry-title, .title")?.text() ?: ""
        thumbnail_url = element.selectFirst("img")?.let { img ->
            img.attr("abs:data-src").ifEmpty { img.attr("abs:src") }
        }
    }
    override fun popularMangaNextPageSelector(): String = "a.next, .nav-previous a"

    // Mais Recentes
    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)
    override fun latestUpdatesSelector(): String = popularMangaSelector()
    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun latestUpdatesNextPageSelector(): String = popularMangaNextPageSelector()

    // Busca
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/page/$page/?s=$query", headers)
    override fun searchMangaSelector(): String = popularMangaSelector()
    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun searchMangaNextPageSelector(): String = popularMangaNextPageSelector()

    // Detalhes
    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("h1.entry-title, h1.title")?.text() ?: ""
        description = document.select(".entry-content p").text()
        thumbnail_url = document.selectFirst(".entry-content img, .post-thumbnail img")?.let { img ->
            img.attr("abs:data-src").ifEmpty { img.attr("abs:src") }
        }
    }

    // Capítulos
    override fun chapterListSelector(): String = "html"
    override fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        setUrlWithoutDomain(element.ownerDocument()?.location() ?: "")
        name = "Capítulo Único"
    }

    // Páginas de Leitura
    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()
        val imgs = document.select(".entry-content img, .page-break img, .reading-content img")
        imgs.forEachIndexed { index, img ->
            val url = img.attr("abs:data-src").ifEmpty { img.attr("abs:src") }
            if (url.isNotEmpty()) {
                pages.add(Page(index, "", url))
            }
        }
        return pages
    }

    override fun imageUrlParse(document: Document): String = ""
}
