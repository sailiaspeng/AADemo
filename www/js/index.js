/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

var PATROL_POINTS_FEATURE_SERVICE_URL = 'https://devmulti29.transfinder.com/arcgis/rest/services/pf0904/pf0904PatrolPointsFeatureService/MapServer/0/query';

var QUERY_MIN_MOVE_METRES = 100;
var QUERY_MIN_INTERVAL_MS = 10000;
var QUERY_EXTENT_METRES = 500;
var QUERY_EXTENT_STORAGE_KEY = 'autoarcgis.queryExtentMetres';
var LOCATION_MODE_STORAGE_KEY = 'autoarcgis.locationMode';
var LOCATION_MODE_REAL = 'real-vehicle';
var LOCATION_MODE_SIMULATOR = 'simulator';
var AUTO_MAX_DISTANCE_METRES = 3219; // 2 miles
var DEFAULT_ZOOM = 17;

var autoNavPlugin = null;
var map = null;
var mapView = null;
var GraphicCtor = null;
var patrolPointsLayer = null;
var currentLocationLayer = null;
var currentLocationGraphic = null;
var currentLocationLabelGraphic = null;
var currentHeadingDeg = 0;
var lastLocationSample = null;
var lastLocationUpdateTs = 0;
var lastNativeLocationTs = 0;
var lastNativeProvider = '';
var lastNativeMock = false;
var lastPositionSource = 'none';
var browserWatchId = null;
var aaLocationPollInterval = null;
var debugStatusInterval = null;
var lastAutoNavPollTs = 0;
var lastAutoNavSuccessTs = 0;
var lastAutoNavError = '';
var lastQueryLat = null;
var lastQueryLon = null;
var lastQueryTs = 0;
var queryPending = false;
var pointsById = {};
var patrolFetchedCount = 0;
var patrolDrawnCount = 0;
var headingModeEnabled = false;
var currentLocationMode = LOCATION_MODE_REAL;

document.addEventListener('deviceready', onDeviceReady, false);

function resolvePlugin(clobberPath, moduleId) {
    var fromWindow = window.plugins && window.plugins[clobberPath] ? window.plugins[clobberPath] : null;
    if (fromWindow) {
        return fromWindow;
    }

    if (window.cordova && typeof cordova.require === 'function') {
        try {
            return cordova.require(moduleId);
        } catch (e) {
            console.warn('Unable to require plugin module', moduleId, e);
        }
    }

    return null;
}

function onDeviceReady() {
    console.log('Running cordova-' + cordova.platformId + '@' + cordova.version);
    document.getElementById('deviceready').classList.add('ready');

    autoNavPlugin = resolvePlugin('androidAutoNav', 'cordova-plugin-android-auto-navigation.AndroidAutoNav');
    initLocationModeControl();
    initQueryExtentControl();
    initPanelToggle();
    initCompassButton();
    initZoomControls();
    initPanelResizeListener();
        startDebugStatusTimer();

    requestRequiredPermissions(function () {
        startApp();
    });
}

function initPanelToggle() {
    var panel = document.getElementById('statusPanel');
    var showBtn = document.getElementById('panelToggle');
    var hideBtn = document.getElementById('panelHideBtn');
    if (!panel || !showBtn || !hideBtn) { return; }

    hideBtn.addEventListener('click', function () {
        panel.classList.add('collapsed');
        showBtn.style.display = 'block';
    });
    showBtn.addEventListener('click', function () {
        panel.classList.remove('collapsed');
        showBtn.style.display = 'none';
    });
}

function initCompassButton() {
    var btn = document.getElementById('compassBtn');
    if (!btn) { return; }
    btn.addEventListener('click', function () {
        toggleHeadingMode();
    });
    updateHeadingModeUi();
}

