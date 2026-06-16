package com.fireflicker.fireplex2.data

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

object PlexXmlParser {
    fun libraries(xml: String): List<PlexLibrary> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))

        val libraries = mutableListOf<PlexLibrary>()

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "Directory") {
                val key = parser.getAttributeValue(null, "key") ?: continue
                val title = parser.getAttributeValue(null, "title") ?: "Library"
                val type = parser.getAttributeValue(null, "type") ?: "library"
                libraries.add(PlexLibrary(key = key, title = title, type = type))
            }
        }

        return libraries
    }

    fun parseMediaItems(xml: String): List<PlexMediaItem> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))

        val items = mutableListOf<PlexMediaItem>()
        var current: MutableMedia? = null
        var insideVideo = false

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "Directory") {
                val type = parser.attr("type")
                val ratingKey = parser.attr("ratingKey")
                val key = parser.attr("key")

                if (ratingKey.isNotBlank() || key.isNotBlank()) {
                    val title = parser.attr("title")
                        .ifBlank { parser.attr("grandparentTitle") }
                        .ifBlank { parser.attr("parentTitle") }
                        .ifBlank { "Untitled" }

                    items.add(
                        PlexMediaItem(
                            ratingKey = ratingKey.ifBlank { key },
                            parentRatingKey = parser.attr("parentRatingKey"),
                            grandparentRatingKey = parser.attr("grandparentRatingKey"),
                            key = key,
                            title = title,
                            type = type.ifBlank { "show" },
                            summary = parser.attr("summary"),
                            thumb = parser.attr("thumb").ifBlank { parser.attr("grandparentThumb") },
                            art = parser.attr("art").ifBlank { parser.attr("grandparentArt") },
                            year = parser.attr("year"),
                            contentRating = parser.attr("contentRating"),
                            durationMs = parser.attr("duration").toLongOrNull() ?: 0L,
                            viewOffsetMs = parser.attr("viewOffset").toLongOrNull() ?: 0L,
                            addedAt = parser.attr("addedAt").toLongOrNull() ?: 0L,
                            partKey = "",
                            subtitles = emptyList()
                        )
                    )
                }
            }

            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "Video") {
                insideVideo = true
                val type = parser.attr("type")
                val episodeTitle = parser.attr("title")
                val showTitle = parser.attr("grandparentTitle")
                val displayTitle = if (type.equals("episode", ignoreCase = true)) {
                    showTitle.ifBlank { parser.attr("parentTitle") }.ifBlank { episodeTitle }
                } else {
                    episodeTitle.ifBlank { showTitle }
                }

                current = MutableMedia(
                    ratingKey = parser.attr("ratingKey"),
                    parentRatingKey = parser.attr("parentRatingKey"),
                    grandparentRatingKey = parser.attr("grandparentRatingKey"),
                    key = parser.attr("key"),
                    title = displayTitle.ifBlank { "Untitled" },
                    type = type,
                    summary = parser.attr("summary").ifBlank { episodeTitle },
                    thumb = parser.attr("grandparentThumb").ifBlank { parser.attr("thumb") },
                    art = parser.attr("grandparentArt").ifBlank { parser.attr("art") },
                    year = parser.attr("year"),
                    contentRating = parser.attr("contentRating"),
                    durationMs = parser.attr("duration").toLongOrNull() ?: 0L,
                    viewOffsetMs = parser.attr("viewOffset").toLongOrNull() ?: 0L,
                    addedAt = parser.attr("addedAt").toLongOrNull() ?: 0L
                )
            }

            if (insideVideo && parser.eventType == XmlPullParser.START_TAG && parser.name == "Part") {
                current?.partKey = parser.attr("key")
            }

            if (insideVideo && parser.eventType == XmlPullParser.START_TAG && parser.name == "Stream") {
                if (parser.attr("streamType") == "3") {
                    current?.subtitles?.add(
                        PlexSubtitleTrack(
                            id = parser.attr("id"),
                            key = parser.attr("key"),
                            language = parser.attr("language").ifBlank { parser.attr("languageCode") },
                            title = parser.attr("title")
                                .ifBlank { parser.attr("displayTitle") }
                                .ifBlank { parser.attr("language").ifBlank { "Subtitle" } },
                            codec = parser.attr("codec"),
                            selected = parser.attr("selected") == "1"
                        )
                    )
                }
            }

            if (insideVideo && parser.eventType == XmlPullParser.END_TAG && parser.name == "Video") {
                current?.let { item ->
                    items.add(
                        PlexMediaItem(
                            ratingKey = item.ratingKey,
                            parentRatingKey = item.parentRatingKey,
                            grandparentRatingKey = item.grandparentRatingKey,
                            key = item.key,
                            title = item.title,
                            type = item.type,
                            summary = item.summary,
                            thumb = item.thumb,
                            art = item.art,
                            year = item.year,
                            contentRating = item.contentRating,
                            durationMs = item.durationMs,
                            viewOffsetMs = item.viewOffsetMs,
                            addedAt = item.addedAt,
                            partKey = item.partKey,
                            subtitles = item.subtitles
                        )
                    )
                }
                current = null
                insideVideo = false
            }
        }

        return items
    }

    private fun XmlPullParser.attr(name: String): String = getAttributeValue(null, name).orEmpty()

    private data class MutableMedia(
        val ratingKey: String,
        val parentRatingKey: String,
        val grandparentRatingKey: String,
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
        var partKey: String = "",
        val subtitles: MutableList<PlexSubtitleTrack> = mutableListOf()
    )
}
