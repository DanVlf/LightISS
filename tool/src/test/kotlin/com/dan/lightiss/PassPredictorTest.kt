package com.dan.lightiss

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PassPredictorTest {

    private val place = Place("Prague", 50.0, 14.0)

    /** A synthetic northbound overflight: lat 25 to 75 along lng 14, 5 min steps. */
    private fun overflight(
        startSec: Long,
        visibility: String = "daylight",
    ): List<IssPosition> = (0..10).map { step ->
        IssPosition(
            latitude = 25.0 + step * 5.0,
            longitude = 14.0,
            altitude = 420.0,
            velocity = 27600.0,
            visibility = visibility,
            footprint = 4400.0,
            timestamp = startSec + step * 300L,
        )
    }

    @Test
    fun `builds a single pass for a direct overflight at night`() {
        val orbit = overflight(startSec = GeoMathTest.PRAGUE_NIGHT_SEC - 1500L)
        val passes = PassPredictor.buildPasses(
            orbit = orbit,
            place = place,
            rangeKm = PassPredictor.rangeKmFor(4400.0),
            nowSec = GeoMathTest.PRAGUE_NIGHT_SEC - 1800L,
        )

        assertEquals(1, passes.size)
        val pass = passes.first()
        assertEquals(600L, pass.startSec - (GeoMathTest.PRAGUE_NIGHT_SEC - 1500L))
        assertEquals(90.0, pass.maxElevationDeg, 0.01)
        assertEquals(GeoMathTest.PRAGUE_NIGHT_SEC, pass.maxAtSec)
        assertTrue(pass.visual, "daylight ISS over a night observer should be visible")
        assertTrue(pass.durationSec > 0)
    }

    @Test
    fun `pass over a sunlit observer is not visible`() {
        val orbit = overflight(startSec = GeoMathTest.PRAGUE_DAY_SEC - 1500L)
        val passes = PassPredictor.buildPasses(
            orbit = orbit,
            place = place,
            rangeKm = PassPredictor.rangeKmFor(4400.0),
            nowSec = GeoMathTest.PRAGUE_DAY_SEC - 1800L,
        )

        assertEquals(1, passes.size)
        assertTrue(!passes.first().visual)
    }

    @Test
    fun `eclipsed iss over a night observer is not visible`() {
        val orbit = overflight(
            startSec = GeoMathTest.PRAGUE_NIGHT_SEC - 1500L,
            visibility = "eclipsed",
        )
        val passes = PassPredictor.buildPasses(
            orbit = orbit,
            place = place,
            rangeKm = PassPredictor.rangeKmFor(4400.0),
            nowSec = GeoMathTest.PRAGUE_NIGHT_SEC - 1800L,
        )

        assertEquals(1, passes.size)
        assertTrue(!passes.first().visual)
    }

    @Test
    fun `no pass when the orbit stays far away`() {
        val orbit = (0..10).map { step ->
            IssPosition(
                latitude = -40.0 + step,
                longitude = 120.0,
                altitude = 420.0,
                visibility = "daylight",
                footprint = 4400.0,
                timestamp = GeoMathTest.PRAGUE_NIGHT_SEC + step * 300L,
            )
        }
        val passes = PassPredictor.buildPasses(
            orbit = orbit,
            place = place,
            rangeKm = PassPredictor.rangeKmFor(4400.0),
            nowSec = GeoMathTest.PRAGUE_NIGHT_SEC,
        )

        assertTrue(passes.isEmpty())
    }

    @Test
    fun `finds the closest approach in the future only`() {
        val orbit = overflight(startSec = GeoMathTest.PRAGUE_NIGHT_SEC - 1500L)
        val closest = PassPredictor.findClosestApproach(
            orbit = orbit,
            place = place,
            rangeKm = 2200.0,
            nowSec = GeoMathTest.PRAGUE_NIGHT_SEC,
        )

        assertNotNull(closest)
        assertEquals(GeoMathTest.PRAGUE_NIGHT_SEC, closest.timestampSec)
        assertEquals(0.0, closest.distanceKm, 0.01)
        assertTrue(closest.inRange)
    }

    @Test
    fun `no closest approach before now`() {
        val orbit = overflight(startSec = GeoMathTest.PRAGUE_NIGHT_SEC - 15000L)
        val closest = PassPredictor.findClosestApproach(
            orbit = orbit,
            place = place,
            rangeKm = 2200.0,
            nowSec = GeoMathTest.PRAGUE_NIGHT_SEC,
        )

        assertNull(closest)
    }

    @Test
    fun `range clamps to sensible bounds`() {
        assertEquals(2200.0, PassPredictor.rangeKmFor(4400.0), 0.01)
        assertEquals(2600.0, PassPredictor.rangeKmFor(8000.0), 0.01)
        assertEquals(1800.0, PassPredictor.rangeKmFor(1000.0), 0.01)
        assertEquals(2200.0, PassPredictor.rangeKmFor(0.0), 0.01)
    }
}

class IssFormattingTest {

    @Test
    fun `thousands separators`() {
        assertEquals("0", IssFormatting.formatThousands(0))
        assertEquals("999", IssFormatting.formatThousands(999))
        assertEquals("1,000", IssFormatting.formatThousands(1000))
        assertEquals("12,345,678", IssFormatting.formatThousands(12345678))
        assertEquals("-1,234", IssFormatting.formatThousands(-1234))
    }

    @Test
    fun `duration labels round to minutes`() {
        assertEquals("1 min", IssFormatting.durationLabel(30))
        assertEquals("6 min", IssFormatting.durationLabel(360))
        assertEquals("7 min", IssFormatting.durationLabel(390))
    }

    @Test
    fun `age labels`() {
        assertEquals("now", IssFormatting.ageLabel(15))
        assertEquals("2 min ago", IssFormatting.ageLabel(120))
        assertEquals("61 min ago", IssFormatting.ageLabel(3700))
    }

    @Test
    fun `pass quality buckets`() {
        fun pass(elevation: Double, minutes: Long) = IssPass(
            startSec = 0,
            endSec = minutes * 60,
            maxAtSec = 0,
            maxElevationDeg = elevation,
            minDistanceKm = 0.0,
            visual = false,
        )

        assertEquals("short", IssFormatting.passQuality(pass(10.0, 3)))
        assertEquals("medium", IssFormatting.passQuality(pass(25.0, 3)))
        assertEquals("long", IssFormatting.passQuality(pass(50.0, 3)))
        assertEquals("long", IssFormatting.passQuality(pass(10.0, 9)))
    }
}
