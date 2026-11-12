package com.example.browse

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * One scraped drama card from a wow-drama.com listing page.
 */
data class MovieCard(
    val detailUrl: String,
    val title: String,
    val posterUrl: String?,
    val badge: String?,
    val imdb: String?,
    val epInfo: String?,
    val year: String?
)

/**
 * Cards from one listing page + the URL of the next page (for "load more").
 */
data class ScrapeResult(
    val cards: List<MovieCard>,
    val nextPageUrl: String?
)

/**
 * Scrapes wow-drama.com listing pages (HTML) into MovieCard lists.
 *
 * The site's card markup is stable across category/home/search pages:
 *   .list-movie > .-movie { .pic a[href], .pic img, .qa-label, .imdb, .epseries, .year, .info h2/h3 }
 *
 * No API is exposed by the site (REST API is login-gated), so HTML scraping
 * with well-known selectors is the data source for the native browse grid.
 */
object WowScraper {

    private const val UA =
        "Mozilla/5.0 (Android TV; WOW Drama TV/1.2) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    suspend fun loadPage(url: String): ScrapeResult? = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.connect(url)
                .userAgent(UA)
                .referrer("https://wow-drama.com/")
                .timeout(15_000)
                .get()

            val cards = parseCards(doc)
            if (cards.isEmpty()) null else ScrapeResult(cards, findNextPage(doc))
        } catch (e: Exception) {
            android.util.Log.w("WowScraper", "Failed to scrape $url", e)
            null
        }
    }

    private fun parseCards(doc: Document): List<MovieCard> {
        var nodes = doc.select(".list-movie .-movie")
        if (nodes.isEmpty()) nodes = doc.select(".-movie")

        val out = ArrayList<MovieCard>(nodes.size)
        for (el in nodes) {
            try {
                val link = el.selectFirst(".pic a[href]") ?: el.selectFirst("a[href]") ?: continue
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (href.isBlank()) continue

                val img = el.selectFirst("img")
                val poster: String? = img?.let { image ->
                    listOf("src", "data-lazy-src", "data-src", "data-original")
                        .firstNotNullOfOrNull { attr ->
                            image.attr(attr)
                                .takeIf { value -> value.isNotBlank() && !value.startsWith("data:") }
                        }
                        ?.let { raw -> image.absUrl(raw).ifBlank { raw } }
                }

                val title = el.selectFirst(".info h2, .info h3, .info a")?.text()?.trim()
                    ?: img?.attr("alt")?.trim().orEmpty()
                if (title.isBlank()) continue

                val imdb = el.selectFirst(".imdb")?.text()?.trim()

                // Mirror the WebView behavior: on listing pages, keep Thai-dubbed
                // entries (drop cards that are ONLY Thai-subbed)
                if (imdb != null && imdb.contains("ซับไทย") && !imdb.contains("พากย์ไทย")) {
                    continue
                }

                out.add(
                    MovieCard(
                        detailUrl = href,
                        title = title,
                        posterUrl = poster,
                        badge = el.selectFirst(".qa-label")?.text()?.trim()?.takeIf { it.isNotBlank() },
                        imdb = imdb?.takeIf { it.isNotBlank() },
                        epInfo = el.selectFirst(".epseries")?.text()?.trim()?.takeIf { it.isNotBlank() },
                        year = el.selectFirst(".year")?.text()?.trim()?.takeIf { it.isNotBlank() }
                    )
                )
            } catch (e: Exception) {
                // Skip malformed card, keep the rest
            }
        }
        return out
    }

    private fun findNextPage(doc: Document): String? {
        // rel=next can be a <link> in <head> or an <a> in the pagination area
        val next = doc.selectFirst("a[rel=next], link[rel=next]")
            ?: doc.selectFirst(".pagination a.next, .nav-links a.next, a.next")
            ?: return null
        val href = next.absUrl("href").ifBlank { next.attr("href") }
        return href.takeIf { it.isNotBlank() && it.contains("wow-drama.com") }
    }
}
