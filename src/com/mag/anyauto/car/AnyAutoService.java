package com.mag.anyauto.car;

import android.content.pm.ApplicationInfo;

import androidx.car.app.CarAppService;
import androidx.car.app.Session;
import androidx.car.app.SessionInfo;
import androidx.car.app.validation.HostValidator;

/**
 * Android Auto entry point, category NAVIGATION: the only category whose template gives the app a
 * raw surface for the whole car screen ({@link MirrorScreen}). AnyAuto never starts navigation,
 * so a Google Maps route keeps the navigation focus.
 */
public final class AnyAutoService extends CarAppService {

    @Override
    public HostValidator createHostValidator() {
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
        }
        // Android Auto and the Android Automotive templates host, from the library's allow-list
        return new HostValidator.Builder(getApplicationContext())
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build();
    }

    @Override
    public Session onCreateSession(SessionInfo sessionInfo) {
        return new AnySession();
    }
}
