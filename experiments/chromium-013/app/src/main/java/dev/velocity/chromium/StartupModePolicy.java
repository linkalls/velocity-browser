package dev.velocity.chromium;

/** Experiment settings must never override Android renderer safety checks. */
public final class StartupModePolicy {
    public static final String PREFS = "cefrium_startup_experiment_v1";
    public static final String KEY_FAST_ATTACH = "fast_attach_enabled";

    private StartupModePolicy() {}

    public static boolean onAttach(boolean userOptIn, boolean smokeTest,
                                   boolean debuggable, boolean requestedAttach) {
        return userOptIn || (smokeTest && debuggable && requestedAttach);
    }
}
