import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "InstaHentai"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"
}
