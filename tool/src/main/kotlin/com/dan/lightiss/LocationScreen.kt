package com.dan.lightiss

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.PermissionRequestLauncher
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.callRemoteServiceMethod
import com.thelightphone.sdk.checkPermission
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.asKotlinResult
import com.thelightphone.sdk.shared.getOrNull
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Picks the observer place: presets, city search, manual coordinates or device location. */
class LocationScreen(
    sealedActivity: SealedLightActivity,
    private val issViewModel: IssViewModel,
) : SimpleLightScreen<Unit>(sealedActivity) {

    private var resumeCount by mutableIntStateOf(0)

    override fun willShow() {
        resumeCount++
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by issViewModel.uiState.collectAsState()
        val resumeKey = resumeCount
        val scope = rememberCoroutineScope()

        var openEditor by remember { mutableStateOf<EditorKind?>(null) }
        var editorSession by remember { mutableIntStateOf(0) }
        var error by remember { mutableStateOf<String?>(null) }
        var locating by remember { mutableStateOf(false) }
        var locationStatus by remember { mutableStateOf<String?>(null) }
        var locationSession by remember { mutableIntStateOf(0) }
        var permissionAsked by remember { mutableStateOf(false) }

        val searchFieldState = rememberTextFieldState("")
        val latFieldState = rememberTextFieldState("")
        val lngFieldState = rememberTextFieldState("")
        val keyboardOptionsFlow = rememberKeyboardOptions()
        val permissionLauncher =
            rememberPermissionRequestLauncher(Manifest.permission.ACCESS_FINE_LOCATION)

        fun applyPlace(place: Place) {
            if (!place.isValid()) {
                error = "Invalid location."
                return
            }
            issViewModel.setPlace(place)
            goBack()
        }

        fun submitSearch(query: CharSequence) {
            val trimmed = query.toString().trim()
            if (trimmed.isEmpty()) {
                error = "Please enter a city name."
                return
            }
            scope.launch {
                val result = withContext(Dispatchers.IO) { issViewModel.searchPlace(trimmed) }
                result.fold(
                    onSuccess = { place -> applyPlace(place) },
                    onFailure = {
                        error = if (it is IssApi.PlaceNotFoundException) {
                            "City not found."
                        } else {
                            "City search requires a network connection."
                        }
                    },
                )
            }
        }

        fun submitCoordinates() {
            val lat = latFieldState.text.toString().trim().toDoubleOrNull()
            val lng = lngFieldState.text.toString().trim().toDoubleOrNull()
            if (lat == null || lng == null || lat !in -90.0..90.0 || lng !in -180.0..180.0) {
                error = "Enter a latitude (-90 to 90) and longitude (-180 to 180)."
                return
            }
            applyPlace(Place(name = "Custom location", lat = lat, lng = lng))
        }

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(
                            icon = LightIcons.BACK,
                            onClick = { goBack() },
                            contentDescription = "Back",
                        ),
                        center = LightTopBarCenter.Text("LOCATION"),
                        modifier = Modifier.padding(bottom = 0.25f.gridUnitsAsDp()),
                    )

                    LightScrollView(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 1f.gridUnitsAsDp()),
                    ) {
                        SectionLabel("CITY SEARCH")
                        LightTextField(
                            label = "SEARCH",
                            value = searchFieldState.text.toString(),
                            placeholder = "City name",
                            onClick = {
                                editorSession++
                                openEditor = EditorKind.SEARCH
                            },
                            modifier = Modifier.padding(bottom = 1.5f.gridUnitsAsDp()),
                        )

                        SectionLabel("PRESETS")
                        CITY_PRESETS.forEach { preset ->
                            val selected = state.place.sameLocation(preset)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .lightClickable { applyPlace(preset) }
                                    .padding(vertical = 0.5f.gridUnitsAsDp()),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                LightText(
                                    text = preset.name,
                                    variant = LightTextVariant.Copy,
                                    modifier = Modifier.weight(1f),
                                )
                                if (selected) {
                                    LightText(
                                        text = "SELECTED",
                                        variant = LightTextVariant.Superfine,
                                        lighten = true,
                                    )
                                }
                            }
                        }

                        SectionLabel("COORDINATES")
                        LightTextField(
                            label = "LATITUDE",
                            value = latFieldState.text.toString(),
                            placeholder = "50.0755",
                            onClick = {
                                editorSession++
                                openEditor = EditorKind.LATITUDE
                            },
                            modifier = Modifier.padding(bottom = 0.75f.gridUnitsAsDp()),
                        )
                        LightTextField(
                            label = "LONGITUDE",
                            value = lngFieldState.text.toString(),
                            placeholder = "14.4378",
                            onClick = {
                                editorSession++
                                openEditor = EditorKind.LONGITUDE
                            },
                            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                        )
                        LightText(
                            text = "SET COORDINATES",
                            variant = LightTextVariant.Button,
                            modifier = Modifier
                                .fillMaxWidth()
                                .lightClickable { submitCoordinates() }
                                .padding(bottom = 1.5f.gridUnitsAsDp()),
                        )

                        SectionLabel("DEVICE")
                        LightText(
                            text = "USE DEVICE LOCATION",
                            variant = LightTextVariant.Button,
                            modifier = Modifier
                                .fillMaxWidth()
                                .lightClickable {
                                    permissionAsked = false
                                    locationStatus = "Locating..."
                                    locating = true
                                    locationSession++
                                }
                                .padding(bottom = 0.5f.gridUnitsAsDp()),
                        )
                        locationStatus?.let { status ->
                            LightText(
                                text = status,
                                variant = LightTextVariant.Detail,
                                lighten = true,
                            )
                        }
                    }

                    LightBottomBar(
                        items = listOf(
                            LightBarButton.LightIcon(
                                icon = LightIcons.BACK,
                                onClick = { goBack() },
                                contentDescription = "Back",
                            ),
                            null,
                            null,
                        ),
                    )
                }

                if (locating) {
                    DeviceLocationRequest(
                        session = locationSession,
                        resumeKey = resumeKey,
                        permissionAsked = permissionAsked,
                        onPermissionAsked = { permissionAsked = true },
                        permissionLauncher = permissionLauncher,
                        onStatus = { locationStatus = it },
                        onLocated = { place ->
                            locating = false
                            applyPlace(place)
                        },
                        onFailed = { message ->
                            locating = false
                            locationStatus = message
                        },
                    )
                }

                when (openEditor) {
                    EditorKind.SEARCH -> {
                        LightTextInputEditor(
                            title = "Search City",
                            state = searchFieldState,
                            onSubmit = { query ->
                                openEditor = null
                                submitSearch(query)
                            },
                            onBack = { openEditor = null },
                            keyboardOptionsFlow = keyboardOptionsFlow,
                            submitIcon = LightIcons.SEARCH,
                            singleLine = true,
                            editorKey = editorSession,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    EditorKind.LATITUDE -> {
                        LightTextInputEditor(
                            title = "Latitude",
                            state = latFieldState,
                            onSubmit = {
                                openEditor = if (lngFieldState.text.isBlank()) {
                                    editorSession++
                                    EditorKind.LONGITUDE
                                } else {
                                    null
                                }
                            },
                            onBack = { openEditor = null },
                            keyboardOptionsFlow = keyboardOptionsFlow,
                            singleLine = true,
                            editorKey = editorSession,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    EditorKind.LONGITUDE -> {
                        LightTextInputEditor(
                            title = "Longitude",
                            state = lngFieldState,
                            onSubmit = { openEditor = null },
                            onBack = { openEditor = null },
                            keyboardOptionsFlow = keyboardOptionsFlow,
                            singleLine = true,
                            editorKey = editorSession,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    null -> Unit
                }

                error?.let { message ->
                    LightFullscreenModal(
                        message = message,
                        onClose = { error = null },
                    )
                }
            }
        }
    }

    private enum class EditorKind {
        SEARCH,
        LATITUDE,
        LONGITUDE,
    }
}

/**
 * Runs the LightOS location permission dance and waits for a single fix,
 * then reports the place. Mirrors the SDK ui-demo location screen. Re-runs
 * after the permission activity closes so a fresh grant is picked up.
 */
@Composable
private fun DeviceLocationRequest(
    session: Int,
    resumeKey: Int,
    permissionAsked: Boolean,
    onPermissionAsked: () -> Unit,
    permissionLauncher: PermissionRequestLauncher?,
    onStatus: (String) -> Unit,
    onLocated: (Place) -> Unit,
    onFailed: (String) -> Unit,
) {
    LaunchedEffect(session, resumeKey) {
        val granted = checkPermission(Manifest.permission.ACCESS_FINE_LOCATION).asKotlinResult
            .map { it.permissionResult == LightServiceMethod.GetPermission.Result.Granted }
            .getOrDefault(false)
        if (!granted) {
            if (!permissionAsked) {
                onPermissionAsked()
                permissionLauncher?.launch()
            } else {
                onFailed("Location permission needed.")
            }
            return@LaunchedEffect
        }

        coroutineScope {
            val updatesJob = launch {
                while (isActive) {
                    callRemoteServiceMethod(LightServiceMethod.RequestLocationUpdates, Unit)
                    delay(30_000)
                }
            }
            try {
                while (isActive) {
                    val current = callRemoteServiceMethod(
                        LightServiceMethod.GetCurrentLocation,
                        Unit,
                    ).getOrNull()
                    val fixLatitude = current?.latitude
                    val fixLongitude = current?.longitude
                    if (fixLatitude != null && fixLongitude != null) {
                        onLocated(
                            Place(
                                name = "Current location",
                                lat = fixLatitude,
                                lng = fixLongitude,
                            ),
                        )
                        break
                    }
                    delay(2_000)
                }
            } finally {
                updatesJob.cancel()
                withContext(NonCancellable) {
                    callRemoteServiceMethod(LightServiceMethod.ReleaseLocationUpdates, Unit)
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Superfine,
        lighten = true,
        modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
    )
}
