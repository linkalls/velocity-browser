package dev.velocity.chromium;

/**
 * Pure-Java guard for Cefrium 0.16 renderer selection. Android 17 can select
 * Java-less native services, while earlier releases use Java child services.
 * Validation rejects unrecognized names rather than disabling sandboxing.
 */
public final class RendererPoolSelection {
    public static final String JAVA = "com.cefrium.ChildServices$Sandboxed";
    public static final String NATIVE = "com.cefrium.ChildServices$NativeSandboxed";
    private RendererPoolSelection() {}

    public static boolean usingNative(int api, boolean nativeDeclared,
                                      String javaPrefix, String nativePrefix,
                                      String selected, int javaSlotCount) {
        if (!JAVA.equals(javaPrefix) || javaSlotCount != 40) {
            throw new IllegalStateException("Unexpected Java renderer manifest");
        }
        if (api >= 37) {
            if (!nativeDeclared || !NATIVE.equals(nativePrefix) || !NATIVE.equals(selected)) {
                throw new IllegalStateException("Android 17 native renderer selection mismatch: "
                        + selected + "; declared=" + nativeDeclared + "; prefix=" + nativePrefix);
            }
            return true;
        }
        if (!JAVA.equals(selected)) {
            throw new IllegalStateException("Unexpected Java renderer selection: " + selected);
        }
        return false;
    }
}
