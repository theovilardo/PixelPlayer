package com.theveloper.pixelplay.presentation.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Highlight
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumExtendedFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.components.subcomps.SineWaveLine
import com.theveloper.pixelplay.ui.theme.ExpTitleTypography
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BetaInfoBottomSheet(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val issuesUrl = "https://github.com/theovilardo/PixelPlayer/issues"
    val reportUrl = "https://github.com/theovilardo/PixelPlayer/issues/new/choose"

    val fabCornerRadius = 18.dp

    val versionName = remember {
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "0.7.5-beta"
        } catch (_: Exception) {
            "0.7.5-beta"
        }
    }

    val versionStrTemplate = stringResource(R.string.beta_sheet_version)
    val welcomeTitleTemplate = stringResource(R.string.beta_sheet_welcome_title)

    val displayVersion = remember(versionName, versionStrTemplate) {
        val cleanVersion = versionName.substringBefore("-")
        versionStrTemplate.replace(Regex("""\d+\.\d+\.\d+"""), cleanVersion)
    }

    val welcomeTitle = remember(versionName, welcomeTitleTemplate) {
        welcomeTitleTemplate.replace(Regex("""\d+\.\d+\.\d+(?:-beta)?"""), versionName)
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            contentPadding = PaddingValues(top = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(key = "header") {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = displayVersion,
                        fontFamily = GoogleSansRounded,
                        style = ExpTitleTypography.displaySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SineWaveLine(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(26.dp)
                            .padding(horizontal = 8.dp),
                        animate = true,
                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.75f),
                        alpha = 0.95f,
                        strokeWidth = 4.dp,
                        amplitude = 4.dp,
                        wavesDensity = 7.6f,
                        phase = 0f
                    )
                }
            }

            item(key = "welcome") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = AbsoluteSmoothCornerShape(
                        cornerRadiusTR = fabCornerRadius,
                        cornerRadiusTL = fabCornerRadius,
                        cornerRadiusBL = fabCornerRadius,
                        cornerRadiusBR = fabCornerRadius,
                        smoothnessAsPercentTR = 60,
                        smoothnessAsPercentTL = 60,
                        smoothnessAsPercentBL = 60,
                        smoothnessAsPercentBR = 60
                    ),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .background(
                                    brush = Brush.linearGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                                        )
                                    ),
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.beta_sheet_letter),
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Black
                            )
                        }
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = welcomeTitle,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.beta_sheet_welcome_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item(key = "github-shortcut") {
                GitHubReportCard(
                    onOpenIssues = { launchUrl(context, issuesUrl) },
                    onReportIssue = { launchUrl(context, reportUrl) }
                )
            }

            item(key = "section-expect") {
                BetaFaqSection(
                    title = stringResource(R.string.beta_sheet_expect_title),
                    summary = stringResource(R.string.beta_sheet_expect_summary),
                    icon = Icons.Rounded.Whatshot,
                    iconTint = MaterialTheme.colorScheme.primary,
                    initiallyExpanded = true
                ) {
                    BetaBulletList(
                        items = listOf(
                            R.string.beta_sheet_expect_1,
                            R.string.beta_sheet_expect_2,
                            R.string.beta_sheet_expect_3,
                            R.string.beta_sheet_expect_4
                        )
                    )
                }
            }

            item(key = "section-reporting") {
                BetaFaqSection(
                    title = stringResource(R.string.beta_sheet_report_title),
                    summary = stringResource(R.string.beta_sheet_report_summary),
                    icon = Icons.Rounded.Search,
                    iconTint = MaterialTheme.colorScheme.secondary
                ) {
                    BetaSubsectionHeader(
                        icon = Icons.Rounded.Search,
                        title = stringResource(R.string.beta_sheet_before_title)
                    )
                    BetaBulletList(
                        items = listOf(
                            R.string.beta_sheet_before_1,
                            R.string.beta_sheet_before_2,
                            R.string.beta_sheet_before_3,
                            R.string.beta_sheet_before_4
                        )
                    )
                    BetaSectionDivider()
                    BetaSubsectionHeader(
                        icon = Icons.Rounded.CheckCircle,
                        title = stringResource(R.string.beta_sheet_issue_type_title)
                    )
                    BetaBulletList(
                        items = listOf(
                            R.string.beta_sheet_issue_type_bug,
                            R.string.beta_sheet_issue_type_feature,
                            R.string.beta_sheet_issue_type_question
                        )
                    )
                }
            }

            item(key = "section-bug-report") {
                BetaFaqSection(
                    title = stringResource(R.string.beta_sheet_bug_title),
                    summary = stringResource(R.string.beta_sheet_bug_summary_text),
                    icon = Icons.Rounded.BugReport,
                    iconTint = MaterialTheme.colorScheme.error,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    BetaSubsectionHeader(
                        icon = Icons.Rounded.BugReport,
                        title = stringResource(R.string.beta_sheet_bug_template_title)
                    )
                    BetaFieldList(
                        items = listOf(
                            R.string.beta_sheet_bug_summary,
                            R.string.beta_sheet_bug_expected,
                            R.string.beta_sheet_bug_actual,
                            R.string.beta_sheet_bug_steps,
                            R.string.beta_sheet_bug_frequency,
                            R.string.beta_sheet_bug_screenshot,
                            R.string.beta_sheet_bug_logs
                        )
                    )
                    BetaSectionDivider()
                    BetaSubsectionHeader(
                        icon = Icons.Rounded.Info,
                        title = stringResource(R.string.beta_sheet_env_title)
                    )
                    BetaFieldList(
                        items = listOf(
                            R.string.beta_sheet_env_version,
                            R.string.beta_sheet_env_source,
                            R.string.beta_sheet_env_android,
                            R.string.beta_sheet_env_device,
                            R.string.beta_sheet_env_extra
                        )
                    )
                }
            }

            item(key = "section-feature") {
                BetaFaqSection(
                    title = stringResource(R.string.beta_sheet_feature_title),
                    summary = stringResource(R.string.beta_sheet_feature_summary),
                    icon = Icons.Rounded.Highlight,
                    iconTint = MaterialTheme.colorScheme.primary
                ) {
                    BetaFieldList(
                        items = listOf(
                            R.string.beta_sheet_feature_problem,
                            R.string.beta_sheet_feature_solution,
                            R.string.beta_sheet_feature_alternatives,
                            R.string.beta_sheet_feature_scope,
                            R.string.beta_sheet_feature_mockup
                        )
                    )
                }
            }

            item(key = "section-quality") {
                BetaFaqSection(
                    title = stringResource(R.string.beta_sheet_quality_title),
                    summary = stringResource(R.string.beta_sheet_quality_summary),
                    icon = Icons.Rounded.Gavel,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
                ) {
                    BetaSubsectionHeader(
                        icon = Icons.Rounded.Gavel,
                        title = stringResource(R.string.beta_sheet_title_examples_title)
                    )
                    BetaBulletList(
                        items = listOf(
                            R.string.beta_sheet_title_example_1,
                            R.string.beta_sheet_title_example_2,
                            R.string.beta_sheet_title_example_3
                        )
                    )
                    BetaSectionDivider()
                    BetaSubsectionHeader(
                        icon = Icons.Rounded.Warning,
                        title = stringResource(R.string.beta_sheet_avoid_title),
                        tint = MaterialTheme.colorScheme.error
                    )
                    BetaBulletList(
                        items = listOf(
                            R.string.beta_sheet_avoid_1,
                            R.string.beta_sheet_avoid_2,
                            R.string.beta_sheet_avoid_3
                        )
                    )
                    BetaSectionDivider()
                    BetaSubsectionHeader(
                        icon = Icons.Rounded.Shield,
                        title = stringResource(R.string.beta_sheet_privacy_title)
                    )
                    Text(
                        text = stringResource(R.string.beta_sheet_privacy_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            item(key = "section-nightly") {
                BetaFaqSection(
                    title = stringResource(R.string.beta_sheet_nightly_title),
                    summary = stringResource(R.string.beta_sheet_nightly_summary),
                    icon = Icons.Rounded.NightsStay,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
                ) {
                    BetaSubsectionHeader(
                        icon = Icons.Rounded.NightsStay,
                        title = stringResource(R.string.beta_sheet_nightly_title)
                    )
                    Text(
                        text = stringResource(R.string.beta_sheet_nightly_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.beta_sheet_nightly_access),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    BetaSectionDivider()
                    BetaSubsectionHeader(
                        icon = Icons.Rounded.Cloud,
                        title = stringResource(R.string.beta_sheet_nightly_report_title)
                    )
                    Text(
                        text = stringResource(R.string.beta_sheet_nightly_report_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            item(key = "bottom-spacer") {
                Spacer(modifier = Modifier.height(104.dp))
            }
        }
        ExtendedFloatingActionButton(
            onClick = { launchUrl(context, reportUrl) },
            shape = AbsoluteSmoothCornerShape(
                cornerRadiusTR = fabCornerRadius,
                cornerRadiusTL = fabCornerRadius,
                cornerRadiusBL = fabCornerRadius,
                cornerRadiusBR = fabCornerRadius,
                smoothnessAsPercentTR = 60,
                smoothnessAsPercentTL = 60,
                smoothnessAsPercentBL = 60,
                smoothnessAsPercentBR = 60
            ),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            icon = {
                Icon(
                    painter = painterResource(id = R.drawable.github),
                    contentDescription = null
                )
            },
            text = {
                Text(text = stringResource(R.string.beta_sheet_report_bug))
            },
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
private fun GitHubReportCard(
    onOpenIssues: () -> Unit,
    onReportIssue: () -> Unit
) {
    BetaCardSurface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(MaterialTheme.colorScheme.surface, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.github),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.beta_sheet_github_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = stringResource(R.string.beta_sheet_github_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.82f)
                    )
                }
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilledTonalButton(
                    onClick = onOpenIssues,
                    modifier = Modifier.fillMaxWidth(),
                    shape = AbsoluteSmoothCornerShape(
                        cornerRadiusTR = 14.dp,
                        cornerRadiusTL = 14.dp,
                        cornerRadiusBL = 14.dp,
                        cornerRadiusBR = 14.dp,
                        smoothnessAsPercentTR = 60,
                        smoothnessAsPercentTL = 60,
                        smoothnessAsPercentBL = 60,
                        smoothnessAsPercentBR = 60
                    ),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Text(text = stringResource(R.string.beta_sheet_open_issues))
                }
                Button(
                    onClick = onReportIssue,
                    modifier = Modifier.fillMaxWidth(),
                    shape = AbsoluteSmoothCornerShape(
                        cornerRadiusTR = 14.dp,
                        cornerRadiusTL = 14.dp,
                        cornerRadiusBL = 14.dp,
                        cornerRadiusBR = 14.dp,
                        smoothnessAsPercentTR = 60,
                        smoothnessAsPercentTL = 60,
                        smoothnessAsPercentBL = 60,
                        smoothnessAsPercentBR = 60
                    )
                ) {
                    Text(text = stringResource(R.string.beta_sheet_report_bug))
                }
            }
        }
    }
}

@Composable
private fun BetaFaqSection(
    title: String,
    summary: String,
    icon: ImageVector,
    iconTint: Color,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }

    BetaCardSurface(
        modifier = modifier,
        color = containerColor,
        onClick = { expanded = !expanded }
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(iconTint.copy(alpha = 0.16f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content
                )
            }
        }
    }
}

@Composable
private fun BetaCardSurface(
    modifier: Modifier = Modifier,
    color: Color,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val shape = AbsoluteSmoothCornerShape(
        cornerRadiusTR = 18.dp,
        cornerRadiusTL = 18.dp,
        cornerRadiusBL = 18.dp,
        cornerRadiusBR = 18.dp,
        smoothnessAsPercentTR = 60,
        smoothnessAsPercentTL = 60,
        smoothnessAsPercentBL = 60,
        smoothnessAsPercentBR = 60
    )

    if (onClick == null) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = color,
            content = content
        )
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = color,
            onClick = onClick,
            content = content
        )
    }
}

@Composable
private fun BetaSubsectionHeader(
    icon: ImageVector,
    title: String,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun BetaBulletList(items: List<Int>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            BetaBulletText(text = stringResource(item))
        }
    }
}

@Composable
private fun BetaFieldList(items: List<Int>) {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        items.forEachIndexed { index, item ->
            if (index > 0) {
                BetaSectionDivider()
            }
            Text(
                text = stringResource(item),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun BetaBulletText(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .size(5.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BetaSectionDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
}

private fun launchUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
    }
}
