package com.downx.app;

/**
 * 把「锁定画面框」算成具体像素（纯逻辑，不碰 Android API，可主机验证）。
 *
 * 播放器手动转过屏之后，画面框按当时那条视频的宽高比锁住：
 * 之后左右滑切换视频，只换内容、不换框 —— 竖屏视频在横屏里就是中间一条竖画面，
 * 滑到横拍视频也是缩在同一个框里，不会突然撑满屏幕。
 *
 * 规则：按宽高比等比缩放到能塞进屏幕为准（宽先试、放不下再按高算），居中显示。
 */
public final class FitBox {

    private FitBox() { }

    /**
     * @param pw     可用宽（屏幕宽）
     * @param ph     可用高（屏幕高）
     * @param aspect 画面框的宽/高比（<=0 表示没锁定，返回铺满）
     * @return {宽, 高}
     */
    public static int[] of(int pw, int ph, float aspect) {
        if (pw <= 0 || ph <= 0) return new int[]{0, 0};
        if (aspect <= 0.01f) return new int[]{pw, ph};      // 没锁定：铺满
        int w = pw;
        int h = Math.round(pw / aspect);
        if (h > ph) {                                        // 竖画面：先按高贴住，再反算宽
            h = ph;
            w = Math.round(ph * aspect);
        }
        if (w < 1) w = 1;
        if (h < 1) h = 1;
        return new int[]{w, h};
    }
}
