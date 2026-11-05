package com.gameroulette.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.AtomicFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale

data class LibraryConfig(val source: String = "esde", val roms: String = "", val esde: String = "", val media: String = "")
data class ImportResult(val games: List<Game>, val warnings: List<String>)

/** Bulk directory queries avoid DocumentFile's per-file stat calls on big SD cards. */
class ContentTree(private val context: Context, value: String) {
    data class Entry(val id: String, val name: String, val directory: Boolean)
    private val tree = Uri.parse(value)
    val rootId: String = DocumentsContract.getTreeDocumentId(tree)
    private val cache = mutableMapOf<String, List<Entry>>()
    private val parents = mutableMapOf<String, String>()
    fun uri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
    fun children(id: String): List<Entry> = cache.getOrPut(id) {
        val query = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        val result = mutableListOf<Entry>()
        val cursor = context.contentResolver.query(query, projection, null, null, null)
            ?: throw IOException("This folder could not be read. Please select it again.")
        cursor.use {
            while (it.moveToNext()) {
                val entry = Entry(it.getString(0), it.getString(1).orEmpty(), it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
                parents[entry.id] = id
                result += entry
            }
        }
        result
    }
    fun child(id: String, name: String): Entry? = children(id).firstOrNull { it.name.equals(name, true) }
    fun resolve(path: String, start: String = rootId): Entry? {
        val raw = path.replace('\\', '/')
        val relative = if (raw.startsWith('/')) {
            // ExternalStorageProvider IDs map both primary storage and removable SD cards.
            val selected = rootId.substringAfter(':', "")
            val rootPrefix = if (rootId.startsWith("primary:")) "/storage/emulated/0/" else "/storage/${rootId.substringBefore(':')}/"
            val selectedPath = rootPrefix + selected.trim('/')
            if (!raw.startsWith("$selectedPath/")) return null
            raw.removePrefix("$selectedPath/")
        } else raw
        val stack = mutableListOf(if (raw.startsWith('/')) rootId else start)
        while (stack.first() != rootId) {
            val parent = parents[stack.first()] ?: break
            if (parent in stack) break
            stack.add(0, parent)
        }
        var found: Entry? = null
        for (part in relative.split('/')) {
            when (part) {
                "", "." -> continue
                ".." -> {
                    if (stack.size == 1) return null
                    stack.removeAt(stack.lastIndex)
                    found = null
                }
                else -> {
                    found = child(stack.last(), part) ?: return null
                    stack += found.id
                }
            }
        }
        return found
    }
    fun open(entry: Entry) = context.contentResolver.openInputStream(uri(entry.id)) ?: throw IOException("Cannot open ${entry.name}")
}

class LibraryRepository(private val context: Context) {
    private val cacheFile = AtomicFile(File(context.filesDir, "library.json"))

