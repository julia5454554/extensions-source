package eu.kanade.tachiyomi.extension.pt.threehentainetbr

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.time.Duration.Companion.seconds

@Source
class ThreeHentaiNetBr(
    override val lang: String = "pt-BR",
    override val id: Long = 2024060001L, // Troque por um ID único
) : HttpSource() {

    override val name = "3Hentai.net.br"
    override val baseUrl = "https://3hentai.net.br"
    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(2, 1.seconds)
        .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // ==================== LISTAGEM ====================

    override fun popularMangaRequest(page: Int): Request {
        val url = if (page == 1) baseUrl else "$baseUrl/page/$page/"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = mutableListOf<SManga>()

        document.select("div.lista li").forEach { li ->
            val link = li.selectFirst("a[href*='3hentai.net.br']") ?: return@forEach
            val title = link.attr("title").ifBlank {
                link.selectFirst("span.tituloConteudo")?.text()?.trim() ?: ""
            }
            val thumb = link.selectFirst("img")?.attr("abs:src") ?: ""

            if (title.isNotBlank() && thumb.isNotBlank()) {
                SManga.create().apply {
                    this.title = title
                    this.thumbnail_url = thumb
                    setUrlWithoutDomain(link.attr("href"))
                }.let { mangas.add(it) }
            }
        }

        val hasNextPage = document.selectFirst("ul.paginacao li.next a") != null
        return MangasPage(mangas, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)
    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ==================== BUSCA ====================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = if (page == 1) {
            "$baseUrl/?s=$query"
        } else {
            "$baseUrl/page/$page/?s=$query"
        }
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ==================== DETALHES ====================

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val title = document.selectFirst("h1.post-titulo")?.text()?.trim() ?: "Sem título"
        val cover = document.selectFirst("div.post-capa img")?.attr("abs:src") ?: ""
        val description = ""

        val genres = mutableListOf<String>()
        document.select("ul.post-itens a[rel='tag'], ul.post-itens a[href*='/category/'], ul.post-itens a[href*='/tag/']").forEach {
            val text = it.text().trim()
            if (text.isNotBlank()) genres.add(text)
        }

        return SManga.create().apply {
            this.title = title
            this.thumbnail_url = cover
            this.description = description
            this.genre = genres.joinToString(", ")
            this.status = SManga.COMPLETED
            this.author = ""
            update_strategy = UpdateStrategy.ALWAYS_UPDATE
        }
    }

    // ==================== CAPÍTULOS ====================

    override fun chapterListParse(response: Response): List<SChapter> = listOf(
        SChapter.create().apply {
            name = "Capítulo Único"
            chapter_number = 1f
            setUrlWithoutDomain(response.request.url.toString())
        },
    )

    // ==================== PÁGINAS ====================

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val pages = mutableListOf<Page>()
        var index = 0

        document.select("div.galeriaConteudo img, div.galeriaHtml img, div.post-conteudo img").forEach { img ->
            val src = img.attr("abs:src").ifBlank { img.attr("data-src").ifBlank { img.attr("src") } }
            if (src.isNotBlank() && !src.startsWith("data:image")) {
                pages.add(Page(index++, url = baseUrl, imageUrl = src))
            }
        }

        return pages
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .set("Referer", page.url)
            .build()
        return GET(page.imageUrl!!, newHeaders)
    }
}
