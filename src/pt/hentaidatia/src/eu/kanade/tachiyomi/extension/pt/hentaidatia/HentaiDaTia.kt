package eu.kanade.tachiyomi.extension.pt.hentaidatia

import eu.kanade.tachiyomi.multisrc.gattsu.Gattsu
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
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
    override val id: Long = 123456789L, // troque por um ID único na publicação
) : Gattsu() {

    override val name = "HentaiDaTia"
    override val baseUrl = "https://hentaidatia.com"

    override val client: OkHttpClient = super.client.newBuilder()
        .rateLimit(2, 1.seconds)
        .build()

    // ============ EXTRAÇÃO DE IMAGEM PARA MINIATURAS ============
    private fun extractImageUrl(element: Element): String {
        // Tenta atributos comuns de lazy load, ignorando data:image
        val direct = element.attr("abs:data-src")
            .ifEmpty { element.attr("abs:data-lazy-src") }
            .ifEmpty { element.attr("abs:data-cfsrc") }
            .ifEmpty { element.attr("abs:data-orig-file") }
            .ifEmpty { element.attr("abs:src") }

        if (direct.isNotEmpty() && !direct.startsWith("data:image")) {
            return direct
        }

        // Se não achou, tenta srcset/data-srcset e pega a maior imagem
        val srcset = element.attr("data-srcset")
            .ifEmpty { element.attr("srcset") }
        if (srcset.isNotEmpty()) {
            val best = srcset.split(",").mapNotNull { entry ->
                val parts = entry.trim().split(Regex("\\s+"))
                if (parts.isEmpty()) return@mapNotNull null
                val url = parts[0]
                val width = parts.getOrNull(1)?.removeSuffix("w")?.toIntOrNull()
                if (url.startsWith("http") || url.startsWith("/")) {
                    width to url
                } else null
            }.maxByOrNull { it.first ?: 0 }

            if (best != null) {
                return if (best.second.startsWith("http")) best.second
                       else baseUrl + best.second
            }
        }
        return ""
    }

    // ============ CONVERSÃO GENÉRICA DE ELEMENTO PARA SManga ============
    private fun genericMangaFromElement(element: Element): SManga = SManga.create().apply {
        val titleEl = element.selectFirst("span.thumb-titulo, h2, h3, .entry-title")
        title = titleEl?.text()?.trim() ?: element.text().trim()

        val imgEl = element.selectFirst("img")
        thumbnail_url = imgEl?.let { extractImageUrl(it) } ?: ""

        val linkEl = element.selectFirst("a")
        setUrlWithoutDomain(linkEl?.attr("href") ?: "")
    }

    // ============ LISTAGEM (POPULARES) ============
    override fun popularMangaRequest(page: Int): Request {
        val pageStr = if (page != 1) "page/$page/" else ""
        return GET("$baseUrl/$pageStr", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        // Seletor correto: div.thumb-conteudo
        val elements = document.select("div.thumb-conteudo")

        val mangas = elements.mapNotNull { el ->
            val link = el.selectFirst("a") ?: return@mapNotNull null
            val href = link.attr("abs:href")

            // Filtra URLs indesejadas
            if (href.isEmpty() ||
                href == "$baseUrl/" ||
                href.contains("/category/") ||
                href.contains("/tag/") ||
                href.contains("/page/") ||
                href.contains("/galeria/")) {
                return@mapNotNull null
            }

            genericMangaFromElement(el)
        }.distinctBy { it.url }

        val hasNextPage = document.selectFirst(
            "ul.paginacao li.next, a.next, .pagination a:contains(›), a.next.page-numbers"
        ) != null

        return MangasPage(mangas, hasNextPage)
    }

    // ============ LISTAGEM (RECENTES) ============
    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)
    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ============ BUSCA ============
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            if (query.isNotEmpty()) {
                addQueryParameter("s", query)
            }
            if (page > 1) {
                addQueryParameter("paged", page.toString())
            }
        }.build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()

        // Seletores comuns em páginas de busca do Gattsu/WordPress
        val selectors = listOf(
            "div.thumb-conteudo",
            "article.post",
            "div.post",
            "div.lista > ul > li div.thumb-conteudo",
            "div.home-box li div.thumb-conteudo"
        )

        var mangas = emptyList<SManga>()
        for (selector in selectors) {
            val elements = document.select(selector)
            if (elements.isNotEmpty()) {
                mangas = elements.mapNotNull { el ->
                    val link = el.selectFirst("a") ?: return@mapNotNull null
                    val href = link.attr("abs:href")
                    if (href.isEmpty() ||
                        href == "$baseUrl/" ||
                        href.contains("/category/") ||
                        href.contains("/tag/") ||
                        href.contains("/page/") ||
                        href.contains("/galeria/")) {
                        return@mapNotNull null
                    }
                    genericMangaFromElement(el)
                }.distinctBy { it.url }
                break
            }
        }

        val hasNextPage = document.selectFirst(
            "ul.paginacao li.next, a.next, .pagination a:contains(›), a.next.page-numbers"
        ) != null

        return MangasPage(mangas, hasNextPage)
    }
}
