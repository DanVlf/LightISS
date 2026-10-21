package com.dan.lightiss

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlin.math.abs

/** Equirectangular camera: center point plus visible longitude span. */
data class MapCamera(
    val centerLat: Double,
    val centerLng: Double,
    val lngSpanDeg: Double,
)

private const val MIN_SPAN_DEG = 25.0
private const val MAX_SPAN_DEG = 360.0
private const val TRACK_PAST_SEC = 900L
private const val TRACK_FUTURE_SEC = 5400L
private const val STALE_POSITION_SEC = 600L
private const val ORBIT_STEP_SEC = 300L
private const val DEFAULT_ASPECT = 2.4f

/** Centers the map on the place, zoomed to include the ISS and its footprint. */
fun fitCamera(place: Place, iss: IssPosition?, aspect: Double): MapCamera {
    if (iss == null || !iss.isValid()) {
        return MapCamera(place.lat.coerceIn(-85.0, 85.0), place.lng, MAX_SPAN_DEG)
    }
    val dLng = abs(GeoMath.wrapLongitude(iss.longitude - place.lng))
    val dLat = abs(iss.latitude - place.lat)
    val spanFromLng = dLng * 1.45 + 26.0
    val spanFromLat = (dLat * 1.45 + 16.0) * aspect
    val span = maxOf(spanFromLng, spanFromLat, 45.0).coerceAtMost(MAX_SPAN_DEG)
    val centerLat = ((place.lat + iss.latitude) / 2.0).coerceIn(-85.0, 85.0)
    val centerLng = place.lng + GeoMath.wrapLongitude(iss.longitude - place.lng) / 2.0
    return MapCamera(centerLat, GeoMath.wrapLongitude(centerLng), span)
}

/**
 * A monochrome, pan-and-zoom world map with the ISS ground track, radio
 * footprint, observer place and current station position. Tapping the map
 * recenters it on the place (and the station, once known).
 */
