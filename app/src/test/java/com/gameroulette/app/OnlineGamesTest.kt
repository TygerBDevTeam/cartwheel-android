package com.gameroulette.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnlineGamesTest {
    private fun response(score: String = "92/100", platform: String = "Q10677", scorePlatform: String? = platform): String {
        val row = JSONObject()
        fun add(key: String, value: String) { row.put(key, JSONObject().put("value", value)) }
        add("game", "http://www.wikidata.org/entity/Q123")
        add("title", "Test Game")
        add("score", score)
        add("platform", "http://www.wikidata.org/entity/$platform")
        add("aliases", "An Alternate Title")
        scorePlatform?.let { add("scorePlatform", "http://www.wikidata.org/entity/$it") }
        return JSONObject().put("results", JSONObject().put("bindings", JSONArray().put(row))).toString()
    }

    @Test fun rejectsInvalidScoresAndWrongPlatformVersions() {
        assertEquals(92, ReviewMatcher.parse(response()).single().score)
        assertTrue(ReviewMatcher.parse(response("101/100")).isEmpty())
        assertTrue(ReviewMatcher.parse(response("NaN")).isEmpty())
        assertTrue(ReviewMatcher.parse(response(scorePlatform = "Q19610114")).isEmpty())
        assertFalse(ReviewMatcher.parse(response(scorePlatform = null)).single().platformSpecific)
    }

    @Test fun matchesAliasesAndSystemButRejectsAmbiguousRemakes() {
        val records = ReviewMatcher.parse(response())
        val psx = Game("psx", "An Alternate Title (USA).chd", "An Alternate Title")
        val snes = psx.copy(system = "snes")
        assertEquals(setOf(psx.id), ReviewMatcher.match(listOf(psx, snes), records).keys)
        assertTrue(ReviewMatcher.match(listOf(psx), records + records.single().copy(entity = "Q456")).isEmpty())
    }

    @Test fun keepsUnspecifiedScoreRangesAndPrefersPlatformSpecificScores() {
        val game = Game("psx", "Test Game.chd", "Test Game")
        val generic = ReviewMatcher.parse(response(scorePlatform = null)).single()
        val range = ReviewMatcher.match(listOf(game), listOf(generic, generic.copy(score = 80)))[game.id]!!
        assertEquals("80–92/100", range.scoreText)
        assertFalse(range.platformSpecific)
        val specific = ReviewMatcher.match(listOf(game), listOf(generic, generic.copy(score = 85, platformSpecific = true)))[game.id]!!
        assertEquals("85/100", specific.scoreText)
        assertTrue(specific.platformSpecific)
        assertEquals("https://www.wikidata.org/wiki/Q123", specific.sourceUrl)
    }

    @Test fun onlineRatingsWorkWithoutPersonalRatingsAndRerollsCanLeaveSingletons() {
        val rated = Game("psx", "Test Game.chd", "Test Game")
        val other = Game("snes", "Other.sfc", "Other")
        val reviews = ReviewMatcher.match(listOf(rated, other), ReviewMatcher.parse(response()))
        assertEquals(0.0, rated.rating, 0.0)
        assertEquals(rated, Roulette.weekly(listOf(rated, other), "2026-11-02", 0, null, emptySet(), reviews))
        assertEquals(other, Roulette.weekly(listOf(rated, other), "2026-11-02", 1, rated.id, emptySet(), reviews))
    }

    @Test fun failedRefreshPreservesTheSavedCatalog() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val json = response()
        var fail = false
        val service = OnlineGames(context) { url ->
            assertTrue(url.startsWith("https://query.wikidata.org/"))
            if (fail) throw IOException("offline")
            json
        }
        val first = service.fetchCatalog()
        fail = true
        assertTrue(runCatching { service.fetchCatalog() }.isFailure)
        assertEquals(first, service.loadCatalog())
    }

    @Test fun backgroundLookupKeepsArticleSourcesAndRejectsUntrustedLinks() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val json = """{"query":{"pages":[{"title":"Test Game","extract":"Game background.","fullurl":"https://en.wikipedia.org/wiki/Test_Game"},{"title":"Wrong link","fullurl":"https://example.com"}]}}"""
        val articles = OnlineGames(context) { url -> assertTrue(url.startsWith("https://en.wikipedia.org/w/api.php?")); json }.articles("Test Game")
        assertEquals(1, articles.size)
        assertEquals("Game background.", articles.single().extract)
    }

    @Test fun normalizesTitlesWithoutRemovingNumbersOrWordPrefixes() {
        assertEquals(normalizedTitle("The Legend of Zelda"), normalizedTitle("Legend of Zelda (USA)"))
        assertNotEquals(normalizedTitle("Final Fantasy VII"), normalizedTitle("Final Fantasy VIII"))
        assertEquals("theatre", normalizedTitle("Theatre"))
        assertEquals("pokemon", normalizedTitle("Pokémon"))
    }

    @Test fun artworkUsesRomNamesRegionTagsAndAbsolutePathSystem() {
        assertEquals("rpg/game (usa)", artworkStems(Game("snes", "RPG/Game (USA).sfc", "Different title")).first())
        assertEquals("rpg/game (usa)", artworkStems(Game("snes", "/storage/emulated/0/ROMs/snes/RPG/Game (USA).sfc", "Different title")).first())
        assertEquals("dig.scummvm", artworkStems(Game("scummvm", "dig.scummvm/dig.scummvm", "The Dig")).first())
    }
}