function initPanelResizeListener() {
    // Adjust panel max-height based on viewport changes (e.g., Android Auto left panel).
    var panel = document.getElementById('statusPanel');
    if (!panel) { return; }

    var updatePanelHeight = function () {
        var viewportHeight = window.innerHeight;
        var safeAreaTop = parseInt(getComputedStyle(document.documentElement).getPropertyValue('--safe-area-inset-top')) || 0;
        var maxHeightVh = Math.max(200, viewportHeight - 48 - safeAreaTop);
        panel.style.maxHeight = maxHeightVh + 'px';
    };

    updatePanelHeight();
    window.addEventListener('resize', updatePanelHeight);
    if (mapView && typeof mapView.watch === 'function') {
        mapView.watch('extent', updatePanelHeight);
    }
}

function toggleHeadingMode() {
    headingModeEnabled = !headingModeEnabled;
    updateHeadingModeUi();

    if (mapView) {
        // heading-up: rotate map to face heading; north-up: reset to 0
        var targetRotation = headingModeEnabled ? -currentHeadingDeg : 0;
        mapView.goTo({ rotation: targetRotation }, { animate: true, duration: 250 }).catch(function () {});
    }

    if (autoNavPlugin && typeof autoNavPlugin.setRotateWithHeading === 'function') {
        autoNavPlugin.setRotateWithHeading(headingModeEnabled);
    }

    if (lastLocationSample) {
        renderCurrentLocation(lastLocationSample.lat, lastLocationSample.lon);
    }
}

function updateHeadingModeUi() {
    var btn = document.getElementById('compassBtn');
    if (!btn) { return; }
    btn.classList.toggle('heading-mode', headingModeEnabled);
    btn.setAttribute('aria-pressed', headingModeEnabled ? 'true' : 'false');
    btn.title = headingModeEnabled ? 'Switch to north-up' : 'Switch to heading-up';
    // Update SVG angle immediately when mode changes (before next location event)
    updateCompassImage(currentHeadingDeg || 0);
}

function initZoomControls() {
    var zoomInBtn = document.getElementById('zoomInBtn');
    var zoomOutBtn = document.getElementById('zoomOutBtn');
    if (!zoomInBtn || !zoomOutBtn) { return; }

    zoomInBtn.addEventListener('click', function () {
        applyZoomDelta(1);
    });
    zoomOutBtn.addEventListener('click', function () {
        applyZoomDelta(-1);
    });
}

function applyZoomDelta(delta) {
    if (mapView && typeof mapView.zoom === 'number') {
        var target = mapView.zoom + delta;
        mapView.goTo({ zoom: target }, { animate: true, duration: 200 }).catch(function () {});
    }
}

var lastSyncedZoom = null;
function startMapZoomWatch() {
    if (!mapView || typeof mapView.watch !== 'function') { return; }
    lastSyncedZoom = Math.round(mapView.zoom);
    mapView.watch('zoom', function (newZoom) {
        if (!autoNavPlugin) { return; }
        var rounded = Math.round(newZoom);
        if (lastSyncedZoom === null || rounded === lastSyncedZoom) { return; }
        var delta = rounded - lastSyncedZoom;
        lastSyncedZoom = rounded;
        if (delta > 0 && typeof autoNavPlugin.zoomIn === 'function') {
            for (var i = 0; i < delta; i++) { autoNavPlugin.zoomIn(); }
        } else if (delta < 0 && typeof autoNavPlugin.zoomOut === 'function') {
            for (var j = 0; j < -delta; j++) { autoNavPlugin.zoomOut(); }
        }
    });
}

function startApp() {
    // Do not set Android Auto route subtitle here — prefer native car service as source of truth.
    // The native Android Auto service will update the subtitle when it has a location.

    initArcGisJsMap();
    startLocationWatch();
}

