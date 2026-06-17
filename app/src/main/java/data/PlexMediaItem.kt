package com.fireflicker.fireplex2.data

data class PlexMediaItem(
    val ratingKey: String,
    val parentRatingKey: String = "",
    val grandparentRatingKey: String = "",
    val key: String,
    val title: String,
    val type: String,
    val summary: String,
    val thumb: String,
    val art: String,
    val year: String,
    val contentRating: String,
    val durationMs: Long,
    val viewOffsetMs: Long,
    val addedAt: Long,
    val partKey: String,
    val subtitles: List<PlexSubtitleTrack>
)
