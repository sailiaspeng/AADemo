package com.cordova.androidauto;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.Surface;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.car.app.SurfaceCallback;
import androidx.car.app.SurfaceContainer;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class AutoNavSurfaceRenderer implements SurfaceCallback {

    private static final String TAG = "AutoNavSurfaceRenderer";

    private static final int MIN_ZOOM = 11;
    private static final int MAX_ZOOM = 19;
    private static final int DEFAULT_ZOOM = 17;
    private static final String TILE_SOURCE = "arcgis-streets";
    private static final String ARCGIS_STREET_TILE_URL = "https://services.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/%d/%d/%d";
    private static final String ARCGIS_IMAGERY_TILE_URL = "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/%d/%d/%d";
    private static final String OSM_TILE_URL = "https://tile.openstreetmap.org/%d/%d/%d.png";
    private static final int TILE_PX = 256;

    private final Paint bgPaint    = new Paint();
    private final Paint textPaint  = new Paint();
    private final Paint markerPaint = new Paint();
    private final Paint overlayPaint = new Paint();
    private final Paint featurePointPaint = new Paint();
    private final Bitmap vehicleIcon;

    private Surface surface;
    private int surfW = 800;
    private int surfH = 400;
    private int zoomLevel = DEFAULT_ZOOM;
    private boolean rotateWithHeading;
    private boolean panModeEnabled;
    private boolean userAdjustedCamera;
    private float panOffsetPxX;
    private float panOffsetPxY;
    private Rect visibleMapArea;
    private Rect stableMapArea;

    private final Map<String, Bitmap> tileCache = new HashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    AutoNavSurfaceRenderer(Context context) {
        bgPaint.setColor(Color.rgb(200, 200, 200));
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(28f);
        textPaint.setAntiAlias(true);
        textPaint.setShadowLayer(3f, 1f, 1f, Color.BLACK);
        markerPaint.setAntiAlias(true);
        overlayPaint.setColor(Color.argb(170, 0, 0, 0));

        featurePointPaint.setColor(Color.rgb(37, 99, 235));
        featurePointPaint.setStyle(Paint.Style.FILL);
        featurePointPaint.setAntiAlias(true);
        vehicleIcon = loadVehicleIcon(context);
    }

    @Override
    public synchronized void onSurfaceAvailable(@NonNull SurfaceContainer c) {
        surface = c.getSurface();
        if (c.getWidth() > 0) surfW = c.getWidth();
        if (c.getHeight() > 0) surfH = c.getHeight();
        renderNow();
        fetchTilesAsync();
    }

    @Override public synchronized void onVisibleAreaChanged(@NonNull Rect r) {
        visibleMapArea = new Rect(r);
        renderNow();
        fetchTilesAsync();
    }
    @Override public synchronized void onStableAreaChanged(@NonNull Rect r) {
        stableMapArea = new Rect(r);
        renderNow();
        fetchTilesAsync();
    }
    @Override public synchronized void onSurfaceDestroyed(@NonNull SurfaceContainer c) { surface = null; }

    @Override
    public synchronized void onScroll(float distanceX, float distanceY) {
        if (!panModeEnabled) return;
        userAdjustedCamera = true;
        panOffsetPxX -= distanceX;
        panOffsetPxY -= distanceY;
        renderNow();
        fetchTilesAsync();
    }

    @Override
    public synchronized void onFling(float velocityX, float velocityY) {
        if (!panModeEnabled) return;
        userAdjustedCamera = true;
        panOffsetPxX += velocityX * 0.015f;
        panOffsetPxY += velocityY * 0.015f;
        renderNow();
        fetchTilesAsync();
    }

    @Override
    public synchronized void onScale(float focusX, float focusY, float scaleFactor) {
        if (scaleFactor > 1.06f) {
            zoomIn();
        } else if (scaleFactor < 0.94f) {
            zoomOut();
        }
    }

    synchronized void zoomIn() {
        userAdjustedCamera = true;
        if (zoomLevel < MAX_ZOOM) {
            zoomLevel++;
            renderNow();
            fetchTilesAsync();
        }
    }

    synchronized void zoomOut() {
        userAdjustedCamera = true;
        if (zoomLevel > MIN_ZOOM) {
            zoomLevel--;
            renderNow();
            fetchTilesAsync();
        }
    }

    synchronized void toggleRotateWithHeading() {
        setRotateWithHeading(!rotateWithHeading);
    }

    synchronized void setRotateWithHeading(boolean enabled) {
        if (rotateWithHeading == enabled) {
            return;
        }
        rotateWithHeading = enabled;
        renderNow();
        fetchTilesAsync();
    }

    synchronized boolean isRotateWithHeadingEnabled() {
        return rotateWithHeading;
    }

    synchronized void setPanModeEnabled(boolean enabled) {
        panModeEnabled = enabled;
        if (!enabled) {
            panOffsetPxX = 0f;
            panOffsetPxY = 0f;
        }
        renderNow();
        fetchTilesAsync();
    }

    synchronized int getZoomLevel() {
        return zoomLevel;
    }

    synchronized void renderNow() {
        if (surface == null || !surface.isValid()) return;

        double lat = AndroidAutoNavState.getLatitude();
        double lon = AndroidAutoNavState.getLongitude();
        double headingDegrees = AndroidAutoNavState.getHeadingDegrees();
        List<AndroidAutoNavState.FeaturePoint> featurePoints = AndroidAutoNavState.getFeaturePoints();
        Rect mapArea = getActiveMapArea();
        float mapCenterPxX = (mapArea.left + mapArea.right) / 2f;
        float mapCenterPxY = (mapArea.top + mapArea.bottom) / 2f;
        if (!userAdjustedCamera) {
            zoomLevel = computeAutoFitZoom(lat, lon, featurePoints, mapArea.width(), mapArea.height());
        }
        double[] displayCenter = getDisplayCenter(lat, lon, featurePoints);
        double centerLat = displayCenter[0];
        double centerLon = displayCenter[1];

        Canvas canvas;
        try {
            canvas = surface.lockCanvas(null);
        } catch (Throwable t) {
            return;
        }
        try {
            canvas.drawRect(0, 0, surfW, surfH, bgPaint);

            double[] centre = latLonToTileXY(centerLat, centerLon, zoomLevel);
            double tCentreX = centre[0];
            double tCentreY = centre[1];

            double pixOffX = (tCentreX - Math.floor(tCentreX)) * TILE_PX;
            double pixOffY = (tCentreY - Math.floor(tCentreY)) * TILE_PX;

            int originX = (int) (mapCenterPxX - pixOffX + panOffsetPxX);
            int originY = (int) (mapCenterPxY - pixOffY + panOffsetPxY);

            int baseTX = (int) Math.floor(tCentreX);
            int baseTY = (int) Math.floor(tCentreY);
            int maxTile = (1 << zoomLevel) - 1;

            int tilesX = (int) Math.ceil((double) surfW / TILE_PX) + 4;
            int tilesY = (int) Math.ceil((double) surfH / TILE_PX) + 4;

            canvas.save();
            if (rotateWithHeading) {
                canvas.rotate((float) -headingDegrees, mapCenterPxX, mapCenterPxY);
            }

            for (int dy = -tilesY / 2; dy <= tilesY / 2; dy++) {
                for (int dx = -tilesX / 2; dx <= tilesX / 2; dx++) {
                    int tx = baseTX + dx;
                    int ty = baseTY + dy;
                    if (tx < 0 || ty < 0 || tx > maxTile || ty > maxTile) continue;

                    int px = originX + dx * TILE_PX;
                    int py = originY + dy * TILE_PX;

                    String key = tileCacheKey(zoomLevel, tx, ty);
                    Bitmap bmp = tileCache.get(key);
                    if (bmp != null && !bmp.isRecycled()) {
                        canvas.drawBitmap(bmp, px, py, null);
                    }
                }
            }

            for (AndroidAutoNavState.FeaturePoint featurePoint : featurePoints) {
                double[] pointTile = latLonToTileXY(featurePoint.latitude, featurePoint.longitude, zoomLevel);
                float pointX = (float) (originX + ((pointTile[0] - baseTX) * TILE_PX));
                float pointY = (float) (originY + ((pointTile[1] - baseTY) * TILE_PX));
                canvas.drawCircle(pointX, pointY, 8f, featurePointPaint);
            }

            canvas.restore();

            double[] vehicleTile = latLonToTileXY(lat, lon, zoomLevel);
            float cx = (float) (originX + ((vehicleTile[0] - baseTX) * TILE_PX));
            float cy = (float) (originY + ((vehicleTile[1] - baseTY) * TILE_PX));
            if (vehicleIcon != null && !vehicleIcon.isRecycled()) {
                final int halfSizePx = 16;
                canvas.save();
                canvas.translate(cx, cy);
                float vehicleRotation = rotateWithHeading ? 0f : (float) headingDegrees;
                canvas.rotate(vehicleRotation);
                Rect dst = new Rect(-halfSizePx, -halfSizePx, halfSizePx, halfSizePx);
                canvas.drawBitmap(vehicleIcon, null, dst, null);
                canvas.restore();
            } else {
                markerPaint.setColor(Color.rgb(28, 157, 255));
                markerPaint.setAlpha(230);
                canvas.drawCircle(cx, cy, 18f, markerPaint);
                markerPaint.setColor(Color.WHITE);
                markerPaint.setAlpha(255);
                canvas.drawCircle(cx, cy, 7f, markerPaint);
            }

            // Bottom debug overlay hidden per current UX requirement.
        } finally {
            surface.unlockCanvasAndPost(canvas);
        }
    }

    void fetchTilesAsync() {
        double lat = AndroidAutoNavState.getLatitude();
        double lon = AndroidAutoNavState.getLongitude();

        int currentZoom = zoomLevel;
        List<AndroidAutoNavState.FeaturePoint> featurePoints = AndroidAutoNavState.getFeaturePoints();
        Rect mapArea = getActiveMapArea();
        if (!userAdjustedCamera) {
            currentZoom = computeAutoFitZoom(lat, lon, featurePoints, mapArea.width(), mapArea.height());
            zoomLevel = currentZoom;
        }
        double[] displayCenter = getDisplayCenter(lat, lon, featurePoints);
        double[] centre = latLonToTileXY(displayCenter[0], displayCenter[1], currentZoom);
        double pannedCentreX = centre[0] - (panOffsetPxX / TILE_PX);
        double pannedCentreY = centre[1] - (panOffsetPxY / TILE_PX);
        int baseTX = (int) Math.floor(pannedCentreX);
        int baseTY = (int) Math.floor(pannedCentreY);
        int maxTile = (1 << currentZoom) - 1;

        int tilesX = (int) Math.ceil((double) surfW / TILE_PX) + 4;
        int tilesY = (int) Math.ceil((double) surfH / TILE_PX) + 4;

        for (int dy = -tilesY / 2; dy <= tilesY / 2; dy++) {
            for (int dx = -tilesX / 2; dx <= tilesX / 2; dx++) {
                int tx = baseTX + dx;
                int ty = baseTY + dy;
                if (tx < 0 || ty < 0 || tx > maxTile || ty > maxTile) continue;
                String key = tileCacheKey(currentZoom, tx, ty);
                if (tileCache.containsKey(key)) continue;

                final int ftx = tx, fty = ty;
                final int requestedZoom = currentZoom;
                executor.submit(() -> {
                    Bitmap bmp = downloadTile(requestedZoom, ftx, fty);
                    if (bmp != null) {
                        synchronized (AutoNavSurfaceRenderer.this) {
                            tileCache.put(key, bmp);
                        }
                        renderNow();
                    }
                });
            }
        }
    }

    private static Bitmap downloadTile(int z, int x, int y) {
        Bitmap arcGisStreet = downloadTileFromUrl(String.format(ARCGIS_STREET_TILE_URL, z, y, x));
        if (arcGisStreet != null) {
            return arcGisStreet;
        }

        Bitmap arcGisImagery = downloadTileFromUrl(String.format(ARCGIS_IMAGERY_TILE_URL, z, y, x));
        if (arcGisImagery != null) {
            return arcGisImagery;
        }

        return downloadTileFromUrl(String.format(OSM_TILE_URL, z, x, y));
    }

    private static Bitmap downloadTileFromUrl(String urlStr) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "AutoArcGIS/1.0 (Android Auto navigation app)");
            conn.setRequestProperty("Accept", "image/png,image/*;q=0.9,*/*;q=0.8");
            conn.connect();
            if (conn.getResponseCode() == 200) {
                try (InputStream is = conn.getInputStream()) {
                    return BitmapFactory.decodeStream(is);
                }
            }
            Log.w(TAG, "Tile HTTP " + conn.getResponseCode() + " for " + urlStr);
        } catch (Exception ex) {
            Log.w(TAG, "Tile download failed for " + urlStr, ex);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return null;
    }

    private static Bitmap loadVehicleIcon(Context context) {
        if (context == null) {
            return null;
        }
        try (InputStream stream = context.getAssets().open("www/assets/images/blackVehicle.png")) {
            return BitmapFactory.decodeStream(stream);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String tileCacheKey(int z, int x, int y) {
        return TILE_SOURCE + "/" + z + "/" + x + "/" + y;
    }

    private String trimForWidth(Paint paint, String text, float maxWidthPx) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (paint.measureText(text) <= maxWidthPx) {
            return text;
        }
        String ellipsis = "...";
        int end = text.length();
        while (end > 1) {
            String candidate = text.substring(0, end) + ellipsis;
            if (paint.measureText(candidate) <= maxWidthPx) {
                return candidate;
            }
            end--;
        }
        return ellipsis;
    }

    private Rect getActiveMapArea() {
        Rect candidate = (stableMapArea != null && !stableMapArea.isEmpty()) ? stableMapArea
                : (visibleMapArea != null && !visibleMapArea.isEmpty() ? visibleMapArea : null);

        if (candidate == null) {
            return new Rect(0, 0, surfW, surfH);
        }

        int left = Math.max(0, Math.min(candidate.left, surfW));
        int top = Math.max(0, Math.min(candidate.top, surfH));
        int right = Math.max(left + 1, Math.min(candidate.right, surfW));
        int bottom = Math.max(top + 1, Math.min(candidate.bottom, surfH));

        if ((right - left) < 64 || (bottom - top) < 64) {
            return new Rect(0, 0, surfW, surfH);
        }
        return new Rect(left, top, right, bottom);
    }

    private static double[] getDisplayCenter(double lat, double lon,
                                             List<AndroidAutoNavState.FeaturePoint> featurePoints) {
        double[] bounds = getContentBounds(lat, lon, featurePoints);
        return new double[]{(bounds[0] + bounds[1]) / 2.0, (bounds[2] + bounds[3]) / 2.0};
    }

    private static int computeAutoFitZoom(double lat, double lon,
                                          List<AndroidAutoNavState.FeaturePoint> featurePoints,
                                          int widthPx, int heightPx) {
        double[] bounds = getContentBounds(lat, lon, featurePoints);
        double minLat = bounds[0];
        double maxLat = bounds[1];
        double minLon = bounds[2];
        double maxLon = bounds[3];

        if ((maxLat - minLat) < 1e-6 && (maxLon - minLon) < 1e-6) {
            return DEFAULT_ZOOM;
        }

        int availableWidth = Math.max(200, widthPx - 96);
        int availableHeight = Math.max(120, heightPx - 140);

        for (int z = MAX_ZOOM; z >= MIN_ZOOM; z--) {
            double[] topLeft = latLonToTileXY(maxLat, minLon, z);
            double[] bottomRight = latLonToTileXY(minLat, maxLon, z);
            double requiredWidth = Math.abs(bottomRight[0] - topLeft[0]) * TILE_PX;
            double requiredHeight = Math.abs(bottomRight[1] - topLeft[1]) * TILE_PX;
            if (requiredWidth <= availableWidth && requiredHeight <= availableHeight) {
                return z;
            }
        }

        return MIN_ZOOM;
    }

    private static double[] getContentBounds(double lat, double lon,
                                             List<AndroidAutoNavState.FeaturePoint> featurePoints) {
        double minLat = 0.0;
        double maxLat = 0.0;
        double minLon = 0.0;
        double maxLon = 0.0;
        boolean hasContent = false;

        if (featurePoints != null) {
            for (AndroidAutoNavState.FeaturePoint featurePoint : featurePoints) {
                if (!hasContent) {
                    minLat = featurePoint.latitude;
                    maxLat = featurePoint.latitude;
                    minLon = featurePoint.longitude;
                    maxLon = featurePoint.longitude;
                } else {
                    minLat = Math.min(minLat, featurePoint.latitude);
                    maxLat = Math.max(maxLat, featurePoint.latitude);
                    minLon = Math.min(minLon, featurePoint.longitude);
                    maxLon = Math.max(maxLon, featurePoint.longitude);
                }
                hasContent = true;
            }
        }

        if (!hasContent) {
            return new double[]{lat, lat, lon, lon};
        }

        // Always include the vehicle position so it is never rendered off-screen
        minLat = Math.min(minLat, lat);
        maxLat = Math.max(maxLat, lat);
        minLon = Math.min(minLon, lon);
        maxLon = Math.max(maxLon, lon);

        double latPadding = Math.max(0.005, (maxLat - minLat) * 0.15);
        double lonPadding = Math.max(0.005, (maxLon - minLon) * 0.15);
        return new double[]{
                minLat - latPadding,
                maxLat + latPadding,
                minLon - lonPadding,
                maxLon + lonPadding
        };
    }

    private static double[] latLonToTileXY(double lat, double lon, int z) {
        double n = Math.pow(2, z);
        double latRad = Math.toRadians(lat);
        double tileX = (lon + 180.0) / 360.0 * n;
        double tileY = (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n;
        return new double[]{tileX, tileY};
    }
}