function requestRequiredPermissions(done) {
    if (cordova.platformId !== 'android' || !cordova.plugins || !cordova.plugins.permissions) {
        done();
        return;
    }

    var permissions = cordova.plugins.permissions;
    var required = [
        permissions.ACCESS_FINE_LOCATION,
        permissions.ACCESS_COARSE_LOCATION,
        permissions.ACTIVITY_RECOGNITION,
        permissions.POST_NOTIFICATIONS
    ];

    permissions.requestPermissions(
        required,
        function () { done(); },
        function (err) {
            console.warn('Permission request failed', err);
            setStatus('Please allow location permission for Android Auto.');
            done();
        }
    );
}

async function initArcGisJsMap() {
    try {
        if (!window.$arcgis || typeof window.$arcgis.import !== 'function') {
            setStatus('ArcGIS JS API 5.0 not loaded.');
            return;
        }

        var modules = await window.$arcgis.import([
            '@arcgis/core/Map.js',
            '@arcgis/core/Basemap.js',
            '@arcgis/core/layers/TileLayer.js',
            '@arcgis/core/views/MapView.js',
            '@arcgis/core/Graphic.js',
            '@arcgis/core/layers/GraphicsLayer.js'
        ]);

        var Map = modules[0].default || modules[0];
        var Basemap = modules[1].default || modules[1];
        var TileLayer = modules[2].default || modules[2];
        var MapView = modules[3].default || modules[3];
        GraphicCtor = modules[4].default || modules[4];
        var GraphicsLayer = modules[5].default || modules[5];

        // Use ArcGIS raster street tiles to avoid OSM host connectivity failures.
        var streetLayer = new TileLayer({
            url: 'https://services.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer'
        });
        map = new Map({
            basemap: new Basemap({
                baseLayers: [streetLayer]
            })
        });

        patrolPointsLayer = new GraphicsLayer({ id: 'patrol-points-layer' });
        currentLocationLayer = new GraphicsLayer({ id: 'current-location-layer' });
        map.addMany([patrolPointsLayer, currentLocationLayer]);

        mapView = new MapView({
            container: 'mapView',
            map: map,
            center: [-73.7562, 42.6526],
            zoom: DEFAULT_ZOOM,
            ui: { components: [] },
            constraints: { rotationEnabled: true }
        });

        mapView.when(function () {
            setStatus('Map ready (ArcGIS 5.0).');
            startMapZoomWatch();
        }, function (err) {
            console.warn('ArcGIS MapView init error', err);
            setStatus('Map init error: ' + JSON.stringify(err));
        });
    } catch (err) {
        console.warn('ArcGIS 5.0 module load error', err);
        setStatus('ArcGIS load error: ' + (err && err.message ? err.message : JSON.stringify(err)));
    }
}

var aaLocationPollInterval = null;

function startLocationWatch() {
    // Try to poll Android Auto plugin for its native GPS location
    var hasAutoNavLocation = autoNavPlugin && typeof autoNavPlugin.getVehicleLocation === 'function';
    if (hasAutoNavLocation) {
        console.log('Starting Android Auto GPS polling...');
        startAutoNavLocationPoll();
    }

    // If Android Auto location is available, do not run browser geolocation in parallel.
    // Running both sources can cause jumps between mock/injected and real device GPS.
    if (hasAutoNavLocation) {
        return;
    }

    // Browser geolocation fallback only when Android Auto source is unavailable.
    if (!navigator.geolocation || browserWatchId != null) {
        return;
    }

    browserWatchId = navigator.geolocation.watchPosition(
        function (pos) {
            var lat = pos && pos.coords ? pos.coords.latitude : null;
            var lon = pos && pos.coords ? pos.coords.longitude : null;
            var heading = extractHeadingDeg(pos);
            if (lat == null || lon == null) { return; }
            // Only use browser GPS if AA isn't providing location
            if (!lastLocationSample || lastLocationSample.source !== 'android-auto') {
                handleLocationSample(lat, lon, 'phone-gps', heading);
            }
        },
        function (err) {
            var errMsg = 'Phone GPS unavailable';
            if (err && err.code === 1) {
                errMsg = 'Location permission denied';
            } else if (err && err.message) {
                errMsg = 'GPS error: ' + err.message;
            }
            console.warn('Browser geolocation error:', err);
            if (!lastLocationSample) {
                setStatus(errMsg);
            }
        },
        {
            enableHighAccuracy: true,
            timeout: 15000,
            maximumAge: 1000
        }
    );
}