@Composable
fun WorldMap(
    land: List<WorldMapData.LandPolygon>,
    place: Place,
    iss: IssPosition?,
    orbit: List<IssPosition>,
    rangeKm: Double,
    nowSec: Long,
    recenterTick: Int,
    modifier: Modifier = Modifier,
) {
    val colors = LightThemeTokens.colors
    var camera by remember { mutableStateOf(fitCamera(place, iss, DEFAULT_ASPECT.toDouble())) }

    BoxWithConstraints(modifier = modifier) {
        val aspect = if (maxHeight.value > 0f) {
            maxWidth.value / maxHeight.value
        } else {
            DEFAULT_ASPECT
        }

        LaunchedEffect(place, iss != null, recenterTick) {
            camera = fitCamera(place, iss, aspect.toDouble())
        }

        val displayTrack = remember(orbit, iss?.timestamp) { displayTrack(orbit, iss, nowSec) }
        val headingDeg = remember(displayTrack, iss?.timestamp) { issHeading(displayTrack, iss) }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures {
                        camera = fitCamera(place, iss, aspect.toDouble())
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val widthPx = size.width.toFloat()
                        val heightPx = size.height.toFloat()
                        val old = camera
                        val oldPpd = widthPx / old.lngSpanDeg.toFloat()

                        // geo coordinates under the gesture focal point
                        val geoLng = old.centerLng + (centroid.x - widthPx / 2f) / oldPpd
                        val geoLat = old.centerLat - (centroid.y - heightPx / 2f) / oldPpd

                        val newSpan = (old.lngSpanDeg / zoom).coerceIn(MIN_SPAN_DEG, MAX_SPAN_DEG)
                        val newPpd = widthPx / newSpan

                        var centerLng = geoLng - (centroid.x - widthPx / 2f) / newPpd - pan.x / newPpd
                        var centerLat = geoLat + (centroid.y - heightPx / 2f) / newPpd + pan.y / newPpd
                        centerLat = centerLat.coerceIn(-85.0, 85.0)
                        centerLng = GeoMath.wrapLongitude(centerLng)

                        camera = MapCamera(centerLat, centerLng, newSpan)
                    }
                },
        ) {
            val ppd = size.width / camera.lngSpanDeg.toFloat()

            fun toX(dxLng: Double): Float = size.width / 2f + (dxLng * ppd).toFloat()
            fun toY(lat: Double): Float = size.height / 2f - ((lat - camera.centerLat) * ppd).toFloat()

            drawGraticule(camera, colors.contentSecondary.copy(alpha = 0.35f), ::toX, ::toY)
            drawLand(land, colors.content, camera, ppd)

            if (displayTrack.size > 1) {
                val path = Path()
                var prevDx = 0.0
                var prevTime = 0L
                displayTrack.forEachIndexed { index, position ->
                    var dx = GeoMath.wrapLongitude(position.longitude - camera.centerLng)
                    if (index > 0) {
                        while (dx - prevDx > 180.0) dx -= 360.0
                        while (dx - prevDx < -180.0) dx += 360.0
                    }
                    prevDx = dx
                    val x = toX(dx)
                    val y = toY(position.latitude)
                    if (index == 0 || position.timestamp - prevTime > ORBIT_STEP_SEC * 3) {
                        path.moveTo(x, y)
                    } else {
                        path.lineTo(x, y)
                    }
                    prevTime = position.timestamp
                }
                drawPath(
                    path,
                    color = colors.content.copy(alpha = 0.80f),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }

            // observer place: circle with a plus
            val placeX = toX(GeoMath.wrapLongitude(place.lng - camera.centerLng))
            val placeY = toY(place.lat)
            drawCircle(
                color = colors.content,
                radius = 11f,
                center = Offset(placeX, placeY),
                style = Stroke(width = 2.5f),
            )
            drawLine(
                colors.content,
                Offset(placeX - 6f, placeY),
                Offset(placeX + 6f, placeY),
                2.5f,
            )
            drawLine(
                colors.content,
                Offset(placeX, placeY - 6f),
                Offset(placeX, placeY + 6f),
                2.5f,
            )

            if (iss != null && iss.isValid()) {
                drawFootprint(iss, rangeKm, colors.content, camera, ppd)
                val x = toX(GeoMath.wrapLongitude(iss.longitude - camera.centerLng))
                val y = toY(iss.latitude)
                val stale = nowSec - iss.timestamp > STALE_POSITION_SEC
                drawIssIcon(x, y, headingDeg, colors.content, colors.background, stale)
            }
        }
    }
}

private fun DrawScope.drawGraticule(
    camera: MapCamera,
    gridColor: Color,
    toX: (Double) -> Float,
    toY: (Double) -> Float,
) {
    val gridStroke = 1.dp.toPx()
    for (meridian in -180..180 step 30) {
        val x = toX(GeoMath.wrapLongitude(meridian.toDouble() - camera.centerLng))
        if (x in 0f..size.width) {
            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), gridStroke)
        }
    }
    for (parallel in -60..60 step 30) {
        val y = toY(parallel.toDouble())
        if (y in 0f..size.height) {
            drawLine(
                if (parallel == 0) gridColor.copy(alpha = 0.55f) else gridColor,
                Offset(0f, y),
                Offset(size.width, y),
                gridStroke,
            )
        }
    }
}

private fun DrawScope.drawLand(
    land: List<WorldMapData.LandPolygon>,
    content: Color,
    camera: MapCamera,
    ppd: Float,
) {
    val stroke = Stroke(width = 1.2.dp.toPx())
    for (polygon in land) {
        val points = polygon.points
        var minDx = Double.MAX_VALUE
        var maxDx = -Double.MAX_VALUE
        var index = 1
        while (index < points.size) {
            val dx = GeoMath.wrapLongitude(points[index].toDouble() - camera.centerLng)
            if (dx < minDx) minDx = dx
            if (dx > maxDx) maxDx = dx
            index += 2
        }
        val halfWidth = size.width / 2f
        if (maxDx * ppd + halfWidth < -20f || minDx * ppd - halfWidth > size.width + 20f) {
            continue
        }
        val path = Path()
        var first = true
        index = 0
        while (index < points.size) {
            val lat = points[index].toDouble()
            val dx = GeoMath.wrapLongitude(points[index + 1].toDouble() - camera.centerLng)
            val x = halfWidth + (dx * ppd).toFloat()
            val y = size.height / 2f - ((lat - camera.centerLat) * ppd).toFloat()
            if (first) {
                path.moveTo(x, y)
                first = false
            } else {
                path.lineTo(x, y)
            }
            index += 2
        }
        path.close()
        drawPath(path, color = content, alpha = 0.05f, style = Fill)
        drawPath(path, color = content, alpha = 0.85f, style = stroke)
    }
}

