package com.cordova.androidauto;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AndroidAutoNavState {
    private static final String TAG = "AndroidAutoNavState";
    private static final long EXTERNAL_OVERRIDE_MS = 15000L;
    private static final long MOCK_LOCATION_LOCK_MS = 120000L;

    static final String LOCATION_MODE_REAL = "real-vehicle";
    static final String LOCATION_MODE_SIMULATOR = "simulator";

    interface StateListener {
        void onStateChanged();
    }

    private static String routeTitle = "Map ready";
    private static String routeSubtitle = "";
    private static double latitude = 42.6526;
    private static double longitude = -73.7562;
    private static double headingDegrees = 0.0;
    private static boolean hasPosition;
    private static long externalOverrideUntilMs;
    private static long mockLocationLockUntilMs;
    private static String locationMode = LOCATION_MODE_REAL;
    private static long lastNativeLocationTs;
    private static String lastNativeProvider = "";
    private static boolean lastNativeMock;
    private static String lastVehiclePositionSource = "none";
    private static final Map<String, FeaturePoint> featurePointsById = new LinkedHashMap<>();
    private static StateListener stateListener;

    static final class FeaturePoint {
        final String id;
        final double latitude;
        final double longitude;
        final String label;

        FeaturePoint(String id, double latitude, double longitude, String label) {
            this.id = id == null ? "" : id;
            this.latitude = latitude;
            this.longitude = longitude;
            this.label = label == null ? "" : label;
        }
    }

    private AndroidAutoNavState() {}

    private static void notifyStateChanged() {
        StateListener listener = stateListener;
        if (listener != null) {
            listener.onStateChanged();
        }
    }

    static synchronized void setStateListener(StateListener listener) {
        stateListener = listener;
    }

    static synchronized void clearStateListener(StateListener listener) {
        if (stateListener == listener) {
            stateListener = null;
        }
    }

    static synchronized void setRouteInfo(String title, String subtitle) {
        routeTitle = title == null ? "" : title;
        routeSubtitle = subtitle == null ? "" : subtitle;
        notifyStateChanged();
    }
    static synchronized void setVehiclePosition(double lat, double lon) {
        setVehiclePosition(lat, lon, "unknown");
    }

    static synchronized void setVehiclePosition(double lat, double lon, String source) {
        if (hasPosition) {
            double deltaLat = lat - latitude;
            double deltaLon = lon - longitude;
            double distanceSquared = (deltaLat * deltaLat) + (deltaLon * deltaLon);
            if (distanceSquared > 1e-10) {
                headingDegrees = computeBearing(latitude, longitude, lat, lon);
            }
        }
        latitude = lat;
        longitude = lon;
        hasPosition = true;
        lastVehiclePositionSource = source == null ? "unknown" : source;
        // Update route subtitle so the Android Auto overlay shows live position instead of the waiting message
        routeSubtitle = String.format("%.5f, %.5f", latitude, longitude);
        Log.d(TAG, "state updated lat=" + latitude + " lon=" + longitude + " heading=" + headingDegrees + " subtitle=" + routeSubtitle + " source=" + lastVehiclePositionSource);
        notifyStateChanged();
    }

    static synchronized void markExternalVehiclePositionUpdate() {
        externalOverrideUntilMs = System.currentTimeMillis() + EXTERNAL_OVERRIDE_MS;
    }

    static synchronized void markNativeVehicleLocation(String provider, boolean isMockLocation) {
        lastNativeLocationTs = System.currentTimeMillis();
        lastNativeProvider = provider == null ? "unknown" : provider;
        lastNativeMock = isMockLocation;
    }

    static synchronized boolean shouldAcceptNativeVehicleLocation() {
        return System.currentTimeMillis() >= externalOverrideUntilMs;
    }

    static synchronized boolean shouldAcceptNativeVehicleLocation(boolean isMockLocation) {
        if (LOCATION_MODE_REAL.equals(locationMode)) {
            return true;
        }

        long now = System.currentTimeMillis();
        if (isMockLocation) {
            // Keep mock/injected GPS as source-of-truth for a while to prevent jumps to real fixes.
            mockLocationLockUntilMs = now + MOCK_LOCATION_LOCK_MS;
            return true;
        }
        if (now < externalOverrideUntilMs) {
            return false;
        }
        return now >= mockLocationLockUntilMs;
    }

    static synchronized void setLocationMode(String nextMode) {
        if (!LOCATION_MODE_SIMULATOR.equals(nextMode)) {
            nextMode = LOCATION_MODE_REAL;
        }
        locationMode = nextMode;
        if (LOCATION_MODE_REAL.equals(locationMode)) {
            externalOverrideUntilMs = 0L;
            mockLocationLockUntilMs = 0L;
        }
        Log.d(TAG, "location mode=" + locationMode);
        notifyStateChanged();
    }

    static synchronized String getLocationMode() {
        return locationMode;
    }
    static synchronized String getRouteTitle() { return routeTitle; }
    static synchronized String getRouteSubtitle() { return routeSubtitle; }
    static synchronized double getLatitude() { return latitude; }
    static synchronized double getLongitude() { return longitude; }
    static synchronized double getHeadingDegrees() { return headingDegrees; }
    static synchronized long getLastNativeLocationTs() { return lastNativeLocationTs; }
    static synchronized String getLastNativeProvider() { return lastNativeProvider; }
    static synchronized boolean isLastNativeMock() { return lastNativeMock; }
    static synchronized String getLastVehiclePositionSource() { return lastVehiclePositionSource; }
    static synchronized List<FeaturePoint> getFeaturePoints() {
        return List.copyOf(featurePointsById.values());
    }

    static synchronized void setFeaturePoints(JSONArray points) {
        featurePointsById.clear();
        if (points != null) {
            for (int i = 0; i < points.length(); i++) {
                JSONObject point = points.optJSONObject(i);
                if (point == null) {
                    continue;
                }
                double lat = point.optDouble("latitude", Double.NaN);
                double lon = point.optDouble("longitude", Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lon)) {
                    continue;
                }
                String id = pointIdFor(point, i, lat, lon);
                String label = point.optString("label", "");
                featurePointsById.put(id, new FeaturePoint(id, lat, lon, label));
            }
        }
        Log.d(TAG, "feature points updated count=" + featurePointsById.size());
        notifyStateChanged();
    }

    static synchronized void updateFeaturePoints(JSONArray addedPoints, JSONArray removedPointIds, JSONArray updatedPoints) {
        int addedCount = applyFeaturePointAddsOrUpdates(addedPoints, false);
        int updatedCount = applyFeaturePointAddsOrUpdates(updatedPoints, true);
        int removedCount = removeFeaturePoints(removedPointIds);
        Log.d(TAG, "feature points diff added=" + addedCount + " updated=" + updatedCount + " removed=" + removedCount + " total=" + featurePointsById.size());
        notifyStateChanged();
    }

    private static int applyFeaturePointAddsOrUpdates(JSONArray points, boolean overwrite) {
        if (points == null) {
            return 0;
        }

        int applied = 0;
        for (int i = 0; i < points.length(); i++) {
            JSONObject point = points.optJSONObject(i);
            if (point == null) {
                continue;
            }
            double lat = point.optDouble("latitude", Double.NaN);
            double lon = point.optDouble("longitude", Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lon)) {
                continue;
            }
            String id = pointIdFor(point, i, lat, lon);
            if (!overwrite && featurePointsById.containsKey(id)) {
                continue;
            }
            String label = point.optString("label", "");
            featurePointsById.put(id, new FeaturePoint(id, lat, lon, label));
            applied++;
        }
        return applied;
    }

    private static int removeFeaturePoints(JSONArray pointIds) {
        if (pointIds == null) {
            return 0;
        }

        int removed = 0;
        for (int i = 0; i < pointIds.length(); i++) {
            String id = pointIds.optString(i, "");
            if (id.isEmpty()) {
                continue;
            }
            if (featurePointsById.remove(id) != null) {
                removed++;
            }
        }
        return removed;
    }

    private static String pointIdFor(JSONObject point, int index, double lat, double lon) {
        String explicitId = point.optString("id", "");
        if (!explicitId.isEmpty()) {
            return explicitId;
        }
        return index + ":" + lat + "," + lon;
    }

    private static double computeBearing(double startLat, double startLon, double endLat, double endLon) {
        double startLatRad = Math.toRadians(startLat);
        double endLatRad = Math.toRadians(endLat);
        double deltaLonRad = Math.toRadians(endLon - startLon);
        double y = Math.sin(deltaLonRad) * Math.cos(endLatRad);
        double x = (Math.cos(startLatRad) * Math.sin(endLatRad))
                - (Math.sin(startLatRad) * Math.cos(endLatRad) * Math.cos(deltaLonRad));
        double bearing = Math.toDegrees(Math.atan2(y, x));
        return (bearing + 360.0) % 360.0;
    }
}