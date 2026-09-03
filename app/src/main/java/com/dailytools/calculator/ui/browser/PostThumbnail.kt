package com.dailytools.calculator.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.dailytools.calculator.data.model.MediaKind
import com.dailytools.calculator.data.model.Post

@Composable
fun PostThumbnail(
    post: Post,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isLiked: Boolean = false,
    onToggleLike: (() -> Unit)? = null,
) {
    val ratio = post.aspectRatio.coerceIn(0.5f, 2f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = post.previewUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (post.mediaKind == MediaKind.VIDEO) {
            Icon(
                imageVector = Icons.Filled.PlayCircle,
                contentDescription = "Video",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(36.dp)
                    .padding(4.dp),
            )
        }
        if (onToggleLike != null) {
            IconButton(
                onClick = onToggleLike,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.35f)),
            ) {
                Icon(
                    imageVector = if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (isLiked) "Unlike" else "Like",
                    tint = if (isLiked) Color(0xFFFF5C8A) else Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
