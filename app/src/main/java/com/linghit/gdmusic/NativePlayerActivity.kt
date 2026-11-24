package com.linghit.gdmusic

/**
 * 独立原生播放器：整屏只有画面（没有详情页），用于「历史续播 / 外部入口直接拉起」这类
 * 一次性播放场景。播放内核全部在 [PlayerHostActivity] 里，与原生影视详情页共用同一套，
 * 两边的控件/手势/体验完全一致。
 *
 * 起播参数走 intent 的 `native_play_json`（见 PlayerHostActivity.EXTRA_NATIVE_PLAY）。
 */
class NativePlayerActivity : PlayerHostActivity()
