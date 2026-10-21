package com.dan.lightiss

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock

data class IssUiState(
    val place: Place = DEFAULT_PLACE,
    val iss: IssPosition? = null,
    val orbit: List<IssPosition> = emptyList(),
    val passes: List<IssPass> = emptyList(),
    val closest: ClosestApproach? = null,
    val lastOrbitSyncSec: Long = 0L,
    val nowSec: Long = 0L,
    val syncing: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
) {
    val rangeKm: Double
        get() = PassPredictor.rangeKmFor(iss?.footprint ?: PassPredictor.DEFAULT_FOOTPRINT_KM)

    fun statusLabel(): String = when {
        syncing -> "SYNC"
        iss == null || !iss.isValid() -> "OFF"
        nowSec - iss.timestamp < LIVE_MAX_AGE_SEC -> "LIVE"
        else -> "OLD"
    }
}

val CITY_PRESETS = listOf(
    Place("Prague", 50.0755, 14.4378),
    Place("Brno", 49.1951, 16.6068),
    Place("Ostrava", 49.8209, 18.2625),
    Place("Pilsen", 49.7384, 13.3736),
    Place("New York", 40.7128, -74.0060),
    Place("Tokyo", 35.6762, 139.6503),
)

val DEFAULT_PLACE: Place = CITY_PRESETS.first()

private const val LIVE_MAX_AGE_SEC = 120L
private const val POSITION_POLL_SEC = 15L
private const val NOW_TICK_SEC = 5L
private const val ORBIT_REFRESH_SEC = 10 * 60L
private const val ORBIT_PAST_SEC = 1800L
private const val ORBIT_FUTURE_SEC = 8 * 3600L
private const val ORBIT_STEP_SEC = 300L
private const val ORBIT_MAX_POINTS = 160

private const val NETWORK_ERROR_MESSAGE =
    "The LightISS tool requires a network connection to track the station. " +
        "Please insert a data sim or connect to wi-fi."

private fun currentEpochSec(): Long = Clock.System.now().epochSeconds

