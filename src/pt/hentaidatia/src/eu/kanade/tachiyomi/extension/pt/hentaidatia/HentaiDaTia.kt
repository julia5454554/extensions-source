package eu.kanade.tachiyomi.extension.pt.hentaidatia

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.multisrc.gattsu.Gattsu
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import kotlin.time.Duration.Companion.seconds

@Source
class HentaiDaTia(
    override val lang: String = "pt-BR",
    override val id: Long = 0L, // use um ID único real
) : Gattsu() {

    override val name = "HentaiDaTia"
    override val baseUrl = "https://hentaidatia.com"

    override val client: OkHttpClient = super.client.newBuilder()
        .rateLimit(2, 1.seconds)
        .build()

    // ---------- Overrides para listagem ----------

    private fun extractImageUrl(element: Element): String {
        // Mantém a lógica que já funcionava no seu código manual
        val direct = element.attr("abs:data-src")
            .ifEmpty { element.attr("abs:data-lazy-src") }
            .ifEmpty { element.attr("abs:data-cfsrc") }
            .ifEmpty { element.attr("abs:data-orig-file") }
            .ifEmpty { element.attr("abs:src") }

        if (direct.isNotEmpty() && !direct.startsWith("data:image")) {
            return direct
        }

        val srcset = element.attr("data-srcset")
            .ifEmpty { element.attr("srcset") }

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

            val best = candidates.maxByOrNull { it.first ?: 0 } ?: candidates.firstOrNull()
            if (best != null) {
                return if (best.second.startsWith("http")) best.second
                       else baseUrl + best.second
            }
        }

        return ""
    }

    private fun genericMangaFromElement(element: Element): SManga = SManga.create().apply {
        val titleEl = element.selectFirst("span.thumb-titulo, h2, h3, .entry-title")
        title = titleEl?.text()?.trim() ?: element.text().trim()

        val imgEl = element.selectFirst("img")
        thumbnail_url = imgEl?.let { extractImageUrl(it) } ?: ""

        val linkEl = element.selectFirst("a")
        setUrlWithoutDomain(linkEl?.attr("href") ?: "")
    }

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

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)
    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

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
}
