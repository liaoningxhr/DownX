package com.downx.app;

import android.animation.ValueAnimator;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * 双指捏合放大 / 放大后单指拖动 / 轻点还原 —— 播放器和图片查看器共用的一套缩放。
 *
 * 为什么自己写、不用系统的 ScaleGestureDetector：
 * 播放器画面上已经有「轻点显隐控制条 / 按住 2 倍速 / 左右滑切换视频」三套手势，
 * 系统的 ScaleGestureDetector 只负责"检测"不负责"接管"，两指一按，加速计时器
 * 还在跑、抬手还会被当成一次点击，容易互相打架。这里定一条简单规则：
 * 只要第二根手指落下，这次手势就全归它，直到所有手指抬起。
 *
 * 变换走 View 自带的 scale/translation（支点固定在视图中心），不重建 Bitmap、
 * 不换 Texture，放大是 GPU 直接缩放，ImageView 和 VideoView 都能用。
 */
class Zoomer {

    /** 最大放大倍数：再大就是一片马赛克了 */
    static final float MAX = 4.0f;
    /** 松手时小于这个倍数就弹回原样，免得停在 1.03 这种别扭的状态 */
    private static final float SNAP_BACK = 1.06f;

    private View target;
    /** 放大状态下轻点画面 = 还原（图片查看器里单击本来是关闭，放大时先还原更合手） */
    private final boolean tapToReset;

    private float scale = 1f;
    private float tx, ty;
    private ValueAnimator anim;

    /** 两指捏合中 */
    private boolean pinching;
    private float startDist, startScale;
    /** 捏合起点在"内容坐标系"里的位置：缩放过程中让这个点始终跟着手指中点走 */
    private float anchorU, anchorV;

    /** 放大后单指拖动中 */
    private boolean panning;
    private float lastX, lastY;

    /** 本次手势有没有真正动过：没动过的一下就是"轻点" */
    private boolean moved;
    private float downX, downY;

    Zoomer(View v) {
        this(v, true);
    }

    Zoomer(View v, boolean tapToReset) {
        target = v;
        this.tapToReset = tapToReset;
    }

    /**
     * 换一张画布（播放器里视频/图片来回切）。旧的那个要先把变换清干净，
     * 否则切回来时还是放大着的，画面莫名奇妙只剩一块。
     */
    void setTarget(View v) {
        if (v == target) return;
        resetNow(target);
        target = v;
        scale = 1f;
        tx = ty = 0;
        apply();
    }

    float getScale() {
        return scale;
    }

    boolean isZoomed() {
        return scale > 1.001f;
    }

