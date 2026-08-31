package eu.kanade.tachiyomi.extension.pt.nhentainetbr

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
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
        .rateLimit(1, 2.seconds)
        .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Referer", baseUrl)

    // ===== Helpers =====
    private fun genericMangaFromElement(element: Element): SManga = SManga.create().apply {
        title = element.select("span.thumb-titulo").text().trim()
        thumbnail_url = element.select("img").first()?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("src") }
        }.orEmpty()
        setUrlWithoutDomain(element.select("a[href*='$baseUrl/']").first()?.attr("href") ?: "")
    }

    // ===== Listagem (Popular e Latest) =====
    override fun popularMangaRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/popular/" else "$baseUrl/popular/page/$page"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage = parseMangaList(response)

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/ultimos/" else "$baseUrl/ultimos/page/$page"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = parseMangaList(response)

    private fun parseMangaList(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div.thumb-conteudo").mapNotNull { element ->
            val linkElement = element.selectFirst("a[href*='$baseUrl/']:not(.thumbParodiaNome)") ?: return@mapNotNull null
            val title = element.selectFirst(".thumb-titulo")?.text()?.trim() ?: return@mapNotNull null
            val url = linkElement.absUrl("href")
            val thumb = element.selectFirst("img")?.let { img ->
                img.attr("data-src").ifEmpty { img.attr("src") }
            }.orEmpty()

            if (title.isNotEmpty() && url.startsWith(baseUrl)) {
                SManga.create().apply {
                    this.title = title
                    this.thumbnail_url = thumb
                    setUrlWithoutDomain(url.removePrefix(baseUrl))
                }
            } else null
        }

        // Paginação desativada: sempre retorna false para hasNextPage
        return MangasPage(mangas, false)
    }

    // ===== Busca =====
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotEmpty()) {
            val url = baseUrl.toHttpUrl().newBuilder()
                .addQueryParameter("s", query.trim())
                .toString()
            return GET(url, headers)
        }

        return latestUpdatesRequest(page)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseMangaList(response)

    // ===== Detalhes =====
    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val post = document.select("div.post-box")

        val galleries = document.select("div.galeriaTabItem")
        val isMultipleChapters = galleries.isNotEmpty()

        return SManga.create().apply {
            title = post.select("h1.post-titulo").text().trim()
            thumbnail_url = post.select("div.post-capa img").first()?.let { img ->
                img.attr("src").ifEmpty { img.attr("data-src") }
            }.orEmpty()
            description = post.select("meta[property='og:description']")?.attr("content")?.trim()
                ?: post.select("ul.post-itens li").joinToString("\n") { it.text().trim() }
            genre = post.select(
                "a[href*='/category/'], a[href*='/tag/'], a[href*='/parodia/'], a[href*='/cor/']",
            ).eachText().distinct().joinToString(", ")
            author = post.selectFirst("a[href*='/artista/'], a[href*='/artist/']")?.text()?.trim()
                ?: ""
            status = SManga.COMPLETED
            update_strategy = if (isMultipleChapters) UpdateStrategy.ALWAYS_UPDATE else UpdateStrategy.ONLY_FETCH_ONCE
        }
    }

    // ===== Capítulos =====
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val galleries = document.select("div.galeriaTabItem")

        if (galleries.isNotEmpty()) {
            return galleries.mapNotNull { it.toSChapter(document.location()) }.reversed()
        }

        val basePath = response.request.url.toString().removePrefix(baseUrl)
        return listOf(
            SChapter.create().apply {
                name = "Capítulo Único"
                chapter_number = 1f
                setUrlWithoutDomain(basePath)
            }
        )
    }

    private fun Element.toSChapter(mangaUrl: String): SChapter? {
        val galleryId = selectFirst("div.galeriaConteudo[id~=^galeria-\\d+$]")?.id() ?: return null
        val label = selectFirst(".galeriaTabCapitulo")?.text().orEmpty()
        val chapterTitle = selectFirst(".galeriaTabTitulo")?.text().orEmpty()

        return SChapter.create().apply {
            url = "$mangaUrl#$galleryId"
            name = when {
                label.isEmpty() -> chapterTitle.ifEmpty { "Capítulo" }
                chapterTitle.isEmpty() -> label
                else -> "$label: $chapterTitle"
            }
            chapter_number = CHAPTER_NUMBER_REGEX.find(label)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
        }
    }

    // ===== Páginas =====
    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val chapterUrl = response.request.url.toString()
        val galleryId = chapterUrl.substringAfterLast("#", "")

        val images = if (galleryId.isNotEmpty()) {
            document.select("div.galeriaConteudo#$galleryId img")
        } else {
            document.select("ul.post-fotos img")
        }

        return images.mapIndexed { index, img ->
            val imageUrl = img.attr("data-src").ifEmpty { img.attr("src") }
            Page(index, url = chapterUrl, imageUrl = imageUrl)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .set("Referer", page.url)
            .build()
        return GET(page.imageUrl!!, newHeaders)
    }

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Os filtros são ignorados na busca por texto"),
    )

    companion object {
        private val CHAPTER_NUMBER_REGEX = Regex("""(\d+(?:\.\d+)?)""")
    }
}
