package com.downx.app;

import android.content.Context;

/**
 * 桌面验证台的 IG 登录桩。
 *
 * 主机上没有 WebView，登不了；但可以用环境变量 IG_COOKIE 直接喂一份真 Cookie
 * （浏览器登录 IG 后从开发者工具复制整条 Cookie），这样多分辨率也能在本机验证：
 *
 *   IG_COOKIE="sessionid=...; csrftoken=...; ds_user_id=..." sh tests/e2e/run.sh u <链接>
 */
public final class IgSession {

    private static String s = System.getenv("IG_COOKIE");

    public static void init(Context c) { }

    public static boolean has(Context c) { return usable(s); }

    public static String cookie(Context c) { return s; }

    public static String cached() { return s; }

    public static void save(Context c, String v) { s = v; }

    public static void clear(Context c) { s = null; }

    public static boolean usable(String v) { return v != null && v.contains("sessionid="); }
}
