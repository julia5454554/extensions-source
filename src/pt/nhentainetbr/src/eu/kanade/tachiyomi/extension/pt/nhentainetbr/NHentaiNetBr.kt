package eu.kanade.tachiyomi.extension.pt.nhentainetbr

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

class NHentaiNetBr : HttpSource() {

    override val name = "nhentai.net.br"

    override val baseUrl = "https://nhentai.net.br"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(2, 1, TimeUnit.SECONDS)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // Helper para extração de URLs de imagem
    private fun extractImageUrl(element: Element): String {
        val srcset = element.attr("srcset")
        if (srcset.isNotEmpty()) {
            val largest = srcset.split(",")
                .map { it.trim().split(" ") }
                .filter { it.size >= 2 }
                .maxByOrNull { it[1].removeSuffix("w").toIntOrNull() ?: 0 }
            if (largest != null && largest[0].isNotEmpty()) {
                return largest[0]
            }
        }

        val raw = element.attr("data-lazy-src")
            .ifEmpty { element.attr("data-src") }
            .ifEmpty { element.attr("abs:src") }
            .ifEmpty { element.attr("src") }

        if (raw.isEmpty()) return ""
        return if (raw.startsWith("http://") || raw.startsWith("https://")) {
            raw
        } else {
            baseUrl + "/" + raw.removePrefix("/")
        }
    }

    // ===== Listagem (Popular e Últimos) =====

    override fun popularMangaRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/popular/" else "$baseUrl/popular/page/$page/"
        return GET(url, headers)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/ultimos/" else "$baseUrl/ultimos/page/$page/"
        return GET(url, headers)
    }

    private fun parseMangaList(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = mutableListOf<SManga>()

        document.select("div.thumb-conteudo").forEach { element ->
            val linkElement = element.selectFirst("a[href*='$baseUrl/']:not(.thumbParodiaNome)") ?: return@forEach
            val titleElement = element.selectFirst(".thumb-titulo") ?: return@forEach
            val title = titleElement.text().trim()
            val url = linkElement.attr("abs:href")
            val thumb = element.selectFirst("img")?.let { extractImageUrl(it) } ?: ""

            if (title.isNotEmpty() && url.startsWith(baseUrl)) {
                mangas.add(
                    SManga.create().apply {
                        this.title = title
                        this.thumbnail_url = thumb
                        setUrlWithoutDomain(url.removePrefix(baseUrl))
                    }
                )
            }
        }

        val hasNextPage = document.selectFirst("link[rel='next']") != null ||
            document.selectFirst("a.next") != null
        return MangasPage(mangas, hasNextPage)
    }

    override fun popularMangaParse(response: Response) = parseMangaList(response)

    override fun latestUpdatesParse(response: Response) = parseMangaList(response)

    // ===== Busca =====

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addQueryParameter("s", query)
            .apply { if (page > 1) addQueryParameter("paged", page.toString()) }
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response) = parseMangaList(response)

    // ===== Detalhes =====

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val manga = SManga.create()

        manga.title = document.selectFirst("h1.post-titulo")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: "Sem título"

        val coverImg = document.selectFirst("div.post-capa img")
        manga.thumbnail_url = if (coverImg != null) {
            extractImageUrl(coverImg)
        } else {
            document.selectFirst("meta[property='og:image']")?.attr("content") ?: ""
        }

        manga.description = document.selectFirst("meta[property='og:description']")?.attr("content")?.trim()
            ?: document.select("ul.post-itens li").joinToString("\n") { it.text().trim() }

        manga.author = document.selectFirst("a[href*='/artista/'], a[href*='/artist/']")?.text()?.trim()
            ?: ""

        val genres = mutableListOf<String>()
        document.select("a[href*='/category/']").eachText().distinct().let { genres.addAll(it) }
        document.select("a[href*='/tag/']").eachText().distinct().let { genres.addAll(it) }
        document.select("a[href*='/parodia/']").eachText().distinct().let { genres.addAll(it) }
        document.select("a[href*='/cor/']").eachText().distinct().let { genres.addAll(it) }
        manga.genre = genres.distinct().joinToString(", ")

        manga.setUrlWithoutDomain(response.request.url.toString().removePrefix(baseUrl))
        return manga
    }

    // ===== Capítulos =====

    override fun chapterListParse(response: Response): List<SChapter> {
        val basePath = response.request.url.toString().removePrefix(baseUrl)
        return listOf(
            SChapter.create().apply {
                name = "Capítulo Único"
                chapter_number = 1f
                setUrlWithoutDomain(basePath)
            }
        )
    }

    // ===== Páginas =====

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val pages = mutableListOf<Page>()

        document.select("ul.post-fotos img").forEachIndexed { index, img ->
            val imageUrl = extractImageUrl(img)
            if (imageUrl.isNotEmpty()) {
                pages.add(Page(index, imageUrl = imageUrl))
            }
        }

        return pages.distinctBy { it.imageUrl }
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder().set("Referer", page.url).build()
        return GET(page.imageUrl!!, newHeaders)
    }
}
