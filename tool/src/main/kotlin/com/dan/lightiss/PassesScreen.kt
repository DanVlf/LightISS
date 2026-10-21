package com.dan.lightiss

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.datetime.TimeZone

/** Upcoming radio and visible passes over the chosen place. */
class PassesScreen(
    sealedActivity: SealedLightActivity,
    private val issViewModel: IssViewModel,
) : SimpleLightScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by issViewModel.uiState.collectAsState()
        val timeZone = remember { TimeZone.currentSystemDefault() }

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                        contentDescription = "Back",
                    ),
                    center = LightTopBarCenter.Text(state.place.name),
                    modifier = Modifier.padding(bottom = 0.25f.gridUnitsAsDp()),
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    if (state.passes.isEmpty()) {
                        LightText(
                            text = if (state.loaded) {
                                "No upcoming passes over ${state.place.name} " +
                                    "in the prediction window.\n\nSync again for a longer window."
                            } else {
                                "Loading pass predictions..."
                            },
                            variant = LightTextVariant.Copy,
                            align = TextAlign.Center,
                            lighten = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 2f.gridUnitsAsDp())
                                .padding(top = 3f.gridUnitsAsDp()),
                        )
                    } else {
                        LightScrollView(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 1f.gridUnitsAsDp()),
                        ) {
                            state.passes.forEach { pass ->
                                PassRow(pass, timeZone, state.nowSec)
                            }
                        }
                    }
                }

                LightBottomBar(
                    items = listOf(
                        LightBarButton.Text(
                            text = "SYNC",
                            onClick = issViewModel::manualRefresh,
                        ),
                        null,
                        null,
                    ),
                )
            }
        }
    }
}

@Composable
private fun PassRow(
    pass: IssPass,
    timeZone: TimeZone,
    nowSec: Long,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 1f.gridUnitsAsDp()),
    ) {
        LightText(
            text = passTimeRange(pass, timeZone, nowSec),
            variant = LightTextVariant.Copy,
        )
        LightText(
            text = passDetails(pass, timeZone, nowSec),
            variant = LightTextVariant.Detail,
            lighten = true,
        )
        LightText(
            text = if (pass.visual) "VISIBLE" else "NOT VISIBLE",
            variant = LightTextVariant.Superfine,
            lighten = true,
        )
    }
}

private fun passTimeRange(pass: IssPass, timeZone: TimeZone, nowSec: Long): String {
    val start = IssFormatting.dayAndTime(pass.startSec, timeZone, nowSec)
    val end = IssFormatting.clockTime(pass.endSec, timeZone)
    return "$start-$end"
}

private fun passDetails(pass: IssPass, timeZone: TimeZone, nowSec: Long): String {
    val duration = IssFormatting.durationLabel(pass.durationSec)
    val quality = IssFormatting.passQuality(pass)
    val maxAt = IssFormatting.clockTime(pass.maxAtSec, timeZone)
    val elevation = Math.round(pass.maxElevationDeg).toString() + "°"
    return "$duration · $quality · max $maxAt · $elevation"
}