function startAutoNavLocationPoll() {
    // Poll Android Auto plugin for vehicle location every 500ms
    aaLocationPollInterval = setInterval(function () {
        if (!autoNavPlugin || typeof autoNavPlugin.getVehicleLocation !== 'function') {
            return;
        }

        lastAutoNavPollTs = Date.now();

        autoNavPlugin.getVehicleLocation(
            function (location) {
                if (location && typeof location.latitude === 'number' && typeof location.longitude === 'number') {
                    lastAutoNavSuccessTs = Date.now();
                    lastAutoNavError = '';
                    lastNativeLocationTs = typeof location.nativeTimestamp === 'number' ? location.nativeTimestamp : 0;
                    lastNativeProvider = location.nativeProvider || '';
                    lastNativeMock = !!location.nativeMock;
                    lastPositionSource = location.positionSource || 'unknown';
                    var heading = typeof location.heading === 'number' ? location.heading : null;
                    var sample = {
                        lat: location.latitude,
                        lon: location.longitude,
                        source: 'android-auto',
                        headingDeg: heading
                    };
                    handleLocationSample(location.latitude, location.longitude, 'android-auto', heading);
                }
            },
            function (err) {
                    lastAutoNavError = err && err.message ? err.message : String(err || 'unknown error');
                console.warn('Android Auto getVehicleLocation error:', err);
            }
        );
    }, 500);
}

function extractHeadingDeg(source) {
    var raw = source && source.coords ? source.coords.heading : (source ? source.heading : null);
    if (typeof raw !== 'number' || !isFinite(raw) || raw < 0) {
        return null;
    }
    return normalizeHeadingDeg(raw);
}

function normalizeHeadingDeg(heading) {
    var normalized = heading % 360;
    if (normalized < 0) {
        normalized += 360;
    }
    return normalized;
}

function handleLocationSample(lat, lon, source, heading) {
    if (typeof heading === 'number' && isFinite(heading)) {
        currentHeadingDeg = normalizeHeadingDeg(heading);
    }

    lastLocationSample = {
        lat: lat,
        lon: lon,
        source: source,
        headingDeg: currentHeadingDeg
    };
        lastLocationUpdateTs = Date.now();

    renderCurrentLocation(lat, lon);

    // Keep Android Auto native/service location as source of truth.
    // Avoid feeding android-auto samples back into the bridge to prevent source arbitration loops.
    updateRouteInfo(lat, lon, source);

    if (Object.keys(pointsById).length > 0) {
        applyAndroidAutoDiff([], [], []);
    }

    maybeQueryPatrolPoints(lat, lon);
    setStatus('GPS(' + source + '): ' + lat.toFixed(5) + ', ' + lon.toFixed(5));
        updateDebugStatus();
    updatePatrolStatus();
}

    function startDebugStatusTimer() {
        if (debugStatusInterval != null) {
            return;
        }
        debugStatusInterval = setInterval(updateDebugStatus, 1000);
        updateDebugStatus();
    }

    function updateDebugStatus() {
        var node = document.getElementById('debugStatus');
        if (!node) {
            return;
        }

        var source = lastLocationSample ? lastLocationSample.source : 'none';
        var sampleAgeMs = lastLocationUpdateTs ? (Date.now() - lastLocationUpdateTs) : null;
    var nativeAgeMs = lastNativeLocationTs ? (Date.now() - lastNativeLocationTs) : null;
    var pollAgeMs = lastAutoNavPollTs ? (Date.now() - lastAutoNavPollTs) : null;
    var successAgeMs = lastAutoNavSuccessTs ? (Date.now() - lastAutoNavSuccessTs) : null;
    var lines = [
        'Debug: mode=' + currentLocationMode + ' source=' + source + ' positionSource=' + lastPositionSource,
        'sampleAge=' + formatAge(sampleAgeMs)
            + ' nativeAge=' + formatAge(nativeAgeMs)
            + ' pollAge=' + formatAge(pollAgeMs)
            + ' successAge=' + formatAge(successAgeMs)
    ];

    if (lastLocationSample) {
        lines.push('lat=' + lastLocationSample.lat.toFixed(5)
            + ' lon=' + lastLocationSample.lon.toFixed(5)
            + ' heading=' + Math.round(currentHeadingDeg || 0));
    }

    lines.push('nativeProvider=' + (lastNativeProvider || 'n/a') + ' nativeMock=' + (lastNativeMock ? 'yes' : 'no'));
            lines.push('lastError=' + lastAutoNavError);
        }

        node.textContent = lines.join('\n');
    }

    function formatAge(ageMs) {
        if (ageMs == null) {
            return 'n/a';
        }
        if (ageMs < 1000) {
            return ageMs + 'ms';
        }
        return (ageMs / 1000).toFixed(1) + 's';
    }

