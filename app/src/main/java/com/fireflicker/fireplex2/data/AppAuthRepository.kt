package com.fireflicker.fireplex2.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class AppAuthRepository {
    private val apiUrl = "https://plexpin.duckdns.org/api/app/login"
    private val apiKey = "2f6d3334d3f35277a6aab9d8589eb4824ef174195f0ea6407aba301aa0f1085a"

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun login(username: String, deviceId: String? = null): AppLoginResponse = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("username", username)
            if (!deviceId.isNullOrBlank()) put("deviceId", deviceId)
        }.toString()

        val request = Request.Builder()
            .url(apiUrl)
            .header("Content-Type", "application/json")
            .header("X-API-Key", apiKey)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("FirePlex login failed: HTTP ${response.code}")
            }
            if (body.isBlank()) {
                throw IllegalStateException("FirePlex login returned an empty response.")
            }
            json.decodeFromString(AppLoginResponse.serializer(), body)
        }
    }
}

@Serializable
data class AppLoginResponse(
    val success: Boolean = false,
    val allowed: Boolean = false,
    val username: String? = null,
    val expiryDate: String? = null,
    val daysRemaining: Int? = null,
    val reason: String? = null
)