    suspend fun import(config: LibraryConfig, progress: (String) -> Unit): ImportResult {
        val warnings = mutableListOf<String>()
        val romTree = config.roms.takeIf(String::isNotBlank)?.let { ContentTree(context, it) }
        val raw = if (config.source == "esde") {
            require(config.esde.isNotBlank()) { "Choose your ES-DE folder first." }
            importGamelists(ContentTree(context, config.esde), romTree, warnings, progress)
        } else {
            require(romTree != null) { "Choose your ROMs folder first." }
            scanRoms(romTree, warnings, progress)
        }
        require(raw.isNotEmpty()) { if (config.source == "esde") "No games found. Choose the ES-DE folder containing gamelists, or the gamelists folder itself." else "No ROMs found. Choose the folder containing your system folders, such as ROMs/nes and ROMs/psx." }
        val mediaTree = if (config.media.isNotBlank()) ContentTree(context, config.media) else if (config.esde.isNotBlank()) ContentTree(context, config.esde) else null
        val art = mutableMapOf<String, String>()
        if (mediaTree != null) {
            progress("Matching your box art…")
            try {
                val mediaRoot = mediaTree.child(mediaTree.rootId, "downloaded_media")?.takeIf { it.directory }?.id ?: mediaTree.rootId
                for ((system, games) in raw.groupBy(Game::system)) {
                    currentCoroutineContext().ensureActive()
                    try {
                        val systemRoot = mediaTree.child(mediaRoot, system)?.takeIf { it.directory } ?: continue
                        val unmatched = games.associateBy(Game::id).toMutableMap()
                        for (kind in listOf("covers", "3dboxes", "miximages", "screenshots", "titlescreens")) {
                            if (unmatched.isEmpty()) break
                            val folder = mediaTree.child(systemRoot.id, kind)?.takeIf { it.directory } ?: continue
                            val wanted = unmatched.values.flatMap(::artworkStems).toSet()
                            val found = mutableMapOf<String, String>()
                            walkImages(mediaTree, folder.id, "", wanted, found, 0)
                            for (game in unmatched.values.toList()) {
                                val match = artworkStems(game).firstNotNullOfOrNull(found::get) ?: continue
                                art[game.id] = match
                                unmatched.remove(game.id)
                            }
                        }
                    } catch (error: Exception) {
                        currentCoroutineContext().ensureActive()
                        warnings += "Artwork for $system: ${error.message ?: "folder unavailable"}"
                    }
                }
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                warnings += "Artwork folder unavailable. Choose your ES-DE or artwork folder again."
            }
        }
        val games = raw.distinctBy(Game::id).map { game ->
            game.copy(artwork = art[game.id] ?: game.artwork)
        }.sortedWith(compareBy(Game::system, Game::name))
        return ImportResult(games, warnings.distinct())
    }

    private suspend fun importGamelists(tree: ContentTree, roms: ContentTree?, warnings: MutableList<String>, progress: (String) -> Unit): List<Game> {
        val root = tree.child(tree.rootId, "gamelists")?.takeIf { it.directory }?.id ?: tree.rootId
        val results = mutableListOf<Game>()
        for (folder in tree.children(root).filter { it.directory && !it.name.startsWith('.') }) {
            currentCoroutineContext().ensureActive()
            val gamelist = tree.child(folder.id, "gamelist.xml")?.takeUnless { it.directory } ?: continue
            progress("Reading ${systemName(folder.name)}…")
            try {
                val parsed = tree.open(gamelist).use { GamelistParser.parse(it, folder.name) }
                val romSystem = roms?.child(roms.rootId, folder.name)?.takeIf { it.directory }
                for (game in parsed) {
                    currentCoroutineContext().ensureActive()
                    val image = runCatching { game.imagePath.takeIf(String::isNotBlank)?.let { path ->
                        tree.resolve(path, folder.id)?.let { tree.uri(it.id).toString() }
                            ?: romSystem?.let { roms.resolve(path, it.id) }?.let { roms.uri(it.id).toString() }
                    }.orEmpty() }.getOrDefault("")
                    results += game.copy(artwork = image)
                }
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                warnings += "${folder.name}: ${error.message ?: "gamelist could not be read"}"
            }
        }
        return results
    }

