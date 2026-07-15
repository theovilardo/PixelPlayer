package com.theveloper.pixelplay.presentation.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MediumExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.components.subcomps.SineWaveLine
import com.theveloper.pixelplay.ui.theme.ExpTitleTypography
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

data class ChangelogSection(
    @StringRes val titleRes: Int,
    @ArrayRes val itemsRes: Int
)

data class ChangelogVersion(
    val version: String,
    val date: String,
    val sections: List<ChangelogSection>
)

@Composable
private fun changelogVersions(): List<ChangelogVersion> = listOf(
    ChangelogVersion(
        version = "0.7.5-beta",
        date = "2026-06-13",
        sections = listOf(
            ChangelogSection(R.string.changelog_sec_whats_new, R.array.changelog_075_whats_new),
            ChangelogSection(R.string.changelog_sec_improvements, R.array.changelog_075_improvements),
            ChangelogSection(R.string.changelog_sec_fixes, R.array.changelog_075_fixes)
        )
    ),
    ChangelogVersion(
        version = "0.7.0-beta",
        date = "2026-05-25",
        sections = listOf(
            ChangelogSection(R.string.changelog_sec_whats_new, R.array.changelog_070_whats_new),
            ChangelogSection(R.string.changelog_sec_improvements, R.array.changelog_070_improvements),
            ChangelogSection(R.string.changelog_sec_fixes, R.array.changelog_070_fixes),
            ChangelogSection(R.string.changelog_sec_added, R.array.changelog_070_added)
        )
    ),
    ChangelogVersion(
        version = "0.6.0-beta",
        date = "2026-03-05",
        sections = listOf(
            ChangelogSection(R.string.changelog_sec_whats_new, R.array.changelog_060_whats_new),
            ChangelogSection(R.string.changelog_sec_improvements, R.array.changelog_060_improvements),
            ChangelogSection(R.string.changelog_sec_fixes, R.array.changelog_060_fixes)
        )
    ),
    ChangelogVersion(
        version = "0.5.0-beta",
        date = "2026-01-14",
        sections = listOf(
            ChangelogSection(R.string.changelog_sec_improvements, R.array.changelog_050_improvements),
            ChangelogSection(R.string.changelog_sec_fixes, R.array.changelog_050_fixes)
        )
    ),
    ChangelogVersion(
        version = "0.4.0-beta",
        date = "2025-12-15",
        sections = listOf(
            ChangelogSection(R.string.changelog_sec_improvements, R.array.changelog_040_improvements)
        )
    ),
    ChangelogVersion(
        version = "0.3.0-beta",
        date = "2025-10-28",
        sections = listOf(
            ChangelogSection(R.string.changelog_sec_whats_new, R.array.changelog_030_whats_new),
            ChangelogSection(R.string.changelog_sec_improvements, R.array.changelog_030_improvements),
            ChangelogSection(R.string.changelog_sec_fixes, R.array.changelog_030_fixes)
        )
    ),
    ChangelogVersion(
        version = "0.2.0-beta",
        date = "2024-09-15",
        sections = listOf(
            ChangelogSection(R.string.changelog_sec_added, R.array.changelog_020_added),
            ChangelogSection(R.string.changelog_sec_improvements, R.array.changelog_020_improvements),
            ChangelogSection(R.string.changelog_sec_fixes, R.array.changelog_020_fixes)
        )
    )
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChangelogBottomSheet(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val changelogUrl = "https://github.com/theovilardo/PixelPlayer/blob/master/CHANGELOG.md"
    val changelog = changelogVersions()

    val fabCornerRadius = 16.dp

    Box(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.changelog_title),
                fontFamily = GoogleSansRounded,
                style = ExpTitleTypography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(16.dp))

            SineWaveLine(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally)
                    .height(32.dp)
                    .padding(horizontal = 8.dp)
                    .padding(bottom = 4.dp),
                animate = true,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                alpha = 0.95f,
                strokeWidth = 4.dp,
                amplitude = 4.dp,
                wavesDensity = 7.6f,
                phase = 0f
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                contentPadding = PaddingValues(bottom = 120.dp)
            ) {
                items(changelog, key = { it.version }) { version ->
                    ChangelogVersionItem(version = version)
                }
            }
        }

        MediumExtendedFloatingActionButton(
            onClick = { openUrl(context, changelogUrl) },
            shape = AbsoluteSmoothCornerShape(
                cornerRadiusBR = fabCornerRadius,
                smoothnessAsPercentBR = 60,
                cornerRadiusBL = fabCornerRadius,
                smoothnessAsPercentBL = 60,
                cornerRadiusTR = fabCornerRadius,
                smoothnessAsPercentTR = 60,
                cornerRadiusTL = fabCornerRadius,
                smoothnessAsPercentTL = 60
            ),
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            icon = {
                Icon(
                    painter = painterResource(id = R.drawable.github),
                    contentDescription = null
                )
            },
            text = { Text(text = stringResource(R.string.changelog_view_github)) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(horizontal = 24.dp, vertical = 24.dp)
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(30.dp)
                .background(
                    brush = Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    )
                )
        ) {

        }
    }
}

@Composable
fun ChangelogVersionItem(version: ChangelogVersion) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VersionBadge(versionNumber = version.version)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = version.date,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            version.sections.forEach { section ->
                ChangelogCategory(section = section)
            }
        }
    }
}

@Composable
fun ChangelogCategory(section: ChangelogSection) {
    val items = stringArrayResource(section.itemsRes).toList()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(22.dp),
        tonalElevation = 6.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(section.titleRes),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            items.forEachIndexed { index, item ->
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .size(8.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                    )
                    val linkColor = MaterialTheme.colorScheme.primary
                    val annotatedText = buildAnnotatedString {
                        val mentionRegex = Regex("@(\\w+)")
                        var lastIndex = 0
                        mentionRegex.findAll(item).forEach { match ->
                            append(item.substring(lastIndex, match.range.first))
                            val username = match.groupValues[1]
                            withLink(
                                LinkAnnotation.Url(
                                    url = "https://github.com/$username",
                                    styles = TextLinkStyles(
                                        style = SpanStyle(color = linkColor)
                                    )
                                )
                            ) {
                                append(match.value)
                            }
                            lastIndex = match.range.last + 1
                        }
                        if (lastIndex < item.length) {
                            append(item.substring(lastIndex))
                        }
                    }
                    Text(
                        text = annotatedText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (index != items.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                    )
                }
            }
        }
    }
}

@Composable
fun VersionBadge(
    versionNumber: String
) {
    Box(
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = CircleShape
            )
    ) {
        Text(
            modifier = Modifier.padding(vertical = 6.dp, horizontal = 12.dp),
            text = versionNumber,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun openUrl(context: Context, url: String) {
    val uri = try { url.toUri() } catch (_: Throwable) { url.toUri() }
    val intent = Intent(Intent.ACTION_VIEW, uri)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
    }
}
