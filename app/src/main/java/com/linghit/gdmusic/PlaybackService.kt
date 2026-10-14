package com.linghit.gdmusic

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * 前台播放服务壳：仅用于「提权 + 显示播放通知」，真正的播放器在 PlayerManager 单例里。
 * 这样即使首页 Activity 被系统回收/息屏，播放仍由前台服务保活，不会中断。
 */
class PlaybackService : Service() {

    override fun onCreate() {
        super.onCreate()
        runCatching { PlayerManager.init(this) }
        PlayerManager.attachService(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 全程兜底：起通知失败也只是没保活，绝不能让异常冒出去崩进程
        runCatching {
            PlayerManager.attachService(this)
            PlayerManager.showForegroundNotification(this)
        }
        // 不用 START_STICKY：一旦这里崩溃，STICKY 会让系统反复重启服务 → 无限闪退循环
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        PlayerManager.detachService()
        super.onDestroy()
    }
}