    private suspend fun scanRoms(tree: ContentTree, warnings: MutableList<String>, progress: (String) -> Unit): List<Game> {
        val games = mutableListOf<Game>()
        for (folder in tree.children(tree.rootId).filter { it.directory && !skipFolder(it.name) }) {
            currentCoroutineContext().ensureActive()
            progress("Scanning ${systemName(folder.name)}…")
            val found = linkedMapOf<String, ContentTree.Entry>()
            try {
                walkRoms(tree, folder.id, "", found, 0)
                val referenced = mutableSetOf<String>()
                for ((path, entry) in found) {
                    if (path.substringAfterLast('.').lowercase(Locale.ROOT) !in setOf("m3u", "cue", "gdi")) continue
                    currentCoroutineContext().ensureActive()
                    try {
                        // Disc descriptors are tiny. Cap reads to avoid loading a misnamed ROM into RAM.
                        val descriptor = tree.open(entry).use { stream ->
                            val buffer = ByteArray(1024 * 1024)
                            var offset = 0
                            while (offset < buffer.size) {
                                val count = stream.read(buffer, offset, buffer.size - offset)
                                if (count <= 0) break
                                offset += count
                            }
                            String(buffer, 0, offset, Charsets.UTF_8)
                        }
                        referenced += RomFiles.referencedFiles(path, descriptor).map { it.lowercase(Locale.ROOT) }
                    } catch (error: Exception) {
                        currentCoroutineContext().ensureActive()
                        warnings += "Could not read disc list $path; its discs may appear separately."
                    }
                }
                found.keys.filter { it.lowercase(Locale.ROOT) !in referenced }.forEach { path -> games += Game(folder.name, path, displayName(path)) }
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                warnings += "${folder.name}: ${error.message ?: "folder unavailable"}"
            }
        }
        return games
    }

    private fun skipFolder(name: String) = name.startsWith('.') || name.lowercase(Locale.ROOT) in RomFiles.ignoredFolders

    private suspend fun walkRoms(tree: ContentTree, id: String, prefix: String, found: MutableMap<String, ContentTree.Entry>, depth: Int) {
        currentCoroutineContext().ensureActive()
        if (depth > 32) return
        val children = tree.children(id)
        if (children.any { it.name.equals("noload.txt", true) || it.name.equals("systeminfo.txt.disabled", true) }) return
        for (entry in children) {
            val path = "$prefix${entry.name}"
            if (entry.directory && !skipFolder(entry.name)) walkRoms(tree, entry.id, "$path/", found, depth + 1)
            else if (!entry.directory && RomFiles.isPlayable(entry.name)) found[path] = entry
        }
    }

    private suspend fun walkImages(tree: ContentTree, id: String, prefix: String, wanted: Set<String>, result: MutableMap<String, String>, depth: Int) {
        currentCoroutineContext().ensureActive()
        if (depth > 32) return
        for (entry in tree.children(id)) {
            if (entry.name.startsWith('.')) continue
            val path = "$prefix${entry.name}"
            if (entry.directory && wanted.any { it.startsWith("$path/", true) }) walkImages(tree, entry.id, "$path/", wanted, result, depth + 1)
            else if (!entry.directory && entry.name.substringAfterLast('.').lowercase(Locale.ROOT) in setOf("png", "jpg", "jpeg", "webp")) {
                val stem = fileStem(path).lowercase(Locale.ROOT)
                if (stem in wanted) result.putIfAbsent(stem, tree.uri(entry.id).toString())
            }
        }
    }

    fun save(games: List<Game>) {
        val json = JSONArray()
        games.forEach { game -> json.put(JSONObject().apply {
            put("system", game.system); put("path", game.path); put("name", game.name); put("description", game.description)
            put("genre", game.genre); put("developer", game.developer); put("year", game.year); put("rating", game.rating)
            put("playCount", game.playCount); put("favorite", game.favorite); put("hidden", game.hidden); put("artwork", game.artwork)
        }) }
        val stream = cacheFile.startWrite()
        try { stream.write(json.toString().toByteArray(Charsets.UTF_8)); cacheFile.finishWrite(stream) }
        catch (error: Exception) { cacheFile.failWrite(stream); throw error }
    }

    fun load(): List<Game> {
        if (!cacheFile.baseFile.exists()) return emptyList()
        val json = cacheFile.openRead().bufferedReader().use { JSONArray(it.readText()) }
        return (0 until json.length()).map { index -> json.getJSONObject(index).let {
            Game(it.getString("system"), it.getString("path"), it.getString("name"), it.optString("description"),
                it.optString("genre"), it.optString("developer"), it.optString("year"), it.optDouble("rating", 0.0),
                it.optInt("playCount"), it.optBoolean("favorite"), it.optBoolean("hidden"), artwork = it.optString("artwork"))
        } }
    }
}
