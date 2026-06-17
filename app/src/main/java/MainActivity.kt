package com.fireflicker.fireplex2

import android.app.Activity
import android.content.Intent
import android.Manifest
import android.net.Uri
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.fireflicker.fireplex2.data.AppAuthRepository
import com.fireflicker.fireplex2.data.VlcPlayerSettings
import com.fireflicker.fireplex2.data.ExoPlayerSettings
import com.fireflicker.fireplex2.data.MpvPlayerSettings
import com.fireflicker.fireplex2.data.OpenSubtitleResult
import com.fireflicker.fireplex2.data.OpenSubtitlesRepository
import com.fireflicker.fireplex2.data.PlexLibrary
import com.fireflicker.fireplex2.data.PlexMediaItem
import com.fireflicker.fireplex2.data.PlexPin
import com.fireflicker.fireplex2.data.PlexRepository
import com.fireflicker.fireplex2.data.PlexSubtitleTrack
import `is`.xyz.mpv.FirePlexMPVView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media as VlcMedia
import org.videolan.libvlc.MediaPlayer as VlcMediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

enum class PlayerChoice {
    Exo,
    Vlc,
    Mpv
}

enum class ContentMode {
    Vod,
    Series
}

enum class AppDisplayMode {
    Tv,
    Mobile
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestFirePlexPermissions()

        val repo = PlexRepository(applicationContext)
        setContent { FirePlexApp(repo) }
    }

    private fun requestFirePlexPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.RECORD_AUDIO
            )
        } else {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.RECORD_AUDIO
            )
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        }
    }
}

