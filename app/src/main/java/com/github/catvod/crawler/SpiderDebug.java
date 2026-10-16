package com.github.catvod.crawler;

import android.util.Log;

/**
 * SpiderDebug（宿主侧提供）：jar 内爬虫类会引用它打日志。
 * 用 android.util.Log 实现，避免引入 orhanobut/logger 依赖。
 */
public class SpiderDebug {

    private static final String TAG = "CatVodSpider";

    public static void log(Throwable th) {
        if (th != null) th.printStackTrace();
    }

    public static void log(String msg) {
        if (msg != null && !msg.isEmpty()) Log.d(TAG, msg);
    }

    public static void log(String tag, String msg, Object... args) {
        if (msg != null && !msg.isEmpty()) Log.d(tag, msg);
    }
}
