package eu.kanade.tachiyomi.extension.pt.nhentainetbr

import eu.kanade.tachiyomi.network.GET
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

@Source
class NHentaiNetBr(
    override val lang: String = "pt-BR",
    override val id: Long = 0L,
) : HttpSource() {

    override val name = "nhentai.net.br"
    override val baseUrl = "https://nhentai.net.br"
    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(2, 1, TimeUnit.SECONDS)
        .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        .add("Referer", "$baseUrl/")

    // Extrai URL de imagem com fallback para lazy load e srcset
    private fun extractImageUrl(element: Element): String {
        // Prioriza srcset (maior imagem)
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
        return if (raw.startsWith("http://") || raw.startsWith("https://")) raw
               else baseUrl + raw.removePrefix("/")
    }

    // ===== Listagem (popular e latest) =====
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
            // Pega o link principal (não o de paródia)
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

        // Título
        manga.title = document.selectFirst("h1.post-titulo")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: "Sem título"

        // Capa: tentar srcset da post-capa ou og:image
        val coverImg = document.selectFirst("div.post-capa img")
        if (coverImg != null) {
            manga.thumbnail_url = extractImageUrl(coverImg)
        } else {
            manga.thumbnail_url = document.selectFirst("meta[property='og:image']")?.attr("content") ?: ""
        }

        // Descrição: usar og:description ou concatenar tags
        manga.description = document.selectFirst("meta[property='og:description']")?.attr("content")?.trim()
            ?: document.select("ul.post-itens li").joinToString("\n") { it.text().trim() }

        // Autor (não disponível publicamente, tenta pegar algo)
        manga.author = document.selectFirst("a[href*='/artista/'], a[href*='/artist/']")?.text()?.trim()
            ?: ""

        // Gêneros: categorias + tags + paródia + cor
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
        // Como as imagens estão na própria página do mangá, retornamos um único capítulo com a mesma URL
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

        // Seleciona imagens dentro de ul.post-fotos (evita anúncios)
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