@Composable
fun FirePlexApp(repo: PlexRepository) {
    val activity = LocalContext.current as? Activity
    val scope = rememberCoroutineScope()
    val appAuth = remember { AppAuthRepository() }
    val openSubtitles = remember { OpenSubtitlesRepository() }

    var status by remember { mutableStateOf("Checking saved Plex login...") }
    var needsAppLogin by remember { mutableStateOf(false) }
    var appLoginName by remember { mutableStateOf("") }
    var appLoginLoading by remember { mutableStateOf(false) }
    var appLoginMessage by remember { mutableStateOf("Enter your FirePlex username / player name.") }
    var pin by remember { mutableStateOf<PlexPin?>(null) }
    var linked by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }
    var appDisplayMode by remember { mutableStateOf<AppDisplayMode?>(null) }

    var serverName by remember { mutableStateOf<String?>(null) }
    var friendlyName by remember { mutableStateOf("FirePlex3.0") }
    var preferredPlayer by remember { mutableStateOf(PlayerChoice.Vlc) }
    var streamMode by remember { mutableStateOf("direct_play") }
    var exoSettings by remember { mutableStateOf(ExoPlayerSettings()) }
    var vlcSettings by remember { mutableStateOf(VlcPlayerSettings()) }
    var mpvSettings by remember { mutableStateOf(MpvPlayerSettings()) }

    var libraries by remember { mutableStateOf<List<PlexLibrary>>(emptyList()) }
    var hiddenKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var mediaItems by remember { mutableStateOf<List<PlexMediaItem>>(emptyList()) }
    var recentlyMovies by remember { mutableStateOf<List<PlexMediaItem>>(emptyList()) }
    var recentlyShows by remember { mutableStateOf<List<PlexMediaItem>>(emptyList()) }
    var continueWatching by remember { mutableStateOf<List<PlexMediaItem>>(emptyList()) }
    var favoriteKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var favoriteItems by remember { mutableStateOf<List<PlexMediaItem>>(emptyList()) }
    var showFavoritesScreen by remember { mutableStateOf(false) }
    var artworkUrls by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var backdropUrls by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var cachedAt by remember { mutableStateOf(0L) }

    var showSettings by remember { mutableStateOf(false) }
    var showUpdateScreen by remember { mutableStateOf(false) }
    var selectedMode by remember { mutableStateOf<ContentMode?>(null) }
    var menuOpen by remember { mutableStateOf(true) }
    var selectedLibrary by remember { mutableStateOf<PlexLibrary?>(null) }
    var selectedShow by remember { mutableStateOf<PlexMediaItem?>(null) }
    var selectedSeason by remember { mutableStateOf<PlexMediaItem?>(null) }
    var seasonItems by remember { mutableStateOf<List<PlexMediaItem>>(emptyList()) }
    var episodeItems by remember { mutableStateOf<List<PlexMediaItem>>(emptyList()) }
    var selectedDetailItem by remember { mutableStateOf<PlexMediaItem?>(null) }
    var selectedItem by remember { mutableStateOf<PlexMediaItem?>(null) }

    var playUrl by remember { mutableStateOf<String?>(null) }
    var selectedSubtitle by remember { mutableStateOf<PlexSubtitleTrack?>(null) }
    var selectedSubtitleUrl by remember { mutableStateOf<String?>(null) }
    var externalSubtitleUrl by remember { mutableStateOf<String?>(null) }
    var externalSubtitleName by remember { mutableStateOf<String?>(null) }
    var currentPlaybackPositionMs by remember { mutableStateOf(0L) }

    var speedResult by remember { mutableStateOf("Not tested yet") }
    var updateVodStatus by remember { mutableStateOf("Waiting") }
    var updateSeriesStatus by remember { mutableStateOf("Waiting") }
    var updateArtworkStatus by remember { mutableStateOf("Waiting") }

    DisposableEffect(selectedItem) {
        val window = activity?.window
        if (window != null && selectedItem != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        }

        onDispose {
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, true)
                WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    fun sendPlayback(item: PlexMediaItem, state: String, timeMs: Long, durationMs: Long) {
        scope.launch {
            try {
                repo.reportPlayback(item, state, timeMs, durationMs)
            } catch (_: Throwable) {
            }
        }
    }

    fun clearPlayback() {
        selectedItem?.let { sendPlayback(it, "stopped", 0L, it.durationMs) }
        selectedItem = null
        playUrl = null
        selectedSubtitle = null
        selectedSubtitleUrl = null
        externalSubtitleUrl = null
        externalSubtitleName = null
        currentPlaybackPositionMs = 0L
    }

    fun goHome() {
        showSettings = false
        showUpdateScreen = false
        showFavoritesScreen = false
        selectedMode = null
        selectedLibrary = null
        selectedShow = null
        selectedSeason = null
        seasonItems = emptyList()
        episodeItems = emptyList()
        selectedDetailItem = null
        clearPlayback()
        status = "Choose something to watch."
    }

    BackHandler {
        when {
            selectedItem != null -> {
                clearPlayback()
                status = "Choose something to play."
            }

            showFavoritesScreen -> {
                showFavoritesScreen = false
                status = "Choose something to watch."
            }

            selectedDetailItem != null -> {
                selectedDetailItem = null
                status = "Choose something to play."
            }

            selectedSeason != null -> {
                selectedSeason = null
                episodeItems = emptyList()
                status = "Choose a season."
            }

            selectedShow != null -> {
                selectedShow = null
                selectedSeason = null
                seasonItems = emptyList()
                episodeItems = emptyList()
                status = "Choose a TV series."
            }

            selectedLibrary != null -> {
                selectedLibrary = null
                selectedShow = null
                selectedSeason = null
                seasonItems = emptyList()
                episodeItems = emptyList()
                selectedDetailItem = null
                status = "Choose something to watch."
            }

            selectedMode != null -> {
                selectedMode = null
                selectedDetailItem = null
                status = "Choose VOD or Series."
            }

            showSettings -> {
                showSettings = false
                status = "Choose something to watch."
            }

            showUpdateScreen -> {
                showUpdateScreen = false
                status = "Choose something to watch."
            }

            else -> showExitDialog = true
        }
    }

    suspend fun loadArtwork(items: List<PlexMediaItem>): Pair<Map<String, String>, Map<String, String>> {
        val thumbs = mutableMapOf<String, String>()
        val backs = mutableMapOf<String, String>()

        items
            .asSequence()
            .filter { it.ratingKey.isNotBlank() }
            .distinctBy { it.ratingKey }
            .forEach { item ->
                if (item.thumb.isNotBlank()) {
                    thumbs[item.ratingKey] = repo.imageUrl(item.thumb, width = 300, height = 450)
                }

                if (item.art.isNotBlank()) {
                    backs[item.ratingKey] = repo.imageUrl(item.art, width = 1280, height = 720)
                }
            }

        return thumbs to backs
    }

    suspend fun loadCachedContent(): Boolean {
        val cachedLibraries = repo.cachedLibraries()
        val cachedMovies = repo.cachedRecentlyAddedMovies()
        val cachedShows = repo.cachedRecentlyAddedShows()
        cachedAt = repo.cachedUpdatedAt()

        if (cachedLibraries.isEmpty() && cachedMovies.isEmpty() && cachedShows.isEmpty()) {
            return false
        }

        libraries = cachedLibraries
        recentlyMovies = cachedMovies
        recentlyShows = cachedShows
        continueWatching = emptyList()
        hiddenKeys = repo.hiddenLibraryKeys()

        val artwork = loadArtwork(recentlyMovies + recentlyShows + continueWatching)
        artworkUrls = artwork.first
        backdropUrls = artwork.second

        return true
    }

    suspend fun refreshFavoritesFromCache() {
        val keys = repo.favoriteKeys()
        favoriteKeys = keys
        if (keys.isEmpty()) {
            favoriteItems = emptyList()
            return
        }

        val collected = mutableListOf<PlexMediaItem>()
        collected += recentlyMovies
        collected += recentlyShows
        collected += continueWatching
        collected += mediaItems
        collected += seasonItems
        collected += episodeItems
        libraries.forEach { library ->
            collected += runCatching { repo.cachedLibraryItems(library.key) }.getOrDefault(emptyList())
        }

        val favs = collected
            .filter { keys.contains(mediaItemStableId(it)) }
            .distinctBy { mediaItemStableId(it) }
        favoriteItems = favs

        if (favs.isNotEmpty()) {
            val artwork = loadArtwork(favs)
            artworkUrls = artworkUrls + artwork.first
            backdropUrls = backdropUrls + artwork.second
        }
    }

    fun updateContent() {
        scope.launch {
            loading = true
            showUpdateScreen = true
            showSettings = false
            selectedMode = null
            selectedLibrary = null
            selectedDetailItem = null
            updateVodStatus = "Waiting"
            updateSeriesStatus = "Waiting"
            updateArtworkStatus = "Waiting"
            status = "Updating media contents..."

            try {
                if (repo.savedServerBase().isNullOrBlank()) {
                    val server = repo.autoSelectServer()
                    serverName = server.name.ifBlank { "Plex Media Server" }
                } else {
                    serverName = serverName ?: "Plex Media Server"
                }

                val freshLibraries = repo.libraries()
                libraries = freshLibraries
                hiddenKeys = repo.hiddenLibraryKeys()
                val enabled = freshLibraries.filterNot { hiddenKeys.contains(it.key) }

                updateVodStatus = "Updating..."
                val movies = repo.recentlyAddedMovies(enabled)
                recentlyMovies = movies
                updateVodStatus = "Completed"

                updateSeriesStatus = "Updating..."
                val shows = repo.recentlyAddedShows(enabled)
                recentlyShows = shows
                updateSeriesStatus = "Completed"

                enabled.forEach { library ->
                    val items = runCatching { repo.libraryItems(library) }.getOrDefault(emptyList())
                    repo.saveLibraryCache(library.key, items)
                }

                val watching = repo.continueWatching()
                continueWatching = watching

                updateArtworkStatus = "Updating..."
                val artwork = loadArtwork(movies + shows + watching)
                artworkUrls = artwork.first
                backdropUrls = artwork.second
                updateArtworkStatus = "Completed"

                repo.saveHomeCache(freshLibraries, movies, shows)
                refreshFavoritesFromCache()
                cachedAt = repo.cachedUpdatedAt()
                status = "Media contents updated."
            } catch (e: Throwable) {
                status = e.message ?: "Could not update media contents."
                if (updateVodStatus == "Updating...") updateVodStatus = "Failed"
                if (updateSeriesStatus == "Updating...") updateSeriesStatus = "Failed"
                if (updateArtworkStatus == "Updating...") updateArtworkStatus = "Failed"
            }

            loading = false
        }
    }

    fun clearContentCache() {
        scope.launch {
            repo.clearContentCache()
            libraries = emptyList()
            recentlyMovies = emptyList()
            recentlyShows = emptyList()
            continueWatching = emptyList()
            favoriteKeys = emptySet()
            favoriteItems = emptyList()
            showFavoritesScreen = false
            artworkUrls = emptyMap()
            backdropUrls = emptyMap()
            cachedAt = 0L
            status = "Content cache cleared. Run Update Contents to reload."
        }
    }

    fun signOutFirePlex() {
        scope.launch {
            repo.clearToken()
            linked = false
            pin = null
            needsAppLogin = false
            appLoginName = ""
            appLoginMessage = "Enter your FirePlex username / player name."
            serverName = null
            libraries = emptyList()
            hiddenKeys = emptySet()
            recentlyMovies = emptyList()
            recentlyShows = emptyList()
            continueWatching = emptyList()
            favoriteKeys = emptySet()
            favoriteItems = emptyList()
            showFavoritesScreen = false
            mediaItems = emptyList()
            artworkUrls = emptyMap()
            backdropUrls = emptyMap()
            showSettings = false
            showUpdateScreen = false
            selectedMode = null
            selectedLibrary = null
            selectedShow = null
            selectedSeason = null
            seasonItems = emptyList()
            episodeItems = emptyList()
            selectedDetailItem = null
            clearPlayback()
            status = "Signed out. Press Generate Code to link your Plex account."
        }
    }

    fun loadHome() {
        scope.launch {
            needsAppLogin = false
            loading = true
            status = "Loading FirePlex home..."

            try {
                if (repo.savedServerBase().isNullOrBlank()) {
                    val server = repo.autoSelectServer()
                    serverName = server.name.ifBlank { "Plex Media Server" }
                } else {
                    serverName = serverName ?: "Plex Media Server"
                }
                friendlyName = repo.friendlyDeviceName()
                preferredPlayer = when (repo.preferredPlayer()) {
                    "mpv" -> PlayerChoice.Mpv
                    else -> PlayerChoice.Vlc
                }
                streamMode = repo.streamMode()
                exoSettings = repo.exoPlayerSettings()
                vlcSettings = repo.vlcPlayerSettings()
                mpvSettings = repo.mpvPlayerSettings()

                showSettings = false
                showUpdateScreen = false
                showFavoritesScreen = false
                selectedMode = null
                selectedLibrary = null
                selectedShow = null
                selectedSeason = null
                seasonItems = emptyList()
                episodeItems = emptyList()
                selectedDetailItem = null
                clearPlayback()
                mediaItems = emptyList()

                val loadedCache = loadCachedContent()
                refreshFavoritesFromCache()
                if (!loadedCache) {
                    status = "No cached content yet. Run Update Contents."
                    showUpdateScreen = true
                    updateContent()
                } else {
                    status = "Choose VOD or Series."
                }
            } catch (e: Throwable) {
                status = e.message ?: "Could not load home screen."
            }

            loading = false
        }
    }

    fun setLibraryEnabled(library: PlexLibrary, enabled: Boolean) {
        scope.launch {
            repo.setLibraryEnabled(library.key, enabled)
            hiddenKeys = repo.hiddenLibraryKeys()
        }
    }

    fun saveFriendlyName(name: String) {
        scope.launch {
            repo.saveFriendlyDeviceName(name)
            friendlyName = repo.friendlyDeviceName()
            status = "Saved device name."
        }
    }




    fun openFavorites() {
        scope.launch {
            loading = true
            showFavoritesScreen = true
            showSettings = false
            showUpdateScreen = false
            selectedMode = null
            selectedLibrary = null
            selectedDetailItem = null
            status = "Loading favourites..."
            refreshFavoritesFromCache()
            status = if (favoriteItems.isEmpty()) "No favourites yet. Hold OK / long press a poster to add one." else "Choose a favourite."
            loading = false
        }
    }

    fun toggleFavorite(item: PlexMediaItem) {
        scope.launch {
            val isFavorite = repo.toggleFavorite(item)
            val key = mediaItemStableId(item)
            favoriteKeys = if (isFavorite) favoriteKeys + key else favoriteKeys - key
            favoriteItems = if (isFavorite) {
                (favoriteItems + item).distinctBy { mediaItemStableId(it) }
            } else {
                favoriteItems.filterNot { mediaItemStableId(it) == key }
            }
            status = if (isFavorite) "Added to favourites." else "Removed from favourites."
        }
    }

    fun appLogin() {
        val cleanName = appLoginName.trim()
        if (cleanName.isBlank()) {
            appLoginMessage = "Enter your username / player name first."
            return
        }

        scope.launch {
            appLoginLoading = true
            appLoginMessage = "Checking your FirePlex account..."
            status = "Checking account..."

            try {
                val result = appAuth.login(username = cleanName, deviceId = "fireplex-android")
                if (result.allowed) {
                    repo.saveAppUsername(cleanName)
                    repo.saveFriendlyDeviceName(cleanName)
                    friendlyName = repo.friendlyDeviceName()
                    needsAppLogin = false
                    appLoginMessage = "Welcome ${result.username ?: cleanName}."
                    status = "Account active. Loading FirePlex home..."
                    loadHome()
                } else {
                    repo.clearAppUsername()
                    val reasonText = when (result.reason) {
                        "expired" -> "This account expired on ${result.expiryDate ?: "the saved expiry date"}."
                        "not_found" -> "Username not found on the FirePlex panel."
                        else -> result.reason ?: "Account is not active."
                    }
                    appLoginMessage = reasonText
                    status = reasonText
                }
            } catch (e: Throwable) {
                appLoginMessage = e.message ?: "Could not contact FirePlex login server."
                status = appLoginMessage
            }

            appLoginLoading = false
        }
    }

    fun checkSavedAppLogin(savedName: String) {
        scope.launch {
            appLoginLoading = true
            appLoginName = savedName
            appLoginMessage = "Checking your FirePlex account..."
            status = "Checking account..."

            try {
                val result = appAuth.login(username = savedName, deviceId = "fireplex-android")
                if (result.allowed) {
                    repo.saveAppUsername(savedName)
                    repo.saveFriendlyDeviceName(savedName)
                    friendlyName = repo.friendlyDeviceName()
                    needsAppLogin = false
                    status = "Account active. Loading FirePlex home..."
                    loadHome()
                } else {
                    repo.clearAppUsername()
                    needsAppLogin = true
                    val reasonText = when (result.reason) {
                        "expired" -> "This account expired on ${result.expiryDate ?: "the saved expiry date"}."
                        "not_found" -> "Username not found on the FirePlex panel."
                        else -> result.reason ?: "Account is not active."
                    }
                    appLoginMessage = reasonText
                    status = reasonText
                }
            } catch (e: Throwable) {
                needsAppLogin = false
                status = "Could not check account, using saved login."
                loadHome()
            }

            appLoginLoading = false
        }
    }

    LaunchedEffect(Unit) {
        val token = repo.savedToken()
        if (!token.isNullOrBlank()) {
            linked = true
            val savedName = repo.savedAppUsername()
            if (!savedName.isNullOrBlank()) {
                needsAppLogin = false
                checkSavedAppLogin(savedName)
            } else {
                needsAppLogin = true
                status = "Enter your FirePlex player name to continue."
            }
        } else {
            status = "Press Generate Code to link your Plex account."
        }
    }

    fun savePlayerChoice(choice: PlayerChoice) {
        scope.launch {
            preferredPlayer = if (choice == PlayerChoice.Mpv) PlayerChoice.Mpv else PlayerChoice.Vlc
            repo.savePreferredPlayer(if (choice == PlayerChoice.Mpv) "mpv" else "vlc")
            status = "Saved embedded player setting."
        }
    }

    fun saveStreamMode(mode: String) {
        scope.launch {
            streamMode = mode
            repo.saveStreamMode(mode)
            status = "Saved stream type: ${streamModeLabel(mode)}."
        }
    }

    fun saveExoSettings(settings: ExoPlayerSettings) {
        scope.launch {
            exoSettings = settings
            repo.saveExoPlayerSettings(settings)
            status = "Saved ExoPlayer settings."
        }
    }

    fun saveVlcSettings(settings: VlcPlayerSettings) {
        scope.launch {
            vlcSettings = settings
            repo.saveVlcPlayerSettings(settings)
            status = "Saved VLC settings."
        }
    }

    fun saveMpvSettings(settings: MpvPlayerSettings) {
        scope.launch {
            mpvSettings = settings
            repo.saveMpvPlayerSettings(settings)
            status = "Saved MPV settings."
        }
    }

    fun runSpeedTest() {
        scope.launch {
            speedResult = "Testing..."
            try {
                speedResult = repo.speedTest()
            } catch (e: Throwable) {
                speedResult = e.message ?: "Speed test failed."
            }
        }
    }

    fun openTvShow(show: PlexMediaItem) {
        scope.launch {
            loading = true
            status = "Loading seasons..."

            try {
                selectedShow = show
                selectedSeason = null
                selectedDetailItem = null
                episodeItems = emptyList()
                seasonItems = repo.tvSeasons(show)

                val artwork = loadArtwork(seasonItems + listOf(show))
                artworkUrls = artworkUrls + artwork.first
                backdropUrls = backdropUrls + artwork.second

                status = if (seasonItems.isEmpty()) {
                    "No seasons found for ${show.title}."
                } else {
                    "Choose a season."
                }
            } catch (e: Throwable) {
                status = e.message ?: "Could not load seasons."
            }

            loading = false
        }
    }

    fun openTvSeason(season: PlexMediaItem) {
        scope.launch {
            loading = true
            status = "Loading episodes..."

            try {
                selectedSeason = season
                selectedDetailItem = null
                episodeItems = repo.seasonEpisodes(season)

                val artwork = loadArtwork(episodeItems + listOf(season))
                artworkUrls = artworkUrls + artwork.first
                backdropUrls = backdropUrls + artwork.second

                status = if (episodeItems.isEmpty()) {
                    "No episodes found in ${season.title}."
                } else {
                    "Choose an episode."
                }
            } catch (e: Throwable) {
                status = e.message ?: "Could not load episodes."
            }

            loading = false
        }
    }

    fun openDetails(item: PlexMediaItem) {
        if (item.type.equals("show", ignoreCase = true) || item.type.equals("tv", ignoreCase = true)) {
            openTvShow(item)
            return
        }

        if (item.type.equals("season", ignoreCase = true)) {
            openTvSeason(item)
            return
        }

        scope.launch {
            loading = true
            status = "Loading details..."

            try {
                val detailed = repo.mediaDetails(item)
                selectedDetailItem = detailed

                val artwork = loadArtwork(listOf(detailed))
                artworkUrls = artworkUrls + artwork.first
                backdropUrls = backdropUrls + artwork.second

                status = "Choose playback options."
            } catch (e: Throwable) {
                selectedDetailItem = item
                status = e.message ?: "Could not load full details."
            }

            loading = false
        }
    }

    fun openPlayer(item: PlexMediaItem, subtitle: PlexSubtitleTrack?) {
        selectedItem = item
        selectedSubtitle = subtitle
        playUrl = null
        selectedSubtitleUrl = null
        externalSubtitleUrl = null
        externalSubtitleName = null

        scope.launch {
            loading = true
            status = "Opening ${item.title}..."

            try {
                playUrl = repo.streamUrl(item, streamMode)
                selectedSubtitleUrl = subtitle?.let { repo.subtitleUrl(it) }?.takeIf { it.isNotBlank() }
                status = "Playing ${item.title} - ${streamModeLabel(streamMode)}"
                sendPlayback(item, "playing", item.viewOffsetMs, item.durationMs)
            } catch (e: Throwable) {
                status = e.message ?: "Could not open video player."
            }

            loading = false
        }
    }

    fun openPlayerWithOpenSubtitle(item: PlexMediaItem, subtitle: OpenSubtitleResult) {
        selectedItem = item
        selectedSubtitle = null
        playUrl = null
        selectedSubtitleUrl = null
        externalSubtitleUrl = null
        externalSubtitleName = subtitle.displayName
        currentPlaybackPositionMs = 0L

        scope.launch {
            loading = true
            status = "Downloading subtitles..."

            try {
                playUrl = repo.streamUrl(item, streamMode)
                externalSubtitleUrl = openSubtitles.downloadSubtitleUrl(subtitle.fileId)
                status = "Playing ${item.title} with ${subtitle.displayName} - ${streamModeLabel(streamMode)}"
                sendPlayback(item, "playing", item.viewOffsetMs, item.durationMs)
            } catch (e: Throwable) {
                status = e.message ?: "Could not open video player with subtitles."
            }

            loading = false
        }
    }

    fun loadLibraryItems(library: PlexLibrary) {
        scope.launch {
            loading = true
            status = "Opening ${library.title}..."

            try {
                // Keep the user on the VOD / Series category page.
                // Do NOT switch to LibraryContentScreen, because that is the wide-row library screen.
                selectedLibrary = null
                selectedShow = null
                selectedSeason = null
                seasonItems = emptyList()
                episodeItems = emptyList()
                selectedDetailItem = null
                clearPlayback()
                showSettings = false
                showUpdateScreen = false

                val cachedItems = repo.cachedLibraryItems(library.key)
                mediaItems = if (cachedItems.isNotEmpty()) {
                    status = "Loaded ${library.title} from saved content."
                    cachedItems
                } else {
                    status = "Loading ${library.title} from Plex..."
                    val freshItems = repo.libraryItems(library)
                    repo.saveLibraryCache(library.key, freshItems)
                    freshItems
                }

                val artwork = loadArtwork(mediaItems)
                artworkUrls = artworkUrls + artwork.first
                backdropUrls = backdropUrls + artwork.second
                refreshFavoritesFromCache()

                status = if (mediaItems.isEmpty()) {
                    "No videos found in ${library.title}."
                } else {
                    "Choose something to watch."
                }
            } catch (e: Throwable) {
                status = e.message ?: "Could not load category content."
            }

            loading = false
        }
    }


    fun checkCurrentPinNow() {
        val currentPin = pin
        if (currentPin == null) {
            status = "Generate a Plex code first."
            return
        }

        scope.launch {
            loading = true
            status = "Checking Plex link..."

            try {
                val latest = repo.checkPin(currentPin.id)

                if (!latest.authToken.isNullOrBlank()) {
                    repo.saveToken(latest.authToken)
                    linked = true
                    val savedName = repo.savedAppUsername()
                    if (!savedName.isNullOrBlank()) {
                        needsAppLogin = false
                        status = "Plex linked. Checking account..."
                        loading = false
                        checkSavedAppLogin(savedName)
                    } else {
                        needsAppLogin = true
                        status = "Plex linked. Enter your FirePlex player name."
                        loading = false
                    }
                } else {
                    status = "Not linked yet. Enter the code on plex.tv/link then press Check Link."
                    loading = false
                }
            } catch (e: Throwable) {
                loading = false
                status = e.message ?: "Could not check Plex link."
            }
        }
    }

    fun startLink() {
        scope.launch {
            loading = true
            status = "Getting Plex link code..."
            linked = false
            pin = null
            serverName = null
            libraries = emptyList()
            hiddenKeys = emptySet()
            recentlyMovies = emptyList()
            recentlyShows = emptyList()
            continueWatching = emptyList()
            artworkUrls = emptyMap()
            backdropUrls = emptyMap()
            showSettings = false
            showUpdateScreen = false
            selectedMode = null
            selectedLibrary = null
            selectedShow = null
            selectedSeason = null
            seasonItems = emptyList()
            episodeItems = emptyList()
            selectedDetailItem = null
            mediaItems = emptyList()
            clearPlayback()
            needsAppLogin = false
            appLoginName = ""
            appLoginMessage = "Enter your FirePlex username / player name."

            try {
                val created = repo.createPin()
                pin = created
                val code = created.code.orEmpty()

                status = if (code.isBlank()) "Plex returned no code." else "Enter this code at plex.tv/link"
                loading = false

                repeat(240) {
                    delay(2000)
                    val latest = repo.checkPin(created.id)

                    if (!latest.authToken.isNullOrBlank()) {
                        repo.saveToken(latest.authToken)
                        linked = true
                        val savedName = repo.savedAppUsername()
                        if (!savedName.isNullOrBlank()) {
                            needsAppLogin = false
                            status = "Plex linked. Checking account..."
                            loading = false
                            checkSavedAppLogin(savedName)
                        } else {
                            needsAppLogin = true
                            status = "Plex linked. Enter your FirePlex player name."
                            loading = false
                        }
                        return@launch
                    }
                }

                status = "Code expired. Press Generate Code again."
            } catch (e: Throwable) {
                loading = false
                status = e.message ?: "Failed to create Plex link code."
            }
        }
    }

    val backgroundArt = remember(backdropUrls, artworkUrls, recentlyMovies, recentlyShows, continueWatching, selectedDetailItem, selectedShow, selectedSeason) {
        selectedDetailItem?.let { backdropUrls[it.ratingKey] ?: artworkUrls[it.ratingKey] }
            ?: selectedSeason?.let { backdropUrls[it.ratingKey] ?: artworkUrls[it.ratingKey] }
            ?: selectedShow?.let { backdropUrls[it.ratingKey] ?: artworkUrls[it.ratingKey] }
            ?: recentlyMovies.firstOrNull()?.let { backdropUrls[it.ratingKey] ?: artworkUrls[it.ratingKey] }
            ?: continueWatching.firstOrNull()?.let { backdropUrls[it.ratingKey] ?: artworkUrls[it.ratingKey] }
            ?: recentlyShows.firstOrNull()?.let { backdropUrls[it.ratingKey] ?: artworkUrls[it.ratingKey] }
            ?: ""
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFE5A00D))) {
        if (appDisplayMode == null) {
            DisplayModeChooserScreen(
                onTv = { appDisplayMode = AppDisplayMode.Tv },
                onMobile = { appDisplayMode = AppDisplayMode.Mobile }
            )
            return@MaterialTheme
        }

        val mobileMode = appDisplayMode == AppDisplayMode.Mobile

        if (selectedItem != null) {
            SelectedVideoScreen(
                item = selectedItem!!,
                playUrl = playUrl,
                subtitleTracks = selectedItem!!.subtitles,
                selectedSubtitle = selectedSubtitle,
                subtitleUrl = selectedSubtitleUrl ?: externalSubtitleUrl,
                externalSubtitleName = externalSubtitleName,
                playerChoice = preferredPlayer,
                vlcSettings = vlcSettings,
                mpvSettings = mpvSettings,
                startPositionMs = currentPlaybackPositionMs,
                loading = loading,
                status = status,
                onSubtitleSelected = { track ->
                    selectedSubtitle = track
                    scope.launch {
                        selectedSubtitleUrl = track?.let { repo.subtitleUrl(it) }?.takeIf { it.isNotBlank() }
                        externalSubtitleUrl = null
                        externalSubtitleName = null
                    }
                },
                onSearchOpenSubtitles = { query, language ->
                    openSubtitles.search(selectedItem!!, query, language)
                },
                onOpenSubtitleSelected = { result ->
                    loading = true
                    status = "Downloading subtitles..."
                    try {
                        externalSubtitleUrl = openSubtitles.downloadSubtitleUrl(result.fileId)
                        externalSubtitleName = result.displayName
                        selectedSubtitle = null
                        selectedSubtitleUrl = null
                        status = "Using subtitle: ${result.displayName}"
                    } finally {
                        loading = false
                    }
                },
                onPlayback = { state, time, duration ->
                    if (time > 0L) currentPlaybackPositionMs = time
                    selectedItem?.let { sendPlayback(it, state, time, duration) }
                }
            )
            return@MaterialTheme
        }

        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF050608))) {
            Image(
                painter = painterResource(id = R.drawable.fireplex_bg),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x99000000))
            )

            if (backgroundArt.isNotBlank() && !needsAppLogin) {
                AsyncImage(
                    model = backgroundArt,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.horizontalGradient(listOf(Color(0xF7050608), Color(0xE6050608), Color(0xB3050608))))
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Color(0x66050608), Color(0xF2050608))))
                )
            }

            Box(modifier = Modifier.fillMaxSize().padding(if (mobileMode) 10.dp else 22.dp)) {
                when {
                    needsAppLogin -> {
                        AppLoginScreen(
                            playerName = appLoginName,
                            message = appLoginMessage,
                            loading = appLoginLoading,
                            onPlayerNameChange = { appLoginName = it },
                            onContinue = { appLogin() },
                            onRelinkPlex = { startLink() }
                        )
                    }

                    showFavoritesScreen -> {
                        FavoritesScreen(
                            items = favoriteItems,
                            artworkUrls = artworkUrls,
                            favoriteKeys = favoriteKeys,
                            loading = loading,
                            status = status,
                            mobileMode = mobileMode,
                            onSelectDetails = { openDetails(it) },
                            onToggleFavorite = { toggleFavorite(it) }
                        )
                    }

                    selectedDetailItem != null -> {
                        MediaDetailsScreen(
                            item = selectedDetailItem!!,
                            artworkUrl = artworkUrls[selectedDetailItem!!.ratingKey].orEmpty(),
                            backdropUrl = backdropUrls[selectedDetailItem!!.ratingKey].orEmpty(),
                            playerChoice = preferredPlayer,
                            onPlay = { item, subtitle -> openPlayer(item, subtitle) }
                        )
                    }

                    showSettings -> {
                        SettingsScreen(
                            libraries = libraries,
                            hiddenKeys = hiddenKeys,
                            friendlyName = friendlyName,
                            playerChoice = preferredPlayer,
                            streamMode = streamMode,
                            exoSettings = exoSettings,
                            vlcSettings = vlcSettings,
                            mpvSettings = mpvSettings,
                            speedResult = speedResult,
                            status = status,
                            cachedAt = cachedAt,
                            onSaveFriendlyName = { saveFriendlyName(it) },
                            onSetLibraryEnabled = { library, enabled -> setLibraryEnabled(library, enabled) },
                            onSavePlayerChoice = { savePlayerChoice(it) },
                            onSaveStreamMode = { saveStreamMode(it) },
                            onSaveExoSettings = { saveExoSettings(it) },
                            onSaveVlcSettings = { saveVlcSettings(it) },
                            onSaveMpvSettings = { saveMpvSettings(it) },
                            onRunSpeedTest = { runSpeedTest() },
                            onOpenUpdate = {
                                showSettings = false
                                showUpdateScreen = true
                            },
                            onClearCache = { clearContentCache() },
                            onSignOut = { signOutFirePlex() },
                            onCloseSettings = { showSettings = false }
                        )
                    }

                    showUpdateScreen -> {
                        UpdateContentsScreen(
                            loading = loading,
                            status = status,
                            vodStatus = updateVodStatus,
                            seriesStatus = updateSeriesStatus,
                            artworkStatus = updateArtworkStatus,
                            cachedAt = cachedAt,
                            onStartUpdate = { updateContent() },
                            onClearCache = { clearContentCache() }
                        )
                    }

                    selectedSeason != null -> {
                        TvEpisodesScreen(
                            show = selectedShow,
                            season = selectedSeason!!,
                            episodes = episodeItems,
                            artworkUrls = artworkUrls,
                            status = status,
                            loading = loading,
                            onSelectEpisode = { openDetails(it) }
                        )
                    }

                    selectedShow != null -> {
                        TvSeasonsScreen(
                            show = selectedShow!!,
                            seasons = seasonItems,
                            artworkUrls = artworkUrls,
                            status = status,
                            loading = loading,
                            onSelectSeason = { openTvSeason(it) }
                        )
                    }

                    selectedLibrary != null -> {
                        LibraryContentScreen(
                            library = selectedLibrary!!,
                            mediaItems = mediaItems,
                            artworkUrls = artworkUrls,
                            status = status,
                            loading = loading,
                            onSelectDetails = { openDetails(it) }
                        )
                    }

                    selectedMode != null -> {
                        val enabledLibraries = libraries.filterNot { hiddenKeys.contains(it.key) }
                        if (mobileMode) {
                            MobileContentBrowseScreen(
                                mode = selectedMode!!,
                                libraries = enabledLibraries,
                                recentlyMovies = recentlyMovies,
                                recentlyShows = recentlyShows,
                                continueWatching = continueWatching,
                                favoriteItems = favoriteItems,
                                favoriteKeys = favoriteKeys,
                                categoryItems = mediaItems,
                                artworkUrls = artworkUrls,
                                status = status,
                                loading = loading,
                                cachedAt = cachedAt,
                                onOpenLibrary = { loadLibraryItems(it) },
                                onToggleFavorite = { toggleFavorite(it) },
                                onOpenSettings = { showSettings = true },
                                onOpenUpdate = { showUpdateScreen = true },
                                onSelectDetails = { openDetails(it) }
                            )
                        } else {
                            ContentBrowseScreen(
                                mode = selectedMode!!,
                                libraries = enabledLibraries,
                                recentlyMovies = recentlyMovies,
                                recentlyShows = recentlyShows,
                                continueWatching = continueWatching,
                                favoriteItems = favoriteItems,
                                favoriteKeys = favoriteKeys,
                                categoryItems = mediaItems,
                                artworkUrls = artworkUrls,
                                status = status,
                                loading = loading,
                                cachedAt = cachedAt,
                                onOpenLibrary = { loadLibraryItems(it) },
                                onToggleFavorite = { toggleFavorite(it) },
                                onOpenSettings = { showSettings = true },
                                onOpenUpdate = { showUpdateScreen = true },
                                onSelectDetails = { openDetails(it) }
                            )
                        }
                    }

                    libraries.isNotEmpty() -> {
                        val enabledLibraries = libraries.filterNot { hiddenKeys.contains(it.key) }

                        if (mobileMode) {
                            MobileLobbyScreen(
                                serverName = serverName ?: "Plex Media Server",
                                friendlyName = friendlyName,
                                libraries = enabledLibraries,
                                allLibrariesHidden = libraries.isNotEmpty() && enabledLibraries.isEmpty(),
                                status = status,
                                loading = loading,
                                cachedAt = cachedAt,
                                onOpenVod = { selectedMode = ContentMode.Vod },
                                onOpenSeries = { selectedMode = ContentMode.Series },
                                onOpenFavorites = { openFavorites() },
                                onOpenSettings = { showSettings = true },
                                onOpenUpdate = { showUpdateScreen = true }
                            )
                        } else {
                            LobbyScreen(
                                serverName = serverName ?: "Plex Media Server",
                                friendlyName = friendlyName,
                                libraries = enabledLibraries,
                                allLibrariesHidden = libraries.isNotEmpty() && enabledLibraries.isEmpty(),
                                status = status,
                                loading = loading,
                                cachedAt = cachedAt,
                                onOpenVod = { selectedMode = ContentMode.Vod },
                                onOpenSeries = { selectedMode = ContentMode.Series },
                                onOpenFavorites = { openFavorites() },
                                onOpenSettings = { showSettings = true },
                                onOpenUpdate = { showUpdateScreen = true }
                            )
                        }
                    }

                    else -> {
                        LinkScreen(
                            pin = pin,
                            status = status,
                            linked = linked,
                            loading = loading,
                            onGenerate = { startLink() },
                            onCheckLink = { checkCurrentPinNow() },
                            onLoadHome = { loadHome() }
                        )
                    }
                }

                if (showExitDialog) {
                    AlertDialog(
                        onDismissRequest = { showExitDialog = false },
                        title = { Text("Exit FirePlex?") },
                        text = { Text("Press Exit to close the app, or Cancel to stay here.") },
                        confirmButton = {
                            Button(onClick = {
                                showExitDialog = false
                                activity?.finish()
                            }) {
                                Text("Exit")
                            }
                        },
                        dismissButton = {
                            OutlinedButton(onClick = { showExitDialog = false }) {
                                Text("Cancel")
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun AppLoginScreen(
    playerName: String,
    message: String,
    loading: Boolean,
    onPlayerNameChange: (String) -> Unit,
    onContinue: () -> Unit,
    onRelinkPlex: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(top = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Image(
            painter = painterResource(id = R.drawable.app_logo),
            contentDescription = "FirePlex logo",
            modifier = Modifier.size(78.dp),
            contentScale = ContentScale.Fit
        )
        Spacer(Modifier.height(8.dp))
        Text("FirePlex3.0", fontSize = 38.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("Player account", color = Color(0xFFE5A00D), fontSize = 16.sp)
        Spacer(Modifier.height(16.dp))

        Card(
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xF2111820)),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Enter player name", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Use the username from your FirePlex web panel. This also becomes the friendly name shown to Plex.",
                    color = Color(0xFFBAC6D3),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(22.dp))

                OutlinedTextField(
                    value = playerName,
                    onValueChange = onPlayerNameChange,
                    singleLine = true,
                    enabled = !loading,
                    label = { Text("Username / player name") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(16.dp))
                Text(message, color = Color(0xFF4DFF9B), textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onContinue, enabled = !loading) { Text(if (loading) "Checking..." else "Continue") }
                    OutlinedButton(onClick = onRelinkPlex, enabled = !loading) { Text("Relink Plex") }
                }
            }
        }
    }
}

@Composable
fun LinkScreen(
    pin: PlexPin?,
    status: String,
    linked: Boolean,
    loading: Boolean,
    onGenerate: () -> Unit,
    onCheckLink: () -> Unit,
    onLoadHome: () -> Unit
) {
    val context = LocalContext.current
    val codeText = pin?.code?.uppercase().orEmpty()

    Column(
        modifier = Modifier.fillMaxSize().padding(top = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Image(
            painter = painterResource(id = R.drawable.app_logo),
            contentDescription = "FirePlex logo",
            modifier = Modifier.size(78.dp),
            contentScale = ContentScale.Fit
        )
        Spacer(Modifier.height(8.dp))
        Text("FirePlex3.0", fontSize = 38.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("Your Plex player", color = Color(0xFFE5A00D), fontSize = 16.sp)
        Spacer(Modifier.height(16.dp))

        Card(modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xF2111820)), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Go to plex.tv/link", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))

                Box(modifier = Modifier.background(Color(0xFF1A2028), RoundedCornerShape(18.dp)).padding(horizontal = 36.dp, vertical = 18.dp), contentAlignment = Alignment.Center) {
                    Text(text = codeText.ifBlank { "----" }, color = Color(0xFFE5A00D), fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 6.sp)
                }

                Spacer(Modifier.height(12.dp))
                Text(status, color = if (linked) Color(0xFF4DFF9B) else Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onGenerate, enabled = !loading) { Text(if (loading) "Working..." else "Generate Code") }
                    if (codeText.isNotBlank() && !linked) {
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Plex link code", codeText))
                                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("https://plex.tv/link")))
                            },
                            enabled = !loading
                        ) { Text("Open Link Page") }
                        OutlinedButton(onClick = onCheckLink, enabled = !loading) { Text("Check Link") }
                    }
                    if (linked) {
                        OutlinedButton(onClick = onLoadHome, enabled = !loading) { Text("Open Home") }
                    }
                }
            }
        }
    }
}


