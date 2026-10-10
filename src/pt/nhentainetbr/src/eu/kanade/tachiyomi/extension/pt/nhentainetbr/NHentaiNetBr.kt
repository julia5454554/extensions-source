package eu.kanade.tachiyomi.extension.pt.nhentainetbr

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.time.Duration.Companion.seconds

@Source
class NHentaiNetBr(
    override val lang: String = "pt-BR",
    override val id: Long = 0L,
) : HttpSource() {

    override val name = "nhentai.net.br"
    override val baseUrl = "https://nhentai.net.br"
    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(2, 1.seconds)
        .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        .add("Referer", "$baseUrl/")

    // ================= Popular =================
    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/popular/page/$page/", headers)

    override fun popularMangaParse(response: Response): MangasPage =
        parseMangaList(response.asJsoup())

    // ================= Latest =================
    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/ultimos/page/$page/", headers)

    override fun latestUpdatesParse(response: Response): MangasPage =
        parseMangaList(response.asJsoup())

    // ================= Search =================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("page")
            .addPathSegment(page.toString())
            .addQueryParameter("s", query)
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage =
        parseMangaList(response.asJsoup())

    private fun parseMangaList(document: Document): MangasPage {
        val mangas = document.select("div.thumb-conteudo").mapNotNull { element ->
            val link = element.selectFirst("a[href*='${baseUrl}/']") ?: return@mapNotNull null
            val titleElement = element.selectFirst(".thumb-titulo") ?: return@mapNotNull null
            val imgElement = element.selectFirst("img")

            SManga.create().apply {
                this.title = titleElement.text().trim()
                this.setUrlWithoutDomain(link.absUrl("href"))
                this.thumbnail_url = imgElement?.absUrl("data-src")?.ifEmpty { imgElement.absUrl("src") } ?: ""
            }
        }

        val hasNextPage = document.selectFirst("ul.paginacao li.next a[href]") != null
        return MangasPage(mangas, hasNextPage)
    }

    // ================= Details =================
    override fun mangaDetailsParse(response: Response): SManga = SManga.create().apply {
        val document = response.asJsoup()
        title = document.selectFirst("h1.post-titulo")?.text()?.trim() ?: ""
        thumbnail_url = document.selectFirst("div.post-capa img")?.absUrl("src") ?: ""
        description = document.select("ul.post-itens li").joinToString("\n") { it.text() }
        genre = document.select("a[href*='/tag/']").eachText().joinToString(", ")
        status = SManga.COMPLETED
        update_strategy = UpdateStrategy.ALWAYS_UPDATE
    }

    // ================= Chapters =================
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val galleries = document.select("div.galeriaTabItem")

        if (galleries.isEmpty()) {
            return listOf(
                SChapter.create().apply {
                    name = "Capítulo Único"
                    chapter_number = 1f
                    setUrlWithoutDomain(response.request.url.encodedPath)
                }
            )
        }

        return galleries.map { gallery ->
            val id = gallery.selectFirst("div.galeriaConteudo")?.id() ?: ""
            val label = gallery.selectFirst(".galeriaTabCapitulo")?.text() ?: "Capítulo"
            val title = gallery.selectFirst(".galeriaTabTitulo")?.text() ?: ""

            SChapter.create().apply {
                name = if (title.isNotEmpty()) "$label: $title" else label
                chapter_number = 1f
                setUrlWithoutDomain("${response.request.url.encodedPath}#$id")
            }
        }.reversed()
    }

    // ================= Pages =================
    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val fragment = response.request.url.fragment
        
        val images = if (!fragment.isNullOrEmpty()) {
            document.select("div.galeriaConteudo#$fragment img")
        } else {
            document.select("ul.post-fotos img")
        }

        return images.mapIndexed { index, img ->
            val url = img.absUrl("data-src").ifEmpty { img.absUrl("src") }
            Page(index, imageUrl = url)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()
}
