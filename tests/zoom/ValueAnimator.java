package android.animation;

/** 验证台用的假动画器：start() 直接跳到终值，方便断言"动画跑完是什么样" */
public class ValueAnimator {

    public interface AnimatorUpdateListener {
        void onAnimationUpdate(ValueAnimator animation);
    }

    private final float to;
    private float value;
    private AnimatorUpdateListener listener;

    private ValueAnimator(float from, float to) {
        this.value = from;
        this.to = to;
    }

    public static ValueAnimator ofFloat(float... values) {
        return new ValueAnimator(values[0], values[values.length - 1]);
    }

    public ValueAnimator setDuration(long d) { return this; }

    public void setInterpolator(Object o) { }

    public void addUpdateListener(AnimatorUpdateListener l) { listener = l; }

    public Object getAnimatedValue() { return value; }

    public void start() {
        value = to;
        if (listener != null) listener.onAnimationUpdate(this);
    }

    public void cancel() { }
}
