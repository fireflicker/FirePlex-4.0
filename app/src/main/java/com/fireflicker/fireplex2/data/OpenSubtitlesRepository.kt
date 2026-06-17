package com.fireflicker.fireplex2.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class OpenSubtitleResult(
    val fileId: Int,
    val displayName: String,
    val language: String,
    val releaseName: String,
    val downloads: Int
)

class OpenSubtitlesRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private var token: String? = null

    suspend fun search(item: PlexMediaItem, query: String, language: String = "en"): List<OpenSubtitleResult> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim().ifBlank { item.title }
        if (cleanQuery.isBlank()) return@withContext emptyList()

        val url = buildString {
            append("https://api.opensubtitles.com/api/v1/subtitles?")
            append("query=").append(URLEncoder.encode(cleanQuery, "UTF-8"))
            append("&languages=").append(URLEncoder.encode(language.ifBlank { "en" }, "UTF-8"))
            append("&order_by=download_count&order_direction=desc")
            item.year.takeIf { it.isNotBlank() }?.let {
                append("&year=").append(URLEncoder.encode(it, "UTF-8"))
            }
        }

        val request = baseRequest(url).get().build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("OpenSubtitles search failed: ${response.code}")
            response.body?.string().orEmpty()
        }

        val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
        val results = mutableListOf<OpenSubtitleResult>()
        for (i in 0 until data.length()) {
            val entry = data.optJSONObject(i) ?: continue
            val attributes = entry.optJSONObject("attributes") ?: continue
            val files = attributes.optJSONArray("files") ?: continue
            if (files.length() == 0) continue
            val firstFile = files.optJSONObject(0) ?: continue
            val fileId = firstFile.optInt("file_id", 0)
            if (fileId <= 0) continue

            val release = attributes.optString("release", "")
            val featureDetails = attributes.optJSONObject("feature_details")
            val title = featureDetails?.optString("title").orEmpty().ifBlank { attributes.optString("filename", "Subtitle") }
            val lang = attributes.optString("language", language.ifBlank { "en" })
            val downloads = attributes.optInt("download_count", 0)
            val display = attributes.optString("filename", "").ifBlank { "$title - ${lang.uppercase()}" }

            results.add(
                OpenSubtitleResult(
                    fileId = fileId,
                    displayName = display,
                    language = lang,
                    releaseName = release,
                    downloads = downloads
                )
            )
        }
        results.distinctBy { it.fileId }.take(20)
    }

    suspend fun downloadSubtitleUrl(fileId: Int): String = withContext(Dispatchers.IO) {
        if (fileId <= 0) throw IllegalArgumentException("Invalid OpenSubtitles file id.")
        val authToken = token ?: login().also { token = it }
        val json = JSONObject().put("file_id", fileId).toString()
        val request = baseRequest("https://api.opensubtitles.com/api/v1/download")
            .addHeader("Authorization", "Bearer $authToken")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()

        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("OpenSubtitles download failed: ${response.code}")
            response.body?.string().orEmpty()
        }
        JSONObject(body).optString("link").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("OpenSubtitles returned no subtitle link.")
    }

    private fun login(): String {
        val json = JSONObject()
            .put("username", ApiKeys.OPENSUBTITLES_USERNAME)
            .put("password", ApiKeys.OPENSUBTITLES_PASSWORD)
            .toString()

        val request = baseRequest("https://api.opensubtitles.com/api/v1/login")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()

        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("OpenSubtitles login failed: ${response.code}")
            response.body?.string().orEmpty()
        }
        return JSONObject(body).optString("token").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("OpenSubtitles returned no login token.")
    }

    private fun baseRequest(url: String): Request.Builder {
        return Request.Builder()
            .url(url)
            .addHeader("Api-Key", ApiKeys.OPENSUBTITLES_API_KEY)
            .addHeader("User-Agent", "FirePlex v4")
            .addHeader("Accept", "application/json")
            .addHeader("Content-Type", "application/json")
    }
}
