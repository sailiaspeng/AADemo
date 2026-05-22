package com.cordova.androidauto;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.MessageTemplate;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.car.app.navigation.model.PanModeListener;

public class AutoNavScreen extends Screen {
    private final AutoNavSurfaceRenderer surfaceRenderer;

    public AutoNavScreen(@NonNull CarContext carContext, @NonNull AutoNavSurfaceRenderer surfaceRenderer) {
        super(carContext);
        this.surfaceRenderer = surfaceRenderer;
    }

    @NonNull @Override
    public Template onGetTemplate() {
        try {
            Action refreshAction = new Action.Builder()
                    .setTitle("Refresh").setOnClickListener(this::invalidate).build();
            Action zoomInAction = new Action.Builder()
                    .setTitle("+")
                    .setOnClickListener(() -> {
                        surfaceRenderer.zoomIn();
                        invalidate();
                    })
                    .build();
            Action zoomOutAction = new Action.Builder()
                    .setTitle("-")
                    .setOnClickListener(() -> {
                        surfaceRenderer.zoomOut();
                        invalidate();
                    })
                    .build();
            String rotateTitle = surfaceRenderer.isRotateWithHeadingEnabled() ? "North" : "Heading";
            Action rotateAction = new Action.Builder()
                    .setTitle(rotateTitle)
                    .setOnClickListener(() -> {
                        surfaceRenderer.toggleRotateWithHeading();
                        invalidate();
                    })
                    .build();
            return new NavigationTemplate.Builder()
                    .setActionStrip(new ActionStrip.Builder()
                        .addAction(refreshAction)
                        .addAction(zoomInAction)
                        .addAction(zoomOutAction)
                        .addAction(rotateAction)
                        .build())
                    .setMapActionStrip(new ActionStrip.Builder().addAction(Action.PAN).build())
                    .setPanModeListener(new PanModeListener() {
                    @Override
                    public void onPanModeChanged(boolean isInPanMode) {
                        surfaceRenderer.setPanModeEnabled(isInPanMode);
                        invalidate();
                    }
                    })
                    .build();
        } catch (Throwable t) {
            Action retryAction = new Action.Builder()
                    .setTitle("Retry")
                    .setOnClickListener(this::invalidate)
                    .build();
            return new MessageTemplate.Builder("Unable to render navigation UI yet.")
                    .setTitle("AutoArcGIS")
                    .setHeaderAction(Action.APP_ICON)
                    .addAction(retryAction)
                    .build();
        }
    }
}