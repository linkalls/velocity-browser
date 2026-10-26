package dev.velocity.chromium;

import static org.junit.Assert.*;
import org.junit.Test;

public final class StartupModePolicyTest {
    @Test public void defaultUsesSafePost() {
        assertFalse(StartupModePolicy.onAttach(false, false, false, false));
        assertFalse(StartupModePolicy.onAttach(false, false, true, true));
        assertFalse(StartupModePolicy.onAttach(false, true, false, true));
    }

    @Test public void debugSmokeCanTestAttach() {
        assertTrue(StartupModePolicy.onAttach(false, true, true, true));
        assertFalse(StartupModePolicy.onAttach(false, true, true, false));
    }

    @Test public void userOptInIsExplicit() {
        assertTrue(StartupModePolicy.onAttach(true, false, false, false));
    }
}
