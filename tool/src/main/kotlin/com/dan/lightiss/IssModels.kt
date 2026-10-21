package com.dan.lightiss

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class IssPosition(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 0.0,
    val velocity: Double = 0.0,
    val visibility: String = "",
    val footprint: Double = 0.0,
    val timestamp: Long = 0L,
) {
    fun isValid(): Boolean =
        latitude in -90.0..90.0 && longitude in -180.0..180.0 && timestamp > 0L

    companion object {
        val ISS_SATELLITE_ID = 25544L
    }
}

@Serializable
data class Place(
    val name: String,
    val lat: Double,
    val lng: Double,
) {
    fun isValid(): Boolean =
        name.isNotBlank() && lat in -90.0..90.0 && lng in -180.0..180.0

    fun sameLocation(other: Place): Boolean =
        kotlin.math.abs(lat - other.lat) < 0.01 && kotlin.math.abs(lng - other.lng) < 0.01
}

data class ClosestApproach(
    val timestampSec: Long,
    val distanceKm: Double,
    val inRange: Boolean,
)

data class IssPass(
    val startSec: Long,
    val endSec: Long,
    val maxAtSec: Long,
    val maxElevationDeg: Double,
    val minDistanceKm: Double,
    val visual: Boolean,
) {
    val durationSec: Long
        get() = endSec - startSec
}

@Serializable
internal data class WhereTheIssResponse(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitude: Double = 0.0,
    val velocity: Double = 0.0,
    val visibility: String = "",
    val footprint: Double = 0.0,
    val timestamp: Long = 0L,
) {
    fun toIssPosition(): IssPosition = IssPosition(
        latitude = latitude,
        longitude = longitude,
        altitude = altitude,
        velocity = velocity,
        visibility = visibility,
        footprint = footprint,
        timestamp = timestamp,
    )
}

@Serializable
internal data class NominatimResult(
    @SerialName("display_name") val displayName: String = "",
    val lat: String = "",
    val lon: String = "",
)
