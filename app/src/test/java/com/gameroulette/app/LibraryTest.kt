package com.gameroulette.app

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryTest {
    private val games = listOf(
        Game("snes", "./Chrono.sfc", "Chrono", genre = "RPG", rating = .95, favorite = true),
        Game("gba", "Metroid.gba", "Metroid", genre = "Action", playCount = 3),
        Game("psx", "Hidden.chd", "Hidden", hidden = true),
        Game("snes", "Earthbound.sfc", "Earthbound", genre = "RPG")
    )

    @Test fun parsesMetadataAndIgnoresFoldersAndNonGames() {
        val xml = """<?xml version="1.0"?><gameList>
            <folder><path>./RPG</path><name>RPGs</name></folder>
            <game><path>./RPG/Chrono.sfc</path><name>Chrono &amp; Friends</name><desc>Time travel.</desc><rating>0.95</rating><favorite>true</favorite><playcount>2</playcount><releasedate>19950311T000000</releasedate><image>./images/chrono.png</image></game>
            <game><path>./bios.bin</path><nogame>true</nogame></game>
            <game><path>./RPG/Chrono.sfc</path><name>Duplicate</name></game>
            <game><path>./Secret.sfc</path><hidden>true</hidden><rating>NaN</rating></game>
        </gameList>"""
        val parsed = GamelistParser.parse(xml.byteInputStream(), "snes")
        assertEquals(2, parsed.size)
        assertEquals("Chrono & Friends", parsed[0].name)
        assertEquals("RPG/Chrono.sfc", parsed[0].path)
        assertEquals("1995", parsed[0].year)
        assertEquals(.95, parsed[0].rating, .001)
        assertEquals(2, parsed[0].playCount)
        assertTrue(parsed[0].favorite)
        assertTrue(parsed[1].hidden)
        assertEquals(0.0, parsed[1].rating, 0.0)
    }

    @Test fun missingTagsUseSafeDefaultsAndFileNames() {
        val parsed = GamelistParser.parse("<gameList><game><path>./Super_Game (USA).gba</path><playcount>-3</playcount></game><game><name>No file</name></game></gameList>".byteInputStream(), "gba")
        assertEquals(1, parsed.size)
        assertEquals("Super Game", parsed.single().name)
        assertEquals(0, parsed.single().playCount)
    }

    @Test fun rejectsExternalEntities() {
        val xml = """<!DOCTYPE gameList [<!ENTITY secret SYSTEM "file:///nonexistent">]><gameList><game><path>game.sfc</path><name>&secret;</name></game></gameList>"""
        assertTrue(runCatching { GamelistParser.parse(xml.byteInputStream(), "snes") }.isFailure)
    }

    @Test fun combinesSystemGenreFavoriteAndUnplayedFilters() {
        assertEquals(listOf(games[0]), Roulette.eligible(games, GameFilters(genre = "RPG", favoritesOnly = true, unplayedOnly = true), emptySet(), emptySet()))
        assertEquals(listOf(games[3]), Roulette.eligible(games, GameFilters(unplayedOnly = true), emptySet(), setOf(games[0].id)))
        assertTrue(Roulette.eligible(games, GameFilters(excludedSystems = setOf("snes", "gba")), emptySet(), emptySet()).isEmpty())
        assertEquals(listOf(games[3]), Roulette.eligible(games, GameFilters(genre = "RPG", favoritesOnly = true), setOf(games[3].id), emptySet()).filter { !it.favorite })
    }

    @Test fun rouletteWorksThroughPoolBeforeRepeating() {
        val pool = games.filterNot(Game::hidden)
        val seen = mutableSetOf<String>()
        var current: String? = null
        repeat(pool.size) {
            val game = Roulette.pick(pool, seen, current, Random(it))!!
            assertTrue(seen.add(game.id))
            current = game.id
        }
        assertNotEquals(current, Roulette.pick(pool, seen, current, Random(10))!!.id)
        assertNull(Roulette.pick(emptyList(), emptySet(), null))
        assertEquals(pool[0], Roulette.pick(listOf(pool[0]), setOf(pool[0].id), pool[0].id))
    }

    @Test fun weeklyIsStableRegardlessOfLibraryOrderingAndCanReroll() {
        val pool = games.filterNot(Game::hidden)
        val first = Roulette.weekly(pool, "2026-11-02", 0, null, emptySet())!!
        assertEquals(first, Roulette.weekly(pool.reversed(), "2026-11-02", 0, null, emptySet()))
        assertNotEquals(first.id, Roulette.weekly(pool, "2026-11-02", 1, first.id, emptySet())!!.id)
        assertEquals(first, Roulette.weekly(listOf(first), "2026-11-02", 1, first.id, emptySet()))
        assertNull(Roulette.weekly(emptyList(), "2026-11-02", 0, null, emptySet()))
    }

    @Test fun weekStartsMondayAndHandlesYearBoundaries() {
        assertEquals("2026-11-02", Roulette.weekKey(LocalDate.of(2026, 11, 5)))
        assertEquals("2026-11-02", Roulette.weekKey(LocalDate.of(2026, 11, 8)))
        assertEquals("2026-11-09", Roulette.weekKey(LocalDate.of(2026, 11, 9)))
        assertEquals("2025-12-29", Roulette.weekKey(LocalDate.of(2026, 1, 1)))
    }

    @Test fun recognizesGamesAndSkipsNonGameFiles() {
        listOf("Game.SFC", "Game.chd", "Collection.m3u", "cart.p8.png", "Game.rvZ").forEach { assertTrue(it, RomFiles.isPlayable(it)) }
        listOf(".hidden.sfc", "save.srm", "cover.png", "gamelist.xml", "bios.bin", "game.chd.bak").forEach { assertFalse(it, RomFiles.isPlayable(it)) }
    }

    @Test fun discDescriptorsSuppressTracksAndPlaylistDiscsIncludingNestedPaths() {
        assertEquals(setOf("Final Fantasy/Disc 1.chd", "Final Fantasy/Disc 2.chd"), RomFiles.referencedFiles("Final Fantasy/Final Fantasy.m3u", "# discs\n./Disc 1.chd\nDisc 2.chd\n"))
        assertEquals(setOf("Tracks/Game (Track 1).bin", "Tracks/track2.bin"), RomFiles.referencedFiles("Tracks/Game.cue", "FILE \"Game (Track 1).bin\" BINARY\n TRACK 01 MODE2/2352\nFILE track2.bin BINARY"))
        assertEquals(setOf("tracks/track01.bin"), RomFiles.referencedFiles("Game.gdi", "1\n1 0 4 2352 tracks/track01.bin 0"))
        assertEquals(setOf("disc.chd"), RomFiles.referencedFiles("lists/game.m3u", "../disc.chd"))
    }
}
