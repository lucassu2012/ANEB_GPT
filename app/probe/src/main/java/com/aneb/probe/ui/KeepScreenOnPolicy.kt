package com.aneb.probe.ui

/**
 * autorun 测量窗常亮策略（T25，D-427）。
 *
 * 保持 autorun 窗口全程常亮，才能让未来批次与既有语料处于同一屏态，彼此可比；
 * 手动模式下 `autorun=false`，`onCreate(false)` 后 [held] 恒为 `false`，窗口 flag 从不被设置。
 * 交互测试屏的手动保屏由各 Compose 测试页通过 `LocalView.current.keepScreenOn` 持有，
 * 避免改变 [KeepScreenOnPolicy] 的 `autorun`-only 生命周期契约。
 */
internal class KeepScreenOnPolicy {

    /** 当前是否应持有屏幕常亮标志；仅供 [MainActivity] 读取以决定是否调用 window flag。 */
    var held: Boolean = false
        private set

    /** onCreate 阶段调用：autorun 才持有；手动模式（`autorun=false`）不持有。 */
    fun onCreate(autorun: Boolean) {
        held = autorun
    }

    /** onDestroy 阶段调用：无条件释放。未持有时是安全的空操作，不会误清手动模式的窗口态。 */
    fun onDestroy() {
        held = false
    }
}
