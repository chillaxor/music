package com.github.catvod.net;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * CatVod net.OkHttp（宿主侧最小实现）：jar 内爬虫通过 Spider.client() /
 * Spider.safeDns() 拿到网络客户端。TVBox 原版还带 DoH/代理选择器等，本项目用不到，
 * 只提供基类签名所需的三个静态方法。
 */
public class OkHttp {

    public static Dns dns() {
        return Dns.SYSTEM;
    }

    public static OkHttpClient client() {
        return Holder.CLIENT;
    }

    /** 同步 GET，返回字符串（供可能需要它的爬虫使用） */
    public static String string(String url) throws IOException {
        Request req = new Request.Builder().url(url).build();
        try (Response resp = client().newCall(req).execute()) {
            return resp.body() != null ? resp.body().string() : "";
        }
    }

    private static class Holder {
        static final OkHttpClient CLIENT = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
    }
}
