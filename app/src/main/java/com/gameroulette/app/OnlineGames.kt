package com.gameroulette.app

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

fun normalizedTitle(title: String): String = Normalizer.normalize(
    title.replace(Regex("\\s*\\([^)]*\\)|\\s*\\[[^]]*]"), "").trim().replace(Regex("(?i)^the\\s+"), ""), Normalizer.Form.NFKD
).lowercase(Locale.ROOT).replace("&", "and").replace(Regex("[^a-z0-9]"), "")

private val platformIds = mapOf(
    "nes" to "Q172742", "famicom" to "Q172742", "snes" to "Q183259", "sfc" to "Q183259", "n64" to "Q184839",
    "gb" to "Q186437", "gbc" to "Q203992", "gba" to "Q188642", "nds" to "Q170323", "3ds" to "Q203597",
    "gc" to "Q182172", "gamecube" to "Q182172", "wii" to "Q8079", "wiiu" to "Q56942", "switch" to "Q19610114",
    "psx" to "Q10677", "ps2" to "Q10680", "psp" to "Q170325", "psvita" to "Q188808", "megadrive" to "Q10676",
    "genesis" to "Q10676", "mastersystem" to "Q209868", "gamegear" to "Q751719", "dreamcast" to "Q184198",
    "saturn" to "Q200912", "sega32x" to "Q1063978", "segacd" to "Q1047516", "pcengine" to "Q1057377",
    "atari2600" to "Q206261", "atarilynx" to "Q753657", "wonderswan" to "Q1065792", "neogeo" to "Q1054350"
)

data class ReviewRecord(val entity: String, val title: String, val aliases: List<String>, val platform: String, val score: Int, val platformSpecific: Boolean)
data class OnlineReview(val entity: String, val title: String, val low: Int, val high: Int, val platformSpecific: Boolean) {
    val scoreText: String get() = if (low == high) "$low/100" else "$low–$high/100"
    val sourceUrl: String get() = "https://www.wikidata.org/wiki/$entity"
}
data class ReviewCatalog(val records: List<ReviewRecord> = emptyList(), val fetchedAt: Long = 0L)
data class GameArticle(val title: String, val extract: String, val url: String)

object ReviewMatcher {
    fun parse(json: String): List<ReviewRecord> {
        val document = JSONObject(json)
        if (document.has("error")) throw IOException("The review service could not process the request.")
        val rows = document.optJSONObject("results")?.optJSONArray("bindings") ?: throw IOException("The review service returned an unexpected response.")
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.getJSONObject(index)
            fun field(name: String) = row.optJSONObject(name)?.optString("value").orEmpty()
            val entity = field("game").substringAfterLast('/')
            val platform = field("platform").substringAfterLast('/')
            val scoredPlatform = field("scorePlatform").substringAfterLast('/')
            val score = Regex("^(\\d{1,3})/100$").matchEntire(field("score"))?.groupValues?.get(1)?.toIntOrNull()
            if (!Regex("Q[1-9][0-9]*").matches(entity) || platform !in platformIds.values || score == null || score !in 0..100 || field("title").isBlank() || (scoredPlatform.isNotEmpty() && scoredPlatform != platform)) null
            else ReviewRecord(entity, field("title"), field("aliases").split('|').filter(String::isNotBlank), platform, score,
                field("scorePlatform").substringAfterLast('/') == platform)
        }.distinct()
    }

    fun match(games: List<Game>, records: List<ReviewRecord>): Map<String, OnlineReview> {
        val index = mutableMapOf<Pair<String, String>, MutableList<ReviewRecord>>()
        for (record in records) for (title in (listOf(record.title) + record.aliases).distinct()) {
            val key = record.platform to normalizedTitle(title)
            index.getOrPut(key) { mutableListOf() }.add(record)
        }
        return games.mapNotNull { game ->
            val platform = platformIds[game.system.lowercase(Locale.ROOT)] ?: return@mapNotNull null
            val found = listOf(game.name, displayName(game.path)).distinct().firstNotNullOfOrNull { name ->
                index[platform to normalizedTitle(name)]?.takeIf { matches -> matches.map(ReviewRecord::entity).distinct().size == 1 }
            } ?: return@mapNotNull null
            val scores = found.filter(ReviewRecord::platformSpecific).ifEmpty { found }
            game.id to OnlineReview(scores.first().entity, scores.first().title, scores.minOf(ReviewRecord::score), scores.maxOf(ReviewRecord::score), scores.first().platformSpecific)
        }.toMap()
    }
}

