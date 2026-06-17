package com.fireflicker.fireplex2.data

import kotlinx.serialization.Serializable

@Serializable
data class PlexServer(
    val name: String = "",
    val product: String = "",
    val provides: String = "",
    val clientIdentifier: String = "",
    val accessToken: String? = null,
    val connections: List<PlexConnection> = emptyList()
) {
    fun isServer(): Boolean {
        return product.contains("Plex Media Server", ignoreCase = true) ||
            provides.contains("server", ignoreCase = true)
    }
}

@Serializable
data class PlexConnection(
    val uri: String = "",
    val local: Boolean = false,
    val relay: Boolean = false
)
