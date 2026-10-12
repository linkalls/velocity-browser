package dev.velocity.browser.ui

import org.junit.Assert.*
import org.junit.Test

class ScrollTraceBufferTest {
    @Test fun ringRetainsOnlyNewestRecordsChronologicallyAndClears() {
        val ring=ScrollTraceBuffer(3)
        repeat(10){ring.add(it.toLong(),1,4,12,2,y=it.toFloat())}
        assertEquals(3,ring.size);assertEquals(10L,ring.total)
        val rows=ring.csv().trim().lines().drop(1)
        assertEquals(listOf("7","8","9"),rows.map {it.substringBefore(',')})
        assertEquals(22,rows.first().split(',').size)
        ring.clear();assertEquals(0,ring.size);assertEquals(1,ring.csv().trim().lines().size)
    }
    @Test fun sustainedInputCannotGrowDiagnosticStorage() {
        val ring=ScrollTraceBuffer()
        repeat(100000){ring.add(it.toLong(),2,1,123,4,delta=2.5f,intent=24f)}
        assertEquals(768,ring.size);assertEquals(100000L,ring.total)
        val csv=ring.csv();assertTrue(csv.length<200000);assertFalse(csv.contains("http"));assertEquals(769,csv.trim().lines().size)
    }
}
