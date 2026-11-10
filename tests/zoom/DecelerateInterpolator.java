package android.view.animation;

public class DecelerateInterpolator {
    public DecelerateInterpolator() { }
    public DecelerateInterpolator(float factor) { }
    public float getInterpolation(float t) { return 1f - (1f - t) * (1f - t); }
}
