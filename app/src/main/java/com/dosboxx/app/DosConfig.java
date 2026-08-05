package com.dosboxx.app;

/**
 * JNI bridge for live DOSBox-X configuration access.
 * These methods are implemented in native/dosbox-x/src/gui/sdlmain.cpp.
 */
public final class DosConfig {
    /** Returns all section names: "sdl", "dosbox", "cpu", etc. */
    public static native String[] getSections();

    /** Returns a JSON array of property objects for a section.
     *  Each object: { name, type, value, default, help, values: [] } */
    public static native String getSectionProperties(String section);

    /** Updates a running engine property. Returns true if successful. */
    public static native boolean setProperty(String section, String property, String value);

    /** Saves current config to the active .conf file. Returns true if successful. */
    public static native boolean saveConfig();

    private DosConfig() {}
}
