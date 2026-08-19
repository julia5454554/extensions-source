package eu.kanade.tachiyomi.extension.pt.instahentai

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import io.github.keiyoushi.gradle.api.Source

@Source
class InstaHentai : ParsedHttpSource() {

    override val name = "InstaHentai"

    override val baseUrl = "https://instahentai.com"

    override val lang = "pt"

    override val supportsLatest = true

    // Lista de populares/início
    override fun popularMangaRequest(page: Int) = GET("$baseUrl/page/$page/", headers)

    override fun popularMangaSelector() = ".list-upd .bsx, .manga-item"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        manga.setUrlWithoutDomain(element.select("a").attr("href"))
        manga.title = element.select(".tt, .title").text()
        manga.thumbnail_url = element.select("img").attr("abs:src")
        return manga
    }

    override fun popularMangaNextPageSelector() = "a.next"

    // Mais recentes
    override fun latestUpdatesRequest(page: Int) = popularMangaRequest(page)

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // Busca
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) = 
        GET("$baseUrl/?s=$query&page=$page", headers)

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // Detalhes da obra
    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()
        manga.title = document.select("h1.entry-title").text()
        manga.description = document.select(".entry-content, .desc").text()
        manga.genre = document.select(".mgen a").joinToString { it.text() }
        manga.thumbnail_url = document.select(".thumb img").attr("abs:src")
        return manga
    }

    // Lista de capítulos
    override fun chapterListSelector() = "#chapterlist ul li, .eplister ul li"

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        chapter.setUrlWithoutDomain(element.select("a").attr("href"))
        chapter.name = element.select(".chapternum, .chaplist a").text()
        return chapter
    }

    // Leitor de páginas
    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()
        document.select("#readerarea img").forEachIndexed { index, element ->
            val url = element.attr("abs:src")
            pages.add(Page(index, "", url))
        }
        return pages
    }

    override fun imageUrlParse(document: Document) = throw UnsupportedOperationException("Não utilizado")
}