@Composable
fun DisplayModeChooserScreen(
    onTv: () -> Unit,
    onMobile: () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF050608), Color(0xFF111820), Color.Black)))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        val landscape = maxWidth > maxHeight
        val logoSize = if (landscape) 64.dp else 96.dp
        val titleSize = if (landscape) 32.sp else 42.sp
        val tileHeight = if (landscape) 138.dp else 210.dp
        val tileWidth = if (landscape) 760.dp else 520.dp

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.app_logo),
                contentDescription = "FirePlex logo",
                modifier = Modifier.size(logoSize),
                contentScale = ContentScale.Fit
            )
            Spacer(Modifier.height(if (landscape) 8.dp else 14.dp))
            Text("FirePlex4.0", color = Color.White, fontSize = titleSize, fontWeight = FontWeight.Bold)
            Text("Choose how you are using the app", color = Color(0xFFE5A00D), fontSize = if (landscape) 14.sp else 17.sp)
            Spacer(Modifier.height(if (landscape) 18.dp else 34.dp))

            if (landscape) {
                Row(
                    modifier = Modifier.widthIn(max = tileWidth).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    DisplayModeTile(
                        title = "TV",
                        subtitle = "Remote friendly layout for Android TV / Fire Stick",
                        icon = "▣",
                        modifier = Modifier.weight(1f).height(tileHeight),
                        onClick = onTv
                    )
                    DisplayModeTile(
                        title = "MOBILE",
                        subtitle = "Touch friendly layout for phones and tablets",
                        icon = "▤",
                        modifier = Modifier.weight(1f).height(tileHeight),
                        onClick = onMobile
                    )
                }
            } else {
                Column(
                    modifier = Modifier.widthIn(max = tileWidth).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    DisplayModeTile(
                        title = "TV",
                        subtitle = "Remote friendly layout for Android TV / Fire Stick",
                        icon = "▣",
                        modifier = Modifier.fillMaxWidth().height(170.dp),
                        onClick = onTv
                    )
                    DisplayModeTile(
                        title = "MOBILE",
                        subtitle = "Touch friendly layout for phones and tablets",
                        icon = "▤",
                        modifier = Modifier.fillMaxWidth().height(170.dp),
                        onClick = onMobile
                    )
                }
            }
        }
    }
}

