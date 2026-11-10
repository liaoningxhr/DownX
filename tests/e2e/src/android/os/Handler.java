package android.os;

/** Handler 桩：post 直接在调用线程跑（验证台是同步跑，不需要切线程） */
public class Handler {
    public Handler() { }
    public Handler(Looper l) { }
    public boolean post(Runnable r) { r.run(); return true; }
    public boolean postDelayed(Runnable r, long ms) { r.run(); return true; }
}
