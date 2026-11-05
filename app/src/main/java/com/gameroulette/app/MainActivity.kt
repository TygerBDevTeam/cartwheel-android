package com.gameroulette.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

private val Ink = Color(0xFF101211)
private val Panel = Color(0xFF1A1E1B)
private val Muted = Color(0xFFA6AFA5)
private val Lime = Color(0xFFB6F36A)
private val Violet = Color(0xFFBCACFF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent { CartwheelApp() }
    }
}

@Composable
fun CartwheelApp(model: CartwheelViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val accent = if (state.theme == "retro") Lime else Color(0xFFA9CDFF)
    val mono = if (state.theme == "retro") FontFamily.Monospace else FontFamily.SansSerif
    var screen by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableStateOf("roulette") }
    var onlineGameId by rememberSaveable { mutableStateOf("") }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.refreshWeek() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.notice) { state.notice?.let { snackbar.showSnackbar(it); model.clearNotice() } }
    MaterialTheme(colorScheme = darkColorScheme(primary = accent, secondary = Violet, background = Ink,
        surface = Panel, onPrimary = Ink, onBackground = Color(0xFFF2F5ED), onSurface = Color(0xFFF2F5ED))) {
        Scaffold(containerColor = Ink, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp, vertical = 8.dp)
                .onPreviewKeyEvent { event ->
                    if (screen.isNotEmpty() || event.type != KeyEventType.KeyUp || state.busy || state.spinning) false
                    else when (event.key) {
                        Key.ButtonX -> { if (tab == "weekly") model.refreshWeek(true) else model.spin(); true }
                        Key.ButtonY -> { screen = "filters"; true }
                        Key.ButtonStart -> { screen = "library"; true }
                        else -> false
                    }
                }) {
                val compact = maxWidth < 700.dp || maxWidth / maxHeight < 1.5f
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Casino, null, Modifier.size(28.dp), tint = accent)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("CARTWHEEL", fontFamily = mono, fontSize = if (compact) 18.sp else 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
                            if (!compact) Text("A little less choosing. A lot more playing.", fontSize = 11.sp, color = Muted)
                        }
                        IconButton(onClick = { screen = "library" }, enabled = !state.spinning) { Icon(Icons.Outlined.FolderOpen, "Library & folders") }
                        IconButton(onClick = { screen = "settings" }) { Icon(Icons.Outlined.Palette, "Appearance") }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(tab == "roulette", { tab = "roulette" }, { Text("Roulette", fontSize = 12.sp) }, leadingIcon = { Icon(Icons.Outlined.Shuffle, null, Modifier.size(16.dp)) })
                        FilterChip(tab == "weekly", { tab = "weekly"; model.refreshWeek(); model.loadRecommendations() }, { Text(if (compact) "This week" else "Game of the Week", fontSize = 12.sp) }, leadingIcon = { Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(16.dp)) })
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { screen = "history" }) { Icon(Icons.Outlined.Bookmarks, "Your play list") }
                        OutlinedButton(onClick = { screen = "filters" }, enabled = !state.spinning, contentPadding = PaddingValues(horizontal = 12.dp)) {
                            Icon(Icons.Outlined.Tune, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Filters", fontSize = 12.sp)
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            state.busy -> Loading(state.progress)
                            state.games.isEmpty() -> EmptyLibrary { screen = "library" }
                            state.eligible.isEmpty() -> EmptyPool { screen = "filters" }
                            else -> {
                                val game = if (tab == "weekly") state.weekly else state.current
                                GameStage(game, state, compact, mono, accent, tab == "weekly", model,
                                    onBackground = { onlineGameId = it.id; screen = "online" },
                                    onOnlineStatus = { screen = "recommendations" })
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).background(accent, RoundedCornerShape(3.dp)))
                        Spacer(Modifier.width(7.dp))
                        Text(if (state.games.isEmpty()) "YOUR NEXT ADVENTURE IS WAITING" else "${state.eligible.size} GAMES IN THE MIX", fontFamily = mono, fontSize = 9.sp, color = Muted, letterSpacing = 1.sp)
                        Spacer(Modifier.weight(1f))
                        Text(if (compact) "X Reroll · Y Filters" else "X Reroll   ·   Y Filters   ·   START Library", fontFamily = mono, fontSize = 9.sp, color = Muted)
                    }
                }
            }
        }
        when (screen) {
            "library" -> LibraryDialog(state, model, { screen = "" })
            "filters" -> FiltersDialog(state, model, { screen = "" })
            "settings" -> PanelDialog("Make it yours", { screen = "" }) {
                Text("Choose your look. Both work with touch and handheld controls.", color = Muted)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(state.theme == "retro", { model.setTheme("retro") }, { Text("Retro · lime + pixel spirit") })
                    FilterChip(state.theme == "clean", { model.setTheme("clean") }, { Text("Clean · calm blue") })
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(state.onlineEnabled, model::onlineRecommendations)
                    Text("Online weekly recommendations", fontSize = 13.sp)
                }
                Text("Uses saved online scores between refreshes. Roulette and discovery picks work offline.", fontSize = 12.sp, color = Muted)
                Text("Cartwheel 0.1.1\nBuilt for landscape handhelds, from 4:3 to 16:9.", fontSize = 12.sp, color = Muted)
            }
            "recommendations" -> RecommendationsDialog(state, model, { screen = "" })
            "online" -> {
                val game = state.games.find { it.id == onlineGameId }
                if (game != null) OnlineBackgroundDialog(game, model, { screen = "" })
                else LaunchedEffect(Unit) { screen = "" }
            }
            "history" -> PanelDialog("Your play list", { screen = "" }) {
                val history = state.history.mapNotNull { id -> state.games.find { it.id == id } }
                if (history.isEmpty()) Text("Say “Yes, this one” to save a game here.", color = Muted)
                history.forEach { game ->
                    ListItem(headlineContent = { Text(game.name) }, supportingContent = { Text(systemName(game.system)) }, leadingContent = { Icon(Icons.Outlined.CheckCircle, null) })
                }
            }
        }
    }
}

