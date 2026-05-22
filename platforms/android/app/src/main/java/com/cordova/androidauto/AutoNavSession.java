package com.cordova.androidauto;

import android.Manifest;
import android.content.Intent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.car.app.AppManager;
import androidx.car.app.Screen;
import androidx.car.app.Session;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AutoNavSession extends Session implements AndroidAutoNavState.StateListener {
    private static final String TAG = "AutoNavSession";
    private static final String PATROL_POINTS_FEATURE_SERVICE_URL = "https://devmulti29.transfinder.com/arcgis/rest/services/pf0904/pf0904PatrolPointsFeatureService/MapServer/0/query";
    private static final long QUERY_MIN_INTERVAL_MS = 10_000L;
    private static final double QUERY_MIN_MOVE_METRES = 100.0;
    private static final double DEFAULT_QUERY_EXTENT_METRES = 500.0;
    private static volatile double queryExtentMetres = DEFAULT_QUERY_EXTENT_METRES;

    interface RendererCommand {
        void apply(@NonNull AutoNavSurfaceRenderer renderer);
    }

    private static AutoNavSurfaceRenderer activeRenderer;
    private static AutoNavSession activeSession;
    private AutoNavScreen screen;
    private AutoNavSurfaceRenderer surfaceRenderer;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback fusedLocationCallback;
    private LocationManager locationManager;
    private final ExecutorService patrolFetchExecutor = Executors.newSingleThreadExecutor();
    private double lastQueryLat = Double.NaN;
    private double lastQueryLon = Double.NaN;
    private long lastQueryTs;
    private volatile boolean queryPending;
    private JSONArray lastFetchedPoints = new JSONArray();

    private final LocationListener nativeLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            onNativeLocationChanged(location);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {}

        @Override
        public void onProviderEnabled(@NonNull String provider) {}

        @Override
        public void onProviderDisabled(@NonNull String provider) {}
    };

    static void withActiveRenderer(@NonNull RendererCommand command) {
        AutoNavSurfaceRenderer renderer = activeRenderer;
        if (renderer != null) {
            command.apply(renderer);
        }
    }

    static void setQueryExtentMetres(double metres) {
        if (Double.isNaN(metres)) {
            return;
        }
        queryExtentMetres = Math.max(50.0, metres);
        AutoNavSession session = activeSession;
        if (session != null) {
            session.onQueryExtentUpdated();
        }
    }

    @NonNull
    @Override
    public Screen onCreateScreen(@NonNull Intent intent) {
        surfaceRenderer = new AutoNavSurfaceRenderer(getCarContext());
        activeRenderer = surfaceRenderer;
        activeSession = this;
        screen = new AutoNavScreen(getCarContext(), surfaceRenderer);
        AppManager appManager = getCarContext().getCarService(AppManager.class);
        appManager.setSurfaceCallback(surfaceRenderer);
        AndroidAutoNavState.setStateListener(this);
        startNativeLocationWatch();
        return screen;
    }

    public void onDestroy() {
        stopNativeLocationWatch();
        queryPending = false;
        patrolFetchExecutor.shutdownNow();
        AndroidAutoNavState.clearStateListener(this);
        activeSession = null;
        activeRenderer = null;
    }

    @Override
    public void onStateChanged() {
        if (screen != null) {
            getCarContext().getMainExecutor().execute(() -> {
                if (surfaceRenderer != null) {
                    surfaceRenderer.renderNow();
                    surfaceRenderer.fetchTilesAsync();
                }
            });
        }
    }

    private void onQueryExtentUpdated() {
        lastQueryLat = Double.NaN;
        lastQueryLon = Double.NaN;
        lastQueryTs = 0L;
        double lat = AndroidAutoNavState.getLatitude();
        double lon = AndroidAutoNavState.getLongitude();
        maybeFetchPatrolPoints(lat, lon);
    }

    private void startNativeLocationWatch() {
        if (!hasLocationPermission()) {
            Log.w(TAG, "Location permission not granted; Android Auto native GPS unavailable until app permission is granted");
            return;
        }

        Context context = getCarContext();
        if (context == null) {
            return;
        }

        // Run both providers. Fused can stall on some devices while LocationManager
        // continues to emit fresh GPS/network fixes.
        startFusedLocationWatch(context);
        startLocationManagerWatch(context);
    }

    private boolean startFusedLocationWatch(Context context) {
        try {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(context);
            fusedLocationClient.getLastLocation().addOnSuccessListener(this::onNativeLocationChanged);

            fusedLocationCallback = new LocationCallback() {
                @Override
                public void onLocationResult(LocationResult locationResult) {
                    if (locationResult == null) {
                        return;
                    }
                    onNativeLocationChanged(locationResult.getLastLocation());
                }
            };

            LocationRequest request = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                    .setMinUpdateIntervalMillis(500L)
                    .setMinUpdateDistanceMeters(1f)
                    .setWaitForAccurateLocation(false)
                    .build();
            fusedLocationClient.requestLocationUpdates(request, fusedLocationCallback, Looper.getMainLooper());
            Log.d(TAG, "Android Auto native fused GPS started");
            return true;
        } catch (SecurityException se) {
            Log.w(TAG, "Fused GPS start failed due to permissions", se);
        } catch (Throwable t) {
            Log.w(TAG, "Fused GPS unavailable; falling back to LocationManager", t);
        }
        return false;
    }

    private void startLocationManagerWatch(Context context) {
        locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            return;
        }
        try {
            Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last == null) {
                last = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
            onNativeLocationChanged(last);

            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, nativeLocationListener, Looper.getMainLooper());
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, nativeLocationListener, Looper.getMainLooper());
            Log.d(TAG, "Android Auto LocationManager GPS started");
        } catch (SecurityException se) {
            Log.w(TAG, "LocationManager GPS start failed", se);
        }
    }

    private void stopNativeLocationWatch() {
        try {
            if (fusedLocationClient != null && fusedLocationCallback != null) {
                fusedLocationClient.removeLocationUpdates(fusedLocationCallback);
                fusedLocationCallback = null;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error stopping fused GPS", t);
        }

        if (locationManager != null) {
            try {
                locationManager.removeUpdates(nativeLocationListener);
            } catch (SecurityException se) {
                Log.w(TAG, "Error stopping LocationManager GPS", se);
            }
        }
    }

    private boolean hasLocationPermission() {
        Context context = getCarContext();
        if (context == null) {
            return false;
        }
        boolean fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        return fine || coarse;
    }

    private void onNativeLocationChanged(Location location) {
        if (location == null) {
            return;
        }
        boolean isMockLocation = isMockLocation(location);
        AndroidAutoNavState.markNativeVehicleLocation(location.getProvider(), isMockLocation);
        if (!AndroidAutoNavState.shouldAcceptNativeVehicleLocation(isMockLocation)) {
            return;
        }
        double lat = location.getLatitude();
        double lon = location.getLongitude();
        AndroidAutoNavState.setVehiclePosition(lat, lon, "native");
        maybeFetchPatrolPoints(lat, lon);
    }

    private static boolean isMockLocation(Location location) {
        if (location == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return location.isMock();
        }
        return location.isFromMockProvider();
    }

    private void maybeFetchPatrolPoints(double lat, double lon) {
        long now = System.currentTimeMillis();
        if (queryPending) {
            return;
        }
        if ((now - lastQueryTs) < QUERY_MIN_INTERVAL_MS) {
            return;
        }
        if (!Double.isNaN(lastQueryLat) && distanceMetres(lastQueryLat, lastQueryLon, lat, lon) < QUERY_MIN_MOVE_METRES) {
            return;
        }

        queryPending = true;
        lastQueryLat = lat;
        lastQueryLon = lon;
        lastQueryTs = now;

        patrolFetchExecutor.submit(() -> {
            try {
                JSONArray newPoints = fetchPatrolPoints(lat, lon);
                if (newPoints == null) {
                    Log.w(TAG, "Null result from fetchPatrolPoints; keeping existing points");
                    return;
                }
                // Compute delta to avoid clearing existing points on update
                JSONArray toAdd = new JSONArray();
                JSONArray toRemove = new JSONArray();
                applyPointsDelta(newPoints, toAdd, toRemove);
                AndroidAutoNavState.updateFeaturePoints(toAdd, toRemove, new JSONArray());
                lastFetchedPoints = newPoints;
                Log.d(TAG, "Native fetch delta: added=" + toAdd.length() + " removed=" + toRemove.length());
            } catch (Throwable t) {
                Log.w(TAG, "Native patrol-point fetch failed", t);
            } finally {
                queryPending = false;
            }
        });
    }

    private JSONArray fetchPatrolPoints(double lat, double lon) throws Exception {
        double extentMetres = queryExtentMetres;
        double latDelta = extentMetres / 111320.0;
        double lonDenominator = 111320.0 * Math.cos(lat * Math.PI / 180.0);
        double lonDelta = lonDenominator == 0.0 ? 0.0 : extentMetres / lonDenominator;
        double xmin = lon - lonDelta;
        double ymin = lat - latDelta;
        double xmax = lon + lonDelta;
        double ymax = lat + latDelta;

        String geometry = String.format(Locale.US,
                "{\"xmin\":%.8f,\"ymin\":%.8f,\"xmax\":%.8f,\"ymax\":%.8f,\"spatialReference\":{\"wkid\":4326}}",
                xmin, ymin, xmax, ymax);

        Uri uri = Uri.parse(PATROL_POINTS_FEATURE_SERVICE_URL).buildUpon()
                .appendQueryParameter("geometry", geometry)
                .appendQueryParameter("geometryType", "esriGeometryEnvelope")
                .appendQueryParameter("spatialRel", "esriSpatialRelIntersects")
                .appendQueryParameter("inSR", "4326")
                .appendQueryParameter("where", "1=1")
                .appendQueryParameter("returnGeometry", "true")
                .appendQueryParameter("outFields", "OBJECTID,ID,SymbolKey")
                .appendQueryParameter("outSR", "4326")
                .appendQueryParameter("f", "pjson")
                .build();

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(uri.toString()).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Accept", "application/json");
            conn.connect();

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("Query failed with HTTP " + code);
            }

            BufferedInputStream bis = new BufferedInputStream(conn.getInputStream());
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = bis.read(buffer)) != -1) {
                baos.write(buffer, 0, read);
            }
            String json = baos.toString(StandardCharsets.UTF_8.name());
            JSONObject root = new JSONObject(json);
            JSONArray features = root.optJSONArray("features");
            JSONArray points = new JSONArray();
            if (features == null) {
                return points;
            }

            for (int i = 0; i < features.length(); i++) {
                JSONObject feature = features.optJSONObject(i);
                if (feature == null) {
                    continue;
                }
                JSONObject geometryObj = feature.optJSONObject("geometry");
                JSONObject attrsObj = feature.optJSONObject("attributes");
                if (geometryObj == null) {
                    continue;
                }

                double y = geometryObj.optDouble("y", Double.NaN);
                double x = geometryObj.optDouble("x", Double.NaN);
                if (Double.isNaN(y) || Double.isNaN(x)) {
                    continue;
                }

                String id = "";
                if (attrsObj != null) {
                    id = attrsObj.optString("OBJECTID", attrsObj.optString("ID", ""));
                }
                if (id.isEmpty()) {
                    id = String.valueOf(i);
                }

                JSONObject point = new JSONObject();
                point.put("id", id);
                point.put("label", id);
                point.put("latitude", y);
                point.put("longitude", x);
                points.put(point);
            }

            Log.d(TAG, "Native patrol-point fetch count=" + points.length());
            return points;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void applyPointsDelta(JSONArray newPoints, JSONArray toAdd, JSONArray toRemove) throws Exception {
        java.util.Map<String, JSONObject> oldById = buildPointMap(lastFetchedPoints);
        java.util.Map<String, JSONObject> newById = buildPointMap(newPoints);

        // Points to add: in new but not in old
        for (String id : newById.keySet()) {
            if (!oldById.containsKey(id)) {
                toAdd.put(newById.get(id));
            }
        }

        // Points to remove: in old but not in new
        for (String id : oldById.keySet()) {
            if (!newById.containsKey(id)) {
                toRemove.put(id);
            }
        }
    }

    private java.util.Map<String, JSONObject> buildPointMap(JSONArray points) throws Exception {
        java.util.Map<String, JSONObject> map = new java.util.HashMap<>();
        if (points == null) {
            return map;
        }
        for (int i = 0; i < points.length(); i++) {
            JSONObject point = points.getJSONObject(i);
            String id = point.optString("OBJECTID", point.optString("ID", point.optString("id", String.valueOf(i))));
            map.put(id, point);
        }
        return map;
    }

    private static double distanceMetres(double lat1, double lon1, double lat2, double lon2) {
        double earthRadius = 6371000.0;
        double lat1Rad = lat1 * Math.PI / 180.0;
        double lat2Rad = lat2 * Math.PI / 180.0;
        double deltaLat = (lat2 - lat1) * Math.PI / 180.0;
        double deltaLon = (lon2 - lon1) * Math.PI / 180.0;
        double a = Math.sin(deltaLat / 2.0) * Math.sin(deltaLat / 2.0)
                + Math.cos(lat1Rad) * Math.cos(lat2Rad)
                * Math.sin(deltaLon / 2.0) * Math.sin(deltaLon / 2.0);
        return earthRadius * 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
    }

}
