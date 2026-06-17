package com.fireflicker.fireplex2.data

import kotlinx.serialization.Serializable

@Serializable
data class ExoPlayerSettings(
    val preBufferSeconds: Int = 20,
    val zoomMode: String = "fill",
    val subtitlesEnabled: Boolean = true,
    val volumePercent: Int = 100
)

@Serializable
data class VlcPlayerSettings(
    val preBufferMs: Int = 3000,
    val zoomMode: String = "fill",
    val subtitlesEnabled: Boolean = true,
    val volumePercent: Int = 100,
    val hardwareDecoder: Boolean = true,
    val h264Level: String = "auto"
)


@Serializable
data class MpvPlayerSettings(
    val preBufferMs: Int = 3000,
    val zoomMode: String = "fill",
    val subtitlesEnabled: Boolean = true,
    val volumePercent: Int = 100,
    val hardwareDecoder: Boolean = true,
    val h264Level: String = "auto"
)
