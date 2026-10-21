package com.dan.lightiss

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import java.net.URLEncoder
import kotlin.text.Charsets.UTF_8

internal class IssApi {

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    suspend fun fetchCurrentPosition(): Result<IssPosition> = runCatching {
        val response = client.get("$SATELLITE_API?units=kilometers")
        if (!response.status.isSuccess()) {
            throw IllegalStateException("ISS position HTTP ${response.status.value}")
        }
        val parsed: WhereTheIssResponse = response.body()
        parsed.toIssPosition()
    }

    /**
     * Samples the orbit at the requested epoch-second timestamps. The API
     * accepts at most 10 timestamps per request, so larger lists are fetched
     * in chunks.
     */
    suspend fun fetchPositions(timestamps: List<Long>): Result<List<IssPosition>> = runCatching {
        val positions = mutableListOf<IssPosition>()
        timestamps.chunked(CHUNK_SIZE).forEachIndexed { index, chunk ->
            if (index > 0) {
                kotlinx.coroutines.delay(CHUNK_PAUSE_MS)
            }
            val query = chunk.joinToString(",")
            val response = client.get("$SATELLITE_API/positions?timestamps=$query&units=kilometers")
            if (!response.status.isSuccess()) {
                throw IllegalStateException("Orbit sample HTTP ${response.status.value}")
            }
            positions += response.body<List<WhereTheIssResponse>>()
                .map { it.toIssPosition() }
                .filter { it.isValid() }
        }
        positions
    }

    /** Looks up a place by free text query via OpenStreetMap Nominatim. */
    suspend fun searchPlace(query: String): Result<Place> = runCatching {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) throw PlaceNotFoundException()
        val encoded = URLEncoder.encode(trimmed, UTF_8.name())
        val response = client.get("$NOMINATIM_SEARCH_URL?format=json&limit=1&q=$encoded") {
            header("User-Agent", USER_AGENT)
            header("Accept", "application/json")
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("City search HTTP ${response.status.value}")
        }
        val results: List<NominatimResult> = response.body()
        val first = results.firstOrNull() ?: throw PlaceNotFoundException()
        val place = Place(
            name = shortenPlaceName(first.displayName.ifBlank { trimmed }),
            lat = first.lat.toDoubleOrNull() ?: throw PlaceNotFoundException(),
            lng = first.lon.toDoubleOrNull() ?: throw PlaceNotFoundException(),
        )
        if (!place.isValid()) throw PlaceNotFoundException()
        place
    }

    fun close() {
        client.close()
    }

    private fun shortenPlaceName(name: String): String =
        name.split(",")
            .take(2)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(", ")
            .ifBlank { "City" }

    class PlaceNotFoundException : Exception("Place not found")

    private companion object {
        const val SATELLITE_API = "https://api.wheretheiss.at/v1/satellites/25544"
        const val NOMINATIM_SEARCH_URL = "https://nominatim.openstreetmap.org/search"
        const val CHUNK_SIZE = 10
        const val CHUNK_PAUSE_MS = 150L
        const val USER_AGENT = "LightISS/1.0 (Light Phone III tool)"
    }
}