@Composable
private fun GameStage(game: Game?, state: UiState, compact: Boolean, mono: FontFamily, accent: Color, weekly: Boolean, model: CartwheelViewModel,
    onBackground: (Game) -> Unit, onOnlineStatus: () -> Unit) {
    Row(Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)).background(Panel)
        .border(1.dp, Color.White.copy(alpha = .07f), RoundedCornerShape(22.dp))) {
        Box(Modifier.weight(if (compact) .40f else .43f).fillMaxHeight()
            .background(Brush.linearGradient(listOf(Color(0xFF35384B), Color(0xFF222B29), Color(0xFF18241D))))
            .padding(if (compact) 16.dp else 24.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                for (x in 0..(size.width / 22).toInt()) for (y in 0..(size.height / 22).toInt())
                    drawCircle(Color.White.copy(alpha = .07f), 1f, androidx.compose.ui.geometry.Offset(x * 22f, y * 22f))
            }
            Artwork(game, mono, accent)
            if (state.spinning) CircularProgressIndicator(Modifier.align(Alignment.BottomCenter).size(24.dp), color = accent, strokeWidth = 2.dp)
        }
        Column(Modifier.weight(if (compact) .60f else .57f).fillMaxHeight().padding(if (compact) 16.dp else 24.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 11.dp)) {
                Text(if (weekly) "YOUR WEEKLY SIDE QUEST" else if (state.spinning) "LET FATE DO ITS THING…" else if (game == null) "GOOD GAMES. ZERO INDECISION." else "HOW ABOUT THIS ONE?", fontFamily = mono, fontSize = 9.sp, letterSpacing = 1.5.sp, color = accent)
                Text(game?.name ?: "Meet your\nnext adventure.", fontSize = if (compact) 23.sp else 34.sp, lineHeight = if (compact) 27.sp else 38.sp, fontWeight = FontWeight.ExtraBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (game != null) {
                    Text(listOf(systemName(game.system), game.year, game.genre).filter(String::isNotBlank).joinToString("  /  "), fontSize = 11.sp, color = Violet)
                    if (weekly) {
                        Text(recommendationReason(game, state), fontSize = 12.sp, color = Muted, lineHeight = 17.sp)
                        Text("Week of ${state.week} · Reroll whenever you like", fontFamily = mono, fontSize = 9.sp, color = Muted)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { onBackground(game) }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                                Icon(Icons.Outlined.Language, null, Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text("Game background", fontSize = 11.sp)
                            }
                            TextButton(onClick = onOnlineStatus, contentPadding = PaddingValues(horizontal = 4.dp)) {
                                Text(if (state.advisorLoading) "Loading scores…" else if (state.onlineEnabled) "Online picks" else "Offline picks", fontSize = 11.sp)
                            }
                        }
                    } else if (game.description.isNotBlank()) {
                        Text(game.description.replace(Regex("<[^>]+>"), ""), fontSize = 12.sp, lineHeight = 17.sp, color = Muted, maxLines = if (compact) 3 else 5, overflow = TextOverflow.Ellipsis)
                    } else Text("A new adventure from your collection. Give it a chance?", fontSize = 12.sp, color = Muted, lineHeight = 17.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (game.rating > 0) Text("★ ${"%.1f".format(game.rating * 5)}  ", color = accent, fontSize = 11.sp)
                        Text(if (game.id in state.accepted) "ON YOUR PLAY LIST" else if (game.playCount == 0) "NOT PLAYED YET*" else "${game.playCount} RECORDED PLAYS", fontFamily = mono, fontSize = 9.sp, color = Muted)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { model.toggleFavorite(game) }, enabled = !state.spinning, modifier = Modifier.size(40.dp)) {
                            val favorite = game.favorite || game.id in state.favorites
                            Icon(if (favorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder, if (favorite) "Favorite game" else "Add favorite", tint = if (favorite) Violet else Muted, modifier = Modifier.size(20.dp))
                        }
                    }
                } else Text("Your collection is full of possibilities.\nLet’s find something worth playing.", color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
            }
            Spacer(Modifier.height(8.dp))
            val focus = remember { FocusRequester() }
            LaunchedEffect(game == null, state.spinning) { if (!state.spinning) focus.requestFocus() }
            if (game == null) {
                Button(onClick = model::spin, enabled = !state.spinning, modifier = Modifier.fillMaxWidth().height(48.dp).focusRequester(focus), shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Outlined.Shuffle, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text("Find my next game", fontWeight = FontWeight.Bold)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { model.accept(game) }, enabled = !state.spinning, modifier = Modifier.weight(1f).height(46.dp).focusRequester(focus), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Icon(Icons.Outlined.Check, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text(if (game.id in state.accepted) "Saved to play" else "Yes, this one", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(onClick = { if (weekly) model.refreshWeek(true) else model.spin() }, enabled = !state.spinning, modifier = Modifier.height(46.dp), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Icon(Icons.Outlined.Refresh, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text(if (weekly) "Reroll" else "Skip", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

private fun recommendationReason(game: Game, state: UiState): String {
    val reasons = mutableListOf<String>()
    val review = state.reviews[game.id].takeIf { state.onlineEnabled }
    if (review != null) reasons += "Metacritic ${review.scoreText}, via Wikidata.${if (!review.platformSpecific) " Edition not specified by source." else ""}"
    else if (game.rating >= .75) reasons += "Highly rated in your ES-DE metadata."
    if (game.playCount == 0 && game.id !in state.accepted) reasons += if (state.config.source == "esde") "No recorded plays—a good excuse to try it." else "A discovery pick from your ROM collection."
    if (reasons.isEmpty()) reasons += "A fresh reason to revisit your ${systemName(game.system)} collection."
    return reasons.joinToString(" ")
}

@Composable
private fun Artwork(game: Game?, mono: FontFamily, accent: Color) {
    val context = LocalContext.current
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, game?.artwork) {
        value = null
        val artwork = game?.artwork.orEmpty()
        if (artwork.isNotBlank()) value = withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(artwork)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                val options = BitmapFactory.Options().apply { inSampleSize = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 700) }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options)?.asImageBitmap() }
            }.getOrNull()
        }
    }
    if (bitmap != null) Image(bitmap!!, "Box art for ${game?.name}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    else Column(Modifier.fillMaxSize().padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(if (game == null) Icons.Outlined.Casino else Icons.Outlined.SportsEsports, null, Modifier.size(58.dp), tint = accent)
        Spacer(Modifier.height(16.dp))
        Text(game?.name ?: "ONE MORE\nGREAT GAME.", fontFamily = mono, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 23.sp, color = Color.White.copy(alpha = .9f), maxLines = 4)
        Spacer(Modifier.height(12.dp))
        Text(if (game == null) "PRESS YOUR LUCK" else "${systemName(game.system)} · NO BOX ART", color = Muted, fontFamily = mono, fontSize = 8.sp, letterSpacing = 1.sp)
    }
}

@Composable
private fun Loading(message: String) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(Modifier.size(32.dp)); Spacer(Modifier.height(18.dp)); Text(message, color = Muted)
    }
}

@Composable
private fun EmptyLibrary(open: () -> Unit) {
    Row(Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)).background(Panel).padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Casino, null, Modifier.size(66.dp), tint = Lime)
        Column(Modifier.weight(1f)) {
            Text("Too many games.\nOne good decision.", fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(10.dp)); Text("Connect your ES-DE library or scan your ROMs.\nYour next adventure is already in your collection.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
            Spacer(Modifier.height(16.dp)); Button(onClick = open) { Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Connect your library") }
        }
    }
}

