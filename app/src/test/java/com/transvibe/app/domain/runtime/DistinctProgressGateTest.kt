package com.transvibe.app.domain.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DistinctProgressGateTest {
    @Test fun identicalProgressInSameStageDoesNotDispatchRedundantNotifications() {
        val gate = DistinctProgressGate()
        assertTrue(gate.shouldEmit("استخراج الصوت", 5))
        repeat(200) { assertFalse(gate.shouldEmit("استخراج الصوت", 5)) }
        assertTrue(gate.shouldEmit("استخراج الصوت", 6))
        assertFalse(gate.shouldEmit("استخراج الصوت", 6))
    }

    @Test fun stageChangesAndProgressChangesAreNeverLost() {
        val gate = DistinctProgressGate()
        assertTrue(gate.shouldEmit("استخراج الصوت", 15))
        assertTrue(gate.shouldEmit("التعرف على الكلام", 15))
        assertTrue(gate.shouldEmit("استخراج الصوت", 15))
        assertTrue(gate.shouldEmit("استخراج الصوت", 20))
        assertTrue(gate.shouldEmit("استخراج الصوت", 5))
    }

    @Test fun aNewJobGetsAnIndependentGate() {
        assertTrue(DistinctProgressGate().shouldEmit("استخراج الصوت", 5))
        assertTrue(DistinctProgressGate().shouldEmit("استخراج الصوت", 5))
    }
}
