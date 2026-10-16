package com.github.catvod.crawler;

import android.content.Context;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Dns;
import okhttp3.OkHttpClient;

/**
 * CatVod Spider 基类（宿主侧提供）。
 *
 * 盒子迷等 TVBox 多仓配置里的 type 3 站点（api 形如 csp_Bili）由订阅自带的
 * dex jar（spider 字段，盒子迷把它伪装成 .png）实现，jar 内的爬虫类全部继承
 * 本类。宿主必须提供同包同名的这个抽象类，DexClassLoader 加载 jar 时才能链接。
 *
 * 方法签名与 FongMi/TV 的 catvod 模块保持一致（jar 里的类按这套签名编译），
 * 仅把网络客户端换成本项目内置实现，避免引入整个 catvod net 包。
 */
public abstract class Spider {

    public String siteKey;

    public static Dns safeDns() {
        return com.github.catvod.net.OkHttp.dns();
    }

    public static OkHttpClient client() {
        return com.github.catvod.net.OkHttp.client();
    }

    public void init(Context context) throws Exception {
    }

    public void init(Context context, String extend) throws Exception {
        init(context);
    }

    public String homeContent(boolean filter) throws Exception {
        return "";
    }

    public String homeVideoContent() throws Exception {
        return "";
    }

    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        return "";
    }

    public String detailContent(List<String> ids) throws Exception {
        return "";
    }

    public String searchContent(String key, boolean quick) throws Exception {
        return "";
    }

    public String searchContent(String key, boolean quick, String pg) throws Exception {
        return "";
    }

    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        return "";
    }

    public String liveContent(String url) throws Exception {
        return "";
    }

    public boolean manualVideoCheck() throws Exception {
        return false;
    }

    public boolean isVideoFormat(String url) throws Exception {
        return false;
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        return null;
    }

    public String action(String action) throws Exception {
        return null;
    }

    public void destroy() {
    }
}
