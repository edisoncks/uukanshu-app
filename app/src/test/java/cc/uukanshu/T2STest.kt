package cc.uukanshu

import com.github.houbb.opencc4j.util.ZhConverterUtil
import org.junit.Assert.assertEquals
import org.junit.Test

class T2STest {
    @Test fun traditionalToSimplified() {
        assertEquals("生命不息，奋斗不止", ZhConverterUtil.toSimple("生命不息，奮鬥不止"))
        // Empty input stays empty; converter never throws into the reader.
        assertEquals("", ZhConverterUtil.toSimple(""))
    }
}
