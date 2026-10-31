package dev.velocity.chromium;

import static org.junit.Assert.*;
import org.junit.Test;

public final class RendererPoolSelectionTest {
    @Test public void android16UsesIsolatedJavaServices() {
        assertFalse(RendererPoolSelection.usingNative(36, true,
            RendererPoolSelection.JAVA, RendererPoolSelection.NATIVE,
            RendererPoolSelection.JAVA, 40));
        assertFalse(RendererPoolSelection.usingNative(29, false,
            RendererPoolSelection.JAVA, null, RendererPoolSelection.JAVA, 40));
    }

    @Test public void android17UsesNativeServicePoolFromSdk014() {
        assertTrue(RendererPoolSelection.usingNative(37, true,
            RendererPoolSelection.JAVA, RendererPoolSelection.NATIVE,
            RendererPoolSelection.NATIVE, 40));
    }

    @Test public void nativeMissingOrJavaFallbackOn17IsRejected() {
        for (boolean marker : new boolean[] {false, true}) {
            try {
                RendererPoolSelection.usingNative(37, marker,
                    RendererPoolSelection.JAVA, RendererPoolSelection.NATIVE,
                    RendererPoolSelection.JAVA, 40);
                fail("Should reject Java selection on Android 17");
            } catch (IllegalStateException expected) {}
        }
        try {
            RendererPoolSelection.usingNative(37, true, RendererPoolSelection.JAVA,
                "some.other.Native", RendererPoolSelection.NATIVE, 40);
            fail("Should reject unknown native pool metadata");
        } catch (IllegalStateException expected) {}
    }

    @Test public void rejectsMissingOrUntrustedJavaSlots() {
        for (int count : new int[] {0, 2, 39, 41}) {
            try {
                RendererPoolSelection.usingNative(37, true,
                    RendererPoolSelection.JAVA, RendererPoolSelection.NATIVE,
                    RendererPoolSelection.NATIVE, count);
                fail("Should reject invalid Java renderer slots");
            } catch (IllegalStateException expected) {}
        }
    }
}