@Composable
private fun EmptyPool(open: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Nothing in this mix yet.", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp)); Text("Try including more systems or loosening your filters.", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(14.dp)); OutlinedButton(onClick = open) { Text("Adjust filters") }
    }
}

@Composable
private fun PanelDialog(title: String, dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.94f).widthIn(max = 840.dp).fillMaxHeight(.94f), shape = RoundedCornerShape(22.dp), color = Panel) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, "Close") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), content = content)
            }
        }
    }
}

@Composable
private fun LibraryDialog(state: UiState, model: CartwheelViewModel, dismiss: () -> Unit) {
    var config by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.listSaver(
        save = { listOf(it.source, it.roms, it.esde, it.media) }, restore = { LibraryConfig(it[0], it[1], it[2], it[3]) }
    )) { mutableStateOf(state.config) }
    var picking by rememberSaveable { mutableStateOf("roms") }
    var pickerError by remember { mutableStateOf("") }
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                config = when (picking) { "roms" -> config.copy(roms = uri.toString()); "esde" -> config.copy(esde = uri.toString()); else -> config.copy(media = uri.toString()) }
                pickerError = ""
            } catch (error: Exception) { pickerError = "Android could not keep access to this folder. Try a folder on internal storage or your SD card." }
        }
    }
    PanelDialog("Connect your collection", dismiss) {
        Text("Choose how Cartwheel reads your library. Your files stay untouched.", fontSize = 12.sp, color = Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(config.source == "esde", { config = config.copy(source = "esde") }, { Text("ES-DE gamelists") })
            FilterChip(config.source == "roms", { config = config.copy(source = "roms") }, { Text("Scan ROM folders") })
        }
        Text(if (config.source == "esde") "Best for scraped names, ratings, genres, favorites and play history. Choose ES-DE or its gamelists folder." else "Scans one folder per system (ROMs/nes, ROMs/psx…). No gamelists needed. Artwork is optional.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        Spacer(Modifier.height(10.dp))
        if (config.source == "esde" || config.esde.isNotBlank()) FolderRow("ES-DE folder · library + artwork", config.esde) { picking = "esde"; picker.launch(null) }
        FolderRow(if (config.source == "roms") "ROMs folder" else "ROMs folder · optional", config.roms) { picking = "roms"; picker.launch(null) }
        if (config.esde.isNotBlank() && config.media.isBlank()) {
            Text("Artwork: automatic from your ES-DE folder", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            Text("Matches downloaded_media by system and ROM filename. Covers first; other media fills gaps.", fontSize = 11.sp, color = Muted)
            TextButton(onClick = { picking = "media"; picker.launch(null) }) { Text("Use another artwork folder") }
        } else FolderRow("Artwork folder · optional", config.media) { picking = "media"; picker.launch(null) }
        if (config.source == "roms" && config.esde.isBlank()) TextButton(onClick = { picking = "esde"; picker.launch(null) }) { Text("Link ES-DE for its downloaded artwork") }
        if (config.media.isNotBlank()) TextButton(onClick = { config = config.copy(media = "") }) { Text("Use default ES-DE artwork location") }
        if (pickerError.isNotBlank()) Text(pickerError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        Text("Select a folder inside your SD card, not the entire card. If artwork is in ES-DE/downloaded_media, selecting ES-DE is enough.", color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
        Spacer(Modifier.height(12.dp))
        Button(onClick = { model.import(config); dismiss() }, enabled = !state.busy && (if (config.source == "esde") config.esde.isNotBlank() else config.roms.isNotBlank()), modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Sync, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (state.games.isEmpty()) "Build my library" else "Rescan library")
        }
        if (state.games.isNotEmpty()) Text("${state.games.count { !it.hidden }} games · ${state.games.map(Game::system).distinct().size} systems · ${state.games.count { it.artwork.isNotBlank() }} with artwork", fontSize = 11.sp, color = Muted)
        if (state.warnings.isNotEmpty()) {
            Spacer(Modifier.height(10.dp)); Text("Import notes", fontWeight = FontWeight.Bold)
            state.warnings.take(30).forEach { Text(it, color = Muted, fontSize = 11.sp) }
        }
    }
}

@Composable
private fun FolderRow(title: String, uri: String, choose: () -> Unit) {
    OutlinedButton(onClick = choose, modifier = Modifier.fillMaxWidth().padding(bottom = 5.dp), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(12.dp), border = BorderStroke(1.dp, Color.White.copy(alpha = .15f))) {
        Icon(Icons.Outlined.FolderOpen, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp)
            Text(if (uri.isBlank()) "Tap to choose" else Uri.decode(uri.substringAfterLast("/tree/")).substringBefore("/document/"), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Muted)
        }
        Icon(if (uri.isBlank()) Icons.Outlined.ChevronRight else Icons.Outlined.CheckCircle, null, Modifier.size(18.dp))
    }
}

@Composable
private fun FiltersDialog(state: UiState, model: CartwheelViewModel, dismiss: () -> Unit) {
    PanelDialog("Build your mix", dismiss) {
        Text("${state.eligible.size} games match. These filters apply to roulette and weekly picks.", fontSize = 12.sp, color = Muted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(state.filters.favoritesOnly, { model.filters(state.filters.copy(favoritesOnly = it)) }); Text("Favorites only", fontSize = 13.sp)
            Spacer(Modifier.width(16.dp)); Checkbox(state.filters.unplayedOnly, { model.filters(state.filters.copy(unplayedOnly = it)) }); Text("Unplayed only*", fontSize = 13.sp)
        }
        var expanded by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { expanded = true }) { Text("Genre: ${state.filters.genre.ifBlank { "All genres" }}"); Icon(Icons.Outlined.ExpandMore, null) }
            DropdownMenu(expanded, { expanded = false }) {
                (listOf("") + state.games.map(Game::genre).filter(String::isNotBlank).distinct().sorted()).forEach { genre ->
                    DropdownMenuItem(text = { Text(genre.ifBlank { "All genres" }) }, onClick = { model.filters(state.filters.copy(genre = genre)); expanded = false })
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Include systems", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { model.filters(state.filters.copy(excludedSystems = emptySet())) }) { Text("All") }
            TextButton(onClick = { model.filters(state.filters.copy(excludedSystems = state.games.map(Game::system).toSet())) }) { Text("None") }
        }
        state.games.groupBy(Game::system).toSortedMap().forEach { (system, games) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(system !in state.filters.excludedSystems, { include -> model.filters(state.filters.copy(excludedSystems = if (include) state.filters.excludedSystems - system else state.filters.excludedSystems + system)) })
                Text(systemName(system), Modifier.weight(1f), fontSize = 13.sp)
                Text("${games.count { !it.hidden }}", color = Muted, fontSize = 12.sp)
            }
        }
        Text("*Unplayed means no ES-DE play count and not saved to your Cartwheel play list. ROM scans have no emulator play history. ES-DE hidden games are always excluded.", fontSize = 10.sp, lineHeight = 15.sp, color = Muted)
        TextButton(onClick = { model.filters(GameFilters()) }) { Text("Reset all filters") }
    }
}

