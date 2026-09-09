package cc.uukanshu

import cc.uukanshu.data.updatecheck.AppInit
import org.junit.Assert.assertEquals
import org.junit.Test

/** Boot init survives faulty channel/schedule; schedule runs even if channel throws. */
class AppInitTest {
    @Test fun channelThrowStillSchedules() {
        var scheduled = 0
        AppInit.init(
            channel = { throw RuntimeException("binder dead") },
            schedule = { scheduled++ },
        )
        assertEquals(1, scheduled)
    }

    @Test fun scheduleThrowDoesNotPropagate() {
        var channeled = 0
        AppInit.init(
            channel = { channeled++ },
            schedule = { throw RuntimeException("work stripped") },
        )
        assertEquals(1, channeled)
    }

    @Test fun happyPathRunsBoth() {
        var c = 0
        var s = 0
        AppInit.init(channel = { c++ }, schedule = { s++ })
        assertEquals(1, c)
        assertEquals(1, s)
    }
}
