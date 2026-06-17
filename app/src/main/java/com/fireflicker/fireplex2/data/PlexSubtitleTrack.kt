package com.fireflicker.fireplex2.data

data class PlexSubtitleTrack(
    val id: String,
    val key: String,
    val language: String,
    val title: String,
    val codec: String,
    val selected: Boolean
)