@Composable
private fun RecommendationsDialog(state: UiState, model: CartwheelViewModel, dismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    PanelDialog("Weekly recommendations", dismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(state.onlineEnabled, model::onlineRecommendations)
            Text("Use online review scores", fontSize = 14.sp)
        }
        Text("Cartwheel looks for well-reviewed games you own. It downloads a shared catalog and matches titles and systems on your handheld.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(10.dp))
        Text("${state.reviews.size} games matched to online scores", fontWeight = FontWeight.Bold)
        Text("Source: Metacritic scores recorded in Wikidata. Coverage varies, especially for older systems. When no reviewed games match your mix, you get a discovery pick. Every fourth reroll also explores the wider library.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(10.dp))
        Text("Platform-specific scores take priority. If the source does not identify an edition, that is shown on the pick. Multiple unspecified scores appear as a range.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        if (state.advisorUpdated > 0) {
            val date = java.time.Instant.ofEpochMilli(state.advisorUpdated).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            Text("Catalog saved $date · refreshes at most weekly", fontSize = 11.sp, color = Muted)
        }
        state.advisorError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        Spacer(Modifier.height(10.dp))
        Button(onClick = { model.loadRecommendations(force = true) }, enabled = state.onlineEnabled && !state.advisorLoading) {
            Text(if (state.advisorLoading) "Getting online scores…" else "Refresh online scores")
        }
        state.weekly?.let { game -> state.reviews[game.id]?.let { review ->
            TextButton(onClick = { uriHandler.openUri(review.sourceUrl) }) { Text("View this pick’s score source") }
        } }
    }
}

private data class ArticleState(val busy: Boolean = true, val articles: List<GameArticle> = emptyList(), val error: String? = null)

@Composable
private fun OnlineBackgroundDialog(game: Game, model: CartwheelViewModel, dismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    var search by rememberSaveable(game.id) { mutableStateOf(game.name) }
    var submitted by rememberSaveable(game.id) { mutableStateOf(game.name) }
    var attempt by remember { mutableIntStateOf(0) }
    val result by produceState(ArticleState(), submitted, attempt) {
        value = ArticleState()
        value = try { ArticleState(false, model.articles(submitted)) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { ArticleState(false, error = "Could not reach Wikipedia. Check your connection and try again.") }
    }
    PanelDialog("About ${game.name}", dismiss) {
        Text("Wikipedia search results. Check the title and edition before opening an article.", color = Muted, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(search, { search = it }, modifier = Modifier.weight(1f), label = { Text("Game title") }, singleLine = true)
            Button(onClick = { submitted = search.trim(); attempt++ }, enabled = search.isNotBlank() && !result.busy) { Text("Search") }
        }
        Spacer(Modifier.height(10.dp))
        if (result.busy) CircularProgressIndicator(Modifier.size(24.dp))
        result.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        if (!result.busy && result.error == null && result.articles.isEmpty()) Text("No matching articles. Try a different title.", color = Muted)
        result.articles.forEach { article ->
            Text(article.title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(article.extract.ifBlank { "Open the article for more information." }, fontSize = 12.sp, lineHeight = 18.sp, color = Muted)
            TextButton(onClick = { uriHandler.openUri(article.url) }) { Text("Read on Wikipedia") }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Color.White.copy(alpha = .1f))
        }
        Text("Excerpts from Wikipedia contributors; shortened for display.", color = Muted, fontSize = 10.sp)
        TextButton(onClick = { uriHandler.openUri("https://creativecommons.org/licenses/by-sa/4.0/") }) { Text("CC BY-SA 4.0", fontSize = 11.sp) }
    }
}
