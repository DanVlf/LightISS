package com.dan.lightiss

/**
 * Builds upcoming ISS passes for a place from sampled orbit positions.
 *
 * The orbit is sampled every few minutes from wheretheiss.at, so passes are
 * reconstructed from the samples: a pass is a run of consecutive samples that
 * are inside the radio footprint and above the horizon. This mirrors the
 * original LightISS web prototype.
 */
object PassPredictor {

    const val DEFAULT_FOOTPRINT_KM = 4400.0
    private const val MIN_RANGE_KM = 1800.0
    private const val MAX_RANGE_KM = 2600.0
    private const val MAX_PASSES = 8
    private const val VISUAL_SUN_ELEVATION_DEG = -6.0

    /** Radio range in km for the given footprint diameter. */
    fun rangeKmFor(footprintKm: Double): Double {
        val half = if (footprintKm > 0.0) footprintKm / 2.0 else DEFAULT_FOOTPRINT_KM / 2.0
        return MIN_RANGE_KM.coerceAtLeast(MAX_RANGE_KM.coerceAtMost(half))
    }

    /**
     * Finds the sampled orbit point closest to [place] in the future,
     * starting from [nowSec].
     */
    fun findClosestApproach(
        orbit: List<IssPosition>,
        place: Place,
        rangeKm: Double,
        nowSec: Long,
    ): ClosestApproach? = orbit.asSequence()
        .filter { it.timestamp >= nowSec && it.isValid() }
        .map { position ->
            val distance = GeoMath.haversineKm(
                place.lat, place.lng, position.latitude, position.longitude,
            )
            ClosestApproach(
                timestampSec = position.timestamp,
                distanceKm = distance,
                inRange = distance <= rangeKm,
            )
        }
        .minByOrNull { it.distanceKm }

    /**
     * Groups future samples into passes over [place]. A sample counts as
     * visible from the place when it is inside the footprint and above the
     * horizon; a pass is visible to the naked eye when the ISS is sunlit
     * while the observer is in darkness.
     */
    fun buildPasses(
        orbit: List<IssPosition>,
        place: Place,
        rangeKm: Double,
        nowSec: Long,
    ): List<IssPass> {
        val sorted = orbit
            .filter { it.isValid() && it.timestamp >= nowSec - SAMPLE_TOLERANCE_SEC }
            .sortedBy { it.timestamp }

        val passes = mutableListOf<IssPass>()
        var active: IssPass? = null
        for (position in sorted) {
            val distance = GeoMath.haversineKm(
                place.lat, place.lng, position.latitude, position.longitude,
            )
            val elevation = GeoMath.elevationDegrees(distance, position.altitude)
            val inRange = distance <= rangeKm && elevation > 0.0
            if (inRange) {
                val visual = isVisualPass(position, place)
                val current = active
                if (current == null) {
                    active = IssPass(
                        startSec = position.timestamp,
                        endSec = position.timestamp,
                        maxAtSec = position.timestamp,
                        maxElevationDeg = elevation,
                        minDistanceKm = distance,
                        visual = visual,
                    )
                } else {
                    active = current.copy(
                        endSec = position.timestamp,
                        visual = current.visual || visual,
                        maxAtSec = if (elevation > current.maxElevationDeg) {
                            position.timestamp
                        } else {
                            current.maxAtSec
                        },
                        maxElevationDeg = maxOf(current.maxElevationDeg, elevation),
                        minDistanceKm = if (elevation > current.maxElevationDeg) {
                            distance
                        } else {
                            current.minDistanceKm
                        },
                    )
                }
            } else if (active != null) {
                passes.add(active)
                active = null
            }
        }
        active?.let { passes.add(it) }
        return passes.take(MAX_PASSES)
    }

    /** A pass is naked-eye visible when the ISS is lit and the sky is dark at the place. */
    fun isVisualPass(position: IssPosition, place: Place): Boolean =
        position.visibility == "daylight" &&
            GeoMath.sunElevationDegrees(place.lat, place.lng, position.timestamp) <
            VISUAL_SUN_ELEVATION_DEG

    private const val SAMPLE_TOLERANCE_SEC = 300L
}
