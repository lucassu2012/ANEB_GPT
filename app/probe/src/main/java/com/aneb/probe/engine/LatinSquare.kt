package com.aneb.probe.engine

/**
 * 取证模式的场景顺序拉丁方轮转（KPI 文档 5.3.6：三遍间场景执行顺序按拉丁方轮转，
 * 防固定顺序的无线状态冷热系统性污染对照；实际顺序入库）。
 * 纯 JVM、无 Android 依赖。
 */
object LatinSquare {

    /**
     * n×n 循环拉丁方：第 r 行 = [r, r+1, …, r+n-1] mod n。
     * n=3 即 [0,1,2] / [1,2,0] / [2,0,1]（任务口径 [123][231][312]，0 起下标）。
     * 性质（单测锚定）：每行是 0..n-1 的排列；任一场景在任一位置恰好出现一次；行两两互异。
     */
    fun orders(n: Int): List<List<Int>> {
        require(n >= 1) { "n must be >= 1" }
        return List(n) { r -> List(n) { c -> (r + c) % n } }
    }

    /** 快测模式单遍固定顺序 [0,1,2,…]。 */
    fun quickOrder(n: Int): List<List<Int>> = listOf(List(n) { it })
}
