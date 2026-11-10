package android.util;

/** 桌面验证台用的 Log 桩：把标签打到 stderr，行为与 Android 无关 */
public final class Log {
    public static int i(String tag, String msg) { return p("I", tag, msg); }
    public static int d(String tag, String msg) { return p("D", tag, msg); }
    public static int w(String tag, String msg) { return p("W", tag, msg); }
    public static int e(String tag, String msg) { return p("E", tag, msg); }
    public static int v(String tag, String msg) { return p("V", tag, msg); }
    public static int w(String tag, String msg, Throwable t) { return p("W", tag, msg + " / " + t); }
    public static int e(String tag, String msg, Throwable t) { return p("E", tag, msg + " / " + t); }

    private static int p(String lv, String tag, String msg) {
        System.err.println("  [" + lv + "/" + tag + "] " + msg);
        return 0;
    }
}
