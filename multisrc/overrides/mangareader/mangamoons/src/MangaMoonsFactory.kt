package eu.kanade.tachiyomi.extension.all.mangamoons

import eu.kanade.tachiyomi.source.SourceFactory

class MangaMoonsFactory : SourceFactory {
    override fun createSources() = listOf(
        MangaMoons("th")
    )
}
