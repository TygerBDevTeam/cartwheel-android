package com.gameroulette.app

import java.io.InputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import java.util.Locale
import org.xmlpull.v1.XmlPullParserFactory
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import kotlin.random.Random

data class Game(
    val system: String,
    val path: String,
    val name: String,
    val description: String = "",
    val genre: String = "",
    val developer: String = "",
    val year: String = "",
    val rating: Double = 0.0,
    val playCount: Int = 0,
    val favorite: Boolean = false,
    val hidden: Boolean = false,
    val imagePath: String = "",
    val artwork: String = ""
) {
    val id: String get() = system.lowercase(Locale.ROOT) + ":" + normalizePath(path)
}

fun normalizePath(value: String): String {
    val normalized = value.trim().replace('\\', '/')
    return normalized.split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")
}

fun fileStem(path: String): String = path.substringBeforeLast('.', path)
fun displayName(path: String): String = fileStem(path.substringAfterLast('/'))
    .replace(Regex("\\s*\\([^)]*\\)|\\s*\\[[^]]*]"), "").replace('_', ' ').trim()
    .ifBlank { fileStem(path.substringAfterLast('/')) }

/** ES-DE matches the ROM filename, never the scraped display name or a cleaned title. */
fun artworkStems(game: Game): List<String> {
    var path = normalizePath(game.path)
    if (path.startsWith("storage/", true) || Regex("^[A-Za-z]:/").containsMatchIn(path)) {
        val systemStart = path.lastIndexOf("/${game.system}/", ignoreCase = true)
        if (systemStart >= 0) path = path.substring(systemStart + game.system.length + 2)
    }
    val parent = path.substringBeforeLast('/', "")
    val repeatedDirectory = parent.isNotEmpty() && parent.substringAfterLast('/') == path.substringAfterLast('/')
    return (if (repeatedDirectory) listOf(parent, fileStem(path)) else listOf(fileStem(path), path))
        .map { it.lowercase(Locale.ROOT) }.distinct()
}

/** Streaming parser: never loads a full gamelist DOM or resolves external entities. */
object GamelistParser {
    fun parse(input: InputStream, system: String): List<Game> {
        val result = mutableListOf<Game>()
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            // KXml defaults to no declaration processing; reject DOCDECL tokens below.
            setInput(input, null)
        }
        var fields: MutableMap<String, String>? = null
        var tag = ""
        val text = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.DOCDECL -> throw XmlPullParserException("DOCTYPE declarations are not allowed in gamelists.")
                XmlPullParser.START_TAG -> {
                    if (parser.name == "game") fields = linkedMapOf()
                    tag = parser.name
                    text.setLength(0)
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> {
                    if (fields != null) text.append(parser.text ?: "")
                }
                XmlPullParser.END_TAG -> {
                    val values = fields
                    if (values != null) {
                        if (parser.name == "game") {
                            val path = normalizePath(values["path"].orEmpty())
                            if (path.isNotBlank() && !values["nogame"].toBoolean()) {
                                result += Game(
                                    system, path, values["name"].orEmpty().ifBlank { displayName(path) },
                                    description = values["desc"].orEmpty(), genre = values["genre"].orEmpty(),
                                    developer = values["developer"].orEmpty(),
                                    year = values["releasedate"].orEmpty().take(4).takeIf { it.all(Char::isDigit) }.orEmpty(),
                                    rating = values["rating"]?.toDoubleOrNull()?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0,
                                    playCount = values["playcount"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                                    favorite = values["favorite"].toBoolean(), hidden = values["hidden"].toBoolean(),
                                    imagePath = values["image"].orEmpty().ifBlank { values["thumbnail"].orEmpty() }
                                )
                            }
                            fields = null
                        } else if (tag == parser.name) values[parser.name] = text.toString().trim()
                    }
                    text.setLength(0)
                }
            }
            event = parser.nextToken()
        }
        return result.distinctBy(Game::id)
    }
}

data class GameFilters(
    val excludedSystems: Set<String> = emptySet(),
    val genre: String = "",
    val favoritesOnly: Boolean = false,
    val unplayedOnly: Boolean = false
)

object Roulette {
    fun eligible(games: List<Game>, filters: GameFilters, favorites: Set<String>, accepted: Set<String>): List<Game> =
        games.filter { game ->
            !game.hidden && game.system !in filters.excludedSystems &&
                (filters.genre.isBlank() || game.genre.equals(filters.genre, ignoreCase = true)) &&
                (!filters.favoritesOnly || game.favorite || game.id in favorites) &&
                (!filters.unplayedOnly || (game.playCount == 0 && game.id !in accepted))
        }

    fun pick(games: List<Game>, seen: Set<String>, current: String?, random: Random = Random.Default): Game? {
        if (games.isEmpty()) return null
        val remaining = games.filter { it.id !in seen && it.id != current }
        val pool = remaining.ifEmpty { games.filter { it.id != current }.ifEmpty { games } }
        return pool[random.nextInt(pool.size)]
    }

    fun weekKey(date: LocalDate): String = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()

