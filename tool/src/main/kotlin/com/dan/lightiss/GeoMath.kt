package com.dan.lightiss

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure spherical geometry used for distances, bearings, look angles and the
 * sun elevation. Ported from the original LightISS web prototype.
 */
object GeoMath {

    const val EARTH_RADIUS_KM = 6371.0

    fun toRadians(degrees: Double): Double = degrees * PI / 180.0

    fun toDegrees(radians: Double): Double = radians * 180.0 / PI

    /** Wraps a longitude difference into [-180, 180). */
    fun wrapLongitude(value: Double): Double {
        var wrapped = (value + 180.0) % 360.0
        if (wrapped < 0.0) wrapped += 360.0
        return wrapped - 180.0
    }

    fun haversineKm(
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double,
    ): Double {
        val dLat = toRadians(lat2 - lat1)
        val dLng = toRadians(wrapLongitude(lng2 - lng1))
        val phi1 = toRadians(lat1)
        val phi2 = toRadians(lat2)
        val h = sin(dLat / 2.0) * sin(dLat / 2.0) +
            cos(phi1) * cos(phi2) * sin(dLng / 2.0) * sin(dLng / 2.0)
        return 2.0 * EARTH_RADIUS_KM * atan2(sqrt(h), sqrt(1.0 - h))
    }

    /** Initial bearing from point 1 to point 2, degrees clockwise from north. */
    fun bearingDegrees(
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double,
    ): Double {
        val phi1 = toRadians(lat1)
        val phi2 = toRadians(lat2)
        val dLng = toRadians(wrapLongitude(lng2 - lng1))
        val y = sin(dLng) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLng)
        return (toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun cardinalDirection(degrees: Double): String {
        val directions = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val index = Math.round(degrees / 45.0).toInt() % directions.size
        return directions[index]
    }

    /**
     * Approximate look angle (elevation) of the satellite above the horizon
     * for an observer at [distanceKm] from the sub-satellite point.
     */
    fun elevationDegrees(distanceKm: Double, altitudeKm: Double): Double {
        val radius = EARTH_RADIUS_KM
        val central = distanceKm / radius
        val effectiveAltitude = maxOf(altitudeKm, 400.0)
        val top = cos(central) - radius / (radius + effectiveAltitude)
        val bottom = sin(central)
        return toDegrees(atan2(top, bottom))
    }

    /**
     * Destination point given a start, an initial bearing and a distance,
     * used to sketch the ISS radio footprint.
     */
    fun destinationPoint(
        lat: Double,
        lng: Double,
        bearingDeg: Double,
        distanceKm: Double,
    ): Pair<Double, Double> {
        val angular = distanceKm / EARTH_RADIUS_KM
        val bearing = toRadians(bearingDeg)
        val phi1 = toRadians(lat)
        val lambda1 = toRadians(lng)

        val sinPhi2 = sin(phi1) * cos(angular) + cos(phi1) * sin(angular) * cos(bearing)
        val phi2 = kotlin.math.asin(sinPhi2)
        val lambda2 = lambda1 + atan2(
            sin(bearing) * sin(angular) * cos(phi1),
            cos(angular) - sin(phi1) * sinPhi2,
        )
        val lngDest = ((toDegrees(lambda2) + 540.0) % 360.0) - 180.0
        return Pair(toDegrees(phi2), lngDest)
    }

    /**
     * Sun elevation above the horizon at [lat]/[lng] for an epoch-seconds
     * [timestamp]. Low-precision solar position, good to a fraction of a
     * degree, enough for visible/radio pass classification.
     */
    fun sunElevationDegrees(lat: Double, lng: Double, timestampSec: Long): Double {
        val julian = timestampSec / 86400.0 + 2440587.5
        val days = julian - 2451545.0

        val meanLongitude = (280.46 + 0.9856474 * days) % 360.0
        val meanAnomaly = toRadians((357.528 + 0.9856003 * days) % 360.0)
        val eclipticLongitude = toRadians(
            meanLongitude + 1.915 * sin(meanAnomaly) + 0.02 * sin(2.0 * meanAnomaly),
        )
        val obliquity = toRadians(23.439 - 0.0000004 * days)

        val rightAscension = atan2(
            cos(obliquity) * sin(eclipticLongitude),
            cos(eclipticLongitude),
        )
        val declination = kotlin.math.asin(sin(obliquity) * sin(eclipticLongitude))
        val gmst = (280.46061837 + 360.98564736629 * (julian - 2451545.0)) % 360.0
        val hourAngle = toRadians(
            ((gmst + lng - toDegrees(rightAscension) + 540.0) % 360.0) - 180.0,
        )

        val phi = toRadians(lat)
        return toDegrees(
            kotlin.math.asin(
                sin(phi) * sin(declination) + cos(phi) * cos(declination) * cos(hourAngle),
            ),
        )
    }

    /** True if the satellite footprint (circle with [rangeKm] radius) covers the place. */
    fun withinRange(
        place: Place,
        position: IssPosition,
        rangeKm: Double,
    ): Boolean = haversineKm(place.lat, place.lng, position.latitude, position.longitude) <= rangeKm
}
