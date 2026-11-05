package com.gameroulette.app

import android.content.Context
import android.app.Application
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
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
class ScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var libraryModel: CartwheelViewModel

    @Before fun awaitLibrary() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("cartwheel", 0).edit().clear().putBoolean("onlineRecommendations", false).commit()
        app.getSharedPreferences("cartwheel_updates", 0).edit().clear().commit()
        LibraryRepository(app).save(emptyList())
        lateinit var model: CartwheelViewModel
        compose.runOnUiThread {
            model = CartwheelViewModel(app)
            libraryModel = model
            compose.activity.setContent { CartwheelApp(model) }
        }
        compose.waitUntil(15_000) { compose.waitForIdle(); !model.state.value.busy }
    }

    @Test fun connectsLibraryAndOffersBothImportModes() {
        compose.onNodeWithText("Connect your library").assertIsDisplayed().performClick()
        compose.onNodeWithText("ES-DE gamelists").assertIsDisplayed()
        compose.onNodeWithText("Scan ROM folders").performClick()
        compose.onNodeWithText("ROMs folder").assertExists()
        compose.onNodeWithText("Build my library").assertIsNotEnabled()
        screenshot("setup-16x9")
    }

    @Test fun homeFitsWidescreen() {
        compose.onNodeWithText("Connect your library").assertIsDisplayed()
        compose.onNodeWithText("Filters").assertIsDisplayed()
        screenshot("welcome-16x9")
    }

    @Test @Config(qualifiers = "w640dp-h480dp-land-mdpi") fun homeFitsFourByThree() {
        compose.onNodeWithText("Connect your library").assertIsDisplayed()
        compose.onNodeWithText("Filters").assertIsDisplayed()
        screenshot("welcome-4x3")
    }

    @Test fun switchesThemeAndKeepsChoice() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Clean · calm blue").performClick()
        compose.waitForIdle()
        val context = ApplicationProvider.getApplicationContext<Context>()
        org.junit.Assert.assertEquals("clean", context.getSharedPreferences("cartwheel", 0).getString("theme", ""))
        compose.onNodeWithContentDescription("Close").performClick()
        screenshot("clean-16x9")
    }

    @Test @Config(qualifiers = "w640dp-h480dp-land-mdpi") fun updatesAreAccessibleBeforeConnectingALibrary() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Updates").assertIsDisplayed().performClick()
        compose.onNodeWithText("Installed version ${BuildConfig.VERSION_NAME}").assertIsDisplayed()
        compose.onNodeWithText("Check for updates").assertIsDisplayed()
        compose.onNodeWithText("Include preview versions").performScrollTo().assertIsDisplayed()
        screenshot("updates-4x3")
    }

    @Test fun checksForNewPreviewAndShowsADownloadAction() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val json = """[{"tag_name":"v0.2.0","name":"New preview","body":"A new build for testing.","prerelease":true,"html_url":"$RELEASES_PAGE/tag/v0.2.0","assets":[{"name":"Cartwheel-0.2.0-preview.apk","size":17000000,"digest":"sha256:${"a".repeat(64)}","browser_download_url":"$RELEASES_PAGE/download/v0.2.0/Cartwheel-0.2.0-preview.apk"}]}]"""
        lateinit var updater: UpdateViewModel
        compose.runOnUiThread {
            updater = UpdateViewModel(app, ReleaseService { json })
            compose.activity.setContent { CartwheelApp(libraryModel, updater) }
        }
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Updates").performClick()
        compose.onNodeWithText("Check for updates").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("0.2.0 available · preview").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Download update · 16.2 MB").performScrollTo().assertIsDisplayed()
        screenshot("updates-available-16x9")
        compose.onNodeWithText("Download update · 16.2 MB").performClick()
        compose.waitUntil(15_000) { compose.waitForIdle(); updater.state.value.downloading }
        org.junit.Assert.assertTrue(app.getSharedPreferences("cartwheel_updates", 0).getLong("downloadId", -1) >= 0)
        compose.onNodeWithText("Cancel download").performScrollTo().performClick()
        compose.waitUntil { compose.waitForIdle(); !updater.state.value.downloading }
        org.junit.Assert.assertEquals(-1L, app.getSharedPreferences("cartwheel_updates", 0).getLong("downloadId", -1))
    }

    private fun screenshot(name: String) {
        val folder = File("build/screenshots").apply { mkdirs() }
        compose.waitForIdle()
        compose.runOnUiThread {
            val view = org.robolectric.shadows.ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView
                ?: compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
