package com.aneb.probe.engine

/**
 * KPI 四级分级（agent-qoe-kpi v0.1 门限表，KPI 文档 5.2；实验性）。
 * 接线层适配器——不动 scoring/ 的既有公共 API（门限锚点数字与 AqsScorer 内部表同源 5.2）。
 * 纯 JVM、无 Android 依赖。
 */
object KpiGrading {

    const val EXCELLENT = "excellent"
    const val GOOD = "good"
    const val FAIR = "fair"
    const val POOR = "poor"

    /** 低者优：value < a 优 / < b 良 / <= c 可 / 其余差。 */
    private fun lowBetter(v: Double, a: Double, b: Double, c: Double): String = when {
        v < a -> EXCELLENT
        v < b -> GOOD
        v <= c -> FAIR
        else -> POOR
    }

    /**
     * @param kpiId T1/T2/T3/T4/T5/N1/N2/U1/U2（T5 无门限恒 null）
     * @return 分级串；value=null（失败/缺失）返回 null——绝不给失败样本发分级（R-10）
     */
    fun grade(kpiId: String, value: Double?): String? {
        if (value == null) return null
        return when (kpiId) {
            "T1" -> lowBetter(value, 200.0, 500.0, 1000.0)
            "T2" -> lowBetter(value, 100.0, 200.0, 400.0)
            "T3" -> lowBetter(value, 0.005, 0.02, 0.05)
            "T4" -> when { // 优 = 0（5.2）
                value == 0.0 -> EXCELLENT
                value < 0.002 -> GOOD
                value <= 0.01 -> FAIR
                else -> POOR
            }
            "N1" -> lowBetter(value, 30.0, 60.0, 100.0)
            "N2" -> lowBetter(value, 10.0, 30.0, 80.0)
            "U2" -> lowBetter(value, 150.0, 300.0, 600.0)
            // 阶段 2 C 组（agent-qoe-kpi v0.2，5.2；additive——既有 id 分级不变）
            "C1" -> lowBetter(value, 0.005, 0.02, 0.05) // 会话中断率 0.5/2/5%
            "C2" -> lowBetter(value, 1000.0, 3000.0, 10_000.0) // 切换恢复 1/3/10s（ms）
            "U1" -> when { // 高者优（Mbps）
                value > 20.0 -> EXCELLENT
                value >= 5.0 -> GOOD
                value >= 1.0 -> FAIR
                else -> POOR
            }
            else -> null // T5 等不设门限
        }
    }
}
