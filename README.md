# LightISS

A minimal monochrome ISS tracker, built as a native [Light SDK](https://github.com/lightphone/light-sdk)
tool for the Light Phone III.

Track the International Space Station from a place that matters to you: pick a
city (or use your device location), watch the station move on a quiet world
map with its ground track and radio footprint, and see the next radio and
naked-eye passes overhead.

## Tool

The tool lives in `tool/` and is built with the Light SDK:

- **Kotlin + Compose + MVVM**, restricted to the SDK's allowed APIs
- `HomeScreen` — world map (pan/zoom), live station stats, status (LIVE / SYNC / OLD / OFF)
- `PassesScreen` — upcoming passes over your place, marked `VISIBLE` when the
  station is sunlit while your sky is dark
- `LocationScreen` — city presets, city search, manual coordinates, device location

The map is drawn from a bundled simplified coastline (Natural Earth) — no map
tiles, no tracking, no accounts.

## Data Sources

- ISS position and orbit samples: [wheretheiss.at](https://wheretheiss.at)
- City search: OpenStreetMap Nominatim

Only the ISS position requests and (when you search) the city name leave the
device. Nothing is collected, stored or shared beyond your tool's own local
data.

## Building

Open the project in Android Studio (or use `./gradlew` directly) and run:

```sh
./gradlew :tool:assembleDebug
```

To test on the LightOS emulator, switch `tool/lighttool.toml` to the emulator
server package:

```toml
serverPackage = "com.thelightphone.sdk.emulator"
```

then build with `./gradlew :tool:installDebug -DlightSdk.allowAltServerPackage=true`
(revert to `com.lightos` before submitting). See `docs/system_app` for setting
up the emulator, and the SDK README for submitting tools through the Light
dashboard.

Tests:

```sh
./gradlew :tool:test
```

## How It Works

- The current ISS position is polled while the tool is open (every 15 s) and
  cached in local storage, so the last known state is available offline.
- The orbit is sampled every 5 minutes from 30 min in the past to 8 h ahead;
  passes over your place are reconstructed from those samples using footprint
  range, look angle and solar elevation — the same approach as the original
  web prototype.
- Everything is monochrome and sized for the Light Phone III e-ink display.

## Credits

- Land outlines: [Natural Earth](https://www.naturalearthdata.com/) (public domain)
- ISS data: wheretheiss.at
- Built on the [light-sdk](https://github.com/lightphone/light-sdk) scaffold
