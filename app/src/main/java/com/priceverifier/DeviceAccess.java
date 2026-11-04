package com.priceverifier;

import android.content.Context;
import android.provider.Settings;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Central gate for which physical devices may run the app's privileged screens.
 * Admin access and the home screen are restricted to the registered devices
 * listed below, matched against the Datalogic hardware serial number. The list
 * is hard-coded on purpose: the web portal does not manage device registration.
 */
final class DeviceAccess {
    // === Register devices here ===========================================
    // Add each authorized device's serial number, in quotes, separated by
    // commas. Matching ignores upper/lower case. To find a device's serial,
    // open it: an unregistered device shows the serial on the
    // "not registered/authorized" screen. Rebuild after editing.
    private static final Set<String> ALLOWED_SERIALS = upperCased(
            "B5C4399A793260B9x",


            //Newton Demo Unit
            "U26F00804",

            //Datalogic Demo Unit
            "U26F00563",
            "U26C00093",

            //CashnCarry v1 , 1.6
            "U26F00199",

            //Unimart v2 , 1.6
            "U26F00231",
            "U26F00034",
            "U26F00191",
            "U26F00174",
            "U26F00136",


            "1d59d4baf6b52925",
            "662ce6a08457027a"
    );
    // =====================================================================

    private DeviceAccess() {
    }

    /**
     * This device's identifier: the Datalogic hardware serial when available
     * (works on Datalogic Android 10+, where stock Build.getSerial() is blocked),
     * falling back to Settings.Secure.ANDROID_ID off Datalogic hardware.
     */
    static String serial(Context context) {
        try {
            String serial = com.datalogic.device.info.SYSTEM.SERIAL_NUMBER;
            if (serial != null && !serial.trim().isEmpty()) {
                return serial.trim();
            }
        } catch (Throwable ignored) {
            // SDK unavailable (e.g. non-Datalogic device); fall back below.
        }
        String androidId = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ANDROID_ID);
        return androidId == null ? "" : androidId.trim();
    }

    /** True when this device's serial is in the hard-coded allow-list. */
    static boolean isAuthorized(Context context) {
        String serial = serial(context);
        return !serial.isEmpty() && ALLOWED_SERIALS.contains(serial.toUpperCase(Locale.US));
    }

    private static Set<String> upperCased(String... serials) {
        Set<String> set = new HashSet<>();
        for (String serial : Arrays.asList(serials)) {
            set.add(serial.trim().toUpperCase(Locale.US));
        }
        return set;
    }
}
