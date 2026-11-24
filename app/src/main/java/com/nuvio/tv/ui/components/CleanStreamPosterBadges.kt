package com.nuvio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.core.cleanstream.CleanStreamBadges
import com.nuvio.tv.core.cleanstream.CleanStreamSettings
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview

// Same look as the pale "#17 Today" tabs that catalog posters carry at the top
private val TabBackground = Color(0xF2D4F1EC)
private val TabText = Color(0xFF16353A)
private val ShieldColor = Color(0xFF0F8A6C)

/**
 * NuvioTv-Clean: a small tab on the bottom edge of any poster, in the style of
 * the "#17 Today" tabs at the top. It shows a shield when the title has
 * confirmed skips, and for movies the digital release ("Digital · Mar 2024",
 * "Digital 12 Dec", "In Cinemas"). Posters from the CleanStream add-on already
 * have these drawn in, so they are left alone.
 */
@Composable
fun CleanStreamPosterBadges(item: MetaPreview, modifier: Modifier = Modifier) {
    val config by CleanStreamSettings.flow.collectAsState()
    if (!config.enabled) return
    val serverHost = config.serverUrl.substringAfter("://").substringBefore('/')
    if (serverHost.isNotBlank() && item.poster?.contains(serverHost) == true) return

    val key = badgeKeyFor(item) ?: return
    LaunchedEffect(key) { CleanStreamBadges.request(key) }

    val badges by CleanStreamBadges.badges.collectAsState()
    val badge = badges[key] ?: return
    val release = if (item.type == ContentType.MOVIE) CleanStreamBadges.releaseText(badge) else null
    val text = release ?: if (badge.clean) "Clean" else return

    Row(
        modifier = modifier
            .background(TabBackground, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (badge.clean) {
            Icon(
                imageVector = Icons.Filled.Shield,
                contentDescription = "Clean version available",
                tint = ShieldColor,
                modifier = Modifier.size(12.dp),
            )
        }
        Text(text = text, color = TabText, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/**
 * The id the server knows the title by: its IMDb id, or for catalogs that
 * use TMDB numbers (ids like "tmdb:603"), tmdb:movie:603 / tmdb:tv:603.
 */
internal fun badgeKeyFor(item: MetaPreview): String? {
    listOfNotNull(item.imdbId, item.id)
        .map { it.substringBefore(':') }
        .firstOrNull { it.matches(Regex("tt\\d+")) }
        ?.let { return it }
    val tmdb = Regex("^tmdb:(\\d+)").find(item.id)?.groupValues?.get(1) ?: return null
    val kind = if (item.type == ContentType.MOVIE) "movie" else "tv"
    return "tmdb:$kind:$tmdb"
}
