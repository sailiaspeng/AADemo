package com.cordova.androidauto;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class AndroidAutoNavBridge extends CordovaPlugin {
    private static final String TAG = "AndroidAutoNavBridge";
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback fusedLocationCallback;
    private LocationManager locationManager;
    private final LocationListener nativeLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            onNativeLocationChanged(location);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {}

        @Override
        public void onProviderEnabled(String provider) {}

        @Override
        public void onProviderDisabled(String provider) {}
    };

    @Override
    protected void pluginInitialize() {
        super.pluginInitialize();
        startNativeLocationWatch();
    }

    @Override
    public void onDestroy() {
        stopNativeLocationWatch();
        super.onDestroy();
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        if ("setRouteInfo".equals(action)) {
            JSONObject payload = args.optJSONObject(0);
            String title = payload != null ? payload.optString("title", "") : "";
            String subtitle = payload != null ? payload.optString("subtitle", "") : "";
            Log.d(TAG, "setRouteInfo title=" + title + " subtitle=" + subtitle);
            AndroidAutoNavState.setRouteInfo(title, subtitle);
            callbackContext.success();
            return true;
        }
        if ("setVehiclePosition".equals(action)) {
            JSONObject payload = args.optJSONObject(0);
            if (payload == null) { callbackContext.error("Missing payload"); return true; }
            double latitude = payload.optDouble("latitude", Double.NaN);
            double longitude = payload.optDouble("longitude", Double.NaN);
            if (Double.isNaN(latitude) || Double.isNaN(longitude)) { callbackContext.error("Invalid latitude/longitude"); return true; }
            Log.d(TAG, "setVehiclePosition lat=" + latitude + " lon=" + longitude);
            AndroidAutoNavState.markExternalVehiclePositionUpdate();
            AndroidAutoNavState.setVehiclePosition(latitude, longitude, "external");
            callbackContext.success();
            return true;
        }
        if ("setFeaturePoints".equals(action)) {
            JSONArray points = args.optJSONArray(0);
            AndroidAutoNavState.setFeaturePoints(points);
            callbackContext.success();
            return true;
        }
        if ("updateFeaturePoints".equals(action)) {
            JSONArray addedPoints = args.optJSONArray(0);
            JSONArray removedPointIds = args.optJSONArray(1);
            JSONArray updatedPoints = args.optJSONArray(2);
            AndroidAutoNavState.updateFeaturePoints(addedPoints, removedPointIds, updatedPoints);
            callbackContext.success();
            return true;
        }
        if ("setRotateWithHeading".equals(action)) {
            JSONObject payload = args.optJSONObject(0);
            boolean enabled = payload != null && payload.optBoolean("enabled", false);
            AutoNavSession.withActiveRenderer(renderer -> renderer.setRotateWithHeading(enabled));
            callbackContext.success();
            return true;
        }
        if ("zoomIn".equals(action)) {
            AutoNavSession.withActiveRenderer(AutoNavSurfaceRenderer::zoomIn);
            callbackContext.success();
            return true;
        }
        if ("zoomOut".equals(action)) {
            AutoNavSession.withActiveRenderer(AutoNavSurfaceRenderer::zoomOut);
            callbackContext.success();
            return true;
        }
        if ("getVehicleLocation".equals(action)) {
            try {
                JSONObject location = new JSONObject();
                location.put("latitude", AndroidAutoNavState.getLatitude());
                location.put("longitude", AndroidAutoNavState.getLongitude());
                location.put("heading", AndroidAutoNavState.getHeadingDegrees());
                location.put("nativeTimestamp", AndroidAutoNavState.getLastNativeLocationTs());
                location.put("nativeProvider", AndroidAutoNavState.getLastNativeProvider());
                location.put("nativeMock", AndroidAutoNavState.isLastNativeMock());
                location.put("positionSource", AndroidAutoNavState.getLastVehiclePositionSource());
                callbackContext.success(location);
            } catch (JSONException e) {
                callbackContext.error("Error building location object: " + e.getMessage());
            }
            return true;
        }
        if ("setQueryExtent".equals(action)) {
            JSONObject payload = args.optJSONObject(0);
            double metres = payload != null ? payload.optDouble("metres", Double.NaN) : Double.NaN;
            if (Double.isNaN(metres) || metres < 50.0) {
                callbackContext.error("Invalid query extent metres");
                return true;
            }
            AutoNavSession.setQueryExtentMetres(metres);
            callbackContext.success();
            return true;
        }
        if ("setLocationMode".equals(action)) {
            JSONObject payload = args.optJSONObject(0);
            String mode = payload != null ? payload.optString("mode", AndroidAutoNavState.LOCATION_MODE_REAL) : AndroidAutoNavState.LOCATION_MODE_REAL;
            AndroidAutoNavState.setLocationMode(mode);
            callbackContext.success();
            return true;
        }
        if ("getLocationMode".equals(action)) {
            callbackContext.success(AndroidAutoNavState.getLocationMode());
            return true;
        }
        return false;
    }

    private void startNativeLocationWatch() {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                && !hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            Log.w(TAG, "Location permissions not granted for native watch");
            return;
        }

        Context context = cordova.getContext();
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
            Log.d(TAG, "Native fused location watch started");
            return true;
        } catch (SecurityException ex) {
            Log.w(TAG, "Unable to start fused location watch", ex);
        } catch (Throwable t) {
            Log.w(TAG, "Fused location unavailable, falling back", t);
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

            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    500L,
                    0f,
                    nativeLocationListener,
                    Looper.getMainLooper()
            );
            locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    1000L,
                    0f,
                    nativeLocationListener,
                    Looper.getMainLooper()
            );
            Log.d(TAG, "Native location manager watch started");
        } catch (SecurityException ex) {
            Log.w(TAG, "Unable to start location manager watch", ex);
        }
    }

    private void stopNativeLocationWatch() {
        if (fusedLocationClient != null && fusedLocationCallback != null) {
            fusedLocationClient.removeLocationUpdates(fusedLocationCallback);
            fusedLocationCallback = null;
        }

        if (locationManager == null) {
            return;
        }
        try {
            locationManager.removeUpdates(nativeLocationListener);
        } catch (SecurityException ex) {
            Log.w(TAG, "Unable to stop native location watch", ex);
        }
    }

    private boolean hasPermission(String permission) {
        Context context = cordova != null ? cordova.getContext() : null;
        return context != null
                && ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED;
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
        AndroidAutoNavState.setVehiclePosition(location.getLatitude(), location.getLongitude(), "native");
        try {
            String subtitle = String.format("GPS (AA) %.5f, %.5f", location.getLatitude(), location.getLongitude());
            AndroidAutoNavState.setRouteInfo("AutoArcGIS", String.format("GPS(AA): %.5f, %.5f", location.getLatitude(), location.getLongitude()));
        } catch (Throwable t) {
            // ignore formatting errors
        }
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
}