private fun DrawScope.drawFootprint(
    iss: IssPosition,
    rangeKm: Double,
    content: Color,
    camera: MapCamera,
    ppd: Float,
) {
    val path = Path()
    var prevDx = 0.0
    for (bearing in 0..360 step 5) {
        val (lat, lng) = GeoMath.destinationPoint(
            iss.latitude,
            iss.longitude,
            bearing.toDouble(),
            rangeKm,
        )
        var dx = GeoMath.wrapLongitude(lng - camera.centerLng)
        if (bearing > 0) {
            while (dx - prevDx > 180.0) dx -= 360.0
            while (dx - prevDx < -180.0) dx += 360.0
        }
        prevDx = dx
        val x = size.width / 2f + (dx * ppd).toFloat()
        val y = size.height / 2f - ((lat - camera.centerLat) * ppd).toFloat()
        if (bearing == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color = content, alpha = 0.10f, style = Fill)
    drawPath(path, color = content, alpha = 0.55f, style = Stroke(width = 1.5.dp.toPx()))
}

private fun DrawScope.drawIssIcon(
    x: Float,
    y: Float,
    headingDeg: Float,
    content: Color,
    background: Color,
    stale: Boolean,
) {
    val alpha = if (stale) 0.45f else 1f
    val arm = 11f
    val ink = content.copy(alpha = alpha)

    // halo so the icon reads over the ground track
    drawLine(background, Offset(x - arm, y - arm), Offset(x + arm, y + arm), 8f)
    drawLine(background, Offset(x + arm, y - arm), Offset(x - arm, y + arm), 8f)

    drawLine(ink, Offset(x - arm, y - arm), Offset(x + arm, y + arm), 3f)
    drawLine(ink, Offset(x + arm, y - arm), Offset(x - arm, y + arm), 3f)

    rotate(headingDeg, pivot = Offset(x, y)) {
        drawLine(ink, Offset(x, y), Offset(x, y - arm - 8f), 4f)
    }

    drawRect(
        color = ink,
        topLeft = Offset(x - 5f, y - 5f),
        size = Size(10f, 10f),
    )
}

/** Orbit samples around the current position, oldest to newest. */
private fun displayTrack(
    orbit: List<IssPosition>,
    iss: IssPosition?,
    nowSec: Long,
): List<IssPosition> {
    val center = iss?.timestamp ?: nowSec
    return orbit
        .filter {
            it.isValid() &&
                it.timestamp >= center - TRACK_PAST_SEC &&
                it.timestamp <= center + TRACK_FUTURE_SEC
        }
        .sortedBy { it.timestamp }
}

/** Heading of the ground track at the current position, degrees from north. */
private fun issHeading(track: List<IssPosition>, iss: IssPosition?): Float {
    if (track.size < 2 || iss == null) return 0f
    var closestIndex = 0
    track.forEachIndexed { index, position ->
        if (abs(position.timestamp - iss.timestamp) < abs(track[closestIndex].timestamp - iss.timestamp)) {
            closestIndex = index
        }
    }
    val first = track[maxOf(0, closestIndex - 1)]
    val second = track[minOf(track.size - 1, closestIndex + 1)]
    if (first === second) return 0f
    return GeoMath.bearingDegrees(
        first.latitude,
        first.longitude,
        second.latitude,
        second.longitude,
    ).toFloat()
}
