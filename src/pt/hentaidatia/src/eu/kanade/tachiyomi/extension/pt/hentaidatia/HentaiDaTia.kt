package eu.kanade.tachiyomi.extension.pt.hentaidatia

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
import org.jsoup.nodes.Element
import kotlin.time.Duration.Companion.seconds

@Source
class HentaiDaTia(
    override val lang: String = "pt-BR",
    override val id: Long = 0L,
) : HttpSource() {

    override val name = "HentaiDaTia"
    override val baseUrl = "https://hentaidatia.com"
    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(2, 1.seconds)
        .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    private fun extractImageUrl(element: Element): String {
        return element.attr("abs:data-src")
            .ifEmpty { element.attr("abs:data-lazy-src") }
            .ifEmpty { element.attr("abs:data-cfsrc") }
            .ifEmpty { element.attr("abs:data-orig-file") }
            .ifEmpty { element.attr("abs:src") }
    }

    private fun genericMangaFromElement(element: Element): SManga = SManga.create().apply {
        val titleEl = element.selectFirst("span.thumb-titulo, h2, h3, .entry-title")
        title = titleEl?.text()?.trim() ?: element.text().trim()

        val imgEl = element.selectFirst("img")
        thumbnail_url = imgEl?.let { extractImageUrl(it) } ?: ""

        val linkEl = element.selectFirst("a")
        setUrlWithoutDomain(linkEl?.attr("href") ?: "")
    }

    // Populares
    override fun popularMangaRequest(page: Int): Request {
        val pageStr = if (page != 1) "page/$page/" else ""
        return GET("$baseUrl/$pageStr", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val elements = document.select("div.lista > ul > li div.thumb-conteudo, article.post, div.post")

        val mangas = elements.mapNotNull { el ->
            val link = el.selectFirst("a") ?: return@mapNotNull null
            val href = link.attr("abs:href")

            if (href.isEmpty() || href == "$baseUrl/" || href.contains("/category/") || href.contains("/tag/")) {
                return@mapNotNull null
            }

            genericMangaFromElement(el)
        }.distinctBy { it.url }

        val hasNextPage = document.selectFirst("ul.paginacao li.next, a.next, .pagination a:contains(›)") != null
        return MangasPage(mangas, hasNextPage)
    }

    // Mais Recentes
    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)
    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // Busca
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            if (query.isNotEmpty()) {
                addQueryParameter("s", query)
            }
            if (page > 1) {
                addPathSegment("page")
                addPathSegment(page.toString())
            }
        }.build()

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // Detalhes do Mangá
    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()

        return SManga.create().apply {
            title = document.selectFirst("h1.entry-title, h1.post-title, h1")?.text()?.trim() ?: ""
            author = document.select("ul.post-itens li:contains(Artista:) a, .entry-terms:contains(Artista) a").text()

            val genres = document.select("ul.post-itens li:contains(Tags:) a, .entry-categories a, .entry-tags a, a[rel='tag']")
                .map { it.text() }
                .distinct()
            genre = genres.joinToString(", ")

            description = document.select("ul.post-itens li:contains(Cor:), .entry-content p:not(:has(img))").text()
            status = SManga.COMPLETED

            val mainImg = document.selectFirst("div.post-capa img, .entry-content img, article img")
            thumbnail_url = mainImg?.let { extractImageUrl(it) }

            val isMultipleChapters = document.selectFirst("div.listaImagens div.galeriaTab") != null
            update_strategy = if (isMultipleChapters) UpdateStrategy.ALWAYS_UPDATE else UpdateStrategy.ONLY_FETCH_ONCE
        }
    }

    // Lista de Capítulos
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        // Obtém o caminho relativo da página do mangá (sem domínio e sem fragmentos)
        val basePath = document.location()
            .removePrefix(baseUrl)
            .substringBefore("#")

        val multipleChapters = document.select("div.listaImagens div.galeriaTab")

        if (multipleChapters.isNotEmpty()) {
            return multipleChapters.map { element ->
                val chapterId = element.attr("data-id")
                val title = element.selectFirst("div.galeriaTabTitulo")?.text()

                SChapter.create().apply {
                    name = "Capítulo $chapterId" + (if (!title.isNullOrEmpty()) " - $title" else "")
                    chapter_number = chapterId.toFloatOrNull() ?: -1f
                    // Usa ?chapter= em vez de # para que o servidor receba a URL normalmente
                    setUrlWithoutDomain("$basePath?chapter=$chapterId")
                }
            }.reversed()
        }

        return listOf(
            SChapter.create().apply {
                name = "Capítulo Único"
                chapter_number = 1f
                setUrlWithoutDomain(basePath)
            },
        )
    }

    // Lista de Páginas do Capítulo
    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val currentUrl = document.location()

        // Extrai o ID do capítulo do parâmetro ?chapter=
        val chapterId = currentUrl
            .substringAfterLast("chapter=", "")
            .substringBefore("&")

        val gallerySelector = if (chapterId.isNotEmpty()) {
            "#galeria-$chapterId img"
        } else {
            "div.listaImagens ul.post-fotos img, .entry-content img, article img"
        }

        val images = document.select(gallerySelector)

        val pages = mutableListOf<Page>()
        var index = 0

        images.forEach { el ->
            val src = extractImageUrl(el)
            val lowerSrc = src.lowercase()

            val isValid = src.isNotEmpty() &&
                !lowerSrc.contains("logo") &&
                !lowerSrc.contains("banner") &&
                !lowerSrc.contains("discord") &&
                !lowerSrc.endsWith(".gif")

            if (isValid) {
                pages.add(Page(index++, url = currentUrl, imageUrl = src))
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
