package android.view;

/** 桌面验证台用的极简 MotionEvent：按 (x,y) 列表构造多指事件 */
public class MotionEvent {

    public static final int ACTION_DOWN = 0;
    public static final int ACTION_UP = 1;
    public static final int ACTION_MOVE = 2;
    public static final int ACTION_CANCEL = 3;
    public static final int ACTION_POINTER_DOWN = 5;
    public static final int ACTION_POINTER_UP = 6;

    private final int action;
    private final float[] xs, ys;

    private MotionEvent(int action, float[] xs, float[] ys) {
        this.action = action;
        this.xs = xs;
        this.ys = ys;
    }

    /** ev(ACTION_DOWN, 500, 1200) 或 ev(ACTION_MOVE, 500,1200, 700,1200) */
    public static MotionEvent ev(int action, float... xy) {
        int n = xy.length / 2;
        float[] xs = new float[n], ys = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = xy[i * 2];
            ys[i] = xy[i * 2 + 1];
        }
        return new MotionEvent(action, xs, ys);
    }

    public int getActionMasked() { return action; }

    /** POINTER_UP 时指向抬起的那一根：验证台固定按"抬最后一根"来造 */
    public int getActionIndex() { return xs.length - 1; }
    public int getPointerCount() { return xs.length; }
    public float getX() { return xs[0]; }
    public float getY() { return ys[0]; }
    public float getX(int i) { return xs[i]; }
    public float getY(int i) { return ys[i]; }
}