function renderCurrentLocation(lat, lon) {
    if (!mapView || !GraphicCtor || !currentLocationLayer) {
        return;
    }

    if (currentLocationGraphic) {
        currentLocationLayer.remove(currentLocationGraphic);
    }
    if (currentLocationLabelGraphic) {
        currentLocationLayer.remove(currentLocationLabelGraphic);
    }

    var headingAngle = (typeof currentHeadingDeg === 'number' && isFinite(currentHeadingDeg))
        ? currentHeadingDeg : 0;

    // North-up: vehicle icon rotates with heading, map stays fixed.
    // Heading-up: map rotates to face heading, so vehicle always points "up" (angle=0).
    var iconAngle = headingModeEnabled ? 0 : headingAngle;

    currentLocationGraphic = new GraphicCtor({
        geometry: {
            type: 'point',
            longitude: lon,
            latitude: lat
        },
        symbol: {
            type: 'picture-marker',
            url: 'assets/images/blackVehicle.png',
            width: 36,
            height: 36,
            angle: iconAngle
        }
    });

    currentLocationLabelGraphic = null;
    currentLocationLayer.add(currentLocationGraphic);

    mapView.goTo({ center: [lon, lat] }, { animate: false }).catch(function () { });

    // Heading-up: keep map rotation locked to heading as position updates
    if (headingModeEnabled) {
        var targetRot = -headingAngle;
        if (Math.abs(((mapView.rotation || 0) - targetRot + 540) % 360 - 180) > 0.8) {
            mapView.goTo({ rotation: targetRot }, { animate: false }).catch(function () {});
        }
    }

    updateCompassImage(headingAngle);
}

function updateCompassImage(headingAngle) {
    var compassImg = document.querySelector('#compassBtn img');
    if (!compassImg) { return; }
    // North-up: map is fixed, compass stays at default (no rotation).
    // Heading-up: map rotates to heading, so compass shows where north is (opposite direction).
    var svgRotation = headingModeEnabled ? -headingAngle : 0;
    compassImg.style.transform = 'rotate(' + svgRotation + 'deg)';
}

function fetchPatrolPoints(extent) {
    var queryUrl = PATROL_POINTS_FEATURE_SERVICE_URL
        + '?geometry=' + encodeURIComponent(JSON.stringify(extent))
        + '&geometryType=esriGeometryEnvelope'
        + '&spatialRel=esriSpatialRelIntersects'
        + '&inSR=4326'
        + '&where=' + encodeURIComponent('1=1')
        + '&returnGeometry=true'
        + '&outFields=' + encodeURIComponent('OBJECTID,ID,SymbolKey')
        + '&outSR=4326'
        + '&f=pjson';

    fetch(queryUrl)
        .then(function (response) {
            return response.json();
        })
        .then(function (data) {
            queryPending = false;
            processPatrolPointResults(data && data.features ? data.features : []);
        })
        .catch(function (err) {
            queryPending = false;
            console.warn('Failed loading patrol point feature service', err);
        });
}

