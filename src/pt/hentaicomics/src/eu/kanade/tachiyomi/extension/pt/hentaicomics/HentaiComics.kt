package eu.kanade.tachiyomi.extension.pt.hentaicomics

import eu.kanade.tachiyomi.annotations.Source
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

@Source
class HentaiComics : HttpSource() {

    override val name = "HentaiComics"
    override val baseUrl = "https://hentaicomics.biz"
    override val lang = "pt-BR"
    override val supportsLatest = true

    // Rate limit e Cookie de idade
    override val client = network.client.newBuilder()
        .rateLimit(2, 1, TimeUnit.SECONDS)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Cookie", "ageVerified=true")

    // --- LISTAGEM E BUSCA ---

    override fun popularMangaRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/top-hentais/" else "$baseUrl/top-hentais/page/$page/"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        val mangas = doc.select("div.post").mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            val href = a.absUrl("href").ifBlank { return@mapNotNull null }
            val title = a.attr("title").ifBlank { a.selectFirst("img")?.attr("alt").orEmpty() }
            val thumb = a.selectFirst("img")?.absUrl("src").orEmpty()
            SManga.create().apply {
                url = href
                this.title = title
                thumbnail_url = thumb
            }
        }
        // Paginação baseada na div.paginador e no link "Proxima"
        val hasNext = doc.selectFirst("div.paginador a:contains(Proxima)") != null
        return MangasPage(mangas, hasNext)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/" else "$baseUrl/page/$page/"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = if (page == 1) {
            "$baseUrl/?s=$encoded"
        } else {
            "$baseUrl/page/$page/?s=$encoded"
        }
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // --- DETALHES ---

    override fun mangaDetailsRequest(manga: SManga): Request = GET(manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        return SManga.create().apply {
            title = doc.selectFirst("h1.post-title")?.text()?.trim() ?: ""
            // Descrição: pega apenas os parágrafos dentro do single-post, ignorando anúncios (que são divs)
            description = doc.select("div.single-post > p").joinToString("\n") { it.text().trim() }.trim()
            // Gêneros/Tags
            genre = doc.select("a[rel=tag]").joinToString { it.text().trim() }
            // Thumbnail
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("div.single-post p img")?.absUrl("src").orEmpty()
        }
    }

    // --- CAPÍTULOS (POST ÚNICO) ---

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> = listOf(
        SChapter.create().apply {
            url = response.request.url.toString()
            name = "Capítulo Único"
            chapter_number = 1f
        },
    )

    // --- PÁGINAS DO CAPÍTULO ---

    override fun pageListRequest(chapter: SChapter): Request = GET(chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val doc = response.asJsoup()
        // Seleciona imagens dentro do post e filtra anúncios baseados no estilo 'text-align'
        val images = doc.select("div.single-post img").filterNot { img ->
            img.parents().any { parent ->
                parent.tagName() == "div" && parent.attr("style").contains("text-align")
            }
        }
        return images.mapIndexedNotNull { index, img ->
            val url = img.absUrl("src").ifBlank { img.absUrl("data-src") }
            if (url.isNotBlank()) Page(index, imageUrl = url) else null
        }
    }

    override fun imageUrlParse(response: Response): String = ""
}
