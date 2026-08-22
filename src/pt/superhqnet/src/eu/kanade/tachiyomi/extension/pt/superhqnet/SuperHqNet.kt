package eu.kanade.tachiyomi.extension.pt.superhq

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
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlin.time.Duration.Companion.seconds

@Source
class SuperHq(
    override val lang: String = "pt-BR",
    override val id: Long = 0L, // troque por um ID único na publicação
) : HttpSource() {

    override val name = "SuperHq"
    override val baseUrl = "https://www.superhq.net"
    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(2, 1.seconds)
        .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // ==================== EXTRAÇÃO DE IMAGEM ====================
    private fun extractImageUrl(element: Element): String {
        // Prioriza srcset/data-srcset para obter a maior imagem
        val srcset = element.attr("data-srcset")
            .ifEmpty { element.attr("srcset") }
            .ifEmpty { element.attr("data-lazy-srcset") }

        if (srcset.isNotEmpty()) {
            val candidates = srcset.split(",").mapNotNull { entry ->
                val parts = entry.trim().split(Regex("\\s+"))
                if (parts.isEmpty()) return@mapNotNull null
                val url = parts[0]
                val width = parts.getOrNull(1)?.removeSuffix("w")?.toIntOrNull()
                if (url.startsWith("http") || url.startsWith("/")) {
                    width to url
                } else null
            }
            val best = candidates.maxByOrNull { it.first ?: 0 }
            if (best != null) {
                return if (best.second.startsWith("http")) best.second
                       else baseUrl + best.second
            }
        }

        // Atributos de lazy load
        val direct = element.attr("abs:data-src")
            .ifEmpty { element.attr("abs:data-lazy-src") }
            .ifEmpty { element.attr("abs:data-cfsrc") }
            .ifEmpty { element.attr("abs:data-orig-file") }
            .ifEmpty { element.attr("abs:src") }

        if (direct.isNotEmpty() && !direct.startsWith("data:image")) {
            return direct
        }

        return element.attr("abs:src")
    }

    // ==================== LISTAGEM (API REST) ====================
    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/wp-json/wp/v2/posts".toHttpUrl().newBuilder()
            .addQueryParameter("per_page", "20")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val jsonArray = JSONArray(response.body.string())
        val mangas = mutableListOf<SManga>()

        for (i in 0 until jsonArray.length()) {
            val post = jsonArray.getJSONObject(i)
            val title = post.getJSONObject("title").getString("rendered")
            val link = post.getString("link")   // URL HTML real
            val thumb = post.optString("jetpack_featured_media_url", "").ifEmpty {
                val content = post.getJSONObject("content").getString("rendered")
                val doc = Jsoup.parse(content)
                doc.selectFirst("img")?.attr("src") ?: ""
            }

            SManga.create().apply {
                this.title = title
                this.thumbnail_url = thumb
                setUrlWithoutDomain(link.removePrefix(baseUrl))
            }.let { mangas.add(it) }
        }

        val totalPages = response.header("X-WP-TotalPages")?.toIntOrNull() ?: 1
        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val hasNextPage = currentPage < totalPages

        return MangasPage(mangas, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)
    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ==================== BUSCA (API REST) ====================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/wp-json/wp/v2/posts".toHttpUrl().newBuilder()
            .addQueryParameter("search", query)
            .addQueryParameter("per_page", "20")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ==================== DETALHES (API REST) ====================
    override fun mangaDetailsParse(response: Response): SManga {
        val post = JSONObject(response.body.string())
        val title = post.getJSONObject("title").getString("rendered")
        val thumb = post.optString("jetpack_featured_media_url", "").ifEmpty {
            val content = post.getJSONObject("content").getString("rendered")
            val doc = Jsoup.parse(content)
            doc.selectFirst("img")?.attr("src") ?: ""
        }
        val description = post.getJSONObject("excerpt").getString("rendered")
            .replace(Regex("<[^>]*>"), "").trim()

        return SManga.create().apply {
            this.title = title
            this.thumbnail_url = thumb
            this.description = description
            this.status = SManga.COMPLETED
            this.genre = ""
            this.author = ""
            update_strategy = UpdateStrategy.ALWAYS_UPDATE
        }
    }

    // ==================== CAPÍTULOS ====================
    override fun chapterListParse(response: Response): List<SChapter> {
        // A URL do mangá é a URL HTML normal; usamos a própria página para extrair imagens
        val basePath = response.request.url.toString().removePrefix(baseUrl)
        return listOf(
            SChapter.create().apply {
                name = "Capítulo Único"
                chapter_number = 1f
                setUrlWithoutDomain(basePath)
            }
        )
    }

    // ==================== PÁGINAS (SCRAPING HTML) ====================
    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val currentUrl = response.request.url.toString()

        // Seletores amplos para capturar as imagens do capítulo
        val selectors = listOf(
            ".entry-content img",       // comum em temas WordPress
            "article img",
            ".post-content img",
            "div.foto img",
            "span.aneSliderImagem img",
            "ul.post-fotos img",
            "div.post-box img",
            "img"                       // último recurso
        )

        var images = emptyList<Element>()
        for (selector in selectors) {
            images = document.select(selector)
            if (images.isNotEmpty()) break
        }

        val pages = mutableListOf<Page>()
        var index = 0
        images.forEach { el ->
            val src = extractImageUrl(el)
            if (src.isNotEmpty() && !src.startsWith("data:image")) {
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