@Composable
fun DisplayModeTile(
    title: String,
    subtitle: String,
    icon: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Card(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = if (focused) Color(0xFF203040) else Color(0xE90B0D25)),
        border = BorderStroke(if (focused) 3.dp else 1.dp, if (focused) Color(0xFFE5A00D) else Color(0x44FFFFFF)),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(icon, color = Color(0xFFE5A00D), fontSize = 48.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(subtitle, color = Color(0xFFB7C7D8), textAlign = TextAlign.Center, fontSize = 14.sp)
        }
    }
}

@Composable
fun MobileLobbyScreen(
    serverName: String,
    friendlyName: String,
    libraries: List<PlexLibrary>,
    allLibrariesHidden: Boolean,
    status: String,
    loading: Boolean,
    cachedAt: Long,
    onOpenVod: () -> Unit,
    onOpenSeries: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenUpdate: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(id = R.drawable.app_logo),
                    contentDescription = "FirePlex logo",
                    modifier = Modifier.size(56.dp),
                    contentScale = ContentScale.Fit
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("FirePlex4.0", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text("$serverName - $friendlyName", color = Color(0xFFB7C7D8), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(cacheLabel(cachedAt), color = Color(0xFFE5A00D), fontSize = 11.sp)
                }
            }
        }

        item {
            Text(if (loading) "Loading..." else status, color = Color(0xFFB7C7D8), fontSize = 14.sp, textAlign = TextAlign.Center)
        }

        item {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MobileBigButton("VOD", "Movies and films", !allLibrariesHidden && libraries.any { it.type.equals("movie", true) }, onOpenVod)
                MobileBigButton("SERIES", "TV shows and seasons", !allLibrariesHidden && libraries.any { it.type.equals("show", true) || it.type.equals("tv", true) }, onOpenSeries)
                MobileBigButton("FAVOURITES", "Saved movies and TV series", true, onOpenFavorites)
                MobileBigButton("UPDATE CONTENTS", "Refresh categories and artwork", true, onOpenUpdate)
                MobileBigButton("SETTINGS", "Player, speed test, player name", true, onOpenSettings)
            }
        }
    }
}

@Composable
fun MobileBigButton(title: String, subtitle: String, enabled: Boolean = true, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(86.dp)
            .clickable(enabled = enabled) { onClick() },
        colors = CardDefaults.cardColors(containerColor = if (enabled) Color(0xD9111820) else Color(0x66111820)),
        border = BorderStroke(1.dp, if (enabled) Color(0x77FFFFFF) else Color(0x22FFFFFF)),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.Center) {
            Text(title, color = if (enabled) Color.White else Color(0xFF6D7784), fontSize = 23.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Color(0xFFB7C7D8), fontSize = 13.sp)
        }
    }
}

@Composable
fun MobileContentBrowseScreen(
    mode: ContentMode,
    libraries: List<PlexLibrary>,
    recentlyMovies: List<PlexMediaItem>,
    recentlyShows: List<PlexMediaItem>,
    continueWatching: List<PlexMediaItem>,
    favoriteItems: List<PlexMediaItem>,
    favoriteKeys: Set<String>,
    categoryItems: List<PlexMediaItem>,
    artworkUrls: Map<String, String>,
    status: String,
    loading: Boolean,
    cachedAt: Long,
    onOpenLibrary: (PlexLibrary) -> Unit,
    onToggleFavorite: (PlexMediaItem) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenUpdate: () -> Unit,
    onSelectDetails: (PlexMediaItem) -> Unit
) {
    val movieMode = mode == ContentMode.Vod
    val title = if (movieMode) "VOD" else "SERIES"
    val modeLibraries = libraries.filter {
        if (movieMode) it.type.equals("movie", true) else it.type.equals("show", true) || it.type.equals("tv", true)
    }
    val recent = if (movieMode) recentlyMovies else recentlyShows
    val libraryByKey = remember(modeLibraries) { modeLibraries.associateBy { it.key } }

    var selectedCategory by remember(mode, libraries) { mutableStateOf("RECENTLY ADDED") }
    var searchText by remember { mutableStateOf("") }
    var sortAz by remember(mode) { mutableStateOf(false) }
    var sortNewestFirst by remember(mode) { mutableStateOf(true) }

    val selectedCategoryTitle = when (selectedCategory) {
        "FAVORITES" -> "FAVORITES"
        "RECENTLY ADDED" -> "RECENTLY ADDED"
        "CONTINUE WATCHING" -> "CONTINUE WATCHING"
        else -> libraryByKey[selectedCategory]?.title?.uppercase() ?: selectedCategory
    }

    val baseGridItems = when (selectedCategory) {
        "CONTINUE WATCHING" -> continueWatching
        "FAVORITES" -> favoriteItems.filter { itemMatchesMode(it, mode) }
        "RECENTLY ADDED" -> recent
        else -> if (libraryByKey.containsKey(selectedCategory)) categoryItems else recent
    }

    val visibleGridItems = remember(selectedCategory, mediaItemsFingerprint(baseGridItems), searchText, sortAz, sortNewestFirst) {
        val searched = if (searchText.isBlank()) baseGridItems else baseGridItems.filter { it.title.contains(searchText, ignoreCase = true) }
        if (sortAz) searched.sortedBy { it.title.lowercase() }
        else if (sortNewestFirst) searched.sortedByDescending { it.addedAt }
        else searched.sortedBy { it.addedAt }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(cacheLabel(cachedAt), color = Color(0xFFE5A00D), fontSize = 11.sp)
        }
        Text(selectedCategoryTitle, color = Color(0xFFE5A00D), fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            item { MobileCategoryChip("FAVORITES", selectedCategory == "FAVORITES") { selectedCategory = "FAVORITES" } }
            item { MobileCategoryChip("RECENTLY ADDED", selectedCategory == "RECENTLY ADDED") { selectedCategory = "RECENTLY ADDED" } }
            item { MobileCategoryChip("CONTINUE WATCHING", selectedCategory == "CONTINUE WATCHING") { selectedCategory = "CONTINUE WATCHING" } }
            items(modeLibraries, key = { it.key }) { library ->
                MobileCategoryChip(library.title.uppercase(), selectedCategory == library.key) {
                    selectedCategory = library.key
                    onOpenLibrary(library)
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = searchText,
                onValueChange = { searchText = it },
                modifier = Modifier.weight(1f).height(54.dp),
                singleLine = true,
                label = { Text("Search") }
            )
            FocusActionButton(if (sortAz) "A-Z ON" else "A-Z", Modifier.width(92.dp), Color(0x66111820)) { sortAz = !sortAz }
            FocusActionButton(if (sortNewestFirst) "NEW" else "OLD", Modifier.width(82.dp), Color(0x66111820)) { sortNewestFirst = !sortNewestFirst; sortAz = false }
        }

        Spacer(Modifier.height(8.dp))
        Text(if (loading) "Loading..." else status, color = Color(0xFFB7C7D8), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))

        if (visibleGridItems.isEmpty()) {
            EmptyPanel(if (loading) "Loading..." else "Nothing found in this section.")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 118.dp),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                gridItems(visibleGridItems, key = { it.ratingKey.ifBlank { it.key } }) { item ->
                    MobilePosterCard(
                        item = item,
                        artworkUrl = artworkUrls[item.ratingKey].orEmpty(),
                        isFavorite = favoriteKeys.contains(mediaItemStableId(item)),
                        onClick = { onSelectDetails(item) },
                        onLongClick = { onToggleFavorite(item) }
                    )
                }
            }
        }
    }
}


fun mediaItemStableId(item: PlexMediaItem): String {
    return item.ratingKey.ifBlank { item.key }
}

fun itemMatchesMode(item: PlexMediaItem, mode: ContentMode): Boolean {
    val type = item.type.lowercase()
    return if (mode == ContentMode.Vod) {
        type == "movie" || type == "video" || type == "clip"
    } else {
        type == "show" || type == "tv" || type == "season" || type == "episode"
    }
}

