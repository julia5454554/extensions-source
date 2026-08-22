package eu.kanade.tachiyomi.extension.pt.quadrinhosdesexo

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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.time.Duration.Companion.seconds

@Source
class QuadrinhosDeSexo(
    override val lang: String = "pt-BR",
    override val id: Long = 0L, // troque por um ID único
) : HttpSource() {

    override val name = "QuadrinhosDeSexo"
    override val baseUrl = "https://www.quadrinhosdesexo.com"
    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(2, 1.seconds)
        .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // ==================== EXTRAÇÃO DE IMAGEM ====================
    private fun extractImageUrl(element: Element): String {
        val direct = element.attr("abs:data-src")
            .ifEmpty { element.attr("abs:data-lazy-src") }
            .ifEmpty { element.attr("abs:data-cfsrc") }
            .ifEmpty { element.attr("abs:data-orig-file") }
            .ifEmpty { element.attr("abs:src") }
        if (direct.isNotEmpty() && !direct.startsWith("data:image")) return direct

        val srcset = element.attr("data-srcset").ifEmpty { element.attr("srcset") }
        if (srcset.isNotEmpty()) {
            val best = srcset.split(",").mapNotNull { entry ->
                val parts = entry.trim().split(Regex("\\s+"))
                if (parts.isEmpty()) return@mapNotNull null
                val url = parts[0]
                val width = parts.getOrNull(1)?.removeSuffix("w")?.toIntOrNull()
                if (url.startsWith("http") || url.startsWith("/")) width to url else null
            }.maxByOrNull { it.first ?: 0 }
            if (best != null) return if (best.second.startsWith("http")) best.second else baseUrl + best.second
        }
        return element.attr("abs:src")
    }

    // ==================== LISTAGEM (POPULARES) ====================
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
            val link = post.getString("link")   // URL HTML da página do quadrinho
            val thumb = post.optString("jetpack_featured_media_url", "").ifEmpty {
                val content = post.getJSONObject("content").getString("rendered")
                val doc = org.jsoup.Jsoup.parse(content)
                doc.selectFirst("img")?.attr("src") ?: ""
            }

            SManga.create().apply {
                this.title = title
                this.thumbnail_url = thumb
                setUrlWithoutDomain(link.removePrefix(baseUrl))   // guarda URL relativa da página HTML
            }.let { mangas.add(it) }
        }

        val totalPages = response.header("X-WP-TotalPages")?.toIntOrNull() ?: 1
        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val hasNextPage = currentPage < totalPages

        return MangasPage(mangas, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)
    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ==================== BUSCA ====================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/wp-json/wp/v2/posts".toHttpUrl().newBuilder()
            .addQueryParameter("search", query)
            .addQueryParameter("per_page", "20")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ==================== DETALHES (AGORA USA HTML) ====================
    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("h1.entry-title, h1.post-title, h1")?.text()?.trim()
                ?: document.title()
            thumbnail_url = document.selectFirst("div.cn-thumb img, .entry-content img, article img")?.let {
                extractImageUrl(it)
            } ?: ""
            description = document.selectFirst("meta[name='description']")?.attr("content")
                ?: document.selectFirst(".cn-excerpt, .entry-content p")?.text()
                ?: ""
            author = document.selectFirst(".author, .entry-author, a[rel='author']")?.text() ?: ""
            genre = document.select("a[rel='tag'], .entry-tags a, .tags a").map { it.text() }.distinct().joinToString(", ")
            status = SManga.COMPLETED
            update_strategy = UpdateStrategy.ALWAYS_UPDATE
        }
    }

    // ==================== CAPÍTULOS (HTML) ====================
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

    // ==================== PÁGINAS (HTML) ====================
    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val currentUrl = response.request.url.toString()

        // Seletores específicos para o conteúdo do quadrinho
        val selectors = listOf(
            ".cn-texts img",      // principal (conteúdo do tema)
            "div.foto img",       // fallback
            "article img",        // fallback
            ".entry-content img", // fallback
            "img"                 // último recurso
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
