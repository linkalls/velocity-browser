package dev.velocity.chromium;

/** Experiment settings must never override Android renderer safety checks. */
public final class StartupModePolicy {
    public static final String PREFS = "cefrium_startup_experiment_v1";
    public static final String KEY_FAST_ATTACH = "fast_attach_enabled";

    private StartupModePolicy() {}

    /** Keep benchmark controls explicit and unvalidated Android versions on post. */
    public static boolean defaultOnAttach(int sdk, boolean benchmarkBuild) {
        return sdk == 36 && !benchmarkBuild;
    }

    public static boolean onAttach(boolean userOptIn, boolean smokeTest,
                                   boolean debuggable, boolean requestedAttach) {
        return userOptIn || (smokeTest && debuggable && requestedAttach);
    }
}
