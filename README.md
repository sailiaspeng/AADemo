# AutoArcGIS

Cordova spike project for running an ArcGIS-based map inside an Android WebView, with an Android Auto navigation surface backed by a local Cordova plugin.

## What This Project Does

- Boots a Cordova Android app and waits for `deviceready` before starting map and location services.
- Loads the ArcGIS Maps SDK for JavaScript from the ArcGIS CDN inside the Cordova WebView.
- Displays a live vehicle marker on the map and keeps the map centered on the current position.
- Polls a patrol-points ArcGIS Feature Service and draws nearby points on the phone map.
- Syncs patrol points and map interactions to an Android Auto car app via a local plugin.
- Supports two location modes: `real-vehicle` and `simulator`.

The main app code lives in [www/index.html](www/index.html) and [www/js/index.js](www/js/index.js). Do not edit generated files under [platforms/android](platforms/android).

## Stack

- Apache Cordova
- `cordova-android` 15
- ArcGIS Maps SDK for JavaScript 5.0 via CDN
- `cordova-plugin-geolocation`
- Local plugin: `cordova-plugin-android-auto-navigation`

## Requirements

- Node.js and npm
- JDK 11 or newer
- Android SDK installed and available on `PATH`
- `ANDROID_HOME` pointing to the Android SDK root
- Android platform tooling compatible with:
  - Min SDK 24
  - Target SDK 36

## Project Layout

- [www/index.html](www/index.html): Cordova WebView shell, ArcGIS CDN includes, UI controls, and CSP.
- [www/js/index.js](www/js/index.js): app startup, map initialization, geolocation, patrol-point querying, and Android Auto sync.
- [www/css/index.css](www/css/index.css): app styling.
- [config.xml](config.xml): Cordova app metadata and platform configuration.
- [plugins-local/cordova-plugin-android-auto-navigation](plugins-local/cordova-plugin-android-auto-navigation): local Android Auto Cordova plugin source.
- [plugins](plugins): installed plugin copies managed by Cordova.
- [platforms/android](platforms/android): generated Android project output.

## Setup

Install dependencies:

```powershell
npm install
```

If the Android platform is missing or needs to be recreated:

```powershell
npx cordova platform add android
```

## Run And Build

Copy web assets into the generated Android project without a full rebuild:

```powershell
npx cordova prepare android
```

Build a debug APK:

```powershell
npx cordova build android
```

Run on a connected device or emulator:

```powershell
npx cordova run android
```

## App Behavior

### Map and basemap

- The app loads ArcGIS JS modules dynamically after `deviceready`.
- The phone map uses the ArcGIS `World_Street_Map` tiled basemap.
- The default map zoom is `17`.

### Location flow

- The app prefers vehicle location from the Android Auto plugin when available.
- If the plugin cannot provide location, it falls back to browser geolocation through `navigator.geolocation.watchPosition`.
- The selected location mode is persisted in `localStorage`.

### Patrol points

- Patrol points are queried from this ArcGIS Feature Service:

```text
https://devmulti29.transfinder.com/arcgis/rest/services/pf0904/pf0904PatrolPointsFeatureService/MapServer/0/query
```

- Queries are rate-limited and movement-gated in [www/js/index.js](www/js/index.js):
  - minimum move: `100` meters
  - minimum interval: `10000` ms
- The default query extent is `500` meters and can be changed from the UI.
- Android Auto only receives points within roughly `2` miles (`3219` meters) of the current location.

### UI controls

- Panel show/hide toggle
- Compass toggle for north-up vs heading-up mode
- Zoom in / zoom out buttons
- Query extent input and apply button
- Location mode selector and apply button
- Status and debug text for GPS and patrol-point state

## Android Auto Plugin Notes

This repo depends on a local plugin referenced from `package.json`:

```json
"cordova-plugin-android-auto-navigation": "file:plugins-local/cordova-plugin-android-auto-navigation"
```

Important points:

- Edit the source in [plugins-local/cordova-plugin-android-auto-navigation](plugins-local/cordova-plugin-android-auto-navigation), not the generated copy under [plugins](plugins).
- After changing plugin source, run `npx cordova prepare android` or rebuild so Cordova recopies plugin assets into the platform project.
- The plugin registers an Android Auto `CarAppService` and exposes bridge methods such as vehicle location polling, patrol-point syncing, zoom sync, heading mode, and location-mode updates.

## Content Security Policy

The CSP in [www/index.html](www/index.html) already allows the ArcGIS CDN and ArcGIS service hosts required by the current app. If you add new map, tile, or API hosts, update that CSP accordingly.

## Development Workflow

- Make web-layer changes under [www](www).
- Make Cordova config changes in [config.xml](config.xml).
- Make Android Auto plugin changes under [plugins-local/cordova-plugin-android-auto-navigation](plugins-local/cordova-plugin-android-auto-navigation).
- Treat [platforms/android](platforms/android) as generated output.

## Troubleshooting

### ArcGIS assets fail to load

- Check the CSP in [www/index.html](www/index.html).
- Verify the device has network access to `https://js.arcgis.com` and the ArcGIS service hosts used by the app.

### Android Auto changes do not appear

- Re-run `npx cordova prepare android` after editing the local plugin.
- Confirm the local plugin path still points to [plugins-local/cordova-plugin-android-auto-navigation](plugins-local/cordova-plugin-android-auto-navigation).

### Location stays on fallback GPS or never updates

- Confirm Android location permissions were granted.
- Check the in-app debug status panel for poll age, last success age, provider, and last plugin error.
- If the Android Auto plugin is unavailable, the app will fall back to browser geolocation.

### Build issues after copying Java source

- If `javac` reports an illegal BOM character at the start of a plugin Java file, resave the file as UTF-8 without BOM and rebuild.

## Current Gaps

- `package.json` still contains scaffold metadata such as the default description, author, and test script.
- There is no automated test suite configured in this repo yet.
