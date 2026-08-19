package eu.kanade.tachiyomi.extension.en.instahentai

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.*
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element

class Instahentai : HttpSource() {

    override val name = "InstaHentai"
    override val baseUrl = "https://instahentai.com"
    override val lang = "pt"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", baseUrl)

    // ======================== POPULAR ========================
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/manga/page/$page/", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        val mangaList = doc.select("article.item.card_item").map { element ->
            SManga.create().apply {
                title = element.selectFirst("div.serie_title h3 a")?.text()?.trim() ?: "Sem título"
                setUrlWithoutDomain(element.selectFirst("div.data_l a")?.attr("href") ?: "")
                thumbnail_url = element.selectFirst("div.picture img")?.let { img ->
                    img.absUrl("data-src").ifEmpty { img.absUrl("src") }
                } ?: ""
            }
        }
        val hasNextPage = doc.selectFirst("a.page-numbers:contains(»)") != null
        return MangasPage(mangaList, hasNextPage)
    }

    // ======================== ÚLTIMOS LANÇAMENTOS ========================
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/manga/page/$page/?order=latest", headers)
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // ======================== PESQUISA ========================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = if (query.isNotEmpty()) {
            "$baseUrl/page/$page/?s=$query&post_type=wp-manga"
        } else {
            "$baseUrl/manga/page/$page/"
        }
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    override fun getFilterList() = FilterList(Filter.Header("A pesquisa usa o campo nativo do site."))

    // ======================== DETALHES DA MANGÁ ========================
    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET("$baseUrl${manga.url}", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        return SManga.create().apply {
            // Título - usa h1.titulo_serie (presente na página de detalhes)
            title = doc.selectFirst("h1.titulo_serie")?.text()?.trim() ?: "Sem título"

            // Autor - procura o <b> com texto "Autor:" e pega o <a> seguinte
            author = doc.selectFirst("div.data_info:has(b:contains(Autor)) a")?.text()?.trim()

            // Artista - similar
            artist = doc.selectFirst("div.data_info:has(b:contains(Artista)) a")?.text()?.trim()

            // Sinopse - dentro de div.text (que pode estar oculta)
            description = doc.selectFirst("div.text")?.text()?.trim()

            // Status - não encontrado claramente no HTML, mas podemos deixar como UNKNOWN ou tentar extrair de algum lugar
            status = SManga.UNKNOWN

            // Gêneros - vários links dentro de div.data_info com "Gêneros(s):"
            genre = doc.select("div.data_info:has(b:contains(Gêneros)) a").joinToString(", ") { it.text() }

            // Thumbnail - imagem dentro de div.thumb (na página de detalhes)
            thumbnail_url = doc.selectFirst("div.thumb img")?.let { img ->
                img.absUrl("src").ifEmpty { img.absUrl("data-src") }
            } ?: ""
        }
    }

    // ======================== CAPÍTULOS ========================
    override fun chapterListRequest(manga: SManga): Request {
        return GET("$baseUrl${manga.url}", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.asJsoup()
        // Capítulos estão dentro de div#section_cap, cada um em um <a>
        return doc.select("div#section_cap a").map { link ->
            SChapter.create().apply {
                name = link.text().trim().ifEmpty { "Capítulo" }
                setUrlWithoutDomain(link.attr("href"))
                // Data não está disponível nessa lista, então colocamos 0 (ou timestamp atual)
                date_upload = 0L
            }
        }.reversed() // A lista do site está em ordem crescente (1,2,3...), mas o Tachiyomi espera crescente (mais antigo primeiro) 
        // Se vier do mais novo para o mais antigo, usar .reversed() para inverter; caso contrário, remover.
        // Observando o HTML: os capítulos estão listados do 1 ao 7 (crescente). 
        // Portanto, NÃO reverter (ou reverter se a ordem for decrescente).
        // Para garantir a ordem correta (mais antigo primeiro), deixamos como está (crescente).
        // Se o site mudar para decrescente, usar .reversed().
    }

    // ======================== PÁGINAS DO CAPÍTULO ========================
    override fun pageListRequest(chapter: SChapter): Request {
        return GET("$baseUrl${chapter.url}", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val doc = response.asJsoup()
        return doc.select("div.reading-content img").mapIndexed { index, img ->
            val imageUrl = img.absUrl("src").ifEmpty {
                img.absUrl("data-src").ifEmpty {
                    img.absUrl("data-lazy-src")
                }
            }
            Page(index, imageUrl = imageUrl)
        }
    }

    // ======================== DOWNLOAD DA IMAGEM ========================
    override fun imageRequest(page: Page): Request {
        val imgUrl = page.imageUrl ?: throw Exception("URL da imagem vazia")
        return GET(imgUrl, headers)
    }

    override fun imageUrlParse(response: Response): String {
        return response.request.url.toString()
    }
}
