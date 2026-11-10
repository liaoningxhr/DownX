package com.downx.app;

/**
 * 「锁定画面框」算法的主机验证台（纯 Java，不跑 Android）。
 *
 * 要保证的事：手动转过屏之后，左右滑切换视频时画面框大小**完全不变** ——
 * 竖屏视频在横屏里就是一条竖画面，滑到横拍视频也缩在同一个框里，
 * 不会出现「上一条竖屏宽、下一条突然满屏」。
 */
public class Test {

    static int pass, fail;

    static void check(String what, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + what);
        } else {
            fail++;
            System.out.println("  FAIL  " + what + (detail.isEmpty() ? "" : "  ← " + detail));
        }
    }

    public static void main(String[] a) {
        System.out.println("== FitBox：锁定画面框 ==");

        // 1. 没锁定就铺满
        int[] full = FitBox.of(2400, 1080, 0);
        check("没锁定时铺满屏幕", full[0] == 2400 && full[1] == 1080, full[0] + "x" + full[1]);

        // 2. 竖屏画面在横屏里：一条竖的框（这是用户要的「竖屏宽视频」）
        int[] v = FitBox.of(2400, 1080, 1080f / 1920f);
        // 1080 × 9/16 = 607.5，取整到 608 —— 断言给 ±1px 的余量，别把取整当错
        check("9:16 在 2400x1080 里 → 高贴满、宽 607~608",
                v[1] == 1080 && Math.abs(v[0] - 607.5f) <= 1, v[0] + "x" + v[1]);
        check("9:16 框的宽高比与原视频一致（误差 ≤1px）",
                Math.abs(v[0] * 1f / v[1] - 1080f / 1920f) < 0.002f,
                String.format("%.4f", v[0] * 1f / v[1]));

        // 3. 横拍画面在同一个框里：不越界（这就是「不再满屏」）
        int[] h = FitBox.of(2400, 1080, 1920f / 1080f);
        check("16:9 在 2400x1080 里 → 1920x1080（不撑破屏幕）", h[0] == 1920 && h[1] == 1080,
                h[0] + "x" + h[1]);

        // 4. 竖屏手机上的竖屏视频：贴满，不留黑边
        int[] vp = FitBox.of(1080, 2400, 1080f / 1920f);
        check("9:16 在 1080x2400 竖屏里 → 1080x1920", vp[0] == 1080 && vp[1] == 1920,
                vp[0] + "x" + vp[1]);

        // 5. 关键不变量：框只由「锁定的比例 + 屏幕尺寸」决定，与当前播放的视频无关
        float[] aspects = {1080f / 1920f, 720f / 1280f, 2160f / 3840f};
        int[] first = null;
        boolean same = true;
        for (float as : aspects) {
            int[] box = FitBox.of(2400, 1080, as);
            if (first == null) first = box;
            else if (box[0] != first[0] || box[1] != first[1]) same = false;
        }
        check("同为 9:16 的不同分辨率（720/1080/4K）框完全一致", same,
                first == null ? "" : first[0] + "x" + first[1]);

        // 6. 任意比例都不越界
        boolean inside = true;
        String bad = "";
        float[] all = {0.3f, 0.5625f, 0.75f, 1f, 4f / 3f, 1.7778f, 2.35f, 5f};
        for (float as : all) {
            for (int[] scr : new int[][]{{2400, 1080}, {1080, 2400}, {1080, 1080}}) {
                int[] b = FitBox.of(scr[0], scr[1], as);
                if (b[0] > scr[0] || b[1] > scr[1] || b[0] < 1 || b[1] < 1) {
                    inside = false;
                    bad = String.format("%.3f on %dx%d -> %dx%d", as, scr[0], scr[1], b[0], b[1]);
                }
            }
        }
        check("所有比例/屏幕组合都不越界", inside, bad);

        // 7. 极端比例（超宽 / 超窄）不能算出 0 或负数
        int[] wide = FitBox.of(2400, 1080, 100f);
        int[] thin = FitBox.of(2400, 1080, 0.02f);
        check("超宽 100:1 → 宽贴满、高 ≥1", wide[0] == 2400 && wide[1] >= 1,
                wide[0] + "x" + wide[1]);
        check("超窄 1:50 → 高贴满、宽 ≥1", thin[1] == 1080 && thin[0] >= 1,
                thin[0] + "x" + thin[1]);

        // 8. 屏幕尺寸未知（刚转屏那一帧）不能瞎算
        int[] none = FitBox.of(0, 0, 0.5625f);
        check("屏幕尺寸未知 → 返回 0x0（调用方等一帧再算）", none[0] == 0 && none[1] == 0,
                none[0] + "x" + none[1]);

        System.out.println("\n== 结果：" + pass + " PASS, " + fail + " FAIL ==");
        if (fail > 0) System.exit(1);
    }
}