class IssViewModel(
    private val dataStore: DataStore<Preferences>,
) : LightViewModel<Unit>() {

    private val api = IssApi()
    private val json = Json { ignoreUnknownKeys = true }

    private val _uiState = MutableStateFlow(IssUiState(nowSec = currentEpochSec()))
    val uiState: StateFlow<IssUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { loadStoredState() }
                .onFailure {
                    _uiState.update { state -> state.copy(loaded = true) }
                }
        }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        startPolling()
    }

    override fun onAppPause() {
        super.onAppPause()
        stopPolling()
    }

    override fun onCleared() {
        super.onCleared()
        api.close()
    }

    /** Selects a new observer place and refetches the orbit for its passes. */
    fun setPlace(place: Place) {
        if (!place.isValid()) {
            _uiState.update { it.copy(error = "Invalid location.") }
            return
        }
        _uiState.update { state ->
            state.copy(place = place, error = null).withDerived()
        }
        viewModelScope.launch(Dispatchers.IO) {
            persistPlace(place)
            syncOrbit(quiet = true)
        }
    }

    /** Manual refresh from the bottom bar; shows errors as a modal. */
    fun manualRefresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val synced = syncCurrentPosition(quiet = false)
            if (synced && currentEpochSec() - _uiState.value.lastOrbitSyncSec > ORBIT_REFRESH_SEC) {
                syncOrbit(quiet = true)
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    /** Looks up a place by free-text query for the location picker. */
    suspend fun searchPlace(query: String): Result<Place> = api.searchPlace(query)

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            // let the stored state load first so the cache is not overwritten
            runCatching { uiState.first { it.loaded } }
            var nextPositionIn = 0L
            while (isActive) {
                if (nextPositionIn <= 0L) {
                    syncCurrentPosition(quiet = true)
                    maybeSyncOrbit()
                    nextPositionIn = POSITION_POLL_SEC
                }
                nextPositionIn -= NOW_TICK_SEC
                _uiState.update { it.copy(nowSec = currentEpochSec()) }
                delay(NOW_TICK_SEC * 1000)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun maybeSyncOrbit() {
        val state = _uiState.value
        if (currentEpochSec() - state.lastOrbitSyncSec > ORBIT_REFRESH_SEC) {
            viewModelScope.launch(Dispatchers.IO) {
                syncOrbit(quiet = true)
            }
        }
    }

    private suspend fun syncCurrentPosition(quiet: Boolean): Boolean {
        _uiState.update { it.copy(syncing = true) }
        val result = api.fetchCurrentPosition()
        val next = result.getOrNull()?.takeIf { it.isValid() }
        var timestampChanged = false
        _uiState.update { state ->
            if (next != null) {
                timestampChanged = state.iss?.timestamp != next.timestamp
                state.copy(
                    iss = next,
                    orbit = mergeOrbit(state.orbit, listOf(next)),
                    syncing = false,
                    error = null,
                ).withDerived()
            } else {
                state.copy(syncing = false)
            }
        }
        if (result.isFailure && !quiet) {
            showErrorModal()
            return false
        }
        if (result.isSuccess && next != null && timestampChanged) {
            persistIss(next)
        }
        return result.isSuccess
    }

    private suspend fun syncOrbit(quiet: Boolean) {
        val result = api.fetchPositions(orbitTimestamps())
        result.onSuccess { positions ->
            val merged = mergeOrbit(_uiState.value.orbit, positions)
            _uiState.update { state ->
                state.copy(
                    orbit = merged,
                    lastOrbitSyncSec = currentEpochSec(),
                ).withDerived()
            }
            persistOrbit(merged)
        }
        if (result.isFailure && !quiet) {
            showErrorModal()
        }
    }

    private fun orbitTimestamps(): List<Long> {
        val now = currentEpochSec()
        val timestamps = mutableListOf<Long>()
        var offset = -ORBIT_PAST_SEC
        while (offset <= ORBIT_FUTURE_SEC) {
            timestamps.add(now + offset)
            offset += ORBIT_STEP_SEC
        }
        return timestamps
    }

    private fun mergeOrbit(
        existing: List<IssPosition>,
        incoming: List<IssPosition>,
    ): List<IssPosition> {
        val byTimestamp = LinkedHashMap<Long, IssPosition>()
        (existing + incoming)
            .filter { it.isValid() }
            .forEach { byTimestamp[it.timestamp] = it }
        return byTimestamp.values
            .sortedBy { it.timestamp }
            .takeLast(ORBIT_MAX_POINTS)
    }

    private suspend fun loadStoredState() {
        val prefs = dataStore.data.first()
        val place = Place(
            name = prefs[IssPreferences.PLACE_NAME] ?: DEFAULT_PLACE.name,
            lat = prefs[IssPreferences.PLACE_LAT]?.toDoubleOrNull() ?: DEFAULT_PLACE.lat,
            lng = prefs[IssPreferences.PLACE_LNG]?.toDoubleOrNull() ?: DEFAULT_PLACE.lng,
        ).takeIf { it.isValid() } ?: DEFAULT_PLACE

        val iss = prefs[IssPreferences.LAST_ISS_JSON]
            ?.let { runCatching { json.decodeFromString(IssPosition.serializer(), it) }.getOrNull() }
            ?.takeIf { it.isValid() }
        val orbit = prefs[IssPreferences.ORBIT_JSON]
            ?.let {
                runCatching { json.decodeFromString(ListSerializer(IssPosition.serializer()), it) }
                    .getOrNull()
            }
            ?.filter { it.isValid() }
            .orEmpty()
        val lastOrbitSync = prefs[IssPreferences.LAST_ORBIT_SYNC_SEC]?.toLongOrNull() ?: 0L

        _uiState.update { state ->
            state.copy(
                place = place,
                iss = iss,
                orbit = orbit,
                lastOrbitSyncSec = lastOrbitSync,
                nowSec = currentEpochSec(),
                loaded = true,
            ).withDerived()
        }
    }

    private suspend fun persistPlace(place: Place) {
        runCatching {
            dataStore.edit { prefs ->
                prefs[IssPreferences.PLACE_NAME] = place.name
                prefs[IssPreferences.PLACE_LAT] = place.lat.toString()
                prefs[IssPreferences.PLACE_LNG] = place.lng.toString()
            }
        }
    }

    private suspend fun persistIss(iss: IssPosition) {
        runCatching {
            dataStore.edit { prefs ->
                prefs[IssPreferences.LAST_ISS_JSON] =
                    json.encodeToString(IssPosition.serializer(), iss)
            }
        }
    }

    private suspend fun persistOrbit(orbit: List<IssPosition>) {
        runCatching {
            dataStore.edit { prefs ->
                prefs[IssPreferences.ORBIT_JSON] =
                    json.encodeToString(ListSerializer(IssPosition.serializer()), orbit)
                prefs[IssPreferences.LAST_ORBIT_SYNC_SEC] = currentEpochSec().toString()
            }
        }
    }

    private fun showErrorModal() {
        _uiState.update { state ->
            if (state.error == null) state.copy(error = NETWORK_ERROR_MESSAGE) else state
        }
    }
}

private fun IssUiState.withDerived(): IssUiState {
    val range = rangeKm
    val now = if (nowSec > 0L) nowSec else currentEpochSec()
    return copy(
        closest = PassPredictor.findClosestApproach(orbit, place, range, now),
        passes = PassPredictor.buildPasses(orbit, place, range, now),
    )
}
