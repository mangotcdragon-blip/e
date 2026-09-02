package com.dailytools.calculator.data.repo

import com.dailytools.calculator.data.model.MediaKind
import com.dailytools.calculator.data.model.MediaTypeFilter
import com.dailytools.calculator.data.model.Post
import com.dailytools.calculator.data.model.Rating
import com.dailytools.calculator.data.model.Source
import com.dailytools.calculator.data.model.SortOrder
import com.dailytools.calculator.data.network.E621Post
import com.dailytools.calculator.data.network.NetworkModule
import com.dailytools.calculator.data.network.Rule34Post
import java.net.URLEncoder

/** Posts per page, per the desired browsing density. */
const val PAGE_SIZE = 200

class BooruRepository {

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
        val tags = buildTags(query, rating, sort)
        val response = NetworkModule.e621Api.posts(
            tags = tags,
            limit = PAGE_SIZE,
            page = pageIndex + 1,
        )
        return response.posts.orEmpty().mapNotNull { it.toPost() }
    }

    private suspend fun fetchRule34(query: String, rating: Rating, pageIndex: Int): List<Post> {
        // Rule34's dapi does not reliably support order:/sort: meta tags, so sort is e621-only.
        val tags = buildTags(query, rating, sort = null)
        val response = NetworkModule.rule34Api.posts(
            page = "dapi",
            s = "post",
            q = "index",
            json = 1,
            tags = tags,
            limit = PAGE_SIZE,
            pid = pageIndex,
        )
        return response.orEmpty().mapNotNull { it.toPost() }
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

    private fun E621Post.toPost(): Post? {
        val url = file?.url ?: sample?.url ?: return null
        val preview = preview?.url ?: sample?.url ?: url
        val mediaKind = Post.mediaKindFor(url)
        val sampleCandidate = sample?.takeIf { it.has == true }?.url
        return Post(
            id = "e621_$id",
            source = Source.E621,
            previewUrl = preview,
            fileUrl = url,
            viewUrl = pickViewUrl(url, mediaKind, sampleCandidate),
            width = file?.width ?: 0,
            height = file?.height ?: 0,
            tags = tags?.flatten().orEmpty(),
            ratingLabel = rating.orEmpty(),
            score = score?.total ?: 0,
            mediaKind = mediaKind,
        )
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
