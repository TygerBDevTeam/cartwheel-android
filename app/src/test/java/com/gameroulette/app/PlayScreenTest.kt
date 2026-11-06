package com.gameroulette.app

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var model: CartwheelViewModel

    @Before fun seedTestLibrary() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("cartwheel", 0).edit().clear().putBoolean("onlineRecommendations", false).commit()
        LibraryRepository(app).save(listOf(
            Game("snes", "A.sfc", "Chronicles of the Hourglass", "A time-travel adventure across a world of floating islands. A fictional test entry.", "Role-playing", "Example Studio", "1995", .91),
            Game("gba", "B.gba", "Orbital Explorer", genre = "Action", year = "2002", rating = .86),
            Game("psx", "C.chd", "Ridgeway Racer", genre = "Racing", year = "1997", rating = .80)
        ))
        compose.runOnUiThread {
            model = CartwheelViewModel(app)
            compose.activity.setContent { CartwheelApp(model) }
        }
        compose.waitUntil(15_000) { compose.waitForIdle(); !model.state.value.busy }
    }

    @Test fun drawsFavoritesAndSpinsWithoutMarkingGamesPlayed() {
        compose.onNodeWithText("Find my next game").assertIsDisplayed().performClick()
        compose.waitUntil(15_000) { compose.waitForIdle(); !model.state.value.spinning && model.state.value.current != null }
        val first = model.state.value.current!!.id
        compose.onNodeWithText("Yes, this one").assertDoesNotExist()
        compose.onNodeWithText("Favorite").assertIsDisplayed().performClick()
        compose.waitUntil { compose.waitForIdle(); first in model.state.value.favorites }
        assertTrue(model.state.value.accepted.isEmpty())
        compose.onNodeWithText("Favorited").assertIsDisplayed()
        compose.onNodeWithContentDescription("Favorites").performClick()
        compose.onNodeWithContentDescription("Remove ${model.state.value.current!!.name} from favorites").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithText("Spin again").assertIsDisplayed().performClick()
        compose.waitUntil(15_000) { compose.waitForIdle(); !model.state.value.spinning && model.state.value.current!!.id != first }
        screenshot("roulette-16x9")
    }

    @Test @Config(qualifiers = "w640dp-h480dp-land-mdpi") fun weeklyFitsFourByThreeAndRerollPersists() {
        compose.onNodeWithText("Game of the Week").performClick()
        compose.onNodeWithText("Reroll").assertIsDisplayed()
        val first = model.state.value.weekly!!.id
        compose.onNodeWithText("Reroll").performClick()
        compose.waitUntil { compose.waitForIdle(); model.state.value.weekly!!.id != first }
        val second = model.state.value.weekly!!.id
        compose.runOnUiThread { model.refreshWeek() }
        assertEquals(second, model.state.value.weekly!!.id)
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertEquals(second, app.getSharedPreferences("cartwheel", 0).getString("weeklyId", ""))
        screenshot("weekly-4x3")
    }

    @Test fun systemExclusionsAndEmptyFiltersAreRecoverable() {
        compose.onNodeWithContentDescription("Filters").performClick()
        compose.onNodeWithText("None", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithText("Nothing in this mix yet.").assertIsDisplayed()
        compose.onNodeWithText("Adjust filters").performClick()
        compose.onNodeWithText("All", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithText("Find my next game").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w640dp-h480dp-land-mdpi") fun casinoUsesFullWidthModesAndCanFavoriteWeeklyGames() {
        compose.runOnUiThread { model.setTheme("casino") }
        val roulette = compose.onNodeWithText("Roulette").fetchSemanticsNode().boundsInRoot
        val weekly = compose.onNodeWithText("Game of the Week").fetchSemanticsNode().boundsInRoot
        assertTrue(roulette.width > 250f)
        assertEquals(roulette.width, weekly.width, 1f)
        compose.onNodeWithText("Game of the Week").performClick().assertIsSelected()
        compose.onNodeWithText("Favorite").assertIsDisplayed().performClick()
        compose.onNodeWithText("Favorited").assertIsDisplayed()
        assertTrue(model.state.value.accepted.isEmpty())
        screenshot("casino-weekly-4x3")
        compose.onNodeWithText("Favorited").performClick()
        compose.onNodeWithText("Favorite").assertIsDisplayed()
        compose.onNodeWithText("Roulette").performClick().assertIsSelected()
    }

    private fun screenshot(name: String) {
        File("build/screenshots").mkdirs()
        compose.waitForIdle()
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            File("build/screenshots/$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
