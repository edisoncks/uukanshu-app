package cc.uukanshu

import cc.uukanshu.ui.update.UpdateViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {
    @Test fun autoCheckThrottles24h() {
        val now = 1_000_000_000L
        assertTrue(UpdateViewModel.shouldAutoCheck(0L, now))
        assertTrue(UpdateViewModel.shouldAutoCheck(now - UpdateViewModel.AUTO_CHECK_INTERVAL_MS, now))
        assertFalse(UpdateViewModel.shouldAutoCheck(now - 1000L, now))
        assertFalse(UpdateViewModel.shouldAutoCheck(now, now))
    }

    @Test fun offerNeedsNewerAndNotSkipped() {
        assertTrue(UpdateViewModel.shouldOfferUpdate("1.0.34", "1.0.33", null, manual = false))
        assertTrue(UpdateViewModel.shouldOfferUpdate("1.0.34", "1.0.33", "1.0.33", manual = false))
        // Skipped suppresses auto but not manual.
        assertFalse(UpdateViewModel.shouldOfferUpdate("1.0.34", "1.0.33", "1.0.34", manual = false))
        assertTrue(UpdateViewModel.shouldOfferUpdate("1.0.34", "1.0.33", "1.0.34", manual = true))
        // Up-to-date never offers.
        assertFalse(UpdateViewModel.shouldOfferUpdate("1.0.33", "1.0.33", null, manual = true))
        assertFalse(UpdateViewModel.shouldOfferUpdate("1.0.32", "1.0.33", null, manual = true))
    }
}
