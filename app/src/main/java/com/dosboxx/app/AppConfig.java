package com.dosboxx.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/**
 * App settings. The base folder holds the games / cds / import subfolders.
 * It stays under one of Android's app-specific external file directories, so
 * the native emulator gets real filesystem paths without broad storage access.
 */
final class AppConfig {

    private static final String PREFS = "dosboxx";
    private static final String KEY_BASE = "baseDir";

    /** The configured base folder, or the app default if unset/missing. */
    static File baseDir(Context c) {
        String p = prefs(c).getString(KEY_BASE, null);
        if (p != null) {
            File f = new File(p);
            if (f.isDirectory() || f.mkdirs()) return f;
        }
        return defaultBase(c);
    }

    static boolean isCustom(Context c) {
        return prefs(c).getString(KEY_BASE, null) != null;
    }

    static void setBaseDir(Context c, File dir) {
        prefs(c).edit().putString(KEY_BASE, dir.getAbsolutePath()).apply();
    }

    static String getAdvancedSettings(Context c) {
        return prefs(c).getString("advancedSettings", "");
    }

    static void setAdvancedSettings(Context c, String s) {
        prefs(c).edit().putString("advancedSettings", s).apply();
    }

    static void useDefault(Context c) {
        prefs(c).edit().remove(KEY_BASE).apply();
    }

    /** True once the wizard has run (so it isn't shown on every launch). */
    static boolean setupDone(Context c) {
        return prefs(c).getBoolean("setupDone", false);
    }

    static void markSetupDone(Context c) {
        prefs(c).edit().putBoolean("setupDone", true).apply();
    }

    static boolean shouldResetSetup(Context c) {
        if (!prefs(c).getBoolean("setupReset_v2", false)) {
            prefs(c).edit().putBoolean("setupReset_v2", true).apply();
            return true;
        }
        return false;
    }

    static void resetSetup(Context c) {
        prefs(c).edit().putBoolean("setupDone", false).apply();
    }

    static File defaultBase(Context c) {
        return c.getExternalFilesDir(null);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private AppConfig() { }
}
