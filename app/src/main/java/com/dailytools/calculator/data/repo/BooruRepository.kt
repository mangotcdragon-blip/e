package com.dailytools.calculator.data.repo

import com.dailytools.calculator.data.Rule34Credentials
import com.dailytools.calculator.data.model.MediaKind
import com.dailytools.calculator.data.model.MediaTypeFilter
import com.dailytools.calculator.data.model.Post
import com.dailytools.calculator.data.model.Rating
import com.dailytools.calculator.data.model.Source
import com.dailytools.calculator.data.model.SortOrder
import com.dailytools.calculator.data.network.NetworkModule
import com.dailytools.calculator.data.network.Rule34Post
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URLEncoder

/** Posts per page, per the desired browsing density. */
const val PAGE_SIZE = 200

private val gson = Gson()

class BooruRepository(
    private val rule34Credentials: suspend () -> Rule34Credentials?,
) {

    suspend fun fetchPage(
        source: Source,
        query: String,
        rating: Rating,
        mediaType: MediaTypeFilter,
        sort: SortOrder,
        pageIndex: Int,
    ): List<Post> {
        val posts = when (source) {
            Source.E621 -> fetchE621(query, rating, sort, pageIndex)
            Source.RULE34 -> fetchRule34(query, rating, pageIndex)
        }
        return if (mediaType == MediaTypeFilter.ALL) {
            posts
        } else {
            posts.filter {
                when (mediaType) {
                    MediaTypeFilter.VIDEOS -> it.mediaKind == MediaKind.VIDEO
                    MediaTypeFilter.IMAGES -> it.mediaKind == MediaKind.IMAGE || it.mediaKind == MediaKind.GIF
                    MediaTypeFilter.ALL -> true
                }
            }
        }
    }

    /** Autocompletes the tag the user is currently typing. Fails silently (empty list) on any error. */
    suspend fun suggestTags(source: Source, prefix: String): List<String> {
        if (prefix.isBlank()) return emptyList()
        return runCatching {
            when (source) {
                Source.E621 -> NetworkModule.e621Api
                    .autocompleteTags(prefix = prefix, limit = 8)
                    .mapNotNull { it.name }

                Source.RULE34 -> {
                    val encoded = URLEncoder.encode(prefix, "UTF-8")
                    NetworkModule.rule34Api
                        .autocompleteTags("https://rule34.xxx/autocomplete.php?q=$encoded")
                        .orEmpty()
                        .mapNotNull { it.value ?: it.label }
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun buildTags(query: String, rating: Rating, sort: SortOrder?): String {
        val parts = mutableListOf<String>()
        if (query.isNotBlank()) parts.add(query.trim())
        rating.tag?.let { parts.add(it) }
        sort?.tag?.let { parts.add(it) }
        return parts.joinToString(" ")
    }

    private suspend fun fetchE621(query: String, rating: Rating, sort: SortOrder, pageIndex: Int): List<Post> {
        val root = NetworkModule.e621Api.posts(
            tags = buildTags(query, rating, sort),
            limit = PAGE_SIZE,
            page = pageIndex + 1,
        )
        // Current API: a bare array. Older API: {"posts": [...]}. Anything else is an error payload.
        val array = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject && root.asJsonObject.get("posts")?.isJsonArray == true ->
                root.asJsonObject.getAsJsonArray("posts")
            else -> error("e621: ${serverMessage(root) ?: "unexpected response"}")
        }
        return array.mapNotNull { el ->
            el.takeIf { it.isJsonObject }?.let { runCatching { e621PostFrom(it.asJsonObject) }.getOrNull() }
        }
    }

    private suspend fun fetchRule34(query: String, rating: Rating, pageIndex: Int): List<Post> {
        val credentials = rule34Credentials()
            ?: error("Rule34 now requires a free account API key - add your user ID and API key in Settings.")
        // Rule34's dapi does not reliably support order:/sort: meta tags, so sort is e621-only.
        val body = NetworkModule.rule34Api.posts(
            page = "dapi",
            s = "post",
            q = "index",
            json = 1,
            tags = buildTags(query, rating, sort = null),
            limit = PAGE_SIZE,
            pid = pageIndex,
            userId = credentials.userId,
            apiKey = credentials.apiKey,
        ).use { it.string() }
        if (body.isBlank()) return emptyList()
        val root = JsonParser.parseString(body)
        if (!root.isJsonArray) {
            val message = serverMessage(root) ?: "unexpected response"
            error(
                if (message.contains("auth", ignoreCase = true)) {
                    "Rule34 rejected your API key - double-check the user ID and API key in Settings."
                } else {
                    "Rule34: $message"
                },
            )
        }
        return root.asJsonArray.mapNotNull { el ->
            runCatching { gson.fromJson(el, Rule34Post::class.java).toPost() }.getOrNull()
        }
    }

    /** A human-readable message out of an error payload: a bare JSON string, or a message/reason field. */
    private fun serverMessage(root: JsonElement): String? = when {
        root.isJsonPrimitive -> root.asString
        root.isJsonObject -> root.asJsonObject.str("message") ?: root.asJsonObject.str("reason")
        else -> null
    }

    /**
     * Only accept a "lighter" candidate URL if it's actually the same kind of media as the
     * original - sites commonly point their sample/preview field at a static JPEG frame for
     * video posts, which would otherwise get handed to the video player as if it were the video.
     */
    private fun pickViewUrl(originalUrl: String, mediaKind: MediaKind, candidate: String?): String {
        if (candidate.isNullOrBlank()) return originalUrl
        return if (Post.mediaKindFor(candidate) == mediaKind) candidate else originalUrl
    }

    private fun e621PostFrom(o: JsonObject): Post? {
        val id = o.get("id")?.takeIf { it.isJsonPrimitive }?.asLong ?: return null
        val files = o.obj("files")
        val url: String
        val width: Int
        val height: Int
        val preview: String
        val viewCandidate: String?
        if (files != null) {
            val original = files.obj("original")
            url = original?.str("url") ?: return null
            width = original?.int("width") ?: 0
            height = original?.int("height") ?: 0
            val imageSample = files.obj("sample")?.let { it.str("jpg") ?: it.str("webp") }
            preview = files.obj("preview")?.let { it.str("jpg") ?: it.str("webp") } ?: imageSample ?: url
            // Videos now ship H.264 MP4 encodes alongside the (often VP9/AV1 WebM) original. H.264
            // is hardware-decoded on every Android phone, so these play reliably and load faster.
            val video = files.obj("video")
            val videoSample = video?.obj("samples")?.let { s -> s.obj("720p")?.str("url") ?: s.obj("480p")?.str("url") }
                ?: video?.obj("variants")?.obj("mp4")?.str("url")
            viewCandidate = videoSample ?: imageSample
        } else {
            // Older layout, kept in case e621 serves it from some mirrors or reverts.
            val file = o.obj("file")
            val sample = o.obj("sample")
            url = file?.str("url") ?: sample?.str("url") ?: return null
            width = file?.int("width") ?: 0
            height = file?.int("height") ?: 0
            preview = o.obj("preview")?.str("url") ?: sample?.str("url") ?: url
            viewCandidate = sample?.takeIf { it.get("has")?.asBoolean == true }?.str("url")
        }
        val mediaKind = Post.mediaKindFor(url)
        return Post(
            id = "e621_$id",
            source = Source.E621,
            previewUrl = preview,
            fileUrl = url,
            viewUrl = pickViewUrl(url, mediaKind, viewCandidate),
            width = width,
            height = height,
            tags = e621Tags(o.get("tags")),
            ratingLabel = o.str("rating").orEmpty(),
            score = o.obj("stats")?.obj("score")?.int("total") ?: o.obj("score")?.int("total") ?: 0,
            mediaKind = mediaKind,
        )
    }

    /** Current API: a flat string list. Older API: an object of per-category lists. */
    private fun e621Tags(el: JsonElement?): List<String> = when {
        el == null || el.isJsonNull -> emptyList()
        el.isJsonArray -> el.asJsonArray.mapNotNull { it.takeIf { t -> t.isJsonPrimitive }?.asString }
        el.isJsonObject -> listOf("general", "character", "species", "copyright", "artist").flatMap { category ->
            el.asJsonObject.get(category)?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { it.takeIf { t -> t.isJsonPrimitive }?.asString }
                .orEmpty()
        }
        else -> emptyList()
    }

    private fun Rule34Post.toPost(): Post? {
        val url = file_url ?: sample_url ?: return null
        val preview = preview_url ?: sample_url ?: url
        val mediaKind = Post.mediaKindFor(url)
        // Rule34's dapi has no "is this actually a lighter encode" flag, so just prefer
        // sample_url whenever it differs from the original file (pickViewUrl still guards
        // against it being a still-frame JPEG for a video post).
        val sampleCandidate = sample_url?.takeIf { it != url }
        return Post(
            id = "r34_$id",
            source = Source.RULE34,
            previewUrl = preview,
            fileUrl = url,
            viewUrl = pickViewUrl(url, mediaKind, sampleCandidate),
            width = width ?: 0,
            height = height ?: 0,
            tags = tags.orEmpty().split(" ").filter { it.isNotBlank() },
            ratingLabel = rating.orEmpty(),
            score = score ?: 0,
            mediaKind = mediaKind,
        )
    }
}

private fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.str(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

private fun JsonObject.int(name: String): Int? =
    get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }
