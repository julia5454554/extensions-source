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
import okhttp3.Request
import okhttp3.Response

@Source
class HentaiDaTia : HttpSource() {

    override val name = "HentaiDaTia"
    override val baseUrl = "https://hentaidatia.com"
    override val lang = "pt-BR"
    override val supportsLatest = true

    // Populares
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/page/$page/", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("article, div.post-item, .manga-item").map { element ->
            SManga.create().apply {
                setUrlWithoutDomain(element.selectFirst("a")?.attr("href") ?: "")
                title = element.selectFirst("h2, h3, .entry-title, .title")?.text() ?: ""
                thumbnail_url = element.selectFirst("img")?.let { img ->
                    img.attr("abs:data-src").ifEmpty { img.attr("abs:src") }
                }
            }
        }
        val hasNextPage = document.selectFirst("a.next, .nav-previous a") != null
        return MangasPage(mangas, hasNextPage)
    }

    // Mais Recentes
    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)
    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // Busca
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/page/$page/?s=$query", headers)

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // Detalhes
    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("h1.entry-title, h1.title")?.text() ?: ""
            description = document.select(".entry-content p").text()
            thumbnail_url = document.selectFirst(".entry-content img, .post-thumbnail img")?.let { img ->
                img.attr("abs:data-src").ifEmpty { img.attr("abs:src") }
            }
        }
    }

    // Capítulos
    override fun chapterListParse(response: Response): List<SChapter> {
        return listOf(
            SChapter.create().apply {
                setUrlWithoutDomain(response.request.url.encodedPath)
                name = "Capítulo Único"
            }
        )
    }

    // Páginas de Leitura
    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
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

    override fun imageUrlParse(response: Response): String = ""
}
