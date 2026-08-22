package eu.kanade.tachiyomi.extension.pt.hentaisblog

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
class HentaisBlog(
    override val lang: String = "pt-BR",
    override val id: Long = 0L, // troque por um ID único na publicação
) : HttpSource() {

    override val name = "HentaisBlog"
    override val baseUrl = "https://hentais.blog"
    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(2, 1.seconds)
        .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // ============ EXTRAÇÃO DE IMAGEM ============
    private fun extractImageUrl(element: Element): String {
        // Tenta atributos comuns de lazy load, ignorando placeholders data:image
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

    // ============ LISTAGEM (POPULARES) ============
    override fun popularMangaRequest(page: Int): Request {
        val pageStr = if (page != 1) "page/$page/" else ""
        return GET("$baseUrl/$pageStr", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()

        // Seletor correto para os cards: <a> que contém <span class="itemTitulo">
        val items = document.select("a:has(span.itemTitulo)")

        val mangas = items.mapNotNull { a ->
            val href = a.attr("abs:href")
            if (href.isEmpty() ||
                href == "$baseUrl/" ||
                href.contains("/category/") ||
                href.contains("/tag/") ||
                href.contains("/page/") ||
                href.contains("/galeria/")) {
                return@mapNotNull null
            }

            SManga.create().apply {
                title = a.selectFirst("span.itemTitulo")?.text()?.trim()
                    ?: a.attr("title").trim()
                thumbnail_url = a.selectFirst("img")?.let { extractImageUrl(it) } ?: ""
                setUrlWithoutDomain(href)
            }
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

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ============ DETALHES ============
    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("h1.entry-title, h1.post-title, h1")?.text()?.trim() ?: ""
            author = document.select("ul.post-itens li:contains(Artista:) a, .entry-terms:contains(Artista) a").text()
            genre = document.select("ul.post-itens li:contains(Tags:) a, .entry-categories a, .entry-tags a, a[rel='tag']")
                .map { it.text() }.distinct().joinToString(", ")
            description = document.select("ul.post-itens li:contains(Cor:), .entry-content p:not(:has(img))").text()
            status = SManga.COMPLETED
            val mainImg = document.selectFirst("div.post-capa img, .entry-content img, article img")
            thumbnail_url = mainImg?.let { extractImageUrl(it) }
            update_strategy = if (document.selectFirst("div.listaImagens div.galeriaTab") != null)
                UpdateStrategy.ALWAYS_UPDATE else UpdateStrategy.ONLY_FETCH_ONCE
        }
    }

    // ============ CAPÍTULOS ============
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val basePath = document.location().removePrefix(baseUrl).substringBefore("#").substringBefore("?")

        // Se houver múltiplos capítulos (ex.: galeriaTab), tenta detectar
        val multipleChapters = document.select("div.listaImagens div.galeriaTab")
        if (multipleChapters.isNotEmpty()) {
            return multipleChapters.map { element ->
                val chapterId = element.attr("data-id")
                val title = element.selectFirst("div.galeriaTabTitulo")?.text()
                SChapter.create().apply {
                    name = "Capítulo $chapterId" + (if (!title.isNullOrEmpty()) " - $title" else "")
                    chapter_number = chapterId.toFloatOrNull() ?: -1f
                    setUrlWithoutDomain("$basePath?chapter=$chapterId")
                }
            }.reversed()
        }

        // Caso contrário, capítulo único
        return listOf(
            SChapter.create().apply {
                name = "Capítulo Único"
                chapter_number = 1f
                setUrlWithoutDomain(basePath)
            }
        )
    }

    // ============ PÁGINAS ============
    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val currentUrl = document.location()

        val chapterId = currentUrl.substringAfterLast("chapter=", "").substringBefore("&")

        // Seletores amplos para capturar as imagens do conteúdo
        val selectors = if (chapterId.isNotEmpty()) {
            listOf(
                "#galeria-$chapterId img",
                "div.listaImagens #galeria-$chapterId img",
                "div.listaImagens img",
                "article img",
                ".entry-content img",
                ".post-content img",
                "div[class*='content'] img",
                "div[class*='conteudo'] img",
                "div.post img"
            )
        } else {
            listOf(
                "div.listaImagens ul.post-fotos img",
                "div.listaImagens img",
                "article img",
                ".entry-content img",
                ".post-content img",
                "div[class*='content'] img",
                "div[class*='conteudo'] img",
                "div.post img"
            )
        }

        var images = emptyList<Element>()
        for (selector in selectors) {
            images = document.select(selector)
            if (images.isNotEmpty()) break
        }

        val pages = mutableListOf<Page>()
        var index = 0

        images.forEach { el ->
            val src = extractImageUrl(el)
            val lowerSrc = src.lowercase()

            // Filtra imagens que não fazem parte do capítulo
            val isValid = src.isNotEmpty() &&
                !lowerSrc.contains("logo") &&
                !lowerSrc.contains("banner") &&
                !lowerSrc.contains("discord") &&
                !lowerSrc.contains("icon") &&
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