    /**
     * 处理一次触摸事件。
     *
     * @return true 表示这次手势被缩放/拖动接管了，调用方不要再拿去做点击、快滑、
     *         长按加速；false 表示原样放行。
     */
    boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                stopAnim();
                downX = e.getX();
                downY = e.getY();
                moved = false;
                pinching = false;
                if (isZoomed()) {
                    // 已经放大了：单指就是拖动画面看别处
                    panning = true;
                    lastX = downX;
                    lastY = downY;
                    return true;
                }
                panning = false;
                return false;
            }

            case MotionEvent.ACTION_POINTER_DOWN: {
                if (e.getPointerCount() >= 2) {
                    stopAnim();
                    pinching = true;
                    panning = false;
                    moved = true;               // 捏合过就不算轻点
                    startDist = Math.max(dist(e, -1), 1f);
                    startScale = scale;
                    float mx = midX(e, -1), my = midY(e, -1);
                    anchorU = toContentX(mx, scale, tx);
                    anchorV = toContentY(my, scale, ty);
                    return true;
                }
                return isZoomed();
            }

            case MotionEvent.ACTION_MOVE: {
                if (pinching && e.getPointerCount() >= 2) {
                    float s = startScale * dist(e, -1) / startDist;
                    s = Math.max(1f, Math.min(MAX, s));
                    scale = s;
                    float cx = centerX(), cy = centerY();
                    // 让捏合起点（anchorU/anchorV）此刻正好落在两指中点下方
                    tx = midX(e, -1) - cx - (anchorU - cx) * s;
                    ty = midY(e, -1) - cy - (anchorV - cy) * s;
                    clamp();
                    apply();
                    return true;
                }
                if (panning) {
                    tx += e.getX() - lastX;
                    ty += e.getY() - lastY;
                    lastX = e.getX();
                    lastY = e.getY();
                    clamp();
                    apply();
                    moved = true;
                    return true;
                }
                if (Math.abs(e.getX() - downX) > dp(8) || Math.abs(e.getY() - downY) > dp(8)) {
                    moved = true;
                }
                return false;
            }

            case MotionEvent.ACTION_POINTER_UP: {
                if (pinching) {
                    if (e.getPointerCount() - 1 >= 2) {
                        // 三指以上：还有两指没松，用剩下的手指重新立个基准
                        rebase(e, e.getActionIndex());
                        return true;
                    }
                    // 只剩一根手指：顺手变成拖动，不用抬手再按
                    pinching = false;
                    int keep = e.getActionIndex() == 0 ? 1 : 0;
                    lastX = e.getX(keep);
                    lastY = e.getY(keep);
                    panning = isZoomed();
                    return true;
                }
                return panning;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean took = pinching || panning;
                pinching = false;
                panning = false;
                if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    if (scale < SNAP_BACK) resetNow(target);
                    else { clamp(); apply(); }
                    return took;
                }
                if (isZoomed()) {
                    if (tapToReset && !moved) {
                        reset(true);            // 放大的时候轻点一下 = 还原
                    } else if (scale < SNAP_BACK) {
                        reset(true);
                    }
                    return true;
                }
                // 捏回原尺寸了：把残留的位移抹掉，交还给普通手势
                if (took) {
                    scale = 1f;
                    tx = ty = 0;
                    apply();
                }
                return took;
            }
        }
        return false;
    }

    /** 菜单里的「放大 2 倍」之类：以画面中心为支点缩放 */
    void zoomTo(float want, boolean animate) {
        final float s = Math.max(1f, Math.min(MAX, want));
        stopAnim();
        if (!animate) {
            scale = s;
            tx = ty = 0;
            clamp();
            apply();
            return;
        }
        final float s0 = scale, x0 = tx, y0 = ty;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(220);
        a.setInterpolator(new DecelerateInterpolator(1.6f));
        a.addUpdateListener(an -> {
            float t = (float) an.getAnimatedValue();
            scale = s0 + (s - s0) * t;
            tx = x0 * (1f - t);
            ty = y0 * (1f - t);
            clamp();
            apply();
        });
        anim = a;
        a.start();
    }

    /** 带动画回到 100% */
    void reset(boolean animate) {
        if (!isZoomed() && tx == 0 && ty == 0) return;
        if (!animate) {
            resetNow(target);
            return;
        }
        zoomTo(1f, true);
    }

    private void resetNow(View v) {
        stopAnim();
        scale = 1f;
        tx = ty = 0;
        if (v != null) {
            v.setScaleX(1f);
            v.setScaleY(1f);
            v.setTranslationX(0f);
            v.setTranslationY(0f);
        }
    }

    private void stopAnim() {
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
    }

    private void apply() {
        int w = target.getWidth(), h = target.getHeight();
        if (w <= 0 || h <= 0) return;          // 还没量好尺寸，等下一次事件再贴
        target.setPivotX(w / 2f);
        target.setPivotY(h / 2f);
        target.setScaleX(scale);
        target.setScaleY(scale);
        target.setTranslationX(tx);
        target.setTranslationY(ty);
    }

    /** 画面不能被拖出边界：放大多少，最多能各方向挪一半的余量 */
    private void clamp() {
        int w = target.getWidth(), h = target.getHeight();
        if (scale <= 1.0001f) {
            scale = 1f;
            tx = ty = 0;
            return;
        }
        if (w <= 0 || h <= 0) return;
        float mx = w * (scale - 1f) / 2f;
        float my = h * (scale - 1f) / 2f;
        if (tx > mx) tx = mx;
        if (tx < -mx) tx = -mx;
        if (ty > my) ty = my;
        if (ty < -my) ty = -my;
    }

    private void rebase(MotionEvent e, int skip) {
        startDist = Math.max(dist(e, skip), 1f);
        startScale = scale;
        anchorU = toContentX(midX(e, skip), scale, tx);
        anchorV = toContentY(midY(e, skip), scale, ty);
    }

    private float centerX() {
        return target.getWidth() / 2f;
    }

    private float centerY() {
        return target.getHeight() / 2f;
    }

    /** 屏幕坐标 → 内容坐标（支点在视图中心，所以绕中心做逆变换） */
    private float toContentX(float x, float s, float t) {
        float cx = centerX();
        if (s <= 0f) return x;
        return (x - cx - t) / s + cx;
    }

    private float toContentY(float y, float s, float t) {
        float cy = centerY();
        if (s <= 0f) return y;
        return (y - cy - t) / s + cy;
    }

    /** 两指间距；skip >= 0 时跳过那一根（抬手时用） */
    private static float dist(MotionEvent e, int skip) {
        int n = e.getPointerCount();
        if (n < 2) return 1f;
        int a = -1, b = -1;
        for (int i = 0; i < n; i++) {
            if (i == skip) continue;
            if (a < 0) a = i;
            else if (b < 0) { b = i; break; }
        }
        if (a < 0 || b < 0) return 1f;
        float dx = e.getX(a) - e.getX(b);
        float dy = e.getY(a) - e.getY(b);
        return (float) Math.hypot(dx, dy);
    }

    private static float midX(MotionEvent e, int skip) {
        int a = -1, b = -1;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (i == skip) continue;
            if (a < 0) a = i;
            else if (b < 0) { b = i; break; }
        }
        if (a < 0) return e.getX();
        if (b < 0) return e.getX(a);
        return (e.getX(a) + e.getX(b)) / 2f;
    }

    private static float midY(MotionEvent e, int skip) {
        int a = -1, b = -1;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (i == skip) continue;
            if (a < 0) a = i;
            else if (b < 0) { b = i; break; }
        }
        if (a < 0) return e.getY();
        if (b < 0) return e.getY(a);
        return (e.getY(a) + e.getY(b)) / 2f;
    }

    private int dp(float v) {
        return Math.round(v * target.getResources().getDisplayMetrics().density);
    }
}
