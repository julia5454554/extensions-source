package eu.kanade.tachiyomi.extension.pt.threehentainetbr

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
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
class ThreeHentaiNetBr(
    override val lang: String = "pt-BR",
    override val id: Long = 2024060001L, // ⚠️ Troque por um ID único na publicação
) : HttpSource() {

    override val name = "3Hentai.net.br"
    override val baseUrl = "https://3hentai.net.br"
    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(2, 1.seconds)
        .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // ==================== EXTRAÇÃO DE URL DE IMAGEM ====================
    private fun extractImageUrl(element: Element): String {
        val raw = element.attr("data-lazy-src")
            .ifEmpty { element.attr("data-src") }
            .ifEmpty { element.attr("abs:src") }
            .ifEmpty { element.attr("src") }

        if (raw.isEmpty()) return ""

        return if (raw.startsWith("http://") || raw.startsWith("https://")) {
            raw
        } else {
            baseUrl + raw.removePrefix("/")
        }
    }

    // ==================== LISTAGEM (API REST WordPress) ====================
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

        // Palavras-chave comuns em posts patrocinados/anúncios para filtrar
        val adKeywords = listOf(
            "download", "grátis", "acesse", "clique", "patrocinado",
            "publicidade", "site", "anúncio", "ads", "vazou", "torrent"
        )

        for (i in 0 until jsonArray.length()) {
            val post = jsonArray.getJSONObject(i)
            val id = post.getInt("id")
            val title = Jsoup.parse(post.getJSONObject("title").getString("rendered")).text()
            val link = post.getString("link")

            // Garante que o link pertence ao domínio
            if (!link.contains("3hentai.net.br")) continue

            // Filtra posts que parecem anúncios
            val lowerTitle = title.lowercase()
            if (adKeywords.any { lowerTitle.contains(it) }) continue

            // Obtém o HTML do conteúdo para extrair imagens e validar
            val contentHtml = post.getJSONObject("content").getString("rendered")
            val doc = Jsoup.parse(contentHtml)
            val images = doc.select("img")

            // Pula se houver menos de 2 imagens (provável anúncio ou post vazio)
            if (images.size < 2) continue

            val apiUrl = "/wp-json/wp/v2/posts/$id"
            val thumb = post.optString("jetpack_featured_media_url", "").ifEmpty {
                images.firstOrNull()?.let { extractImageUrl(it) } ?: ""
            }

            SManga.create().apply {
                this.title = title
                this.thumbnail_url = thumb
                setUrlWithoutDomain(apiUrl)
            }.let { mangas.add(it) }
        }

        // Paginação via cabeçalhos HTTP do WordPress
        val totalPages = response.header("X-WP-TotalPages")?.toIntOrNull() ?: 1
        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val hasNextPage = currentPage < totalPages

        return MangasPage(mangas, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)
    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ==================== BUSCA (API REST WordPress) ====================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/wp-json/wp/v2/posts".toHttpUrl().newBuilder()
            .addQueryParameter("search", query)
            .addQueryParameter("per_page", "20")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ==================== DETALHES (API REST WordPress) ====================
    override fun mangaDetailsRequest(manga: SManga): Request {
        val url = manga.url.toHttpUrl().newBuilder()
            .addQueryParameter("_embed", "1") // Inclui taxonomias (categorias, tags, custom)
            .build()
        return GET(url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val post = JSONObject(response.body.string())
        val title = Jsoup.parse(post.getJSONObject("title").getString("rendered")).text()
        val thumb = post.optString("jetpack_featured_media_url", "").ifEmpty {
            val content = post.getJSONObject("content").getString("rendered")
            val doc = Jsoup.parse(content)
            doc.selectFirst("img")?.let { extractImageUrl(it) } ?: ""
        }
        val description = Jsoup.parse(post.getJSONObject("excerpt").getString("rendered")).text().trim()

        // Extrai gêneros (categorias, tags e taxonomias customizadas) do _embedded
        val genres = mutableListOf<String>()
        val embedded = post.optJSONObject("_embedded")
        if (embedded != null) {
            val terms = embedded.optJSONArray("wp:term")
            if (terms != null) {
                for (i in 0 until terms.length()) {
                    val termArray = terms.optJSONArray(i)
                    if (termArray != null) {
                        for (j in 0 until termArray.length()) {
                            val term = termArray.optJSONObject(j)
                            val name = term?.optString("name")
                            if (!name.isNullOrBlank()) {
                                genres.add(name)
                            }
                        }
                    }
                }
            }
        }

        return SManga.create().apply {
            this.title = title
            this.thumbnail_url = thumb
            this.description = description
            this.genre = genres.joinToString(", ")
            this.status = SManga.COMPLETED
            this.author = ""
            update_strategy = UpdateStrategy.ALWAYS_UPDATE
        }
    }

    // ==================== CAPÍTULOS (cada post = 1 capítulo) ====================
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

    // ==================== PÁGINAS (extrai imagens do conteúdo) ====================
    override fun pageListParse(response: Response): List<Page> {
        val post = JSONObject(response.body.string())
        val contentHtml = post.getJSONObject("content").getString("rendered")
        val doc = Jsoup.parse(contentHtml)

        val images = doc.select("img")
        val pages = mutableListOf<Page>()
        var index = 0

        images.forEach { img ->
            val src = extractImageUrl(img)
            if (src.isNotEmpty() && !src.startsWith("data:image")) {
                pages.add(Page(index++, url = baseUrl, imageUrl = src))
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
