package com.downx.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 排列样式选择器：两列卡片，每张卡片上画一个该样式的小样图，点一下立刻切换。
 * 「两列大图」已下架（和竖横分区、单列大卡重复度高，列表太长不美观）；
 * 老设置里还停在它上面的，面板会把当前样式补进来，选一次新的就换了。
 */
public class LayoutPicker {

    public interface OnPick {
        void onPicked(int mode);
    }

    /** 面板里展示的样式，去掉了两列大图 */
    private static final int[] MODES = {
            Prefs.LAYOUT_GRID_3, Prefs.LAYOUT_GRID_4,
            Prefs.LAYOUT_SQUARE, Prefs.LAYOUT_WIDE,
            Prefs.LAYOUT_LIST, Prefs.LAYOUT_MASONRY,
            Prefs.LAYOUT_DRAWER, Prefs.LAYOUT_SPLIT,
    };

    public static void show(final Activity act, final OnPick cb) {
        final int cur = Prefs.layoutMode(act);

        List<Integer> modes = new ArrayList<>();
        for (int m : MODES) modes.add(m);
        if (!modes.contains(cur)) modes.add(0, cur);   // 兜底：老设置值不在面板里也展示出来

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(act, 10), dp(act, 8), dp(act, 10), dp(act, 8));

