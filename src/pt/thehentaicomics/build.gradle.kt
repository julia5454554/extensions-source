import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "TheHentaiComics"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.44"

    source {
        lang = "pt-BR"
        baseUrl = "https://thehentaicomics.com"
    }
}
