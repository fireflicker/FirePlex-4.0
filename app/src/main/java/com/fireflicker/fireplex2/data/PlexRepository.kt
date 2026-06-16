package com.fireflicker.fireplex2.data

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

private val Context.dataStore by preferencesDataStore("fireplex2_clean")

class PlexRepository(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private val clientIdKey = stringPreferencesKey("client_id")
    private val tokenKey = stringPreferencesKey("plex_token")
    private val serverBaseKey = stringPreferencesKey("server_base")
    private val hiddenLibrariesKey = stringPreferencesKey("hidden_libraries")
    private val friendlyDeviceNameKey = stringPreferencesKey("friendly_device_name")
    private val appUsernameKey = stringPreferencesKey("app_username")
    private val preferredPlayerKey = stringPreferencesKey("preferred_player")
    private val exoPlayerSettingsKey = stringPreferencesKey("exo_player_settings")
    private val vlcPlayerSettingsKey = stringPreferencesKey("vlc_player_settings")
    private val cachedLibrariesKey = stringPreferencesKey("cached_libraries")
    private val cachedRecentMoviesKey = stringPreferencesKey("cached_recent_movies")
    private val cachedRecentShowsKey = stringPreferencesKey("cached_recent_shows")
    private val cachedUpdatedAtKey = longPreferencesKey("cached_updated_at")

    suspend fun savedToken(): String? = context.dataStore.data.first()[tokenKey]

    suspend fun savedServerBase(): String? {
        return context.dataStore.data.first()[serverBaseKey]?.trimEnd('/')?.takeIf { it.isNotBlank() }
    }

    suspend fun saveToken(token: String) {
        context.dataStore.edit { it[tokenKey] = token }
    }

    suspend fun clearToken() {
        context.dataStore.edit {
            it.remove(tokenKey)
            it.remove(serverBaseKey)
            it.remove(hiddenLibrariesKey)
            it.remove(appUsernameKey)
            it.remove(cachedLibrariesKey)
            it.remove(cachedRecentMoviesKey)
            it.remove(cachedRecentShowsKey)
            it.remove(cachedUpdatedAtKey)
        }
    }

    suspend fun cachedUpdatedAt(): Long {
        return context.dataStore.data.first()[cachedUpdatedAtKey] ?: 0L
    }

    suspend fun cachedLibraries(): List<PlexLibrary> {
        val raw = context.dataStore.data.first()[cachedLibrariesKey].orEmpty()
        return decodeLibraries(raw)
    }

    suspend fun cachedRecentlyAddedMovies(): List<PlexMediaItem> {
        val raw = context.dataStore.data.first()[cachedRecentMoviesKey].orEmpty()
        return decodeMediaItems(raw)
    }

    suspend fun cachedRecentlyAddedShows(): List<PlexMediaItem> {
        val raw = context.dataStore.data.first()[cachedRecentShowsKey].orEmpty()
        return decodeMediaItems(raw)
    }

    suspend fun cachedLibraryItems(libraryKey: String): List<PlexMediaItem> {
        val raw = context.dataStore.data.first()[cachedLibraryItemsKey(libraryKey)].orEmpty()
        return decodeMediaItems(raw)
    }

    suspend fun saveHomeCache(
        libraries: List<PlexLibrary>,
        recentMovies: List<PlexMediaItem>,
        recentShows: List<PlexMediaItem>
    ) {
        context.dataStore.edit { prefs ->
            prefs[cachedLibrariesKey] = encodeLibraries(libraries)
            prefs[cachedRecentMoviesKey] = encodeMediaItems(recentMovies)
            prefs[cachedRecentShowsKey] = encodeMediaItems(recentShows)
            prefs[cachedUpdatedAtKey] = System.currentTimeMillis()
        }
    }

    suspend fun saveLibraryCache(libraryKey: String, items: List<PlexMediaItem>) {
        if (libraryKey.isBlank()) return
        context.dataStore.edit { prefs ->
            prefs[cachedLibraryItemsKey(libraryKey)] = encodeMediaItems(items)
            prefs[cachedUpdatedAtKey] = System.currentTimeMillis()
        }
    }

    suspend fun clearContentCache() {
        val prefs = context.dataStore.data.first()
        val cachedLibraryKeys = prefs.asMap().keys.filter { it.name.startsWith("cached_library_items_") }
        context.dataStore.edit { edit ->
            edit.remove(cachedLibrariesKey)
            edit.remove(cachedRecentMoviesKey)
            edit.remove(cachedRecentShowsKey)
            edit.remove(cachedUpdatedAtKey)
            cachedLibraryKeys.forEach { key ->
                @Suppress("UNCHECKED_CAST")
                edit.remove(key as Preferences.Key<String>)
            }
        }
    }

    suspend fun friendlyDeviceName(): String {
        return context.dataStore.data.first()[friendlyDeviceNameKey]
            ?.takeIf { it.isNotBlank() }
            ?: "FirePlex3.0"
    }

    suspend fun saveFriendlyDeviceName(name: String) {
        context.dataStore.edit {
            it[friendlyDeviceNameKey] = name.trim().ifBlank { "FirePlex3.0" }
        }
    }

    suspend fun savedAppUsername(): String? {
        return context.dataStore.data.first()[appUsernameKey]?.trim()?.takeIf { it.isNotBlank() }
    }

    suspend fun saveAppUsername(username: String) {
        val clean = username.trim()
        context.dataStore.edit { prefs ->
            if (clean.isBlank()) {
                prefs.remove(appUsernameKey)
            } else {
                prefs[appUsernameKey] = clean
            }
        }
    }

    suspend fun clearAppUsername() {
        context.dataStore.edit { prefs -> prefs.remove(appUsernameKey) }
    }

    suspend fun preferredPlayer(): String {
        return context.dataStore.data.first()[preferredPlayerKey]
            ?.takeIf { it == "vlc" || it == "exo" }
            ?: "vlc"
    }

    suspend fun savePreferredPlayer(player: String) {
        context.dataStore.edit {
            it[preferredPlayerKey] = "vlc"
        }
    }

    suspend fun exoPlayerSettings(): ExoPlayerSettings {
        val raw = context.dataStore.data.first()[exoPlayerSettingsKey]
        return if (raw.isNullOrBlank()) {
            ExoPlayerSettings()
        } else {
            runCatching { json.decodeFromString(ExoPlayerSettings.serializer(), raw) }
                .getOrDefault(ExoPlayerSettings())
        }
    }

    suspend fun saveExoPlayerSettings(settings: ExoPlayerSettings) {
        val encoded = json.encodeToString(ExoPlayerSettings.serializer(), settings)
        context.dataStore.edit { prefs ->
            prefs[exoPlayerSettingsKey] = encoded
        }
    }

    suspend fun vlcPlayerSettings(): VlcPlayerSettings {
        val raw = context.dataStore.data.first()[vlcPlayerSettingsKey]
        return if (raw.isNullOrBlank()) {
            VlcPlayerSettings()
        } else {
            runCatching { json.decodeFromString(VlcPlayerSettings.serializer(), raw) }
                .getOrDefault(VlcPlayerSettings())
        }
    }

    suspend fun saveVlcPlayerSettings(settings: VlcPlayerSettings) {
        val encoded = json.encodeToString(VlcPlayerSettings.serializer(), settings)
        context.dataStore.edit { prefs ->
            prefs[vlcPlayerSettingsKey] = encoded
        }
    }

    suspend fun hiddenLibraryKeys(): Set<String> {
        return context.dataStore.data.first()[hiddenLibrariesKey].orEmpty()
            .split("|")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    suspend fun setLibraryEnabled(key: String, enabled: Boolean) {
        if (key.isBlank()) return

        val hidden = hiddenLibraryKeys().toMutableSet()
        if (enabled) hidden.remove(key) else hidden.add(key)

        context.dataStore.edit {
            it[hiddenLibrariesKey] = hidden.joinToString("|")
        }
    }

    private suspend fun clientId(): String {
        val prefs = context.dataStore.data.first()
        prefs[clientIdKey]?.let { return it }

        val id = UUID.randomUUID().toString()
        context.dataStore.edit { it[clientIdKey] = id }
        return id
    }

    suspend fun createPin(): PlexPin = withContext(Dispatchers.IO) {
        json.decodeFromString<PlexPin>(
            request(url = "https://plex.tv/api/v2/pins", method = "POST")
        )
    }

    suspend fun checkPin(id: Int): PlexPin = withContext(Dispatchers.IO) {
        json.decodeFromString<PlexPin>(
            request("https://plex.tv/api/v2/pins/$id")
        )
    }

    suspend fun servers(): List<PlexServer> = withContext(Dispatchers.IO) {
        val token = savedToken() ?: error("Not linked")

        val builder = Request.Builder()
            .url("https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1")
            .header("Accept", "application/json")
            .header("X-Plex-Token", token)
            .plexHeaders()

        client.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                error("Failed loading servers: Plex HTTP ${response.code}: ${response.message}\n$body")
            }

            json.decodeFromString(
                ListSerializer(PlexServer.serializer()),
                body
            ).filter { it.isServer() }
        }
    }

    suspend fun autoSelectServer(): PlexServer {
        val server = servers().firstOrNull()
            ?: error("No Plex Media Server found")

        val connection = chooseBestConnection(server.connections)
            ?: error("No Plex server connection found")

        context.dataStore.edit {
            it[serverBaseKey] = connection.uri.trimEnd('/')
        }

        return server
    }

    private fun chooseBestConnection(connections: List<PlexConnection>): PlexConnection? {
        return connections
            .filter { it.uri.isNotBlank() }
            .sortedWith(
                compareBy<PlexConnection> { connectionRank(it) }
                    .thenBy { it.relay }
            )
            .firstOrNull()
    }

    private fun connectionRank(connection: PlexConnection): Int {
        val privateUri = isPrivateUri(connection.uri)
        val https = connection.uri.startsWith("https://", ignoreCase = true)

        return when {
            https && !connection.local && !privateUri && !connection.relay -> 0
            !connection.local && !privateUri && !connection.relay -> 1
            https && !privateUri -> 2
            !privateUri -> 3
            connection.relay -> 4
            else -> 9
        }
    }

    private fun uriHost(uri: String): String {
        return try {
            URI(uri).host.orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }

    private fun isPrivateUri(uri: String): Boolean {
        val host = uriHost(uri).lowercase()
        val full = uri.lowercase()

        return isPrivateHost(host) || hasDashedPrivateIp(host) || hasDashedPrivateIp(full)
    }

    private fun isPrivateHost(host: String): Boolean {
        val clean = host.lowercase()

        return clean == "localhost" ||
            clean.startsWith("127.") ||
            clean.startsWith("10.") ||
            clean.startsWith("192.168.") ||
            clean.startsWith("172.16.") ||
            clean.startsWith("172.17.") ||
            clean.startsWith("172.18.") ||
            clean.startsWith("172.19.") ||
            clean.startsWith("172.20.") ||
            clean.startsWith("172.21.") ||
            clean.startsWith("172.22.") ||
            clean.startsWith("172.23.") ||
            clean.startsWith("172.24.") ||
            clean.startsWith("172.25.") ||
            clean.startsWith("172.26.") ||
            clean.startsWith("172.27.") ||
            clean.startsWith("172.28.") ||
            clean.startsWith("172.29.") ||
            clean.startsWith("172.30.") ||
            clean.startsWith("172.31.")
    }

    private fun hasDashedPrivateIp(value: String): Boolean {
        return value.contains("192-168-") ||
            value.contains("127-0-0-") ||
            value.contains("10-") ||
            value.contains("172-16-") ||
            value.contains("172-17-") ||
            value.contains("172-18-") ||
            value.contains("172-19-") ||
            value.contains("172-20-") ||
            value.contains("172-21-") ||
            value.contains("172-22-") ||
            value.contains("172-23-") ||
            value.contains("172-24-") ||
            value.contains("172-25-") ||
            value.contains("172-26-") ||
            value.contains("172-27-") ||
            value.contains("172-28-") ||
            value.contains("172-29-") ||
            value.contains("172-30-") ||
            value.contains("172-31-")
    }

    suspend fun libraries(): List<PlexLibrary> = withContext(Dispatchers.IO) {
        val base = serverBase()
        val token = savedToken() ?: error("Not linked")

        PlexXmlParser.libraries(
            serverRequest("${base.trimEnd('/')}/library/sections", token)
        )
    }

    suspend fun libraryItems(library: PlexLibrary): List<PlexMediaItem> = withContext(Dispatchers.IO) {
        val base = serverBase()
        val token = savedToken() ?: error("Not linked")
        val url = if (library.type.equals("show", ignoreCase = true) || library.type.equals("tv", ignoreCase = true)) {
            "${base.trimEnd('/')}/library/sections/${library.key}/all?type=2&sort=titleSort"
        } else {
            "${base.trimEnd('/')}/library/sections/${library.key}/all"
        }

        PlexXmlParser.parseMediaItems(serverRequest(url, token))
    }

    suspend fun mediaDetails(item: PlexMediaItem): PlexMediaItem = withContext(Dispatchers.IO) {
        val base = serverBase()
        val token = savedToken() ?: error("Not linked")
        val ratingKey = item.ratingKey.ifBlank { error("No media rating key found.") }

        val body = serverRequest("${base.trimEnd('/')}/library/metadata/$ratingKey", token)
        PlexXmlParser.parseMediaItems(body).firstOrNull() ?: item
    }

    suspend fun tvSeasons(show: PlexMediaItem): List<PlexMediaItem> = withContext(Dispatchers.IO) {
        val base = serverBase()
        val token = savedToken() ?: error("Not linked")
        val ratingKey = when {
            show.type.equals("episode", ignoreCase = true) && show.grandparentRatingKey.isNotBlank() -> show.grandparentRatingKey
            show.type.equals("season", ignoreCase = true) && show.parentRatingKey.isNotBlank() -> show.parentRatingKey
            show.grandparentRatingKey.isNotBlank() && !show.type.equals("show", ignoreCase = true) -> show.grandparentRatingKey
            show.parentRatingKey.isNotBlank() && show.type.equals("season", ignoreCase = true) -> show.parentRatingKey
            else -> show.ratingKey
        }.ifBlank { error("No TV series rating key found.") }

        PlexXmlParser.parseMediaItems(
            serverRequest("${base.trimEnd('/')}/library/metadata/$ratingKey/children", token)
        )
    }

    suspend fun seasonEpisodes(season: PlexMediaItem): List<PlexMediaItem> = withContext(Dispatchers.IO) {
        val base = serverBase()
        val token = savedToken() ?: error("Not linked")
        val ratingKey = when {
            season.type.equals("episode", ignoreCase = true) && season.parentRatingKey.isNotBlank() -> season.parentRatingKey
            else -> season.ratingKey
        }.ifBlank { error("No season rating key found.") }

        PlexXmlParser.parseMediaItems(
            serverRequest("${base.trimEnd('/')}/library/metadata/$ratingKey/children", token)
        ).sortedWith(compareBy<PlexMediaItem> { it.addedAt }.thenBy { it.title.lowercase() })
    }

    suspend fun recentlyAdded(library: PlexLibrary): List<PlexMediaItem> = withContext(Dispatchers.IO) {
        val base = serverBase()
        val token = savedToken() ?: error("Not linked")

        PlexXmlParser.parseMediaItems(
            serverRequest("${base.trimEnd('/')}/library/sections/${library.key}/recentlyAdded", token)
        ).sortedByNewest()
    }

    suspend fun recentlyAddedMovies(libraries: List<PlexLibrary>): List<PlexMediaItem> = withContext(Dispatchers.IO) {
        val movies = libraries.filter { it.type.equals("movie", ignoreCase = true) }
        movies.flatMap { recentlyAdded(it) }
            .distinctBy { it.ratingKey }
            .sortedByNewest()
            .take(50)
    }

    suspend fun recentlyAddedShows(libraries: List<PlexLibrary>): List<PlexMediaItem> = withContext(Dispatchers.IO) {
        val shows = libraries.filter { it.type.equals("show", ignoreCase = true) || it.type.equals("tv", ignoreCase = true) }
        if (shows.isEmpty()) return@withContext emptyList()

        val base = serverBase()
        val token = savedToken() ?: error("Not linked")

        shows.flatMap { library ->
            val recentUrl = "${base.trimEnd('/')}/library/sections/${library.key}/recentlyAdded"
            val episodeUrl = "${base.trimEnd('/')}/library/sections/${library.key}/all?type=4&sort=addedAt%3Adesc"
            val recent = runCatching { PlexXmlParser.parseMediaItems(serverRequest(recentUrl, token)) }.getOrDefault(emptyList())
            val episodes = runCatching { PlexXmlParser.parseMediaItems(serverRequest(episodeUrl, token)) }.getOrDefault(emptyList())
            recent + episodes
        }
            .filter { it.type.equals("episode", ignoreCase = true) || it.type.equals("show", ignoreCase = true) || it.type.equals("video", ignoreCase = true) }
            .sortedByNewest()
            .distinctBy { item -> item.ratingKey.ifBlank { item.key.ifBlank { item.title.lowercase() } } }
            .take(50)
    }

    suspend fun continueWatching(): List<PlexMediaItem> = withContext(Dispatchers.IO) {
        val base = serverBase()
        val token = savedToken() ?: error("Not linked")

        val onDeck = runCatching {
            PlexXmlParser.parseMediaItems(serverRequest("${base.trimEnd('/')}/library/onDeck", token))
        }.getOrDefault(emptyList())

        onDeck
            .filter { it.ratingKey.isNotBlank() }
            .distinctBy { it.ratingKey }
            .sortedWith(compareByDescending<PlexMediaItem> { it.viewOffsetMs }.thenByDescending { it.addedAt })
            .take(50)
    }

    private fun List<PlexMediaItem>.sortedByNewest(): List<PlexMediaItem> {
        return sortedWith(compareByDescending<PlexMediaItem> { it.addedAt }.thenBy { it.title.lowercase() })
    }

    suspend fun streamUrl(item: PlexMediaItem): String = withContext(Dispatchers.IO) {
        val base = serverBase()
        val token = savedToken() ?: error("Not linked")

        if (item.partKey.isBlank()) {
            error("This item has no direct playable video file.")
        }

        "${base.trimEnd('/')}${item.partKey}?X-Plex-Token=$token"
    }

    suspend fun imageUrl(path: String): String = withContext(Dispatchers.IO) {
        val base = serverBase()
        val token = savedToken() ?: error("Not linked")

        if (path.isBlank()) "" else "${base.trimEnd('/')}$path?X-Plex-Token=$token"
    }

    suspend fun subtitleUrl(track: PlexSubtitleTrack): String = withContext(Dispatchers.IO) {
        val base = serverBase()
        val token = savedToken() ?: error("Not linked")

        if (track.key.isBlank()) "" else "${base.trimEnd('/')}${track.key}?X-Plex-Token=$token"
    }

    suspend fun speedTest(): String = withContext(Dispatchers.IO) {
        val base = serverBase()
        val token = savedToken() ?: error("Not linked")
        val start = System.nanoTime()

        val body = serverRequest("${base.trimEnd('/')}/library/sections", token)
        val elapsedSeconds = ((System.nanoTime() - start).toDouble() / 1_000_000_000.0).coerceAtLeast(0.001)
        val kb = body.toByteArray().size / 1024.0
        val kbps = kb / elapsedSeconds

        "${kbps.roundToInt()} KB/s to Plex server"
    }

    suspend fun reportPlayback(
        item: PlexMediaItem,
        state: String,
        timeMs: Long,
        durationMs: Long
    ) = withContext(Dispatchers.IO) {
        val base = serverBase()
        val token = savedToken() ?: error("Not linked")
        if (item.ratingKey.isBlank()) return@withContext

        val safeState = when (state) {
            "playing", "paused", "stopped" -> state
            else -> "playing"
        }

        val url = buildString {
            append(base.trimEnd('/'))
            append("/:/timeline")
            append("?ratingKey=${encode(item.ratingKey)}")
            append("&key=${encode(item.key.ifBlank { "/library/metadata/${item.ratingKey}" })}")
            append("&state=${encode(safeState)}")
            append("&time=${timeMs.coerceAtLeast(0L)}")
            append("&duration=${durationMs.coerceAtLeast(0L)}")
            append("&type=${encode(item.type.ifBlank { "video" })}")
            append("&X-Plex-Token=${encode(token)}")
        }

        timelineRequest(url)
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
    }

    private fun cachedLibraryItemsKey(libraryKey: String) = stringPreferencesKey("cached_library_items_$libraryKey")

    private fun encodeLibraries(libraries: List<PlexLibrary>): String {
        return libraries.joinToString("\n") { library ->
            listOf(library.key, library.title, library.type).joinToString("\t") { b64(it) }
        }
    }

    private fun decodeLibraries(raw: String): List<PlexLibrary> {
        if (raw.isBlank()) return emptyList()

        return raw.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split("\t")
                if (parts.size < 3) {
                    null
                } else {
                    PlexLibrary(
                        key = unb64(parts[0]),
                        title = unb64(parts[1]),
                        type = unb64(parts[2])
                    )
                }
            }
            .toList()
    }

    private fun encodeMediaItems(items: List<PlexMediaItem>): String {
        return items.joinToString("\n") { item ->
            listOf(
                item.ratingKey,
                item.parentRatingKey,
                item.grandparentRatingKey,
                item.key,
                item.title,
                item.type,
                item.summary,
                item.thumb,
                item.art,
                item.year,
                item.contentRating,
                item.durationMs.toString(),
                item.viewOffsetMs.toString(),
                item.addedAt.toString(),
                item.partKey
            ).joinToString("\t") { b64(it) }
        }
    }

    private fun decodeMediaItems(raw: String): List<PlexMediaItem> {
        if (raw.isBlank()) return emptyList()

        return raw.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split("\t")
                if (parts.size < 13) {
                    null
                } else {
                    val hasParentKeys = parts.size >= 15
                    val offset = if (hasParentKeys) 2 else 0
                    PlexMediaItem(
                        ratingKey = unb64(parts[0]),
                        parentRatingKey = if (hasParentKeys) unb64(parts[1]) else "",
                        grandparentRatingKey = if (hasParentKeys) unb64(parts[2]) else "",
                        key = unb64(parts[1 + offset]),
                        title = unb64(parts[2 + offset]),
                        type = unb64(parts[3 + offset]),
                        summary = unb64(parts[4 + offset]),
                        thumb = unb64(parts[5 + offset]),
                        art = unb64(parts[6 + offset]),
                        year = unb64(parts[7 + offset]),
                        contentRating = unb64(parts[8 + offset]),
                        durationMs = unb64(parts[9 + offset]).toLongOrNull() ?: 0L,
                        viewOffsetMs = unb64(parts[10 + offset]).toLongOrNull() ?: 0L,
                        addedAt = unb64(parts[11 + offset]).toLongOrNull() ?: 0L,
                        partKey = unb64(parts[12 + offset]),
                        subtitles = emptyList()
                    )
                }
            }
            .toList()
    }

    private fun b64(value: String): String {
        return Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun unb64(value: String): String {
        return runCatching {
            String(Base64.decode(value, Base64.NO_WRAP), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private suspend fun serverBase(): String {
        val savedBase = context.dataStore.data.first()[serverBaseKey]

        if (!savedBase.isNullOrBlank()) {
            return savedBase.trimEnd('/')
        }

        autoSelectServer()

        return context.dataStore.data.first()[serverBaseKey]
            ?.trimEnd('/')
            ?: error("No server selected")
    }

    private suspend fun serverRequest(url: String, token: String): String {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/xml")
            .header("X-Plex-Token", token)
            .plexHeaders()

        client.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                error("Server HTTP ${response.code}: ${response.message}\n$body")
            }

            return body
        }
    }

    private suspend fun timelineRequest(url: String) {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/xml")
            .plexHeaders()

        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                error("Timeline HTTP ${response.code}: ${response.message}\n$body")
            }
        }
    }

    private suspend fun request(url: String, method: String = "GET"): String {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .plexHeaders()

        if (method == "POST") {
            builder.post("{}".toRequestBody("application/json".toMediaType()))
        }

        client.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                error("Plex HTTP ${response.code}: ${response.message}\n$body")
            }

            return body
        }
    }

    private suspend fun Request.Builder.plexHeaders(): Request.Builder {
        return this
            .header("X-Plex-Product", "FirePlex3.0")
            .header("X-Plex-Version", "1.2")
            .header("X-Plex-Platform", "Android")
            .header("X-Plex-Device", "Android")
            .header("X-Plex-Device-Name", friendlyDeviceName())
            .header("X-Plex-Client-Identifier", clientId())
    }
}
