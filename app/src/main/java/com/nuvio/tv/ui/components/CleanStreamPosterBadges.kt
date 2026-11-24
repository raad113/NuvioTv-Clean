package com.nuvio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.nuvio.tv.core.cleanstream.CleanStreamBadges
import com.nuvio.tv.core.cleanstream.CleanStreamSettings
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview

private val CleanTagColor = Color(0xFF2BB673)
private val ReleaseTagColor = Color(0xE6101820)

/**
 * NuvioTv-Clean: small tags in the corner of any poster: "✓ CLEAN" when the
 * title has confirmed skips, and the digital release status for movies.
 * Posters from the CleanStream add-on already have these drawn in, so they
 * are left alone.
 */
@Composable
fun CleanStreamPosterBadges(item: MetaPreview, modifier: Modifier = Modifier) {
    val config by CleanStreamSettings.flow.collectAsState()
    if (!config.enabled) return
    val serverHost = config.serverUrl.substringAfter("://").substringBefore('/')
    if (serverHost.isNotBlank() && item.poster?.contains(serverHost) == true) return

    val imdbId = badgeKeyFor(item) ?: return
    LaunchedEffect(imdbId) { CleanStreamBadges.request(imdbId) }

    val badges by CleanStreamBadges.badges.collectAsState()
    val badge = badges[imdbId] ?: return
    val release = if (item.type == ContentType.MOVIE) CleanStreamBadges.releaseText(badge) else null
    if (!badge.clean && release == null) return

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (badge.clean) Tag("✓ CLEAN", CleanTagColor)
        if (release != null) Tag(release, ReleaseTagColor)
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

@Composable
private fun Tag(text: String, background: Color) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = Modifier
            .background(background, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}