    /** A deterministic weighted discovery pick. Rerolls change the seed and exclude the last pick. */
    fun weekly(games: List<Game>, week: String, reroll: Int, previous: String?, accepted: Set<String>, reviews: Map<String, OnlineReview> = emptyMap()): Game? {
        val available = games.filter { it.id != previous }.ifEmpty { games }
        val reviewed = available.filter { (reviews[it.id]?.low ?: 0) >= 75 }
        // Keep every fourth reroll open to the wider library, including games with no online coverage.
        val pool = (if (reroll % 4 != 3) reviewed.ifEmpty { available } else available).sortedBy(Game::id)
        return pool.minByOrNull { game ->
            val bytes = MessageDigest.getInstance("SHA-256").digest("$week|$reroll|${game.id}".toByteArray(Charsets.UTF_8))
            val bits = bytes.take(6).fold(0L) { total, byte -> (total shl 8) or (byte.toLong() and 255L) }
            val uniform = (bits + 1.0) / (281474976710656.0 + 1.0)
            val novelty = if (game.playCount == 0 && game.id !in accepted) 1.8 else 1.0
            val review = reviews[game.id]
            val onlineWeight = if (review != null) 1.0 + review.low / 100.0 * (if (review.platformSpecific) 4.0 else 2.0) else 1.0
            val weight = (1.0 + game.rating * 2.0) * novelty * onlineWeight
            -kotlin.math.ln(uniform) / weight
        }
    }
}

object RomFiles {
    val extensions = setOf("zip", "7z", "nes", "fds", "unf", "unif", "sfc", "smc", "fig", "swc", "gb", "gbc", "gba", "nds", "3ds", "cci", "cxi", "n64", "z64", "v64", "sms", "gg", "md", "gen", "smd", "32x", "cue", "chd", "iso", "cso", "pbp", "gdi", "cdi", "rvz", "gcm", "wbfs", "wad", "wud", "wux", "wua", "nsp", "xci", "pce", "sgx", "a26", "a52", "a78", "lnx", "ngp", "ngc", "ws", "wsc", "col", "int", "vec", "rom", "bin", "img", "m3u", "dsk", "adf", "ipf", "hdf", "tap", "tzx", "z80", "sna", "cpc", "vpk", "sh", "dosz", "scummvm", "squashfs", "p8", "p8.png")
    val ignoredFolders = setOf("bios", "images", "media", "downloaded_media", "saves", "savestates", "screenshots", "videos", "bezels", "manuals", "textures", "covers", "cache", "system", "lost.dir")
    fun isPlayable(path: String): Boolean {
        val name = path.substringAfterLast('/').lowercase(Locale.ROOT)
        return !name.startsWith('.') && (name.substringAfterLast('.', "") in extensions || name.endsWith(".p8.png")) && name != "bios.bin"
    }

    fun referencedFiles(path: String, text: String): Set<String> {
        val base = path.substringBeforeLast('/', "")
        val lines = when (path.substringAfterLast('.').lowercase(Locale.ROOT)) {
            "m3u" -> text.lineSequence().map(String::trim).filter { it.isNotBlank() && !it.startsWith('#') }.toList()
            "cue" -> Regex("(?im)^\\s*FILE\\s+(?:\"([^\"]+)\"|(\\S+))").findAll(text).map { it.groups[1]?.value ?: it.groupValues[2] }.toList()
            "gdi" -> text.lineSequence().drop(1).mapNotNull { line ->
                Regex("^\\s*\\d+\\s+\\d+\\s+\\d+\\s+\\d+\\s+(?:\"([^\"]+)\"|(\\S+))").find(line)?.let { it.groups[1]?.value ?: it.groupValues[2] }
            }.toList()
            else -> emptyList()
        }
        return lines.map { relative ->
            val parts = mutableListOf<String>()
            for (part in "$base/$relative".replace('\\', '/').split('/')) {
                when (part) { "", "." -> Unit; ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex); else -> parts += part }
            }
            parts.joinToString("/")
        }.toSet()
    }
}

fun systemName(system: String): String = mapOf(
    "nes" to "NES", "famicom" to "Famicom", "snes" to "Super Nintendo", "sfc" to "Super Famicom", "n64" to "Nintendo 64",
    "gb" to "Game Boy", "gbc" to "Game Boy Color", "gba" to "Game Boy Advance", "nds" to "Nintendo DS", "3ds" to "Nintendo 3DS",
    "gc" to "GameCube", "gamecube" to "GameCube", "wii" to "Wii", "wiiu" to "Wii U", "switch" to "Nintendo Switch",
    "psx" to "PlayStation", "ps2" to "PlayStation 2", "psp" to "PSP", "psvita" to "PS Vita", "megadrive" to "Mega Drive",
    "genesis" to "Genesis", "mastersystem" to "Master System", "gamegear" to "Game Gear", "dreamcast" to "Dreamcast",
    "saturn" to "Saturn", "sega32x" to "Sega 32X", "segacd" to "Sega CD", "arcade" to "Arcade", "mame" to "MAME",
    "neogeo" to "Neo Geo", "pcengine" to "PC Engine", "pcenginecd" to "PC Engine CD", "atari2600" to "Atari 2600", "dos" to "DOS"
)[system.lowercase(Locale.ROOT)] ?: system.replaceFirstChar(Char::uppercaseChar)