function maybeQueryPatrolPoints(lat, lon) {
    var now = Date.now();
    if (queryPending) {
        return;
    }
    if ((now - lastQueryTs) < QUERY_MIN_INTERVAL_MS) {
        return;
    }
    if (lastQueryLat !== null && distanceMetres(lastQueryLat, lastQueryLon, lat, lon) < QUERY_MIN_MOVE_METRES) {
        return;
    }

    queryPending = true;
    lastQueryLat = lat;
    lastQueryLon = lon;
    lastQueryTs = now;
    fetchPatrolPoints(buildExtent(lat, lon, QUERY_EXTENT_METRES));
}

function initQueryExtentControl() {
    var input = document.getElementById('queryExtentInput');
    var applyButton = document.getElementById('queryExtentApply');
    if (!input || !applyButton) {
        return;
    }

    var saved = parseInt(window.localStorage.getItem(QUERY_EXTENT_STORAGE_KEY), 10);
    if (isFinite(saved) && saved >= 50) {
        QUERY_EXTENT_METRES = saved;
    }
    input.value = String(QUERY_EXTENT_METRES);

    if (autoNavPlugin && typeof autoNavPlugin.setQueryExtent === 'function') {
        autoNavPlugin.setQueryExtent(QUERY_EXTENT_METRES, null, function (err) {
            console.warn('Failed to sync query extent to Android Auto', err);
        });
    }

    function applyValue() {
        var nextValue = parseInt(input.value, 10);
        if (!isFinite(nextValue) || nextValue < 50) {
            setStatus('Query extent must be at least 50 meters.');
            input.value = String(QUERY_EXTENT_METRES);
            return;
        }

        QUERY_EXTENT_METRES = nextValue;
        window.localStorage.setItem(QUERY_EXTENT_STORAGE_KEY, String(QUERY_EXTENT_METRES));
        if (autoNavPlugin && typeof autoNavPlugin.setQueryExtent === 'function') {
            autoNavPlugin.setQueryExtent(QUERY_EXTENT_METRES, null, function (err) {
                console.warn('Failed to sync query extent to Android Auto', err);
            });
        }
        updatePatrolStatus();

        if (lastLocationSample) {
            fetchPatrolPoints(buildExtent(lastLocationSample.lat, lastLocationSample.lon, QUERY_EXTENT_METRES));
        }

        setStatus('Query extent set to ' + QUERY_EXTENT_METRES + ' meters. Querying...');
    }

    applyButton.addEventListener('click', applyValue);
    input.addEventListener('keydown', function (evt) {
        if (evt.key === 'Enter') {
            evt.preventDefault();
            applyValue();
        }
    });
}

