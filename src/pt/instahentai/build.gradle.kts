import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "InstaHentai"
    versionCode = 1 // Altere para o número correto (geralmente segue a ordem cronológica)
    contentWarning = ContentWarning.NSFW // Se for +18
    libVersion = "1.6"

    source {
        lang = "pt-BR"
        baseUrl = "https://instahentai.com" // Confirme se a URL base correta é essa
    }
}
