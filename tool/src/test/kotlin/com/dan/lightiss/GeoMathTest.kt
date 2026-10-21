package com.dan.lightiss

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoMathTest {

    private val prague = Place("Prague", 50.0755, 14.4378)
    private val brno = Place("Brno", 49.1951, 16.6068)

    @Test
    fun `haversine distance between known cities`() {
        val km = GeoMath.haversineKm(prague.lat, prague.lng, brno.lat, brno.lng)
        assertTrue(abs(km - 184.3) < 1.0, "expected ~184.3 km, was $km")
    }

    @Test
    fun `haversine distance to itself is zero`() {
        val km = GeoMath.haversineKm(prague.lat, prague.lng, prague.lat, prague.lng)
        assertEquals(0.0, km, 0.0001)
    }

    @Test
    fun `bearing east and north`() {
        val north = GeoMath.bearingDegrees(0.0, 0.0, 10.0, 0.0)
        assertEquals(0.0, north, 0.5)

        val east = GeoMath.bearingDegrees(0.0, 0.0, 0.0, 10.0)
        assertEquals(90.0, east, 0.5)
    }

    @Test
    fun `wrap longitude into -180 to 180`() {
        assertEquals(-170.0, GeoMath.wrapLongitude(190.0), 1e-9)
        assertEquals(170.0, GeoMath.wrapLongitude(-190.0), 1e-9)
        assertEquals(10.0, GeoMath.wrapLongitude(370.0), 1e-9)
        assertEquals(-10.0, GeoMath.wrapLongitude(-370.0), 1e-9)
        assertEquals(0.0, GeoMath.wrapLongitude(0.0), 1e-9)
    }

    @Test
    fun `elevation is ninety degrees directly below the satellite`() {
        val elevation = GeoMath.elevationDegrees(0.0, 420.0)
        assertEquals(90.0, elevation, 0.001)
    }

    @Test
    fun `elevation is negative at the footprint edge`() {
        val edge = GeoMath.elevationDegrees(2200.0, 420.0)
        assertTrue(edge < 5.0, "expected a low elevation at the footprint edge, was $edge")
    }

    @Test
    fun `destination point one degree north`() {
        val (lat, lng) = GeoMath.destinationPoint(50.0, 14.0, 0.0, 111.19)
        assertEquals(51.0, lat, 0.001)
        assertEquals(14.0, lng, 0.001)
    }

    @Test
    fun `sun is below the horizon at prague on a january night`() {
        val elevation = GeoMath.sunElevationDegrees(prague.lat, prague.lng, PRAGUE_NIGHT_SEC)
        assertTrue(elevation < -6.0, "expected night, was $elevation")
        assertTrue(abs(elevation - -23.6) < 1.5, "expected ~ -23.6 degrees, was $elevation")
    }

    @Test
    fun `sun is up at prague on a january morning`() {
        val elevation = GeoMath.sunElevationDegrees(prague.lat, prague.lng, PRAGUE_DAY_SEC)
        assertTrue(elevation > 0.0, "expected day, was $elevation")
    }

    @Test
    fun `cardinal directions`() {
        assertEquals("N", GeoMath.cardinalDirection(0.0))
        assertEquals("E", GeoMath.cardinalDirection(90.0))
        assertEquals("S", GeoMath.cardinalDirection(180.0))
        assertEquals("W", GeoMath.cardinalDirection(270.0))
        assertEquals("NE", GeoMath.cardinalDirection(45.0))
    }

    companion object {
        // 2026-01-15 18:00 UTC: night in Prague
        const val PRAGUE_NIGHT_SEC = 1768500000L

        // 2026-01-15 10:00 UTC: morning in Prague
        const val PRAGUE_DAY_SEC = 1768471200L
    }
}
