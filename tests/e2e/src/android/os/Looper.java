package android.os;

/** 桌面验证台用的 Looper 桩：没有真消息循环，getMainLooper 只为满足签名 */
public final class Looper {
    private static final Looper MAIN = new Looper();
    public static Looper getMainLooper() { return MAIN; }
}