class OnlineGames(private val context: Context, private val transport: (String) -> String = ::fetchGameJson) {
    private val cache = AtomicFile(File(context.filesDir, "online-reviews.json"))
    fun loadCatalog(): ReviewCatalog = runCatching {
        if (!cache.baseFile.exists()) return ReviewCatalog()
        val document = cache.openRead().bufferedReader().use { JSONObject(it.readText()) }
        ReviewCatalog(ReviewMatcher.parse(document.getString("response")), document.getLong("fetchedAt"))
    }.getOrDefault(ReviewCatalog())

    suspend fun fetchCatalog(): ReviewCatalog = withContext(Dispatchers.IO) {
        val platforms = platformIds.values.distinct().joinToString(" ") { "wd:$it" }
        val query = """SELECT ?game ?title ?score ?platform ?scorePlatform (GROUP_CONCAT(DISTINCT ?alias; separator="|") AS ?aliases) WHERE {
            VALUES ?platform { $platforms }
            ?review pq:P447 wd:Q150248; ps:P444 ?score .
            ?game p:P444 ?review; wdt:P400 ?platform; rdfs:label ?title .
            FILTER(LANG(?title)="en") FILTER(REGEX(STR(?score), "^[0-9]{1,3}/100$"))
            OPTIONAL { ?review pq:P400 ?scorePlatform } FILTER(!BOUND(?scorePlatform) || ?scorePlatform=?platform)
            OPTIONAL { ?game skos:altLabel ?alias . FILTER(LANG(?alias)="en") }
            } GROUP BY ?game ?title ?score ?platform ?scorePlatform LIMIT 12000""".trimIndent()
        val json = transport("https://query.wikidata.org/sparql?format=json&query=${urlEncode(query)}")
        val parsed = ReviewMatcher.parse(json)
        if (parsed.isEmpty()) throw IOException("No review scores were returned. Try again later; discovery picks still work.")
        val fetchedAt = System.currentTimeMillis()
        val output = cache.startWrite()
        try {
            output.write(JSONObject().put("response", json).put("fetchedAt", fetchedAt).toString().toByteArray(Charsets.UTF_8))
            cache.finishWrite(output)
        } catch (error: Exception) { cache.failWrite(output); throw error }
        ReviewCatalog(parsed, fetchedAt)
    }

    suspend fun articles(query: String): List<GameArticle> = withContext(Dispatchers.IO) {
        val parameters = "action=query&format=json&formatversion=2&generator=search&gsrsearch=${urlEncode(query.take(200) + " video game")}&gsrlimit=3&prop=extracts%7Cinfo&exintro=1&explaintext=1&exchars=700&inprop=url"
        val document = JSONObject(transport("https://en.wikipedia.org/w/api.php?$parameters"))
        if (document.has("error")) throw IOException("Wikipedia is unavailable right now.")
        val pages = document.optJSONObject("query")?.optJSONArray("pages") ?: JSONArray()
        (0 until pages.length()).mapNotNull { index ->
            val page = pages.getJSONObject(index)
            val url = page.optString("fullurl")
            if (runCatching { val uri = URI(url); uri.scheme == "https" && uri.host == "en.wikipedia.org" }.getOrDefault(false))
                GameArticle(page.optString("title"), page.optString("extract").take(1500), url)
            else null
        }
    }
}

private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

private fun fetchGameJson(url: String): String {
    val uri = URI(url)
    require(uri.scheme == "https" && uri.host in setOf("query.wikidata.org", "en.wikipedia.org"))
    val connection = uri.toURL().openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 10_000
        connection.readTimeout = 25_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", "Cartwheel/0.1.1 (https://github.com/TygerBDevTeam/cartwheel-android)")
        connection.setRequestProperty("Accept", "application/json")
        if (connection.responseCode == 429) throw IOException("The online service is busy. Please try again later.")
        if (connection.responseCode != 200) throw IOException("The online service is unavailable (HTTP ${connection.responseCode}).")
        val output = java.io.ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val buffer = ByteArray(8192)
            var count = input.read(buffer)
            while (count != -1) {
                if (output.size() + count > 12 * 1024 * 1024) throw IOException("The online response was too large.")
                output.write(buffer, 0, count)
                count = input.read(buffer)
            }
        }
        return output.toString("UTF-8")
    } finally { connection.disconnect() }
}
