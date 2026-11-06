package com.gameroulette.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppearanceFavoritesTest {
    @Test fun replacesLegacyAppearanceWithoutLosingLibrarySettings() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = app.getSharedPreferences("cartwheel", 0)
        prefs.edit().clear().putString("theme", "retro").putString("roms", "content://test/tree/roms")
            .putStringSet("favorites", setOf("snes:existing.sfc")).commit()
        val model = CartwheelViewModel(app)
        assertEquals("clean", model.state.value.theme)
        assertEquals("clean", prefs.getString("theme", ""))
        assertEquals("content://test/tree/roms", model.state.value.config.roms)
        assertTrue("snes:existing.sfc" in model.state.value.favorites)
        assertEquals("clean", normalizedAppearance(null))
        model.setTheme("casino")
        assertEquals("casino", CartwheelViewModel(app).state.value.theme)
    }

    @Test fun favoritesPersistAndDoNotMarkGamesPlayed() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("cartwheel", 0).edit().clear().commit()
        val game = Game("snes", "Game.sfc", "Game")
        val model = CartwheelViewModel(app)
        model.toggleFavorite(game)
        assertTrue(model.state.value.isFavorite(game))
        assertTrue(CartwheelViewModel(app).state.value.isFavorite(game))
        assertTrue(model.state.value.accepted.isEmpty())
        assertTrue(model.state.value.history.isEmpty())
        model.toggleFavorite(game)
        assertFalse(model.state.value.isFavorite(game))
    }

    @Test fun importedFavoritesCanBeRemovedLocallyAndRestored() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = app.getSharedPreferences("cartwheel", 0)
        prefs.edit().clear().commit()
        val game = Game("snes", "Imported.sfc", "Imported", favorite = true)
        val model = CartwheelViewModel(app)
        assertTrue(model.state.value.isFavorite(game))
        model.toggleFavorite(game)
        assertFalse(model.state.value.isFavorite(game))
        assertTrue(game.favorite)
        assertTrue(game.id in prefs.getStringSet("removedFavorites", emptySet()).orEmpty())
        assertTrue(Roulette.eligible(listOf(game), GameFilters(favoritesOnly = true), model.state.value.favorites, emptySet(), model.state.value.removedFavorites).isEmpty())
        assertFalse(CartwheelViewModel(app).state.value.isFavorite(game))
        model.toggleFavorite(game)
        assertTrue(model.state.value.isFavorite(game))
        assertFalse(game.id in model.state.value.removedFavorites)
    }
}
