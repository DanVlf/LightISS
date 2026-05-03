# LightISS

LightISS is a minimal monochrome ISS tracker for a Light Phone III style workflow.

The app follows the LightOS-inspired direction from `vandamd/light-template` while keeping the same simple shape as `Places`: a browser prototype in `src/web` and an Expo WebView wrapper for Android/APK testing. The interface is intentionally small: choose a city, keep that city as the map center, watch the ISS position, see distance, bearing, altitude, speed, and open a pass list for visual and radio/day passes.

## Run

Open `src/web/index.html` in a browser for the prototype.

## Android APK Test

The APK wrapper is defined by `App.tsx`, `app.json`, `package.json`, and `src/webContent.ts`.

Useful commands:

```powershell
npm install
npm run sync-web
npm run dev
npm run prebuild:android
npm run apk:debug
```

## GitHub Release

APK releases are built by GitHub Actions.

1. Push to `main`
2. Open GitHub Actions
3. Run `APK Release`
4. Optionally enter a tag like `v0.1.0`

The current workflow builds a debug APK and attaches it to a GitHub Release. For a production signed APK, add Android keystore secrets and change the Gradle step to `assembleRelease`.

## Data Sources

- Current ISS data uses `https://api.wheretheiss.at/v1/satellites/25544`
- Sampled orbit data uses `https://api.wheretheiss.at/v1/satellites/25544/positions`
- City search uses OpenStreetMap Nominatim

The app keeps the last city and ISS data in local storage. If map tiles or ISS data are unavailable, it falls back to a local monochrome map sketch with the chosen city at the center.
