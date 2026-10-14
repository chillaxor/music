package com.example.gdmusic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 通知栏的上一首 / 播放暂停 / 下一首 按钮广播 */
class PlayerBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            "PREV" -> PlayerManager.skipPrev()
            "NEXT" -> PlayerManager.skipNext()
            "TOGGLE" -> PlayerManager.togglePlayPause()
            "STOP" -> PlayerManager.stop()
        }
    }
}
