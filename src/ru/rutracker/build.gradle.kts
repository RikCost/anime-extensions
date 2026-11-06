import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "RuTracker"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "16"
    torrent = true

    source {
        lang = "ru"
        baseUrl {
            mirrors(
                "https://rutracker.org",
                "https://rutracker.net",
            )
        }
    }
}