        final AlertDialog[] holder = new AlertDialog[1];
        final int cols = 2;
        for (int i = 0; i < modes.size(); i += cols) {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = dp(act, 8);
            row.setLayoutParams(rlp);
            for (int j = 0; j < cols; j++) {
                int idx = i + j;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (j > 0) lp.leftMargin = dp(act, 8);
                if (idx < modes.size()) {
                    final int m = modes.get(idx);
                    View card = buildCard(act, m, m == cur, new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            Prefs.setLayoutMode(act, m);
                            if (holder[0] != null) holder[0].dismiss();
                            if (cb != null) cb.onPicked(m);
                        }
                    });
                    card.setLayoutParams(lp);
                    row.addView(card);
                } else {
                    View pad = new View(act);
                    pad.setLayoutParams(lp);
                    row.addView(pad);
                }
            }
            root.addView(row);
        }

        ScrollView sv = new ScrollView(act);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(root);

        AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle("排列样式")
                .setView(sv)
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        holder[0] = dlg;
    }

    /** 一张卡片：上面小样图、下面名称；选中的有蓝色描边 */
    private static View buildCard(Context c, int mode, boolean checked,
                                  View.OnClickListener click) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(c, 10), dp(c, 12), dp(c, 10), dp(c, 12));

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(c, 14));
        bg.setColor(checked ? 0x1F1D9BF0 : 0xFF15151C);
        bg.setStroke(dp(c, 1), checked ? 0xFF1D9BF0 : 0xFF26262E);
        card.setBackground(bg);

        FrameLayout pv = (FrameLayout) preview(c, mode);
        card.addView(pv);

        TextView name = new TextView(c);
        name.setText(checked ? Prefs.layoutName(mode) + " ✓" : Prefs.layoutName(mode));
        name.setTextColor(checked ? 0xFF4DA3FF : 0xFFE8E8EE);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(1);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(c, 9);
        name.setLayoutParams(nlp);
        card.addView(name);

        card.setOnClickListener(click);
        card.setClickable(true);
        return card;
    }

    // ---------------- 小样图 ----------------

    private static View preview(Context c, int mode) {
        int S = dp(c, 74);
        FrameLayout box = new FrameLayout(c);
        box.setLayoutParams(new LinearLayout.LayoutParams(S, S));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF101015);
        bg.setCornerRadius(dp(c, 9));
        bg.setStroke(Math.max(1, dp(c, 1)), 0xFF2A2A34);
        box.setBackground(bg);

        int pad = dp(c, 9);
        int iw = S - pad * 2;
        int ih = S - pad * 2;
        int gap = Math.max(1, dp(c, 2));
        int fill = 0xFF43434F;
        int faint = 0xFF2F2F3A;

        switch (mode) {
            case Prefs.LAYOUT_GRID_4: {
                int ch = (ih - gap) / 2;
                int cw = (iw - gap * 3) / 4;
                for (int r = 0; r < 2; r++)
                    for (int col = 0; col < 4; col++)
                        blk(c, box, pad + col * (cw + gap), pad + r * (ch + gap), cw, ch, fill);
                break;
            }
            case Prefs.LAYOUT_GRID_2: {
                int cw = (iw - gap) / 2;
                for (int col = 0; col < 2; col++)
                    blk(c, box, pad + col * (cw + gap), pad, cw, ih, fill);
                break;
            }
            case Prefs.LAYOUT_SQUARE: {
                int cw = (iw - gap * 2) / 3;
                int ch = (ih - gap * 2) / 3;
                for (int r = 0; r < 3; r++)
                    for (int col = 0; col < 3; col++)
                        blk(c, box, pad + col * (cw + gap), pad + r * (ch + gap), cw, ch, fill);
                break;
            }
            case Prefs.LAYOUT_WIDE: {
                int ch = (ih - gap) / 2;
                for (int r = 0; r < 2; r++)
                    blk(c, box, pad, pad + r * (ch + gap), iw, ch, fill);
                break;
            }
            case Prefs.LAYOUT_LIST: {
                int rh = (ih - gap * 2) / 3;
                int th = rh;
                for (int r = 0; r < 3; r++) {
                    int y = pad + r * (rh + gap);
                    blk(c, box, pad, y, th, th, fill);
                    blk(c, box, pad + th + dp(c, 3), y + dp(c, 2), iw - th - dp(c, 3), dp(c, 3), fill);
                    blk(c, box, pad + th + dp(c, 3), y + dp(c, 7), iw / 2, dp(c, 2), faint);
                }
                break;
            }
            case Prefs.LAYOUT_MASONRY: {
                int cw = (iw - gap) / 2;
                int h1 = Math.round(ih * 0.52f);
                int h2 = ih - h1 - gap;
                blk(c, box, pad, pad, cw, h1, fill);
                blk(c, box, pad, pad + h1 + gap, cw, h2, fill);
                blk(c, box, pad + cw + gap, pad, cw, h2, fill);
                blk(c, box, pad + cw + gap, pad + h2 + gap, cw, h1, fill);
                break;
            }
            case Prefs.LAYOUT_DRAWER: {
                // 一张大图占满，右侧留一条缝露出下一张
                blk(c, box, pad, pad, iw - dp(c, 8), ih, fill);
                blk(c, box, pad + iw - dp(c, 5), pad, dp(c, 5), ih, faint);
                break;
            }
            case Prefs.LAYOUT_SPLIT: {
                // 上下两条横滑抽屉：上排竖版卡片、下排横版卡片
                int g2 = Math.max(1, dp(c, 2));
                int topH = (ih - g2 * 3) * 58 / 100;
                int botH = ih - topH - g2 * 3 - dp(c, 2);
                int cwV = Math.max(dp(c, 7), Math.round(topH * 9f / 16f));
                for (int i = 0; i < 2 && pad + i * (cwV + g2) + cwV <= pad + iw; i++)
                    blk(c, box, pad + i * (cwV + g2), pad, cwV, topH, fill);
                int y2 = pad + topH + g2 * 3 + dp(c, 2);
                int cwH = Math.max(dp(c, 9), Math.round(botH * 16f / 9f));
                for (int i = 0; i < 2 && pad + i * (cwH + g2) + cwH <= pad + iw; i++)
                    blk(c, box, pad + i * (cwH + g2), y2, cwH, botH, faint);
                break;
            }
            default: {
                int ch = (ih - gap) / 2;
                int cw = (iw - gap * 2) / 3;
                for (int r = 0; r < 2; r++)
                    for (int col = 0; col < 3; col++)
                        blk(c, box, pad + col * (cw + gap), pad + r * (ch + gap), cw, ch, fill);
                break;
            }
        }
        return box;
    }

    private static void blk(Context c, FrameLayout parent, int x, int y, int w, int h, int color) {
        if (w <= 0 || h <= 0) return;
        View v = new View(c);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = x;
        lp.topMargin = y;
        v.setLayoutParams(lp);
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, 2));
        v.setBackground(d);
        parent.addView(v);
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
