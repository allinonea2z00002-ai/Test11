package com.safevision

import com.safevision.ai.Thresholds
import com.safevision.ai.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    @Test fun thresholdsClassify() {
        val t = Thresholds(0.3f, 0.7f)
        assertEquals(Verdict.SAFE, t.verdict(0.1f))
        assertEquals(Verdict.SUSPICIOUS, t.verdict(0.5f))
        assertEquals(Verdict.INAPPROPRIATE, t.verdict(0.9f))
        assertTrue(t.inBand(0.5f))
        assertFalse(t.inBand(0.8f))
    }

    @Test fun sensitivityLowersThresholds() {
        val low = Settings(sensitivity = 0f).thresholds
        val high = Settings(sensitivity = 1f).thresholds
        assertTrue(high.inappropriate < low.inappropriate)
        assertTrue(high.suspicious <= low.suspicious)
    }

    @Test fun blocklistMatchesSubdomains() {
        val b = Blocklist.parse(sequenceOf("# comment", "0.0.0.0 bad.example", "0.0.0.0 localhost", "other.test"))
        assertTrue(b.isBlocked("bad.example"))
        assertTrue(b.isBlocked("www.bad.example"))
        assertTrue(b.isBlocked("other.test"))
        assertFalse(b.isBlocked("example.com"))
        assertFalse(b.isBlocked("localhost"))
        assertFalse(b.isBlocked(null))
    }
}
