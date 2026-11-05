package com.gameroulette.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImportTest {
    private lateinit var context: Context
    private lateinit var provider: TestDocuments
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        provider = TestDocuments()
        provider.attachInfo(context, ProviderInfo().apply { authority = "cartwheel.test" })
        ShadowContentResolver.registerProviderInternal("cartwheel.test", provider)
    }
    private fun tree(root: String) = DocumentsContract.buildTreeDocumentUri("cartwheel.test", root).toString()

    @Test fun esdeImportMatchesNestedCoversAndUsesMetadata() = runBlocking {
        provider.add("esde/gamelists/snes/gamelist.xml", "<gameList><game><path>./RPG/Adventure.sfc</path><name>Adventure</name><genre>RPG</genre><rating>0.9</rating></game></gameList>")
        provider.add("esde/downloaded_media/snes/covers/RPG/Adventure.png", "cover")
        provider.add("esde/downloaded_media/snes/screenshots/RPG/Adventure.png", "screenshot")
        val result = LibraryRepository(context).import(LibraryConfig(esde = tree("esde"))) {}
        assertEquals(1, result.games.size)
        assertEquals("RPG", result.games.single().genre)
        assertTrue(Uri.decode(result.games.single().artwork).contains("/covers/RPG/Adventure.png"))
        assertTrue(result.warnings.isEmpty())
        LibraryRepository(context).save(result.games)
        assertEquals(result.games, LibraryRepository(context).load())
    }

    @Test fun romImportGroupsDiscsAndIgnoresMediaBiosAndNoLoadFolders() = runBlocking {
        provider.add("roms/psx/Quest.m3u", "discs/Quest Disc 1.cue\ndiscs/Quest Disc 2.chd")
        provider.add("roms/psx/discs/Quest Disc 1.cue", "FILE \"Quest Track.bin\" BINARY")
        provider.add("roms/psx/discs/Quest Track.bin", "rom")
        provider.add("roms/psx/discs/Quest Disc 2.chd", "rom")
        provider.add("roms/snes/Game (USA).sfc", "rom")
        provider.add("roms/snes/images/Artwork.zip", "not a game")
        provider.add("roms/bios/scph.bin", "bios")
        provider.add("roms/snes/disabled/noload.txt", "")
        provider.add("roms/snes/disabled/Ignored.sfc", "rom")
        val result = LibraryRepository(context).import(LibraryConfig(source = "roms", roms = tree("roms"))) {}
        assertEquals(setOf("Quest.m3u", "Game (USA).sfc"), result.games.map(Game::path).toSet())
        assertEquals("Game", result.games.find { it.system == "snes" }!!.name)
    }

    @Test fun malformedSystemDoesNotDiscardOtherSystems() = runBlocking {
        provider.add("esde/gamelists/snes/gamelist.xml", "<gameList><game><path>A.sfc</path></game></gameList>")
        provider.add("esde/gamelists/gba/gamelist.xml", "not xml")
        val result = LibraryRepository(context).import(LibraryConfig(esde = tree("esde"))) {}
        assertEquals(1, result.games.size)
        assertEquals(1, result.warnings.size)
    }

    @Test fun rejectsEmptyLibraryWithoutOverwritingCache() = runBlocking {
        val repository = LibraryRepository(context)
        val old = listOf(Game("snes", "Old.sfc", "Old"))
        repository.save(old)
        provider.add("empty/readme.txt", "No games")
        assertTrue(runCatching { repository.import(LibraryConfig(source = "roms", roms = tree("empty"))) {} }.isFailure)
        assertEquals(old, repository.load())
    }

    @Test fun esdeRootPrioritizesCoversWithoutReadingOtherMedia() = runBlocking {
        provider.add("esde/gamelists/snes/gamelist.xml", "<gameList><game><path>./RPG/Game (USA).sfc</path><name>A different display name</name></game></gameList>")
        provider.add("esde/downloaded_media/snes/covers/RPG/Game (USA).webp", "cover")
        provider.add("esde/downloaded_media/snes/covers/RPG/A different display name.png", "wrong")
        provider.add("esde/downloaded_media/snes/miximages/RPG/Game (USA).png", "miximage")
        provider.add("esde/downloaded_media/snes/videos/RPG/Game (USA).mp4", "video")
        provider.add("esde/downloaded_media/snes/covers/Unrelated/Thousands of images.png", "unused")
        val result = LibraryRepository(context).import(LibraryConfig(esde = tree("esde"))) {}
        assertTrue(Uri.decode(result.games.single().artwork).endsWith("covers/RPG/Game (USA).webp"))
        assertFalse(provider.queries.any { "/miximages" in it || "/videos" in it || "/Unrelated" in it })
    }

    @Test fun handlesMissingCoversAndDirectoryBasedGames() = runBlocking {
        provider.add("esde/gamelists/scummvm/gamelist.xml", "<gameList><game><path>./dig.scummvm/dig.scummvm</path></game><game><path>./other.scummvm</path></game></gameList>")
        provider.add("esde/downloaded_media/scummvm/3dboxes/dig.scummvm.png", "box")
        provider.add("esde/downloaded_media/scummvm/screenshots/other.scummvm.jpg", "screenshot")
        val result = LibraryRepository(context).import(LibraryConfig(esde = tree("esde"))) {}
        assertTrue(result.games.all { it.artwork.isNotBlank() })
        assertTrue(result.games.any { Uri.decode(it.artwork).endsWith("3dboxes/dig.scummvm.png") })
    }
}

/** A tiny documents provider exercises the same content:// queries used on an SD card. */
class TestDocuments : ContentProvider() {
    private val files = linkedMapOf<String, String?>()
    val queries = mutableListOf<String>()
    fun add(path: String, content: String) {
        val parts = path.split('/')
        for (index in 1 until parts.size) files.putIfAbsent(parts.take(index).joinToString("/"), null)
        files[path] = content
    }
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor {
        val id = DocumentsContract.getDocumentId(uri)
        queries += id
        val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val cursor = MatrixCursor(columns)
        files.filterKeys { it.substringBeforeLast('/', "") == id }.forEach { (path, contents) ->
            cursor.addRow(columns.map { column -> when (column) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> path
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> path.substringAfterLast('/')
                DocumentsContract.Document.COLUMN_MIME_TYPE -> if (contents == null) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
                else -> null
            } }.toTypedArray())
        }
        return cursor
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = File(context!!.cacheDir, "provider-${DocumentsContract.getDocumentId(uri).hashCode()}")
        file.writeText(files[DocumentsContract.getDocumentId(uri)] ?: error("Not a file"))
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri) = "application/octet-stream"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = throw UnsupportedOperationException()
}
