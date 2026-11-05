package com.gameroulette.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.time.LocalDate

data class UiState(
    val games: List<Game> = emptyList(),
    val config: LibraryConfig = LibraryConfig(),
    val filters: GameFilters = GameFilters(),
    val favorites: Set<String> = emptySet(),
    val accepted: Set<String> = emptySet(),
    val history: List<String> = emptyList(),
    val current: Game? = null,
    val weekly: Game? = null,
    val weeklyReroll: Int = 0,
    val week: String = "",
    val theme: String = "retro",
    val busy: Boolean = true,
    val spinning: Boolean = false,
    val progress: String = "Opening your library…",
    val notice: String? = null,
    val warnings: List<String> = emptyList(),
    val onlineEnabled: Boolean = true,
    val reviews: Map<String, OnlineReview> = emptyMap(),
    val advisorLoading: Boolean = false,
    val advisorUpdated: Long = 0L,
    val advisorError: String? = null
) {
    val eligible: List<Game> get() = Roulette.eligible(games, filters, favorites, accepted)
}

class CartwheelViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("cartwheel", 0)
    private val repository = LibraryRepository(application)
    private val online = OnlineGames(application)
    private var catalog = ReviewCatalog()
    private fun set(key: String) = prefs.getStringSet(key, emptySet()).orEmpty().toSet()
    private val mutable = MutableStateFlow(UiState(
        config = LibraryConfig(prefs.getString("source", "esde")!!, prefs.getString("roms", "")!!, prefs.getString("esde", "")!!, prefs.getString("media", "")!!),
        filters = GameFilters(set("excluded"), prefs.getString("genre", "")!!, prefs.getBoolean("onlyFavorites", false), prefs.getBoolean("onlyUnplayed", false)),
        favorites = set("favorites"), accepted = set("accepted"), theme = prefs.getString("theme", "retro")!!,
        onlineEnabled = prefs.getBoolean("onlineRecommendations", true),
        history = runCatching { JSONArray(prefs.getString("history", "[]")).let { a -> (0 until a.length()).map(a::getString) } }.getOrDefault(emptyList())
    ))
    val state = mutable.asStateFlow()
    private var importJob: Job? = null
    private var seen = set("seen")

    init {
        viewModelScope.launch {
            try {
                val (games, savedCatalog) = withContext(Dispatchers.IO) { repository.load() to online.loadCatalog() }
                catalog = savedCatalog
                val reviews = withContext(Dispatchers.IO) { ReviewMatcher.match(games, catalog.records) }
                mutable.update { it.copy(games = games, reviews = reviews, advisorUpdated = catalog.fetchedAt, busy = false, current = games.find { g -> g.id == prefs.getString("current", "") }) }
                refreshWeek()
            } catch (error: Exception) {
                mutable.update { it.copy(busy = false, notice = "The saved library could not be read. Please scan your folders again.") }
            }
        }
    }

    fun setTheme(theme: String) {
        prefs.edit().putString("theme", theme).apply()
        mutable.update { it.copy(theme = theme) }
    }

    fun import(config: LibraryConfig) {
        if (mutable.value.busy) return
        importJob = viewModelScope.launch {
            mutable.update { it.copy(busy = true, progress = "Connecting to your folders…", notice = null, warnings = emptyList()) }
            try {
                val result = withContext(Dispatchers.IO) {
                    repository.import(config) { message -> mutable.update { it.copy(progress = message) } }.also { repository.save(it.games) }
                }
                prefs.edit().putString("source", config.source).putString("roms", config.roms).putString("esde", config.esde).putString("media", config.media).apply()
                val reviews = withContext(Dispatchers.IO) { ReviewMatcher.match(result.games, catalog.records) }
                mutable.update { state -> state.copy(config = config, games = result.games, reviews = reviews, busy = false,
                    current = result.games.find { it.id == state.current?.id }, warnings = result.warnings,
                    notice = "${result.games.count { !it.hidden }} games ready${if (result.warnings.isEmpty()) "." else "; ${result.warnings.size} import notes."}") }
                refreshWeek()
            } catch (error: Exception) {
                // Keep the last usable library when a card is removed or a folder is inaccessible.
                mutable.update { it.copy(busy = false, notice = error.message ?: "Could not read this library. Choose your folders again.") }
            }
        }
    }

    fun filters(filters: GameFilters) {
        prefs.edit().putStringSet("excluded", filters.excludedSystems).putString("genre", filters.genre)
            .putBoolean("onlyFavorites", filters.favoritesOnly).putBoolean("onlyUnplayed", filters.unplayedOnly).apply()
        mutable.update { state ->
            val eligible = Roulette.eligible(state.games, filters, state.favorites, state.accepted)
            state.copy(filters = filters, current = state.current?.takeIf { g -> eligible.any { it.id == g.id } })
        }
        refreshWeek()
    }

    fun spin() {
        val state = mutable.value
        if (state.spinning || state.busy) return
        val pool = state.eligible
        val picked = Roulette.pick(pool, seen, state.current?.id) ?: return
        if (pool.none { it.id !in seen && it.id != state.current?.id }) seen = emptySet()
        viewModelScope.launch {
            mutable.update { it.copy(spinning = true, notice = null) }
            // The final choice is drawn once, independently of the visual animation.
            repeat(10) { index ->
                mutable.update { it.copy(current = pool.random()) }
                delay(45L + index * 12L)
            }
            seen = seen + picked.id
            prefs.edit().putStringSet("seen", seen).putString("current", picked.id).apply()
            mutable.update { it.copy(current = picked, spinning = false) }
        }
    }

    fun accept(game: Game) {
        mutable.update { it.copy(accepted = it.accepted + game.id, history = (listOf(game.id) + it.history.filter { id -> id != game.id }).take(100), notice = "${game.name} added to your play list.") }
        prefs.edit().putStringSet("accepted", mutable.value.accepted).putString("history", JSONArray(mutable.value.history).toString()).apply()
    }

    fun toggleFavorite(game: Game) {
        val favorites = mutable.value.favorites.let { if (game.id in it) it - game.id else it + game.id }
        mutable.update { it.copy(favorites = favorites) }
        prefs.edit().putStringSet("favorites", favorites).apply()
    }

    fun refreshWeek(reroll: Boolean = false, reconsider: Boolean = false) {
        val state = mutable.value
        val week = Roulette.weekKey(LocalDate.now())
        val savedWeek = prefs.getString("week", "")
        val count = if (week == savedWeek) prefs.getInt("weeklyReroll", 0) + if (reroll) 1 else 0 else 0
        val savedId = prefs.getString("weeklyId", "")
        val pool = state.eligible
        val reviews = if (state.onlineEnabled) state.reviews else emptyMap()
        val pick = if (!reroll && !reconsider && week == savedWeek) pool.find { it.id == savedId } ?: Roulette.weekly(pool, week, count, null, state.accepted, reviews)
            else Roulette.weekly(pool, week, count, if (reroll) state.weekly?.id else null, state.accepted, reviews)
        // Do not erase a stored pick during startup, before the cached library has loaded.
        if (!state.busy) prefs.edit().putString("week", week).putInt("weeklyReroll", count).putString("weeklyId", pick?.id).apply()
        mutable.update { it.copy(weekly = pick, week = week, weeklyReroll = count) }
    }

    fun onlineRecommendations(enabled: Boolean) {
        prefs.edit().putBoolean("onlineRecommendations", enabled).apply()
        mutable.update { it.copy(onlineEnabled = enabled) }
        if (enabled) loadRecommendations()
    }

    fun loadRecommendations(force: Boolean = false) {
        val state = mutable.value
        if (!state.onlineEnabled || state.advisorLoading || state.games.isEmpty()) return
        if (!force && catalog.fetchedAt > 0 && System.currentTimeMillis() - catalog.fetchedAt < 7L * 24 * 60 * 60 * 1000) return
        viewModelScope.launch {
            mutable.update { it.copy(advisorLoading = true, advisorError = null) }
            try {
                val firstFetch = catalog.records.isEmpty()
                catalog = online.fetchCatalog()
                val reviews = withContext(Dispatchers.IO) { ReviewMatcher.match(mutable.value.games, catalog.records) }
                mutable.update { it.copy(reviews = reviews, advisorUpdated = catalog.fetchedAt, advisorLoading = false) }
                refreshWeek(reconsider = firstFetch)
            } catch (error: Exception) {
                mutable.update { it.copy(advisorLoading = false, advisorError = "Online scores unavailable. ${if (it.reviews.isEmpty()) "Discovery picks still work." else "Using saved scores."}") }
            }
        }
    }

    suspend fun articles(query: String) = online.articles(query)

    fun clearNotice() { mutable.update { it.copy(notice = null) } }
}
