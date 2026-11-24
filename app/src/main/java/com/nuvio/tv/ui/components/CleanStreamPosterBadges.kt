package com.nuvio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.outlined.Shield as OutlinedShield
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.core.cleanstream.CleanStreamBadges
import com.nuvio.tv.core.cleanstream.CleanStreamSettings
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview

// Same look as the pale "#17 Today" tabs that catalog posters carry at the top
private val TabBackground = Color(0xF2D4F1EC)
private val TabText = Color(0xFF16353A)
private val ShieldBackground = Color(0xCC101820)
private val ShieldConfirmed = Color(0xFF78ECC8) // the mint of the Nuvio Clean logo
private val ShieldUnconfirmed = Color(0xB3FFFFFF)

/**
 * NuvioTv-Clean: a small tab on the bottom edge of a movie poster with its
 * digital release ("Digital · Mar 2024", "Digital 12 Dec", "In Cinemas"), in
 * the style of the "#17 Today" tabs at the top. Posters from the CleanStream
 * add-on already have this drawn in, so they are left alone.
 */
@Composable
fun CleanStreamPosterBadges(item: MetaPreview, modifier: Modifier = Modifier) {
    val badge = rememberCleanBadge(item) ?: return
    if (item.type != ContentType.MOVIE) return
    val text = CleanStreamBadges.releaseText(badge) ?: return
    Text(
        text = text,
        color = TabText,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = modifier
            .background(TabBackground, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * NuvioTv-Clean: a small shield for the corner of a poster. Solid mint when
 * the title has confirmed skips, a faded outline when it only has unconfirmed
 * ones, nothing otherwise.
 */
@Composable
fun CleanStreamPosterShield(item: MetaPreview, modifier: Modifier = Modifier) {
    val badge = rememberCleanBadge(item) ?: return
    if (!badge.clean && !badge.hasUnconfirmed) return
    Box(
        modifier = modifier.size(16.dp).background(ShieldBackground, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (badge.clean) Icons.Filled.Shield else Icons.Outlined.OutlinedShield,
            contentDescription = if (badge.clean) "Clean version available" else "Unconfirmed skips available",
            tint = if (badge.clean) ShieldConfirmed else ShieldUnconfirmed,
            modifier = Modifier.size(10.dp),
        )
    }
}

/** This poster's tags from the server, or null when none apply or they are not loaded yet. */
@Composable
private fun rememberCleanBadge(item: MetaPreview): CleanStreamBadges.Badge? {
    val config by CleanStreamSettings.flow.collectAsState()
    if (!config.enabled) return null
    val serverHost = config.serverUrl.substringAfter("://").substringBefore('/')
    if (serverHost.isNotBlank() && item.poster?.contains(serverHost) == true) return null
    val key = badgeKeyFor(item) ?: return null
    LaunchedEffect(key) { CleanStreamBadges.request(key) }
    val badges by CleanStreamBadges.badges.collectAsState()
    return badges[key]
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