function initLocationModeControl() {
    var select = document.getElementById('locationModeSelect');
    var applyButton = document.getElementById('locationModeApply');
    if (!select || !applyButton) {
        return;
    }

    var savedMode = window.localStorage.getItem(LOCATION_MODE_STORAGE_KEY);
    if (savedMode === LOCATION_MODE_SIMULATOR || savedMode === LOCATION_MODE_REAL) {
        currentLocationMode = savedMode;
    }
    select.value = currentLocationMode;

    if (autoNavPlugin && typeof autoNavPlugin.getLocationMode === 'function') {
        autoNavPlugin.getLocationMode(function (mode) {
            if (mode === LOCATION_MODE_SIMULATOR || mode === LOCATION_MODE_REAL) {
                currentLocationMode = mode;
                select.value = mode;
                window.localStorage.setItem(LOCATION_MODE_STORAGE_KEY, mode);
            }
        }, function () {});
    }

    function applyMode() {
        var nextMode = select.value === LOCATION_MODE_SIMULATOR ? LOCATION_MODE_SIMULATOR : LOCATION_MODE_REAL;
        currentLocationMode = nextMode;
        window.localStorage.setItem(LOCATION_MODE_STORAGE_KEY, nextMode);

        if (autoNavPlugin && typeof autoNavPlugin.setLocationMode === 'function') {
            autoNavPlugin.setLocationMode(nextMode, function () {
                setStatus('Location mode: ' + (nextMode === LOCATION_MODE_SIMULATOR ? 'Simulator' : 'Real vehicle'));
            }, function (err) {
                console.warn('Failed to set location mode', err);
                setStatus('Failed to set location mode.');
            });
            return;
        }

        setStatus('Location mode saved for next plugin sync.');
    }

    applyButton.addEventListener('click', applyMode);
}

function processPatrolPointResults(features) {
    patrolFetchedCount = Array.isArray(features) ? features.length : 0;
    var incomingById = {};
    var toAdd = [];
    var toUpdate = [];
    var toRemove = [];

    features.forEach(function (feature) {
        var geometry = feature && feature.geometry ? feature.geometry : {};
        var attributes = feature && feature.attributes ? feature.attributes : {};
        if (typeof geometry.y !== 'number' || typeof geometry.x !== 'number') {
            return;
        }

        var id = String(attributes.OBJECTID || attributes.ID || (geometry.y + ',' + geometry.x));
        var label = String(attributes.ID || id);
        incomingById[id] = {
            id: id,
            latitude: geometry.y,
            longitude: geometry.x,
            label: label
        };
    });

    Object.keys(incomingById).forEach(function (id) {
        var nextPoint = incomingById[id];
        var existingPoint = pointsById[id];
        if (!existingPoint) {
            toAdd.push(nextPoint);
            return;
        }
        if (existingPoint.latitude !== nextPoint.latitude
            || existingPoint.longitude !== nextPoint.longitude
            || existingPoint.label !== nextPoint.label) {
            toUpdate.push(nextPoint);
        }
    });

    Object.keys(pointsById).forEach(function (id) {
        if (!incomingById[id]) {
            toRemove.push(id);
        }
    });

    pointsById = incomingById;
    applyPhoneMapDiff(toAdd, toUpdate, toRemove);
    patrolDrawnCount = Object.keys(pointsById).length;
    updatePatrolStatus();
    if (lastLocationSample) {
        updateRouteInfo(lastLocationSample.lat, lastLocationSample.lon, lastLocationSample.source);
    }
}

function applyPhoneMapDiff(toAdd, toUpdate, toRemove) {
    if (!patrolPointsLayer || !GraphicCtor) {
        return;
    }

    toRemove.forEach(function (id) {
        removePatrolGraphicById(id);
    });

    toUpdate.forEach(function (point) {
        removePatrolGraphicById(point.id);
    });

    var graphics = toAdd.concat(toUpdate).map(function (point) {
        return makePatrolPointGraphic(point);
    });

    if (graphics.length > 0) {
        patrolPointsLayer.addMany(graphics);
    }
}

function removePatrolGraphicById(id) {
    var graphicId = patrolGraphicId(id);
    var toRemove = null;
    patrolPointsLayer.graphics.forEach(function (g) {
        if (!toRemove && g && g.attributes && g.attributes.id === graphicId) {
            toRemove = g;
        }
    });
    if (toRemove) {
        patrolPointsLayer.remove(toRemove);
    }
}

function pointsWithinAutoRadius(points) {
    if (!lastLocationSample) { return []; }
    var lat = lastLocationSample.lat;
    var lon = lastLocationSample.lon;
    return points.filter(function (p) {
        return distanceMetres(lat, lon, p.latitude, p.longitude) <= AUTO_MAX_DISTANCE_METRES;
    });
}

