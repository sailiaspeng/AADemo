package com.cordova.androidauto;

import androidx.annotation.NonNull;
import androidx.car.app.CarAppService;
import androidx.car.app.Session;
import androidx.car.app.validation.HostValidator;

public class AutoNavCarAppService extends CarAppService {
    @NonNull @Override
    public HostValidator createHostValidator() {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
    }
    @NonNull @Override
    public Session onCreateSession() { return new AutoNavSession(); }
}