package com.downx.app;

import android.content.Context;

/**
 * 桌面验证台的 B 站登录桩：真机上这里是 SharedPreferences 里的 Cookie，
 * 主机上永远当"未登录"，正好验证"未登录只能拿 480P"这条降级路径。
 */
public final class BiliSession {
    public static void init(Context c) { }
    public static boolean has(Context c) { return false; }
    public static String cookie(Context c) { return ""; }
    public static void save(Context c, String cookie) { }
    public static void clear(Context c) { }
}