function applyAndroidAutoDiff(toAdd, toUpdate, toRemove) {
    if (!autoNavPlugin) {
        return;
    }

    var filteredAdd = pointsWithinAutoRadius(toAdd);
    var filteredUpdate = pointsWithinAutoRadius(toUpdate);

    var allCurrentIds = Object.keys(pointsById);
    var outOfRange = allCurrentIds.filter(function (id) {
        var p = pointsById[id];
        return !lastLocationSample ||
            distanceMetres(lastLocationSample.lat, lastLocationSample.lon, p.latitude, p.longitude) > AUTO_MAX_DISTANCE_METRES;
    });
    var filteredRemove = toRemove.concat(outOfRange);

    if (typeof autoNavPlugin.updateFeaturePoints === 'function') {
        autoNavPlugin.updateFeaturePoints(filteredAdd, filteredRemove, filteredUpdate);
        return;
    }

    if (typeof autoNavPlugin.setFeaturePoints === 'function') {
        autoNavPlugin.setFeaturePoints(allCurrentIds
            .filter(function (id) { return outOfRange.indexOf(id) === -1; })
            .map(function (id) { return pointsById[id]; }));
    }
}

function patrolGraphicId(id) {
    return 'patrol_' + id;
}

function makePatrolPointGraphic(point) {
    return new GraphicCtor({
        geometry: {
            type: 'point',
            longitude: point.longitude,
            latitude: point.latitude
        },
        symbol: {
            type: 'simple-marker',
            style: 'circle',
            color: [37, 99, 235, 0.86],
            size: 10,
            outline: {
                color: [255, 255, 255, 1],
                width: 1.2
            }
        },
        attributes: {
            id: patrolGraphicId(point.id),
            pointId: point.id,
            label: point.label
        }
    });
}

function buildExtent(lat, lon, radiusMetres) {
    var latDelta = radiusMetres / 111320;
    var lonDenominator = 111320 * Math.cos(lat * Math.PI / 180);
    var lonDelta = lonDenominator === 0 ? 0 : radiusMetres / lonDenominator;
    return {
        xmin: lon - lonDelta,
        ymin: lat - latDelta,
        xmax: lon + lonDelta,
        ymax: lat + latDelta,
        spatialReference: { wkid: 4326 }
    };
}

function distanceMetres(lat1, lon1, lat2, lon2) {
    var earthRadius = 6371000;
    var lat1Rad = lat1 * Math.PI / 180;
    var lat2Rad = lat2 * Math.PI / 180;
    var deltaLat = (lat2 - lat1) * Math.PI / 180;
    var deltaLon = (lon2 - lon1) * Math.PI / 180;
    var a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
        + Math.cos(lat1Rad) * Math.cos(lat2Rad)
        * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
    return earthRadius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

function setStatus(text) {
    var node = document.getElementById('locationStatus');
    if (node) { node.textContent = text; }
}

function updatePatrolStatus() {
    var node = document.getElementById('patrolStatus');
    var text = 'Patrol points: fetched ' + patrolFetchedCount + ', drawn ' + patrolDrawnCount
        + ', extent ' + QUERY_EXTENT_METRES + 'm';
    if (node) {
        node.textContent = text;
    }
}

function updateRouteInfo(lat, lon, source) {
    // Keep Android Auto route subtitle fully native; do not overwrite from web layer.
    if (!autoNavPlugin || typeof autoNavPlugin.setRouteInfo !== 'function') {
        return;
    }
    return;
    var subtitle = 'Tracking '
        + lat.toFixed(5)
        + ', '
        + lon.toFixed(5)
        + ' | PP '
        + patrolDrawnCount
        + '/'
        + patrolFetchedCount;
    autoNavPlugin.setRouteInfo('AutoArcGIS', subtitle);
}