@Composable
fun FavoritesScreen(
    items: List<PlexMediaItem>,
    artworkUrls: Map<String, String>,
    favoriteKeys: Set<String>,
    loading: Boolean,
    status: String,
    mobileMode: Boolean,
    onSelectDetails: (PlexMediaItem) -> Unit,
    onToggleFavorite: (PlexMediaItem) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text("FAVOURITES", color = Color.White, fontSize = if (mobileMode) 28.sp else 38.sp, fontWeight = FontWeight.Bold)
        Text("Hold a poster again to remove it from favourites.", color = Color(0xFFE5A00D), fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        Text(if (loading) "Loading..." else status, color = Color(0xFFB7C7D8), fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))

        if (items.isEmpty()) {
            EmptyPanel("No favourites yet. Hold OK / long press a movie or TV series poster to add it here.")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = if (mobileMode) 118.dp else 150.dp),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(if (mobileMode) 10.dp else 18.dp),
                verticalArrangement = Arrangement.spacedBy(if (mobileMode) 12.dp else 18.dp)
            ) {
                gridItems(items, key = { mediaItemStableId(it) }) { item ->
                    if (mobileMode) {
                        MobilePosterCard(
                            item = item,
                            artworkUrl = artworkUrls[item.ratingKey].orEmpty(),
                            isFavorite = favoriteKeys.contains(mediaItemStableId(item)),
                            onClick = { onSelectDetails(item) },
                            onLongClick = { onToggleFavorite(item) }
                        )
                    } else {
                        MediaPosterCard(
                            item = item,
                            artworkUrl = artworkUrls[item.ratingKey].orEmpty(),
                            isFavorite = favoriteKeys.contains(mediaItemStableId(item)),
                            onClick = { onSelectDetails(item) },
                            onLongClick = { onToggleFavorite(item) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MobileCategoryChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        color = if (selected) Color(0xFFE5A00D) else Color(0xCC111820),
        border = BorderStroke(1.dp, if (selected) Color(0xFFE5A00D) else Color(0x44FFFFFF))
    ) {
        Text(
            text = text,
            color = if (selected) Color.Black else Color.White,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun MobilePosterCard(
    item: PlexMediaItem,
    artworkUrl: String,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(118.dp)
            .height(202.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(containerColor = Color(0xDD111820)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().height(165.dp).background(Color(0xFF1A2028)), contentAlignment = Alignment.Center) {
                if (artworkUrl.isNotBlank()) {
                    AsyncImage(
                        model = artworkUrl,
                        contentDescription = item.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text("No Art", color = Color(0xFFB7C7D8), fontSize = 12.sp)
                }
                if (isFavorite) {
                    Text(
                        "★",
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                        color = Color(0xFFE5A00D),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text(
                item.title.ifBlank { "Untitled" },
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(6.dp)
            )
        }
    }
}

@Composable
fun LobbyScreen(
    serverName: String,
    friendlyName: String,
    libraries: List<PlexLibrary>,
    allLibrariesHidden: Boolean,
    status: String,
    loading: Boolean,
    cachedAt: Long,
    onOpenVod: () -> Unit,
    onOpenSeries: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenUpdate: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(id = R.drawable.app_logo),
                        contentDescription = "FirePlex logo",
                        modifier = Modifier.size(58.dp),
                        contentScale = ContentScale.Fit
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("FirePlex3.0", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Text("$serverName - $friendlyName", color = Color(0xFFB7C7D8), fontSize = 13.sp)
                        Text(cacheLabel(cachedAt), color = Color(0xFFE5A00D), fontSize = 12.sp)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    LobbySmallButton("SEARCH") { }
                    LobbySmallButton("UPDATE") { onOpenUpdate() }
                }
            }

            Spacer(Modifier.weight(1f))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                LobbyCircleTile(label = "VOD", icon = "▷", enabled = !allLibrariesHidden && libraries.any { it.type.equals("movie", true) }, onClick = onOpenVod)
                Spacer(Modifier.width(32.dp))
                LobbyCircleTile(label = "SERIES", icon = "▤", enabled = !allLibrariesHidden && libraries.any { it.type.equals("show", true) || it.type.equals("tv", true) }, onClick = onOpenSeries)
            }

            Spacer(Modifier.weight(1f))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text(if (loading) "Loading..." else status, color = Color(0xFFB7C7D8), fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LobbySmallButton("SETTINGS") { onOpenSettings() }
                    LobbySmallButton("FAVOURITES") { onOpenFavorites() }
                }
            }
        }
    }
}

@Composable
fun ContentBrowseScreen(
    mode: ContentMode,
    libraries: List<PlexLibrary>,
    recentlyMovies: List<PlexMediaItem>,
    recentlyShows: List<PlexMediaItem>,
    continueWatching: List<PlexMediaItem>,
    favoriteItems: List<PlexMediaItem>,
    favoriteKeys: Set<String>,
    categoryItems: List<PlexMediaItem>,
    artworkUrls: Map<String, String>,
    status: String,
    loading: Boolean,
    cachedAt: Long,
    onOpenLibrary: (PlexLibrary) -> Unit,
    onToggleFavorite: (PlexMediaItem) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenUpdate: () -> Unit,
    onSelectDetails: (PlexMediaItem) -> Unit
) {
    val movieMode = mode == ContentMode.Vod
    val title = if (movieMode) "VOD" else "SERIES"
    val modeLibraries = libraries.filter {
        if (movieMode) {
            it.type.equals("movie", true)
        } else {
            it.type.equals("show", true) || it.type.equals("tv", true)
        }
    }
    val recent = if (movieMode) recentlyMovies else recentlyShows
    val libraryByKey = remember(modeLibraries) { modeLibraries.associateBy { it.key } }

    var selectedCategory by remember(mode, libraries) { mutableStateOf("RECENTLY ADDED") }
    var sortNewestFirst by remember(mode) { mutableStateOf(true) }
    var sortAz by remember(mode) { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }

    val selectedCategoryTitle = when (selectedCategory) {
        "FAVORITES" -> "FAVORITES"
        "RECENTLY ADDED" -> "RECENTLY ADDED"
        "CONTINUE WATCHING" -> "CONTINUE WATCHING"
        else -> libraryByKey[selectedCategory]?.title?.uppercase() ?: selectedCategory
    }

    val baseGridItems = when (selectedCategory) {
        "CONTINUE WATCHING" -> continueWatching
        "FAVORITES" -> favoriteItems.filter { itemMatchesMode(it, mode) }
        "RECENTLY ADDED" -> recent
        else -> if (libraryByKey.containsKey(selectedCategory)) categoryItems else recent
    }

    val visibleGridItems = remember(selectedCategory, mediaItemsFingerprint(baseGridItems), searchText, sortNewestFirst, sortAz) {
        val searched = if (searchText.isBlank()) {
            baseGridItems
        } else {
            baseGridItems.filter { it.title.contains(searchText, ignoreCase = true) }
        }

        if (sortAz) searched.sortedBy { it.title.lowercase() }
        else if (sortNewestFirst) searched.sortedByDescending { it.addedAt }
        else searched.sortedBy { it.addedAt }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .width(250.dp)
                .fillMaxHeight()
                .background(Color(0x77000000))
                .padding(top = 8.dp, end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            item {
                Column {
                    Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(cacheLabel(cachedAt), color = Color(0xFFE5A00D), fontSize = 10.sp)
                    Spacer(Modifier.height(6.dp))
                }
            }

            item { VodSeriesMenuItem("FAVORITES", selectedCategory == "FAVORITES") { selectedCategory = "FAVORITES" } }
            item { VodSeriesMenuItem("RECENTLY ADDED", selectedCategory == "RECENTLY ADDED") { selectedCategory = "RECENTLY ADDED" } }
            item { VodSeriesMenuItem("CONTINUE WATCHING", selectedCategory == "CONTINUE WATCHING") { selectedCategory = "CONTINUE WATCHING" } }

            items(modeLibraries, key = { it.key }) { library ->
                VodSeriesMenuItem(library.title.uppercase(), selectedCategory == library.key) {
                    selectedCategory = library.key
                    onOpenLibrary(library)
                }
            }

            item { Spacer(Modifier.height(10.dp)) }
            item { VodSeriesMenuItem("UPDATE CONTENTS", false, onOpenUpdate) }
            item { VodSeriesMenuItem("SETTINGS", false, onOpenSettings) }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            Row(
                modifier = Modifier.fillMaxWidth().height(62.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (searchOpen) {
                    TextField(
                        value = searchText,
                        onValueChange = { searchText = it },
                        modifier = Modifier.weight(1f).height(58.dp),
                        singleLine = true,
                        label = { Text("Search") }
                    )
                    Spacer(Modifier.width(12.dp))
                    FocusActionButton("CLOSE", Modifier.width(104.dp), Color(0xFF203040)) {
                        searchOpen = false
                        searchText = ""
                    }
                } else {
                    Column {
                        Text(selectedCategoryTitle, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Light)
                        Text("CATEGORIES", color = Color(0xFFE5A00D), fontSize = 11.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        FocusActionButton(if (sortNewestFirst) "NEW\nOLD" else "OLD\nNEW", Modifier.width(78.dp), Color(0x66111820)) { sortNewestFirst = !sortNewestFirst; sortAz = false }
                        FocusActionButton("A-Z", Modifier.width(70.dp), Color(0x66111820)) { sortAz = !sortAz }
                        FocusActionButton("SEARCH", Modifier.width(112.dp), Color(0x66111820)) { searchOpen = true }
                    }
                }
            }

            Text(if (loading) "Loading..." else status, color = Color(0xFFB7C7D8), fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))

            if (visibleGridItems.isEmpty()) {
                EmptyPanel(if (loading) "Loading..." else "Nothing found in this section.")
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 112.dp),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    gridItems(visibleGridItems, key = { it.ratingKey.ifBlank { it.key } }) { item ->
                        MediaPosterCard(
                            item = item,
                            artworkUrl = artworkUrls[item.ratingKey].orEmpty(),
                            isFavorite = favoriteKeys.contains(mediaItemStableId(item)),
                            onClick = { onSelectDetails(item) },
                            onLongClick = { onToggleFavorite(item) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun VodSeriesMenuItem(text: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val active = focused || selected

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable { onClick() },
        color = if (active) Color(0x3329D3FF) else Color.Transparent,
        border = if (focused) BorderStroke(2.dp, Color(0xFFE5A00D)) else null,
        shape = RoundedCornerShape(4.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = text,
                color = if (active) Color(0xFFE5A00D) else Color.White,
                fontSize = if (active) 16.sp else 15.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

fun mediaItemsFingerprint(items: List<PlexMediaItem>): String {
    return items.joinToString("|") { it.ratingKey.ifBlank { it.key } }
}

@Composable
fun UpdateContentsScreen(
    loading: Boolean,
    status: String,
    vodStatus: String,
    seriesStatus: String,
    artworkStatus: String,
    cachedAt: Long,
    onStartUpdate: () -> Unit,
    onClearCache: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Update Media Contents", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Light, letterSpacing = 6.sp)
        Spacer(Modifier.height(28.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            UpdateStatusTile("VOD", vodStatus, Modifier.weight(1f))
            UpdateStatusTile("SERIES", seriesStatus, Modifier.weight(1f))
            UpdateStatusTile("ARTWORK", artworkStatus, Modifier.weight(1f))
        }

        Spacer(Modifier.height(38.dp))
        if (loading) {
            CircularProgressIndicator(color = Color(0xFF74F3F0))
            Spacer(Modifier.height(18.dp))
            Text("Please wait...", color = Color.White, fontSize = 26.sp)
        } else {
            Text(status, color = Color(0xFFB7C7D8), fontSize = 17.sp, textAlign = TextAlign.Center)
        }

        Spacer(Modifier.weight(1f))
        Text(cacheLabel(cachedAt), color = Color(0xFFE5A00D), fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onClearCache, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8E0031))) { Text("CLEAR CACHE") }
            Button(onClick = onStartUpdate, enabled = !loading, modifier = Modifier.weight(2f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007C86))) { Text(if (loading) "PLEASE WAIT..." else "START UPDATE") }
        }
    }
}

@Composable
fun LobbyCircleTile(label: String, icon: String, enabled: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = when {
        focused -> Color(0xFFE5A00D)
        enabled -> Color(0x99FFFFFF)
        else -> Color(0x44FFFFFF)
    }
    val contentColor = if (enabled) Color.White else Color(0xFF6D7784)

    Surface(
        modifier = Modifier
            .size(if (focused) 178.dp else 164.dp)
            .onFocusChanged { focused = it.isFocused }
            .focusable(enabled)
            .clickable(enabled = enabled) { onClick() },
        shape = RoundedCornerShape(90.dp),
        color = Color(0x33111820),
        border = BorderStroke(if (focused) 4.dp else 2.dp, borderColor)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(icon, color = contentColor, fontSize = 54.sp, fontWeight = FontWeight.Light)
            Text(label, color = contentColor, fontSize = 24.sp, fontWeight = FontWeight.Light)
        }
    }
}

@Composable
fun LobbySmallButton(text: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable { onClick() },
        shape = RoundedCornerShape(6.dp),
        color = if (focused) Color(0xFFE5A00D) else Color.Transparent,
        border = BorderStroke(1.dp, if (focused) Color(0xFFE5A00D) else Color.White)
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            color = if (focused) Color.Black else Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun ContentSideRail(
    title: String,
    libraries: List<PlexLibrary>,
    onOpenLibrary: (PlexLibrary) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenUpdate: () -> Unit
) {
    Card(modifier = Modifier.width(250.dp).fillMaxHeight(), colors = CardDefaults.cardColors(containerColor = Color(0xE6111820)), shape = RoundedCornerShape(8.dp)) {
        LazyColumn(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text(title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold) }
            item { Text("Categories", color = Color(0xFFE5A00D), fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            item { MenuButton("Recently Added") { } }
            items(libraries, key = { it.key }) { library ->
                MenuButton(library.title.ifBlank { "Library" }) { onOpenLibrary(library) }
            }
            item { Spacer(Modifier.height(10.dp)) }
            item { MenuButton("Update Contents", onOpenUpdate) }
            item { MenuButton("Settings", onOpenSettings) }
        }
    }
}

@Composable
fun ModeChip(text: String, selected: Boolean) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = if (selected) Color(0xFFE5A00D) else Color(0x66111820),
        border = BorderStroke(1.dp, if (selected) Color(0xFFE5A00D) else Color(0x99FFFFFF))
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
            color = if (selected) Color.Black else Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun UpdateStatusTile(title: String, status: String, modifier: Modifier = Modifier) {
    val statusColor = when (status.lowercase()) {
        "completed" -> Color(0xFF4DFF9B)
        "updating..." -> Color(0xFF74F3F0)
        "failed" -> Color(0xFFFF6B6B)
        else -> Color.White
    }

    Card(modifier = modifier.height(126.dp), colors = CardDefaults.cardColors(containerColor = Color(0xC9111820)), shape = RoundedCornerShape(4.dp)) {
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(title, color = Color(0xFFBFEFF2), fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            Text(status, color = statusColor, fontSize = 22.sp)
        }
    }
}

@Composable
fun LeftMenu(
    libraries: List<PlexLibrary>,
    onToggleMenu: () -> Unit,
    onOpenLibrary: (PlexLibrary) -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit
) {
    Card(modifier = Modifier.width(230.dp).fillMaxHeight(), colors = CardDefaults.cardColors(containerColor = Color(0xF2111820)), shape = RoundedCornerShape(18.dp)) {
        LazyColumn(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("Menu", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
            item { MenuButton("Hide Menu", onToggleMenu) }
            item { MenuButton("Refresh", onRefresh) }
            item { MenuButton("Settings", onOpenSettings) }
            item {
                Spacer(Modifier.height(8.dp))
                Text("Categories", color = Color(0xFFE5A00D), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            items(libraries, key = { it.key }) { library ->
                MenuButton(library.title.ifBlank { "Library" }) { onOpenLibrary(library) }
            }
        }
    }
}

@Composable
fun MenuButton(text: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = if (focused) Color(0x3329D3FF) else Color.Transparent
    val fg = if (focused) Color(0xFFE5A00D) else Color.White

    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .padding(10.dp),
        color = fg,
        fontSize = 15.sp,
        fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

fun playerLabel(choice: PlayerChoice): String {
    return if (choice == PlayerChoice.Mpv) "Embedded MPV" else "Embedded VLC"
}

fun streamModeLabel(mode: String): String {
    return when (mode) {
        "transcode" -> "Transcode"
        "direct_stream" -> "Direct Stream"
        else -> "Direct Play"
    }
}

enum class SettingsPage {
    Main,
    App,
    PlayerSettings,
    PlayerName,
    SpeedTest
}

@Composable
fun SettingsScreen(
    libraries: List<PlexLibrary>,
    hiddenKeys: Set<String>,
    friendlyName: String,
    playerChoice: PlayerChoice,
    streamMode: String,
    exoSettings: ExoPlayerSettings,
    vlcSettings: VlcPlayerSettings,
    mpvSettings: MpvPlayerSettings,
    speedResult: String,
    status: String,
    cachedAt: Long,
    onSaveFriendlyName: (String) -> Unit,
    onSetLibraryEnabled: (PlexLibrary, Boolean) -> Unit,
    onSavePlayerChoice: (PlayerChoice) -> Unit,
    onSaveStreamMode: (String) -> Unit,
    onSaveExoSettings: (ExoPlayerSettings) -> Unit,
    onSaveVlcSettings: (VlcPlayerSettings) -> Unit,
    onSaveMpvSettings: (MpvPlayerSettings) -> Unit,
    onRunSpeedTest: () -> Unit,
    onOpenUpdate: () -> Unit,
    onClearCache: () -> Unit,
    onSignOut: () -> Unit,
    onCloseSettings: () -> Unit
) {
    var page by remember { mutableStateOf(SettingsPage.Main) }
    var nameDraft by remember(friendlyName) { mutableStateOf(friendlyName) }

    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(
                            text = when (page) {
                                SettingsPage.Main -> "SETTINGS"
                                SettingsPage.App -> "APP SETTINGS"
                                SettingsPage.PlayerSettings -> "PLAYER SETTINGS"
                                SettingsPage.PlayerName -> "PLAYER NAME"
                                SettingsPage.SpeedTest -> "SPEED TEST"
                            },
                            color = Color.White,
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Light,
                            letterSpacing = 8.sp
                        )
                        Text(status, color = Color(0xFFE5A00D), fontSize = 14.sp)
                        Text(cacheLabel(cachedAt), color = Color(0xFFB7C7D8), fontSize = 12.sp)
                    }

                    if (page != SettingsPage.Main) {
                        FocusActionButton("BACK", Modifier.width(130.dp), Color(0xFF203040)) {
                            page = SettingsPage.Main
                        }
                    }
                }
            }
        }

        when (page) {
            SettingsPage.Main -> {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
                            SettingsTile("APP", "♦", Modifier.weight(1f)) { page = SettingsPage.App }
                            SettingsTile("Player Settings", "⛶", Modifier.weight(1f)) { page = SettingsPage.PlayerSettings }
                            SettingsTile("Player Name", "☻", Modifier.weight(1f)) { page = SettingsPage.PlayerName }
                            SettingsTile("Speed Test", "◴", Modifier.weight(1f)) { page = SettingsPage.SpeedTest; onRunSpeedTest() }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
                            SettingsTile("Update Contents", "⇩", Modifier.weight(1f)) { onOpenUpdate() }
                            SettingsTile("Clear Cache", "⌫", Modifier.weight(1f)) { onClearCache() }
                            SettingsTile("Sign Out", "⇥", Modifier.weight(1f)) { onSignOut() }
                            SettingsTile("Home", "⌂", Modifier.weight(1f)) { onCloseSettings() }
                        }
                    }
                }

                item {
                    SettingsCard(title = "Current Setup") {
                        Text("Player name: $friendlyName", color = Color(0xFFB7C7D8), fontSize = 14.sp)
                        Text("Player: ${playerLabel(playerChoice)}", color = Color(0xFFB7C7D8), fontSize = 14.sp)
                        Text("Stream type: ${streamModeLabel(streamMode)}", color = Color(0xFFB7C7D8), fontSize = 14.sp)
                        Text("Visible categories: ${libraries.count { !hiddenKeys.contains(it.key) }} / ${libraries.size}", color = Color(0xFFB7C7D8), fontSize = 14.sp)
                    }
                }
            }

            SettingsPage.App -> {
                item {
                    SettingsCard(title = "Visible Categories") {
                        Text("Turn categories on or off. These apply to the VOD and Series pages.", color = Color(0xFFB7C7D8), fontSize = 13.sp)
                        Spacer(Modifier.height(12.dp))
                        libraries.forEach { library ->
                            val enabled = !hiddenKeys.contains(library.key)
                            SettingsToggleRow(
                                title = library.title.ifBlank { "Library" },
                                subtitle = library.type.ifBlank { "category" },
                                checked = enabled,
                                onClick = { onSetLibraryEnabled(library, !enabled) }
                            )
                        }
                    }
                }
            }

            SettingsPage.PlayerName -> {
                item {
                    SettingsCard(title = "Player Name") {
                        Text("This name is used as the FirePlex player name and the Plex friendly device name.", color = Color(0xFFB7C7D8), fontSize = 13.sp)
                        Spacer(Modifier.height(14.dp))
                        TextField(
                            value = nameDraft,
                            onValueChange = { nameDraft = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Player name") }
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            FocusActionButton("SAVE NAME", Modifier.weight(1f), Color(0xFF4C8B1F)) { onSaveFriendlyName(nameDraft) }
                            FocusActionButton("RESET", Modifier.weight(1f), Color(0xFF8E0031)) {
                                nameDraft = "FirePlex3.0"
                                onSaveFriendlyName("FirePlex3.0")
                            }
                        }
                    }
                }
            }

            SettingsPage.SpeedTest -> {
                item {
                    SettingsCard(title = "Speed Test") {
                        Text("Tests the connection to your selected Plex server.", color = Color(0xFFB7C7D8), fontSize = 13.sp)
                        Spacer(Modifier.height(16.dp))
                        Box(modifier = Modifier.fillMaxWidth().background(Color(0xFF031C33), RoundedCornerShape(14.dp)).padding(22.dp), contentAlignment = Alignment.Center) {
                            Text(speedResult, color = Color(0xFFE5A00D), fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        }
                        Spacer(Modifier.height(16.dp))
                        FocusActionButton("BEGIN SPEED TEST", Modifier.fillMaxWidth(), Color(0xFF007C86)) { onRunSpeedTest() }
                    }
                }
            }

            SettingsPage.PlayerSettings -> {
                item {
                    SettingsCard(title = "Embedded Player") {
                        Text("Selected: ${playerLabel(playerChoice)}", color = Color(0xFFE5A00D), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            FocusActionButton("VLC", Modifier.weight(1f), if (playerChoice == PlayerChoice.Vlc) Color(0xFFE5A00D) else Color(0xFF203040)) { onSavePlayerChoice(PlayerChoice.Vlc) }
                            FocusActionButton("MPV", Modifier.weight(1f), if (playerChoice == PlayerChoice.Mpv) Color(0xFFE5A00D) else Color(0xFF203040)) { onSavePlayerChoice(PlayerChoice.Mpv) }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("Both options stay inside FirePlex. VLC uses the embedded VLC engine. MPV uses native libmpv from mpv-android with FirePlex controls.", color = Color(0xFFB7C7D8), fontSize = 12.sp)
                    }
                }

                item {
                    SettingsCard(title = "Stream Type") {
                        Text("Use Direct Play for normal playback. Use Direct Stream or Transcode when videos have audio sync, codec, or container problems.", color = Color(0xFFB7C7D8), fontSize = 13.sp)
                        Spacer(Modifier.height(12.dp))
                        SettingsOptionRow(
                            "Playback Stream",
                            listOf(
                                "direct_play" to "Direct Play",
                                "direct_stream" to "Direct Stream",
                                "transcode" to "Transcode"
                            ),
                            streamMode
                        ) { onSaveStreamMode(it) }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            when (streamMode) {
                                "transcode" -> "Transcode asks Plex to convert the stream to HLS. This is the best option to try for out-of-sync sound."
                                "direct_stream" -> "Direct Stream asks Plex to remux without fully converting where possible."
                                else -> "Direct Play sends the original file to the embedded player."
                            },
                            color = Color(0xFFE5A00D),
                            fontSize = 12.sp
                        )
                    }
                }

                item {
                    SettingsCard(title = "VLC Settings") {
                        PlayerSettingsPanel(
                            title = "VLC Player Default Settings",
                            preBufferLabel = "Pre Buffer",
                            preBufferOptions = listOf(300 to "300 ms", 1000 to "1 Sec", 2000 to "2 Sec", 3000 to "3 Sec", 5000 to "5 Sec"),
                            selectedPreBuffer = vlcSettings.preBufferMs,
                            zoomOptions = listOf("best_fit" to "Best Fit", "16:9" to "16:9", "4:3" to "4:3", "original" to "Original", "fill" to "Fill"),
                            selectedZoom = vlcSettings.zoomMode,
                            subtitlesEnabled = vlcSettings.subtitlesEnabled,
                            volumePercent = vlcSettings.volumePercent,
                            hardwareDecoder = vlcSettings.hardwareDecoder,
                            h264Level = vlcSettings.h264Level,
                            onUpdate = { preBuffer, zoom, subtitles, volume, hardware, h264Level ->
                                onSaveVlcSettings(
                                    vlcSettings.copy(
                                        preBufferMs = preBuffer,
                                        zoomMode = zoom,
                                        subtitlesEnabled = subtitles,
                                        volumePercent = volume,
                                        hardwareDecoder = hardware ?: vlcSettings.hardwareDecoder,
                                        h264Level = h264Level
                                    )
                                )
                            },
                            onReset = { onSaveVlcSettings(VlcPlayerSettings()) }
                        )
                    }
                }

                item {
                    SettingsCard(title = "MPV Settings") {
                        PlayerSettingsPanel(
                            title = "MPV Player Default Settings",
                            preBufferLabel = "Pre Buffer",
                            preBufferOptions = listOf(300 to "300 ms", 1000 to "1 Sec", 2000 to "2 Sec", 3000 to "3 Sec", 5000 to "5 Sec"),
                            selectedPreBuffer = mpvSettings.preBufferMs,
                            zoomOptions = listOf("best_fit" to "Best Fit", "16:9" to "16:9", "4:3" to "4:3", "original" to "Original", "fill" to "Fill"),
                            selectedZoom = mpvSettings.zoomMode,
                            subtitlesEnabled = mpvSettings.subtitlesEnabled,
                            volumePercent = mpvSettings.volumePercent,
                            hardwareDecoder = mpvSettings.hardwareDecoder,
                            h264Level = mpvSettings.h264Level,
                            onUpdate = { preBuffer, zoom, subtitles, volume, hardware, h264Level ->
                                onSaveMpvSettings(
                                    mpvSettings.copy(
                                        preBufferMs = preBuffer,
                                        zoomMode = zoom,
                                        subtitlesEnabled = subtitles,
                                        volumePercent = volume,
                                        hardwareDecoder = hardware ?: mpvSettings.hardwareDecoder,
                                        h264Level = h264Level
                                    )
                                )
                            },
                            onReset = { onSaveMpvSettings(MpvPlayerSettings()) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TvSeasonsScreen(
    show: PlexMediaItem,
    seasons: List<PlexMediaItem>,
    artworkUrls: Map<String, String>,
    status: String,
    loading: Boolean,
    onSelectSeason: (PlexMediaItem) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column {
                Text(show.title.ifBlank { "TV Series" }, color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold)
                Text(if (loading) "Loading seasons..." else status, color = Color(0xFFE5A00D), fontSize = 14.sp)
            }
        }

        if (seasons.isEmpty()) {
            item { EmptyPanel(if (loading) "Loading seasons..." else "No seasons found for this TV series.") }
        } else {
            items(seasons, key = { it.ratingKey.ifBlank { it.key } }) { season ->
                MediaWideRow(
                    item = season,
                    artworkUrl = artworkUrls[season.ratingKey].orEmpty().ifBlank { artworkUrls[show.ratingKey].orEmpty() },
                    onClick = { onSelectSeason(season) }
                )
            }
        }
    }
}

@Composable
fun TvEpisodesScreen(
    show: PlexMediaItem?,
    season: PlexMediaItem,
    episodes: List<PlexMediaItem>,
    artworkUrls: Map<String, String>,
    status: String,
    loading: Boolean,
    onSelectEpisode: (PlexMediaItem) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column {
                Text(show?.title?.takeIf { it.isNotBlank() } ?: "TV Series", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text(season.title.ifBlank { "Episodes" }, color = Color(0xFFE5A00D), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(if (loading) "Loading episodes..." else status, color = Color(0xFFB7C7D8), fontSize = 14.sp)
            }
        }

        if (episodes.isEmpty()) {
            item { EmptyPanel(if (loading) "Loading episodes..." else "No episodes found in this season.") }
        } else {
            items(episodes, key = { it.ratingKey.ifBlank { it.key } }) { episode ->
                MediaWideRow(
                    item = episode,
                    artworkUrl = artworkUrls[episode.ratingKey].orEmpty().ifBlank { artworkUrls[season.ratingKey].orEmpty() },
                    onClick = { onSelectEpisode(episode) }
                )
            }
        }
    }
}

@Composable
fun LibraryContentScreen(
    library: PlexLibrary,
    mediaItems: List<PlexMediaItem>,
    artworkUrls: Map<String, String>,
    status: String,
    loading: Boolean,
    onSelectDetails: (PlexMediaItem) -> Unit
) {
    var selectedLetter by remember(library.key, mediaItems) { mutableStateOf("All") }
    val letters = remember(mediaItems) {
        val available = mediaItems
            .map { titleBucket(it.title) }
            .distinct()
            .sortedWith(compareBy<String> { if (it == "#") "0" else it })
        listOf("All") + available
    }
    val visibleItems = remember(mediaItems, selectedLetter) {
        if (selectedLetter == "All") mediaItems else mediaItems.filter { titleBucket(it.title) == selectedLetter }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        if (mediaItems.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.width(72.dp).fillMaxHeight().padding(end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                items(letters, key = { it }) { letter ->
                    AlphabetButton(
                        text = letter,
                        selected = selectedLetter == letter,
                        onClick = { selectedLetter = letter }
                    )
                }
            }
        }

        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Column {
                    Text(library.title.ifBlank { "Library" }, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (selectedLetter == "All") status else "Showing $selectedLetter",
                        color = Color(0xFFE5A00D),
                        fontSize = 14.sp
                    )
                }
            }

            if (mediaItems.isEmpty()) {
                item { EmptyPanel(if (loading) "Loading videos..." else "Nothing found in this category.") }
            } else if (visibleItems.isEmpty()) {
                item { EmptyPanel("Nothing found under $selectedLetter.") }
            } else {
                items(visibleItems, key = { it.ratingKey.ifBlank { it.key } }) { item ->
                    MediaWideRow(item = item, artworkUrl = artworkUrls[item.ratingKey].orEmpty(), onClick = { onSelectDetails(item) })
                }
            }
        }
    }
}

@Composable
fun AlphabetButton(text: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> Color(0xFFE5A00D)
        selected -> Color(0xFF284152)
        else -> Color(0xF2111820)
    }
    val fg = if (focused) Color.Black else Color.White

    Surface(
        modifier = Modifier
            .width(58.dp)
            .height(34.dp)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable { onClick() },
        color = bg,
        shape = RoundedCornerShape(10.dp),
        border = if (focused || selected) BorderStroke(2.dp, Color(0xFFE5A00D)) else null
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, color = fg, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

fun titleBucket(title: String): String {
    val first = title.trim().firstOrNull()?.uppercaseChar() ?: '#'
    return if (first in 'A'..'Z') first.toString() else "#"
}
@Composable
fun MediaDetailsScreen(
    item: PlexMediaItem,
    artworkUrl: String,
    backdropUrl: String,
    playerChoice: PlayerChoice,
    onPlay: (PlexMediaItem, PlexSubtitleTrack?) -> Unit
) {
    var subtitle by remember(item) { mutableStateOf(item.subtitles.firstOrNull { it.selected }) }

    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Box(modifier = Modifier.fillMaxWidth().height(330.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF111820))) {
                val hero = backdropUrl.ifBlank { artworkUrl }
                if (hero.isNotBlank()) {
                    AsyncImage(model = hero, contentDescription = item.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }

                Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF2050608)))))

                Column(modifier = Modifier.align(Alignment.BottomStart).padding(22.dp)) {
                    Text(item.title.ifBlank { "Untitled" }, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Text(mediaMetaLine(item), color = Color(0xFFE5A00D), fontSize = 14.sp)
                }
            }
        }

        item {
            Text(item.summary.ifBlank { "No summary available." }, color = Color(0xFFD7DEE8), fontSize = 15.sp)
        }

        item {
            SettingsCard(title = "Video Format") {
                Text("Player: ${playerLabel(playerChoice)}", color = Color(0xFFE5A00D), fontSize = 14.sp)
                Spacer(Modifier.height(14.dp))

                Text("Subtitles", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(selected = subtitle == null, onClick = { subtitle = null }, label = { Text("Off") })
                    }

                    items(item.subtitles, key = { it.id.ifBlank { it.title } }) { track ->
                        FilterChip(
                            selected = subtitle == track,
                            onClick = { subtitle = track },
                            label = { Text(track.title.ifBlank { track.language.ifBlank { "Subtitle" } }) }
                        )
                    }
                }

                if (item.subtitles.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("No remote subtitles found from Plex for this item.", color = Color(0xFFB7C7D8), fontSize = 13.sp)
                }
            }
        }

        item {
            Button(onClick = { onPlay(item, subtitle) }) { Text("Play Full Screen") }
        }
    }
}

@Composable
fun SelectedVideoScreen(
    item: PlexMediaItem,
    playUrl: String?,
    subtitleTracks: List<PlexSubtitleTrack>,
    selectedSubtitle: PlexSubtitleTrack?,
    subtitleUrl: String?,
    externalSubtitleName: String?,
    playerChoice: PlayerChoice,
    vlcSettings: VlcPlayerSettings,
    mpvSettings: MpvPlayerSettings,
    startPositionMs: Long,
    loading: Boolean,
    status: String,
    onSubtitleSelected: (PlexSubtitleTrack?) -> Unit,
    onSearchOpenSubtitles: suspend (String, String) -> List<OpenSubtitleResult>,
    onOpenSubtitleSelected: suspend (OpenSubtitleResult) -> Unit,
    onPlayback: (String, Long, Long) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (playUrl.isNullOrBlank()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (loading) "Opening player..." else status, color = Color.White, fontSize = 18.sp, textAlign = TextAlign.Center)
            }
        } else if (playerChoice == PlayerChoice.Mpv) {
            MpvVideoPlayer(
                playUrl = playUrl,
                itemTitle = item.title,
                subtitleUrl = if (mpvSettings.subtitlesEnabled) subtitleUrl else null,
                subtitleTracks = subtitleTracks,
                selectedSubtitle = selectedSubtitle,
                externalSubtitleName = externalSubtitleName,
                settings = mpvSettings,
                startPositionMs = startPositionMs,
                onSubtitleSelected = onSubtitleSelected,
                onSearchOpenSubtitles = onSearchOpenSubtitles,
                onOpenSubtitleSelected = onOpenSubtitleSelected,
                onPlayback = onPlayback
            )
        } else {
            VlcVideoPlayer(
                playUrl = playUrl,
                itemTitle = item.title,
                subtitleUrl = if (vlcSettings.subtitlesEnabled) subtitleUrl else null,
                subtitleTracks = subtitleTracks,
                selectedSubtitle = selectedSubtitle,
                externalSubtitleName = externalSubtitleName,
                playerName = "VLC",
                startPositionMs = startPositionMs,
                settings = vlcSettings,
                onSubtitleSelected = onSubtitleSelected,
                onSearchOpenSubtitles = onSearchOpenSubtitles,
                onOpenSubtitleSelected = onOpenSubtitleSelected,
                onPlayback = onPlayback
            )
        }
    }
}

@Composable
fun MpvVideoPlayer(
    playUrl: String,
    itemTitle: String,
    subtitleUrl: String?,
    subtitleTracks: List<PlexSubtitleTrack>,
    selectedSubtitle: PlexSubtitleTrack?,
    externalSubtitleName: String?,
    settings: MpvPlayerSettings,
    startPositionMs: Long,
    onSubtitleSelected: (PlexSubtitleTrack?) -> Unit,
    onSearchOpenSubtitles: suspend (String, String) -> List<OpenSubtitleResult>,
    onOpenSubtitleSelected: suspend (OpenSubtitleResult) -> Unit,
    onPlayback: (String, Long, Long) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var controlsVisible by remember { mutableStateOf(true) }
    var subtitlesOpen by remember { mutableStateOf(false) }
    var mpvView by remember { mutableStateOf<FirePlexMPVView?>(null) }
    var isPlaying by remember { mutableStateOf(true) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }

    fun updatePlayerState() {
        val view = mpvView ?: return
        isPlaying = !view.isPaused()
        positionMs = view.positionMs()
        durationMs = view.durationMs()
    }

    fun seekBy(deltaSeconds: Int) {
        mpvView?.seekBy(deltaSeconds)
        updatePlayerState()
    }

    LaunchedEffect(mpvView) {
        while (true) {
            delay(1000)
            updatePlayerState()
            onPlayback(if (isPlaying) "playing" else "paused", positionMs, durationMs)
        }
    }

    DisposableEffect(playUrl, subtitleUrl, settings.preBufferMs, settings.hardwareDecoder) {
        onDispose {
            val view = mpvView
            val position = view?.positionMs() ?: positionMs
            val duration = view?.durationMs() ?: durationMs
            scope.launch { onPlayback("stopped", position, duration) }
            view?.destroyPlayer()
            mpvView = null
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black).clickable { controlsVisible = !controlsVisible }) {
        key(playUrl, subtitleUrl, settings.preBufferMs, settings.hardwareDecoder) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    FirePlexMPVView(viewContext).apply {
                        val configDir = context.filesDir.resolve("mpv").apply { mkdirs() }.absolutePath
                        val cacheDir = context.cacheDir.resolve("mpv_shader_cache").apply { mkdirs() }.absolutePath
                        initializePlayer(
                            configDir = configDir,
                            cacheDir = cacheDir,
                            preBufferMs = settings.preBufferMs,
                            hardwareDecoder = settings.hardwareDecoder,
                            h264Level = settings.h264Level,
                            zoom = settings.zoomMode,
                            volume = settings.volumePercent
                        )
                        load(playUrl, subtitleUrl)
                        if (startPositionMs > 0L) seekToMs(startPositionMs)
                        mpvView = this
                        isPlaying = true
                    }
                },
                update = { view ->
                    view.applyVolume(settings.volumePercent)
                    view.applyZoom(settings.zoomMode)
                }
            )
        }

        if (controlsVisible) {
            Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent, Color(0xCC000000)))))
            Text(
                "MPV",
                modifier = Modifier.align(Alignment.TopStart).padding(24.dp),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            Column(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (durationMs > 0L) {
                    Slider(
                        value = positionMs.coerceIn(0L, durationMs).toFloat(),
                        onValueChange = {
                            mpvView?.seekToMs(it.toLong())
                            updatePlayerState()
                        },
                        valueRange = 0f..durationMs.toFloat(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(formatTime(positionMs), color = Color.White, fontSize = 13.sp)
                    Text(formatTime(durationMs), color = Color.White, fontSize = 13.sp)
                }

                Spacer(Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    FocusActionButton("-30", Modifier.width(76.dp), Color(0xAA111820)) { seekBy(-30) }
                    FocusActionButton("-10", Modifier.width(76.dp), Color(0xAA111820)) { seekBy(-10) }
                    FocusActionButton(if (isPlaying) "PAUSE" else "PLAY", Modifier.width(120.dp), Color(0xFFE5A00D)) {
                        val view = mpvView
                        if (view != null) {
                            val nextPaused = !view.isPaused()
                            view.setPaused(nextPaused)
                            updatePlayerState()
                            onPlayback(if (nextPaused) "paused" else "playing", view.positionMs(), view.durationMs())
                            controlsVisible = true
                        }
                    }
                    FocusActionButton("+10", Modifier.width(76.dp), Color(0xAA111820)) { seekBy(10) }
                    FocusActionButton("+30", Modifier.width(76.dp), Color(0xAA111820)) { seekBy(30) }
                    FocusActionButton(
                        text = when {
                            externalSubtitleName != null -> "SUBS: $externalSubtitleName"
                            selectedSubtitle == null -> "SUBS: OFF"
                            else -> "SUBS: ${selectedSubtitle.title.ifBlank { selectedSubtitle.language.ifBlank { "ON" } }}"
                        },
                        modifier = Modifier.widthIn(min = 150.dp, max = 260.dp),
                        color = Color(0xAA111820)
                    ) {
                        subtitlesOpen = !subtitlesOpen
                    }
                }

                if (subtitlesOpen) {
                    Spacer(Modifier.height(10.dp))
                    SubtitlePickerPanel(
                        subtitleTracks = subtitleTracks,
                        selectedSubtitle = if (externalSubtitleName != null) null else selectedSubtitle,
                        externalSubtitleName = externalSubtitleName,
                        itemTitle = itemTitle,
                        onSubtitleSelected = {
                            onSubtitleSelected(it)
                            subtitlesOpen = false
                        },
                        onSearchOpenSubtitles = onSearchOpenSubtitles,
                        onOpenSubtitleSelected = onOpenSubtitleSelected
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "MPV embedded - Pre-buffer ${settings.preBufferMs}ms - Volume ${settings.volumePercent}% - Hardware ${if (settings.hardwareDecoder) "On" else "Off"}",
                    color = Color(0xFFB7C7D8),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun VlcVideoPlayer(
    playUrl: String,
    itemTitle: String,
    subtitleUrl: String?,
    subtitleTracks: List<PlexSubtitleTrack>,
    selectedSubtitle: PlexSubtitleTrack?,
    externalSubtitleName: String?,
    playerName: String,
    settings: VlcPlayerSettings,
    startPositionMs: Long,
    onSubtitleSelected: (PlexSubtitleTrack?) -> Unit,
    onSearchOpenSubtitles: suspend (String, String) -> List<OpenSubtitleResult>,
    onOpenSubtitleSelected: suspend (OpenSubtitleResult) -> Unit,
    onPlayback: (String, Long, Long) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var controlsVisible by remember { mutableStateOf(true) }
    var subtitlesOpen by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(true) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }

    val libVlc = remember(playUrl, subtitleUrl, settings.preBufferMs, settings.hardwareDecoder) {
        LibVLC(
            context,
            arrayListOf(
                "--network-caching=${settings.preBufferMs}",
                "--file-caching=${settings.preBufferMs}",
                "--live-caching=${settings.preBufferMs}"
            )
        )
    }
    val mediaPlayer = remember(playUrl, subtitleUrl, settings.preBufferMs, settings.hardwareDecoder) { VlcMediaPlayer(libVlc) }

    fun updatePlayerState() {
        isPlaying = mediaPlayer.isPlaying
        positionMs = mediaPlayer.time.coerceAtLeast(0L)
        durationMs = mediaPlayer.length.coerceAtLeast(0L)
    }

    fun seekBy(deltaMs: Long) {
        val target = (mediaPlayer.time + deltaMs).coerceAtLeast(0L)
        val max = mediaPlayer.length.takeIf { it > 0L } ?: Long.MAX_VALUE
        mediaPlayer.time = target.coerceAtMost(max)
        updatePlayerState()
    }

    LaunchedEffect(mediaPlayer) {
        while (true) {
            delay(1000)
            updatePlayerState()
            onPlayback(if (mediaPlayer.isPlaying) "playing" else "paused", mediaPlayer.time.coerceAtLeast(0L), mediaPlayer.length.coerceAtLeast(0L))
        }
    }

    DisposableEffect(playUrl, subtitleUrl, settings) {
        onDispose {
            val position = mediaPlayer.time.coerceAtLeast(0L)
            val duration = mediaPlayer.length.coerceAtLeast(0L)
            scope.launch { onPlayback("stopped", position, duration) }
            mediaPlayer.stop()
            mediaPlayer.detachViews()
            mediaPlayer.release()
            libVlc.release()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black).clickable { controlsVisible = !controlsVisible }) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                VLCVideoLayout(it).apply {
                    mediaPlayer.attachViews(this, null, false, false)
                    val media = VlcMedia(libVlc, Uri.parse(playUrl))
                    val h264CompatibilityMode = settings.h264Level != "auto"
                    media.setHWDecoderEnabled(settings.hardwareDecoder && !h264CompatibilityMode, false)
                    if (h264CompatibilityMode) {
                        media.addOption(":avcodec-hw=none")
                        media.addOption(":codec=avcodec")
                    }
                    if (!subtitleUrl.isNullOrBlank()) {
                        media.addOption(":sub-file=$subtitleUrl")
                    }
                    mediaPlayer.media = media
                    media.release()
                    mediaPlayer.volume = settings.volumePercent.coerceIn(0, 100)
                    applyVlcZoom(mediaPlayer, settings.zoomMode)
                    mediaPlayer.play()
                    if (startPositionMs > 0L) mediaPlayer.time = startPositionMs
                    isPlaying = true
                }
            },
            update = {
                mediaPlayer.volume = settings.volumePercent.coerceIn(0, 100)
                applyVlcZoom(mediaPlayer, settings.zoomMode)
            }
        )

        if (controlsVisible) {
            Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent, Color(0xCC000000)))))
            Text(
                playerName,
                modifier = Modifier.align(Alignment.TopStart).padding(24.dp),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            Column(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (durationMs > 0L) {
                    Slider(
                        value = positionMs.coerceIn(0L, durationMs).toFloat(),
                        onValueChange = { mediaPlayer.time = it.toLong(); updatePlayerState() },
                        valueRange = 0f..durationMs.toFloat(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(formatTime(positionMs), color = Color.White, fontSize = 13.sp)
                    Text(formatTime(durationMs), color = Color.White, fontSize = 13.sp)
                }

                Spacer(Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    FocusActionButton("-30", Modifier.width(76.dp), Color(0xAA111820)) { seekBy(-30000L) }
                    FocusActionButton("-10", Modifier.width(76.dp), Color(0xAA111820)) { seekBy(-10000L) }
                    FocusActionButton(if (isPlaying) "PAUSE" else "PLAY", Modifier.width(120.dp), Color(0xFFE5A00D)) {
                        if (mediaPlayer.isPlaying) {
                            mediaPlayer.pause()
                            onPlayback("paused", mediaPlayer.time.coerceAtLeast(0L), mediaPlayer.length.coerceAtLeast(0L))
                        } else {
                            mediaPlayer.play()
                            onPlayback("playing", mediaPlayer.time.coerceAtLeast(0L), mediaPlayer.length.coerceAtLeast(0L))
                        }
                        updatePlayerState()
                        controlsVisible = true
                    }
                    FocusActionButton("+10", Modifier.width(76.dp), Color(0xAA111820)) { seekBy(10000L) }
                    FocusActionButton("+30", Modifier.width(76.dp), Color(0xAA111820)) { seekBy(30000L) }
                    FocusActionButton(
                        text = when {
                            externalSubtitleName != null -> "SUBS: $externalSubtitleName"
                            selectedSubtitle == null -> "SUBS: OFF"
                            else -> "SUBS: ${selectedSubtitle.title.ifBlank { selectedSubtitle.language.ifBlank { "ON" } }}"
                        },
                        modifier = Modifier.widthIn(min = 150.dp, max = 260.dp),
                        color = Color(0xAA111820)
                    ) {
                        subtitlesOpen = !subtitlesOpen
                    }
                }

                if (subtitlesOpen) {
                    Spacer(Modifier.height(10.dp))
                    SubtitlePickerPanel(
                        subtitleTracks = subtitleTracks,
                        selectedSubtitle = if (externalSubtitleName != null) null else selectedSubtitle,
                        externalSubtitleName = externalSubtitleName,
                        itemTitle = itemTitle,
                        onSubtitleSelected = {
                            onSubtitleSelected(it)
                            subtitlesOpen = false
                        },
                        onSearchOpenSubtitles = onSearchOpenSubtitles,
                        onOpenSubtitleSelected = onOpenSubtitleSelected
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "$playerName embedded - Pre-buffer ${settings.preBufferMs}ms - Volume ${settings.volumePercent}% - Hardware ${if (settings.hardwareDecoder) "On" else "Off"}",
                    color = Color(0xFFB7C7D8),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

fun applyVlcZoom(mediaPlayer: VlcMediaPlayer, zoomMode: String) {
    when (zoomMode) {
        "16:9" -> {
            mediaPlayer.aspectRatio = "16:9"
            mediaPlayer.scale = 0f
        }
        "4:3" -> {
            mediaPlayer.aspectRatio = "4:3"
            mediaPlayer.scale = 0f
        }
        "original" -> {
            mediaPlayer.aspectRatio = null
            mediaPlayer.scale = 1f
        }
        "fill" -> {
            mediaPlayer.aspectRatio = null
            mediaPlayer.scale = 0f
        }
        "zoom" -> {
            mediaPlayer.aspectRatio = null
            mediaPlayer.scale = 1.25f
        }
        else -> {
            mediaPlayer.aspectRatio = null
            mediaPlayer.scale = 0f
        }
    }
}

@Composable
fun SubtitlePickerPanel(
    subtitleTracks: List<PlexSubtitleTrack>,
    selectedSubtitle: PlexSubtitleTrack?,
    externalSubtitleName: String?,
    itemTitle: String,
    onSubtitleSelected: (PlexSubtitleTrack?) -> Unit,
    onSearchOpenSubtitles: suspend (String, String) -> List<OpenSubtitleResult>,
    onOpenSubtitleSelected: suspend (OpenSubtitleResult) -> Unit
) {
    val scope = rememberCoroutineScope()
    var finderOpen by remember { mutableStateOf(false) }
    var subtitleQuery by remember(itemTitle) { mutableStateOf(itemTitle) }
    var subtitleLanguage by remember { mutableStateOf("en") }
    var subtitleSearchStatus by remember { mutableStateOf("Search OpenSubtitles while the video keeps playing.") }
    var openSubtitleResults by remember { mutableStateOf<List<OpenSubtitleResult>>(emptyList()) }

    Card(
        modifier = Modifier.fillMaxWidth().heightIn(max = if (finderOpen) 430.dp else 180.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xE8111820)),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color(0x554B5C70))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text("Subtitles", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FocusActionButton(
                        text = "OFF",
                        modifier = Modifier.width(82.dp),
                        color = if (selectedSubtitle == null && externalSubtitleName == null) Color(0xFFE5A00D) else Color(0xAA203040)
                    ) {
                        onSubtitleSelected(null)
                    }
                }

                item {
                    FocusActionButton(
                        text = "FIND",
                        modifier = Modifier.width(92.dp),
                        color = if (finderOpen) Color(0xFF007C86) else Color(0xAA203040)
                    ) {
                        finderOpen = !finderOpen
                    }
                }

                if (!externalSubtitleName.isNullOrBlank()) {
                    item {
                        FocusActionButton(
                            text = externalSubtitleName.take(18),
                            modifier = Modifier.widthIn(min = 130.dp, max = 210.dp),
                            color = Color(0xFFE5A00D)
                        ) {
                            onSubtitleSelected(selectedSubtitle)
                        }
                    }
                }

                items(subtitleTracks) { track ->
                    val label = track.title
                        .ifBlank { track.language }
                        .ifBlank { "Subtitle" }
                        .take(22)
                    FocusActionButton(
                        text = label,
                        modifier = Modifier.widthIn(min = 118.dp, max = 220.dp),
                        color = if (track == selectedSubtitle && externalSubtitleName == null) Color(0xFFE5A00D) else Color(0xAA203040)
                    ) {
                        onSubtitleSelected(track)
                    }
                }
            }

            if (finderOpen) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    TextField(
                        value = subtitleQuery,
                        onValueChange = { subtitleQuery = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Search title") }
                    )
                    TextField(
                        value = subtitleLanguage,
                        onValueChange = { subtitleLanguage = it.take(3).lowercase() },
                        modifier = Modifier.width(92.dp),
                        singleLine = true,
                        label = { Text("Lang") }
                    )
                    FocusActionButton("SEARCH", Modifier.width(120.dp), Color(0xFF007C86)) {
                        scope.launch {
                            subtitleSearchStatus = "Searching OpenSubtitles..."
                            openSubtitleResults = emptyList()
                            try {
                                val results = onSearchOpenSubtitles(subtitleQuery, subtitleLanguage.ifBlank { "en" })
                                openSubtitleResults = results
                                subtitleSearchStatus = if (results.isEmpty()) "No subtitles found." else "Found ${results.size} subtitle results."
                            } catch (e: Throwable) {
                                subtitleSearchStatus = e.message ?: "Subtitle search failed."
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(subtitleSearchStatus, color = Color(0xFFE5A00D), fontSize = 12.sp)

                if (openSubtitleResults.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 190.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(openSubtitleResults, key = { it.fileId }) { result ->
                            Surface(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    scope.launch {
                                        subtitleSearchStatus = "Loading ${result.displayName}..."
                                        try {
                                            onOpenSubtitleSelected(result)
                                            subtitleSearchStatus = "Subtitle active: ${result.displayName}"
                                            finderOpen = false
                                        } catch (e: Throwable) {
                                            subtitleSearchStatus = e.message ?: "Could not load subtitle."
                                        }
                                    }
                                },
                                color = Color(0x66111820),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, Color(0x55FFFFFF))
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    Text(result.displayName, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${result.language.uppercase()} - downloads ${result.downloads} - ${result.releaseName}", color = Color(0xFFB7C7D8), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }

            if (subtitleTracks.isEmpty() && externalSubtitleName == null) {
                Spacer(Modifier.height(8.dp))
                Text("No subtitle tracks found for this video.", color = Color(0xFFB7C7D8), fontSize = 12.sp)
            }
        }
    }
}

fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

@Composable
fun TopBar(
    title: String,
    subtitle: String,
    loading: Boolean,
    menuOpen: Boolean,
    onToggleMenu: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Column {
            Text(title, color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Color(0xFFB7C7D8), fontSize = 14.sp)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onToggleMenu) { Text(if (menuOpen) "Hide Menu" else "Show Menu") }
            OutlinedButton(onClick = onOpenSettings) { Text("Settings") }
            OutlinedButton(onClick = onRefresh, enabled = !loading) { Text(if (loading) "Loading..." else "Refresh") }
        }
    }
}

@Composable
fun HeroPanel(status: String) {
    Card(modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp), colors = CardDefaults.cardColors(containerColor = Color(0xF2111820)), shape = RoundedCornerShape(24.dp)) {
        Box(modifier = Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color(0xFF31220A), Color(0xFF111820), Color(0xFF050608)))).padding(24.dp)) {
            Column {
                Text("Home", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(status, color = Color(0xFFD7DEE8), fontSize = 15.sp)
            }
        }
    }
}

@Composable
fun MediaRow(title: String, items: List<PlexMediaItem>, artworkUrls: Map<String, String>, onSelectDetails: (PlexMediaItem) -> Unit) {
    if (title.isNotBlank()) {
        SectionTitle(title)
    }
    if (items.isEmpty()) {
        EmptyPanel("Nothing found yet.")
    } else {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(items, key = { it.ratingKey.ifBlank { it.key } }) { item ->
                MediaPosterCard(item = item, artworkUrl = artworkUrls[item.ratingKey].orEmpty(), onClick = { onSelectDetails(item) })
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun MediaPosterCard(
    item: PlexMediaItem,
    artworkUrl: String,
    isFavorite: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    var focused by remember { mutableStateOf(false) }
    val scaleWidth = if (focused) 124.dp else 116.dp
    val scaleHeight = if (focused) 186.dp else 176.dp

    Card(
        modifier = Modifier
            .width(scaleWidth)
            .height(scaleHeight)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(containerColor = if (focused) Color(0xFF17222D) else Color(0xF2111820)),
        border = if (focused) BorderStroke(3.dp, Color(0xFFE5A00D)) else null,
        shape = RoundedCornerShape(10.dp)
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().height(116.dp).background(Color(0xFF1A2028)), contentAlignment = Alignment.Center) {
                if (artworkUrl.isNotBlank()) {
                    AsyncImage(model = artworkUrl, contentDescription = item.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Text(item.type.take(1).uppercase().ifBlank { "V" }, color = Color(0xFFE5A00D), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                if (isFavorite) {
                    Text(
                        "★",
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                        color = Color(0xFFE5A00D),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Column(Modifier.padding(7.dp)) {
                Text(item.title.ifBlank { "Untitled" }, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(mediaMetaLine(item), color = Color(0xFFE5A00D), fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
@Composable
fun SectionTitle(text: String) {
    Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(7.dp))
}

@Composable
fun PlayerSettingsPanel(
    title: String,
    preBufferLabel: String,
    preBufferOptions: List<Pair<Int, String>>,
    selectedPreBuffer: Int,
    zoomOptions: List<Pair<String, String>>,
    selectedZoom: String,
    subtitlesEnabled: Boolean,
    volumePercent: Int,
    hardwareDecoder: Boolean?,
    h264Level: String,
    onUpdate: (Int, String, Boolean, Int, Boolean?, String) -> Unit,
    onReset: () -> Unit
) {
    var draftPreBuffer by remember(selectedPreBuffer) { mutableStateOf(selectedPreBuffer) }
    var draftZoom by remember(selectedZoom) { mutableStateOf(selectedZoom) }
    var draftSubtitles by remember(subtitlesEnabled) { mutableStateOf(subtitlesEnabled) }
    var draftVolume by remember(volumePercent) { mutableStateOf(volumePercent) }
    var draftHardware by remember(hardwareDecoder) { mutableStateOf(hardwareDecoder ?: true) }
    var draftH264Level by remember(h264Level) { mutableStateOf(h264Level) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(modifier = Modifier.fillMaxWidth().background(Color(0xAA007C86), RoundedCornerShape(8.dp)).padding(12.dp), contentAlignment = Alignment.Center) {
            Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }

        SettingsOptionRow(preBufferLabel, preBufferOptions, draftPreBuffer) { draftPreBuffer = it }
        SettingsOptionRow("Zoom Settings", zoomOptions, draftZoom) { draftZoom = it }
        YesNoRow("Subtitles", draftSubtitles) { draftSubtitles = it }
        SettingsOptionRow("Volume", listOf(60 to "60%", 70 to "70%", 80 to "80%", 90 to "90%", 100 to "100%"), draftVolume) { draftVolume = it }

        if (hardwareDecoder != null) {
            YesNoRow("Hardware Decoder", draftHardware) { draftHardware = it }
        }
        SettingsOptionRow(
            "H.264 Compatibility",
            listOf(
                "auto" to "Auto",
                "4.1" to "Level 4.1",
                "5.0" to "Level 5.0",
                "5.1" to "Level 5.1",
                "5.2" to "Level 5.2"
            ),
            draftH264Level
        ) { draftH264Level = it }
        Text(
            "Auto uses normal hardware decoding. Fixed levels use a compatibility decode path for H.264 files that fail on some TV boxes.",
            color = Color(0xFFB7C7D8),
            fontSize = 12.sp
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            FocusActionButton("RESET", Modifier.weight(1f), Color(0xFF8E0031), onReset)
            FocusActionButton("UPDATE", Modifier.weight(1f), Color(0xFF4C8B1F)) {
                onUpdate(draftPreBuffer, draftZoom, draftSubtitles, draftVolume, if (hardwareDecoder == null) null else draftHardware, draftH264Level)
            }
        }
    }
}

@Composable
fun <T> SettingsOptionRow(label: String, options: List<Pair<T, String>>, selected: T, onSelected: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Color(0xFFBFEFF2), fontSize = 15.sp, fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(options, key = { it.second }) { option ->
                FocusOptionChip(
                    text = option.second,
                    selected = option.first == selected,
                    onClick = { onSelected(option.first) }
                )
            }
        }
    }
}

@Composable
fun YesNoRow(label: String, selected: Boolean, onSelected: (Boolean) -> Unit) {
    SettingsOptionRow(label, listOf(true to "Yes", false to "No"), selected, onSelected)
}

@Composable
fun SettingsTile(title: String, icon: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Card(
        modifier = modifier
            .height(178.dp)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = if (focused) Color(0xFF203040) else Color(0xE90B0D25)),
        border = BorderStroke(if (focused) 5.dp else 1.dp, if (focused) Color(0xFFFFB000) else Color(0xFF273553)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(if (focused) Color(0x2229D3FF) else Color.Transparent)
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(icon, color = if (focused) Color(0xFFFFB000) else Color(0xFFD9DEE8), fontSize = if (focused) 54.sp else 48.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.height(12.dp))
            Text(title, color = if (focused) Color(0xFFFFF4D6) else Color.White, fontSize = if (focused) 16.sp else 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (focused) {
                Spacer(Modifier.height(8.dp))
                Text("OK", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Black, modifier = Modifier.background(Color(0xFFFFB000), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 3.dp))
            }
        }
    }
}

@Composable
fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xF2111820)), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(18.dp)) {
            Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun DropDownSettingsCard(title: String, open: Boolean, onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    SettingsCard(title = title) {
        SettingsExpandRow(open = open, onToggle = onToggle)

        if (open) {
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
fun SettingsExpandRow(open: Boolean, onToggle: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable { onToggle() },
        color = if (focused) Color(0xFF203040) else Color(0x99111820),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(if (focused) 3.dp else 1.dp, if (focused) Color(0xFFFFB000) else Color(0x334B5C70))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (open) "Close section" else "Open section", color = if (focused) Color.White else Color(0xFFB7C7D8), fontSize = 15.sp, fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal)
            Text(if (open) "Hide" else "Show", color = if (focused) Color.Black else Color(0xFFFFB000), fontWeight = FontWeight.Black, modifier = if (focused) Modifier.background(Color(0xFFFFB000), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 5.dp) else Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
        }
    }
}

@Composable
fun SettingsToggleRow(title: String, subtitle: String, checked: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable { onClick() },
        color = if (focused) Color(0xFF203040) else Color(0x66111820),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(if (focused) 3.dp else 1.dp, if (focused) Color(0xFFFFB000) else Color(0x22384758))
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(if (checked) Color(0xFFFFB000) else Color.Transparent, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(if (checked) "ON" else "", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 10.sp)
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 17.sp, fontWeight = if (focused) FontWeight.Black else FontWeight.Bold)
                Text(subtitle, color = if (focused) Color(0xFFFFB000) else Color(0xFFB7C7D8), fontSize = 12.sp)
            }
            if (focused) {
                Text("OK", color = Color.Black, fontWeight = FontWeight.Black, modifier = Modifier.background(Color(0xFFFFB000), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 5.dp))
            }
        }
    }
}

@Composable
fun FocusOptionChip(text: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val active = focused || selected
    Surface(
        modifier = Modifier
            .height(40.dp)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable { onClick() },
        color = when {
            focused -> Color(0xFFFFB000)
            selected -> Color(0xFF29445A)
            else -> Color(0x99111820)
        },
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(if (active) 2.dp else 1.dp, if (active) Color(0xFFFFB000) else Color(0x44566678))
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(text, color = if (focused) Color.Black else Color.White, fontSize = 14.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
        }
    }
}

@Composable
fun FocusActionButton(text: String, modifier: Modifier = Modifier, color: Color, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier
            .height(52.dp)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable { onClick() },
        color = if (focused) Color(0xFFFFB000) else color,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(if (focused) 3.dp else 1.dp, if (focused) Color.White else Color.Transparent)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, color = if (focused) Color.Black else Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
fun EmptyPanel(text: String) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xF2111820)), shape = RoundedCornerShape(16.dp)) {
        Text(text = text, modifier = Modifier.padding(18.dp), color = Color(0xFFB7C7D8), textAlign = TextAlign.Center)
    }
}

@Composable
fun LibraryPosterCard(library: PlexLibrary, onOpen: () -> Unit) {
    var focused by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .width(if (focused) 178.dp else 170.dp)
            .height(if (focused) 240.dp else 230.dp)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable { onOpen() },
        colors = CardDefaults.cardColors(containerColor = if (focused) Color(0xFF17222D) else Color(0xF2111820)),
        border = if (focused) BorderStroke(3.dp, Color(0xFFE5A00D)) else null,
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF4A3208), Color(0xFF111820), Color(0xFF050608)))).padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(library.title.ifBlank { "Library" }, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Text(library.type.ifBlank { "category" }, color = Color(0xFFE5A00D), fontSize = 12.sp)
            }
            Text(if (focused) "Press OK" else "Open", color = Color(0xFFE5A00D), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}
@Composable
fun MediaWideRow(item: PlexMediaItem, artworkUrl: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusable(),
        colors = CardDefaults.cardColors(containerColor = if (focused) Color(0xFF17222D) else Color(0xF2111820)),
        border = if (focused) BorderStroke(3.dp, Color(0xFFE5A00D)) else null,
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(if (focused) 18.dp else 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(width = 116.dp, height = 78.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF1A2028)), contentAlignment = Alignment.Center) {
                if (artworkUrl.isNotBlank()) {
                    AsyncImage(model = artworkUrl, contentDescription = item.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Text(item.type.take(1).uppercase().ifBlank { "V" }, color = Color(0xFFE5A00D), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(item.title.ifBlank { "Untitled" }, color = Color.White, fontSize = if (focused) 21.sp else 19.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(mediaMetaLine(item), color = Color(0xFFE5A00D), fontSize = 13.sp)

                if (item.summary.isNotBlank()) {
                    Spacer(Modifier.height(5.dp))
                    Text(item.summary, color = Color(0xFFB7C7D8), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }

            if (focused) {
                Spacer(Modifier.width(12.dp))
                Text("OK", color = Color.Black, fontWeight = FontWeight.Bold, modifier = Modifier.background(Color(0xFFE5A00D), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }
}
fun mediaMetaLine(item: PlexMediaItem): String {
    return listOf(
        item.year,
        item.contentRating,
        item.type.ifBlank { "video" },
        durationLabel(item.durationMs)
    ).filter { it.isNotBlank() }.joinToString(" - ")
}

fun durationLabel(durationMs: Long): String {
    if (durationMs <= 0L) return ""
    val minutes = durationMs / 60000L
    val hours = minutes / 60L
    val mins = minutes % 60L
    return if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
}

fun cacheLabel(cachedAt: Long): String {
    if (cachedAt <= 0L) return "No saved content yet"
    val ageMinutes = ((System.currentTimeMillis() - cachedAt).coerceAtLeast(0L) / 60000L).coerceAtLeast(0L)
    return when {
        ageMinutes < 1L -> "Updated just now"
        ageMinutes < 60L -> "Updated ${ageMinutes}m ago"
        ageMinutes < 1440L -> "Updated ${ageMinutes / 60L}h ago"
        else -> "Updated ${ageMinutes / 1440L}d ago"
    }
}
