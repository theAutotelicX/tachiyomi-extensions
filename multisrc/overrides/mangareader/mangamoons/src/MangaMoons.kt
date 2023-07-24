package eu.kanade.tachiyomi.extension.all.mangamoons

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.mangareader.MangaReader
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.select.Evaluator
import rx.Observable

open class MangaMoons(
    override val lang: String,
) : MangaReader() {
    override val name = "MangaMoons"

    override val baseUrl = "https://manga-moons.net"

    override val client = network.client.newBuilder()
        .addInterceptor(ImageInterceptor)
        .build()

    override fun latestUpdatesRequest(page: Int) =
        GET("$baseUrl/manga/?order=update&page=$page", headers)

    override fun popularMangaRequest(page: Int) =
        GET("$baseUrl/manga/?order=popular&page=$page", headers)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val urlBuilder = baseUrl.toHttpUrl().newBuilder()
        if (query.isNotBlank()) {
            urlBuilder.addPathSegment("").apply {
                addQueryParameter("s", query)
                addQueryParameter("page", page.toString())
            }
        } else {
            urlBuilder.addPathSegment("").apply {
                addQueryParameter("page", page.toString())
                filters.ifEmpty(::getFilterList).forEach { filter ->
                    when (filter) {
                        is Select -> {
                            addQueryParameter(filter.param, filter.selection)
                        }
                        is DateFilter -> {
                            filter.state.forEach {
                                addQueryParameter(it.param, it.selection)
                            }
                        }
                        is GenresFilter -> {
                            addQueryParameter(filter.param, filter.selection)
                        }
                        else -> {}
                    }
                }
            }
        }
        return GET(urlBuilder.build(), headers)
    }

    override fun searchMangaSelector() = ".listupd .bsx a"

    override fun searchMangaNextPageSelector() = ".hpage .r"

    override fun searchMangaFromElement(element: Element) =
        SManga.create().apply {
            url = element.attr("href")
            element.selectFirst(Evaluator.Tag("img"))!!.let {
                title = it.attr("alt")
                thumbnail_url = it.attr("src")
            }
        }

    private fun Element.parseAuthorsTo(manga: SManga) {
        val authors = select(Evaluator.Tag("a"))
        val text = authors.map { it.ownText().replace(",", "") }
        val count = authors.size
        when (count) {
            0 -> return
            1 -> {
                manga.author = text[0]
                return
            }
        }
        val authorList = ArrayList<String>(count)
        val artistList = ArrayList<String>(count)
        for ((index, author) in authors.withIndex()) {
            val textNode = author.nextSibling() as? TextNode
            val list = if (textNode != null && "(Art)" in textNode.wholeText) artistList else authorList
            list.add(text[index])
        }
        if (authorList.isEmpty().not()) manga.author = authorList.joinToString()
        if (artistList.isEmpty().not()) manga.artist = artistList.joinToString()
    }

    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        val root = document.selectFirst(".main-info")!!

        val mangaTitle = root.selectFirst(Evaluator.Tag("h1"))!!.ownText()
        title = mangaTitle
        description = root.run {
            val description = selectFirst(Evaluator.Tag("h2"))!!.ownText()
            val altTitleElement = selectFirst(Evaluator.Class("alternative"))
            val altTitle = altTitleElement?.ownText() ?: ""
            if (altTitle.isBlank() || altTitle == mangaTitle) {
                description
            } else {
                "$description\n\nAlternative Title: $altTitle"
            }
        }
        thumbnail_url = root.selectFirst(Evaluator.Tag("img"))!!.attr("src")
        genre = root.selectFirst(Evaluator.Class("mgen"))!!.children().joinToString { it.ownText() }
        for (item in root.selectFirst(Evaluator.Class("tsinfo"))!!.children()) {
            if (item.hasClass("imptdt").not()) continue
            when (item.selectFirst(Evaluator.Class("imptdt"))!!.ownText()) {
                "Authors:" -> item.parseAuthorsTo(this)
                "Status:" -> status = when (item.selectFirst(Evaluator.Class("name"))!!.ownText()) {
                    "Finished" -> SManga.COMPLETED
                    "Publishing" -> SManga.ONGOING
                    else -> SManga.UNKNOWN
                }
            }
        }
    }

    override val chapterType get() = "chap"
    override val volumeType get() = "vol"

    private val chapterListSelector = ".chbox .eph-num a"

    override fun chapterListRequest(mangaUrl: String, type: String): Request {
        return GET(mangaUrl, headers)
    }

    override fun parseChapterElements(response: Response, isVolume: Boolean): List<Element> {
        val document = response.asJsoup()
        val chapterElements = document.select(chapterListSelector)
        return chapterElements.subList(2, chapterElements.size)
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.fromCallable {
        val mangaUri = chapter.url.substringBefore('#')
        client.newCall(GET("$baseUrl/$mangaUri", headers)).execute().let(::pageListParse)
    }

    override fun pageListParse(response: Response): List<Page> {
        val pageDocument = response.asJsoup()
        val readerAreaDiv: Element? = pageDocument.selectFirst("#readerarea")

        // Find all img tags within the "readerarea" div
        val imageElements: List<Element> = readerAreaDiv?.select("img") ?: emptyList()

        return imageElements.mapIndexed { index, img ->
            val url = img.attr("src")
            Page(index, imageUrl = url)
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        getPreferences(screen.context).forEach(screen::addPreference)
        super.setupPreferenceScreen(screen)
    }

    override fun getFilterList() =
        FilterList(
            Note,
            TypeFilter(),
            StatusFilter(),
            StartDateFilter(),
            EndDateFilter(),
            SortFilter(),
            GenresFilter(),
        )
}
