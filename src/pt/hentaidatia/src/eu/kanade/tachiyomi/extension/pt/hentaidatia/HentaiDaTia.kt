package eu.kanade.tachiyomi.extension.pt.hentaidatia

import eu.kanade.tachiyomi.multisrc.gattsu.Gattsu
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import okhttp3.OkHttpClient
import kotlin.time.Duration.Companion.seconds

@Source
class HentaiDaTia(
    override val lang: String = "pt-BR",
    override val id: Long = 0L, // troque por um ID único na publicação
) : Gattsu() {

    override val name = "HentaiDaTia"
    override val baseUrl = "https://hentaidatia.com"

    override val client: OkHttpClient = super.client.newBuilder()
        .rateLimit(2, 1.seconds)
        .build()
}
