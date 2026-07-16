package com.aneb.probe.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 取证模式拉丁方生成器（5.3.6，P1 范围 6）。 */
class LatinSquareTest {

    @Test
    fun `3 阶拉丁方即任务口径 123-231-312`() {
        assertEquals(
            listOf(listOf(0, 1, 2), listOf(1, 2, 0), listOf(2, 0, 1)),
            LatinSquare.orders(3),
        )
    }

    @Test
    fun `每行都是完整排列且行两两互异`() {
        val rows = LatinSquare.orders(3)
        for (row in rows) {
            assertEquals(setOf(0, 1, 2), row.toSet()) // 每遍三场景各跑一次
        }
        assertEquals(rows.size, rows.distinct().size) // 三遍顺序互异
    }

    @Test
    fun `任一场景在任一位置恰好出现一次`() {
        val rows = LatinSquare.orders(3)
        for (pos in 0 until 3) {
            val atPos = rows.map { it[pos] }
            assertEquals("位置 $pos 的场景集合", setOf(0, 1, 2), atPos.toSet())
        }
    }

    @Test
    fun `快测模式单遍固定顺序`() {
        assertEquals(listOf(listOf(0, 1, 2)), LatinSquare.quickOrder(3))
    }

    @Test
    fun `一般 n 的拉丁方性质`() {
        for (n in 1..5) {
            val rows = LatinSquare.orders(n)
            assertEquals(n, rows.size)
            for (pos in 0 until n) {
                assertEquals((0 until n).toSet(), rows.map { it[pos] }.toSet())
            }
        }
        assertTrue(LatinSquare.orders(1) == listOf(listOf(0)))
    }
}
