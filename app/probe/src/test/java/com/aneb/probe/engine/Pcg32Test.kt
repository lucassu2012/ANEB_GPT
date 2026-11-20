package com.aneb.probe.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class Pcg32Test {

    @Test
    fun `matches Python Pcg32(20260716) first 10 uint32 values bit-for-bit`() {
        val rng = Pcg32(20260716L)
        val expected = listOf(
            583181481UL,
            3883574046UL,
            2938889893UL,
            1139050084UL,
            4059281936UL,
            1294563415UL,
            136557924UL,
            1270009250UL,
            3215834477UL,
            2790691792UL,
        )
        val actual = List(10) { rng.nextUInt32().toULong() }
        assertEquals(expected, actual)
    }

    @Test
    fun `matches Python Pcg32(20260716) first 10 randbelow(100) draws`() {
        val rng = Pcg32(20260716L)
        val expected = listOf(81, 46, 93, 84, 36, 15, 24, 50, 77, 92)
        val actual = List(10) { rng.randBelow(100) }
        assertEquals(expected, actual)
    }
}
