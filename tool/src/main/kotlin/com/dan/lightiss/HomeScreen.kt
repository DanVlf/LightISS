package com.dan.lightiss

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.designVerticalPxToSp
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.datetime.TimeZone

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, IssViewModel>(sealedActivity) {

    override val viewModelClass: Class<IssViewModel>
        get() = IssViewModel::class.java

    override fun createViewModel(): IssViewModel = IssViewModel(lightContext.dataStore)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()
        val land = remember {
            WorldMapData.parse(lightContext.readAsset(WORLD_MAP_ASSET))
        }
        var recenterTick by remember { mutableIntStateOf(0) }

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(
                            icon = LightIcons.CROSSHAIR,
                            onClick = { openLocationPicker() },
                            contentDescription = "Choose location",
                        ),
                        center = LightTopBarCenter.TwoLineDetail(
                            line1 = state.place.name,
                            line2 = "ISS TRACKER",
                            onClick = { openLocationPicker() },
                        ),
                        rightButton = LightBarButton.Text(
                            text = state.statusLabel(),
                            onClick = null,
                        ),
                        modifier = Modifier.padding(bottom = 0.25f.gridUnitsAsDp()),
                    )

                    WorldMap(
                        land = land,
                        place = state.place,
                        iss = state.iss,
                        orbit = state.orbit,
                        rangeKm = state.rangeKm,
                        nowSec = state.nowSec,
                        recenterTick = recenterTick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(MAP_WEIGHT)
                            .padding(horizontal = 0.5f.gridUnitsAsDp()),
                    )

                    TrackerStats(
                        state = state,
                        modifier = Modifier
                            .weight(1f - MAP_WEIGHT)
                            .fillMaxWidth(),
                    )

                    LightBottomBar(
                        items = listOf(
                            LightBarButton.LightIcon(
                                icon = LightIcons.REFRESH,
                                onClick = {
                                    recenterTick++
                                    viewModel.manualRefresh()
                                },
                                contentDescription = "Refresh",
                            ),
                            LightBarButton.Text(
                                text = "PASSES",
                                onClick = {
                                    navigateTo(screenFactory = {
                                        PassesScreen(it, viewModel)
                                    })
                                },
                            ),
                            LightBarButton.LightIcon(
                                icon = LightIcons.MAP,
                                onClick = { recenterTick++ },
                                contentDescription = "Recenter map",
                            ),
                        ),
                    )
                }

                state.error?.let { message ->
                    LightFullscreenModal(
                        message = message,
                        onClose = viewModel::dismissError,
                    )
                }
            }
        }
    }

    private fun openLocationPicker() {
        navigateTo(screenFactory = { LocationScreen(it, viewModel) })
    }

    private companion object {
        const val MAP_WEIGHT = 0.42f
        const val WORLD_MAP_ASSET = "world_map.txt"
    }
}

@Composable
private fun TrackerStats(
    state: IssUiState,
    modifier: Modifier = Modifier,
) {
    val timeZone = remember { TimeZone.currentSystemDefault() }
    val iss = state.iss

    LightScrollView(
        modifier = modifier.padding(top = 0.5f.gridUnitsAsDp()),
    ) {
        Column(
            modifier = Modifier.padding(
                start = 1f.gridUnitsAsDp(),
                end = 1f.gridUnitsAsDp(),
            ),
        ) {
            if (iss == null) {
                StatLabel("TRACKING")
                StatValue("waiting for ISS data...")
            } else {
                StatRow(
                    leftLabel = "DISTANCE",
                    leftValue = IssFormatting.formatKm(
                        GeoMath.haversineKm(
                            state.place.lat,
                            state.place.lng,
                            iss.latitude,
                            iss.longitude,
                        ),
                    ),
                    rightLabel = "BEARING",
                    rightValue = bearingLabel(state.place, iss),
                )
                StatRow(
                    leftLabel = "ALTITUDE",
                    leftValue = IssFormatting.formatKm(iss.altitude),
                    rightLabel = "SPEED",
                    rightValue = IssFormatting.formatKmh(iss.velocity),
                )
                StatFullRow(
                    label = "NEXT APPROACH",
                    value = state.closest?.let { closest ->
                        val day = IssFormatting.dayAndTime(closest.timestampSec, timeZone, state.nowSec)
                        val suffix = if (closest.inRange) " in range" else ""
                        "$day ${IssFormatting.formatKm(closest.distanceKm)}$suffix"
                    } ?: "--",
                )
                StatFullRow(
                    label = "UPDATED",
                    value = updatedAtLabel(iss, state.nowSec, timeZone),
                    lighten = true,
                )
            }
        }
    }
}

@Composable
private fun StatRow(
    leftLabel: String,
    leftValue: String,
    rightLabel: String,
    rightValue: String,
) {
    Row(modifier = Modifier.padding(bottom = 0.4f.gridUnitsAsDp())) {
        Column(modifier = Modifier.weight(1f)) {
            StatLabel(leftLabel)
            StatValue(leftValue)
        }
        Column(modifier = Modifier.weight(1f)) {
            StatLabel(rightLabel)
            StatValue(rightValue)
        }
    }
}

@Composable
private fun StatFullRow(
    label: String,
    value: String,
    lighten: Boolean = false,
) {
    Column(modifier = Modifier.padding(bottom = 0.4f.gridUnitsAsDp())) {
        StatLabel(label)
        StatValue(value, lighten = lighten)
    }
}

@Composable
private fun StatLabel(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Superfine,
        lighten = true,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun StatValue(text: String, lighten: Boolean = false) {
    val base = LightThemeTokens.typography.copy
    val style = TextStyle(
        fontSize = base.fontSize.value.designVerticalPxToSp(),
        lineHeight = (base.fontSize.value * 1.15f).designVerticalPxToSp(),
        fontWeight = FontWeight.Bold,
    )
    Text(
        text = text,
        style = style,
        color = if (lighten) {
            LightThemeTokens.colors.contentSecondary
        } else {
            LightThemeTokens.colors.content
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
private fun bearingLabel(place: Place, iss: IssPosition): String {
    val bearing = GeoMath.bearingDegrees(
        place.lat,
        place.lng,
        iss.latitude,
        iss.longitude,
    )
    return "${GeoMath.cardinalDirection(bearing)} ${Math.round(bearing)}°"
}

private fun updatedAtLabel(iss: IssPosition, nowSec: Long, timeZone: TimeZone): String {
    val age = (nowSec - iss.timestamp).coerceAtLeast(0L)
    return "${IssFormatting.clockTime(iss.timestamp, timeZone)} · ${IssFormatting.ageLabel(age)}"
}
