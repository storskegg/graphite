package io.github.storskegg.graphite

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionsTest {
    @Test fun aFinishedSessionStartsTheNextOne() {
        var runs = 0
        Sessions.loop { runs++; runs < 3 }   // Done twice, then stopped
        assertEquals(3, runs)
    }
}
