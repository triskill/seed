package cz.trety.seed.runtime

import org.junit.Assert.*
import org.junit.Test

class RestoreUiPolicyTest {
    @Test fun recreatedActivityBindsMaintenanceEvenWhenBootFailed() {
        assertTrue(shouldBindMaintenance(BootState.Failed, true))
        assertFalse(shouldBindMaintenance(BootState.Failed, false))
    }
    @Test fun workingAndFailedRestoreNeverAdmitRootScreensOrShellAttachment() {
        var attached = 0
        for (state in listOf(RestoreState.Working("stopping"), RestoreState.Failed("retry"))) {
            if (rootScreensAllowed(state, false)) attached++
        }
        assertEquals(0, attached)
        assertFalse(rootScreensAllowed(RestoreState.Idle, true))
        assertTrue(rootScreensAllowed(RestoreState.Finished, false))
    }
}
