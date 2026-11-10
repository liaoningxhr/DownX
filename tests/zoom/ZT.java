package com.downx.app;

import android.view.MotionEvent;

import static android.view.MotionEvent.ACTION_CANCEL;
import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_MOVE;
import static android.view.MotionEvent.ACTION_POINTER_DOWN;
import static android.view.MotionEvent.ACTION_UP;

/**
 * Zoomer 纯逻辑验证：不连手机、不联网，用假的 View / MotionEvent 把
 * 「捏合倍数、锚点跟随、边界夹取、轻点还原」逐条断言一遍。
 *
 * 用法：sh tests/zoom/run.sh
 */
public class ZT {

    private static int pass, fail;

    public static void main(String[] args) {
        // 1080×2400，和用户那台手机同量级
        final int W = 1080, H = 2400;

        // ---- 1. 双指从 100px 拉到 200px：放大 2 倍，且捏合中点跟着手指走 ----
        android.view.View v = new android.view.View(W, H);
        Zoomer z = new Zoomer(v);
        check("没放大时单指按下不抢占（单击还要用）",
                !z.onTouchEvent(MotionEvent.ev(ACTION_DOWN, 500, 1200)));
        check("第二根手指落下即接管",
                z.onTouchEvent(MotionEvent.ev(ACTION_POINTER_DOWN, 500, 1200, 600, 1200)));
        check("捏开过程被接管",
                z.onTouchEvent(MotionEvent.ev(ACTION_MOVE, 500, 1200, 700, 1200)));
        check("指距翻倍 = 放大 2.0 倍", near(z.getScale(), 2f));
        check("支点落在画面中心",
                near(v.pivotX, W / 2f) && near(v.pivotY, H / 2f));
        check("缩放已写进 View", near(v.scaleX, 2f) && near(v.scaleY, 2f));
        // 起点中点 550、倍率 1；终点中点 600、倍率 2 → tx = 600-540-(550-540)*2 = 40
        check("捏合中点从 550 挪到 600 → 位移 40px", near(v.translationX, 40f));
        check("抬指后保持放大", z.onTouchEvent(MotionEvent.ev(ACTION_UP, 700, 1200))
                && near(z.getScale(), 2f));

        // ---- 2. 放大后单指拖动，并且在边界停住 ----
        check("放大后单指按下被接管",
                z.onTouchEvent(MotionEvent.ev(ACTION_DOWN, 500, 1200)));
        z.onTouchEvent(MotionEvent.ev(ACTION_MOVE, 2500, 1200));   // 想往右拖 2000px
        // 2 倍时最多各方向挪 (1080*(2-1))/2 = 540
        check("拖出画面边界时停在 540px", near(v.translationX, 540f));
        z.onTouchEvent(MotionEvent.ev(ACTION_CANCEL, 2500, 1200));
        check("拖动被打断也不会错位", near(v.translationX, 540f));

        // ---- 3. 放大状态下轻点 = 还原 100% ----
        check("放大时按下被接管",
                z.onTouchEvent(MotionEvent.ev(ACTION_DOWN, 500, 1200)));
        check("放大时轻点 = 还原",
                z.onTouchEvent(MotionEvent.ev(ACTION_UP, 500, 1200)));
        check("还原后倍率 1、位移 0、View 属性也归位",
                !z.isZoomed() && near(z.getScale(), 1f)
                        && near(v.translationX, 0f) && near(v.scaleX, 1f));

        // ---- 4. 捏到 1 倍以下就停在 1 倍（不会缩成小图） ----
        android.view.View v2 = new android.view.View(W, H);
        Zoomer z2 = new Zoomer(v2);
        z2.onTouchEvent(MotionEvent.ev(ACTION_DOWN, 500, 1200));
        z2.onTouchEvent(MotionEvent.ev(ACTION_POINTER_DOWN, 400, 1200, 600, 1200)); // 指距 200
        z2.onTouchEvent(MotionEvent.ev(ACTION_MOVE, 540, 1200, 560, 1200));         // 指距 20
        check("捏到 1 倍以下夹在 1 倍", near(z2.getScale(), 1f) && near(v2.scaleX, 1f));

        // ---- 5. 只放大到 1.03 倍这种，松手自动弹回 ----
        android.view.View v3 = new android.view.View(W, H);
        Zoomer z3 = new Zoomer(v3);
        z3.zoomTo(1.03f, false);
        z3.onTouchEvent(MotionEvent.ev(ACTION_DOWN, 300, 300));
        z3.onTouchEvent(MotionEvent.ev(ACTION_MOVE, 340, 300));   // 动过，不算轻点
        z3.onTouchEvent(MotionEvent.ev(ACTION_UP, 340, 300));
        check("1.03 倍松手弹回 100%", near(z3.getScale(), 1f) && near(v3.scaleX, 1f));

        // ---- 6. 没放大时，普通单击/左右滑事件一律放行 ----
        Zoomer z4 = new Zoomer(new android.view.View(W, H));
        boolean swallowed = z4.onTouchEvent(MotionEvent.ev(ACTION_DOWN, 100, 100))
                | z4.onTouchEvent(MotionEvent.ev(ACTION_MOVE, 600, 100))
                | z4.onTouchEvent(MotionEvent.ev(ACTION_UP, 600, 100));
        check("没放大时不吞事件（单击关闭、左右滑切换还能用）", !swallowed);

        // ---- 7. 视频/图片来回切：旧画布要归位 ----
        android.view.View a = new android.view.View(W, H), b = new android.view.View(W, H);
        Zoomer z5 = new Zoomer(a);
        z5.zoomTo(2f, false);
        z5.setTarget(b);
        check("换画布后旧画面归位、新画面 100%",
                near(a.scaleX, 1f) && near(z5.getScale(), 1f) && near(b.scaleX, 1f));

        System.out.println("PASS " + pass + " / FAIL " + fail);
        if (fail > 0) System.exit(1);
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) < 0.01f;
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + name);
        } else {
            fail++;
            System.out.println("  FAIL  " + name);
        }
    }
}
