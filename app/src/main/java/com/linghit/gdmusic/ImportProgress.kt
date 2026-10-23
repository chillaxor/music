package com.linghit.gdmusic

import android.os.Handler
import android.os.Looper

/**
 * 全局导入进度状态（纯数据 + 广播，不碰任何 Activity）。
 *
 * 为什么要有它：导入是后台异步任务，用户点了文件夹后可能立刻跳到别的页面，
 * 进度条必须「在所有页面顶部都能看见、直到导入结束」。所以进度不能挂在
 * DownloadedActivity 上（一离开页面就没了），而是放在这里，由 ImportOverlay
 * 给每个 Activity 挂一条浮层进度条订阅同一份状态。
 *
 * ⚠️ 线程约定（踩过坑，别再改回去）：
 *  start()/progress()/end() 会被 MusicImporter 的**后台线程**调用，
 *  而所有订阅者都要碰 UI，所以 emit() 一律用主线程 Handler 分发。
 *  曾经让监听器在调用方线程同步执行，结果后台线程里 MaterialButton.setEnabled()
 *  触发 StateListAnimator，直接崩「Animators may only be run on Looper threads」。
 *  在这里统一切主线程，订阅者就不用各自记得 post（DownloadedActivity 当初就是漏了）。
 */
object ImportProgress {

    data class State(
        /** 是否正在导入 */
        val active: Boolean,
        /** 顶部显示的文案，如「正在扫描「音乐」…」「正在导入 16 / 150 首…」 */
        val status: String,
        /** true=扫描阶段（滑动动画，还不知道总数）；false=导入阶段（有百分比） */
        val indeterminate: Boolean,
        val done: Int,
        val total: Int
    )

    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private var state = State(false, "", true, 0, 0)
    private val listeners = mutableListOf<(State) -> Unit>()

    fun current(): State = synchronized(lock) { state }

    /** 订阅；注册时会立刻用当前状态回调一次（新页面挂上浮层时同步到最新进度）。
     *  回调同样走主线程，订阅者可以放心直接碰 UI */
    fun listen(l: (State) -> Unit) {
        synchronized(lock) { listeners.add(l) }
        val s = synchronized(lock) { state }
        main.post { l(s) }
    }

    fun unlisten(l: (State) -> Unit) {
        synchronized(lock) { listeners.remove(l) }
    }

    /** 分发：一律切到主线程，调用方可能在后台线程（MusicImporter 就是） */
    private fun emit() {
        val s: State
        val ls: List<(State) -> Unit>
        synchronized(lock) { s = state; ls = listeners.toList() }
        main.post { ls.forEach { it(s) } }
    }

    /** 开始：进入扫描阶段（总数未知，进度条走 indeterminate） */
    fun start(folder: String) {
        synchronized(lock) { state = State(true, "正在扫描「$folder」…", true, 0, 0) }
        emit()
    }

    /** 进入/更新导入阶段（有具体 N / M） */
    fun progress(status: String, done: Int, total: Int) {
        synchronized(lock) { state = State(true, status, false, done, total) }
        emit()
    }

    /** 结束：所有页面的浮层都会隐藏 */
    fun end() {
        synchronized(lock) { state = State(false, "", true, 0, 0) }
        emit()
    }
}
