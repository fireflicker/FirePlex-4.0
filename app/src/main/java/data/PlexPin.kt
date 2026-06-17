package com.fireflicker.fireplex2.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PlexPin(
    val id: Int,
    val code: String? = null,
    @SerialName("authToken") val authToken: String? = null,
    @SerialName("clientIdentifier") val clientIdentifier: String? = null,
    @SerialName("expiresAt") val expiresAt: String? = null
)
