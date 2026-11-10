package com.downx.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.TranslateAnimation;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 图片浏览：全屏黑底 + 左右滑切换，专门看"同一来源的一组图"。
 * 纯 SDK 实现（GestureDetector + ImageSwitcher 思路），不引第三方库。
 * 播放列表不收图片（见 PlayerActivity.buildPlaylist），这里就是图片的家。
 */
public class PhotoViewerActivity extends Activity {

    /** 与 MainActivity/PlayerActivity 相同的条目定位方式：name|ts */
    public static final String E_IDS = "photoIds";

    private final List<HistoryStore.Entry> items = new ArrayList<>();
    private FrameLayout root;
    private ImageView img;
    private TextView tvPos;
    private int idx;

    /** 双指捏合放大 / 放大后拖动 / 轻点还原 */
    private Zoomer zoomer;
    /** 这次手势是不是已经被缩放器接管（接管后要给 GestureDetector 补一个 CANCEL） */
    private boolean gCanceled;

    /** 多选平铺模式：勾几张，一次分享出去 */
    private boolean pickMode;
    private final java.util.LinkedHashSet<Integer> picked = new java.util.LinkedHashSet<>();
    private TextView tvPickCount;
    private View pickLayer;

    private final GestureDetector.SimpleOnGestureListener gestureListener =
            new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onFling(MotionEvent e1, MotionEvent e2,
                                       float vx, float vy) {
                    if (e1 == null || e2 == null) return false;
                    float dx = e2.getX() - e1.getX();
                    if (Math.abs(dx) > 80 && Math.abs(dx) > Math.abs(e2.getY() - e1.getY())) {
                        if (dx < 0) step(1, -1); else step(-1, 1);   // 左滑下一张，右滑上一张
                        return true;
                    }
                    return false;
                }

                @Override
                public boolean onSingleTapConfirmed(MotionEvent e) {
                    finish();      // 单击关闭
                    return true;
                }

                @Override
                public void onLongPress(MotionEvent e) {
                    photoMenu();   // 长按：分享 / 删除 单张
                }
            };
    /**
     * 必须等 Activity 挂上 context 之后再建：GestureDetector 构造时会读
     * ViewConfiguration，字段初始化阶段 getResources() 还是 null，直接 NPE 闪退。
     */
    private GestureDetector gesture;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        gesture = new GestureDetector(this, gestureListener);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        // 关键：ImageView 默认不可点击，触摸事件根本不会派发给它，
        // 手势（左右滑动）就永远收不到 —— 必须显式打开 clickable
        img.setClickable(true);
        root.addView(img, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        tvPos = new TextView(this);
        tvPos.setTextColor(0xFFB9B9C4);
        tvPos.setTextSize(12.5f);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        plp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        int pad = Math.round(14 * getResources().getDisplayMetrics().density);
        plp.topMargin = Math.round(24 * getResources().getDisplayMetrics().density);
        tvPos.setPadding(pad, pad, pad, pad);
        root.addView(tvPos, plp);

        setContentView(root);
        zoomer = new Zoomer(img);
        img.setOnTouchListener(this::onImgTouch);

        // 收条目：和播放列表同款的 name|ts 串
        List<HistoryStore.Entry> all = HistoryStore.list(this);
        String ids = getIntent().getStringExtra(E_IDS);
        if (ids != null) {
            for (String one : ids.split(";")) {
                int bar = one.lastIndexOf('|');
                if (bar <= 0) continue;
                String name = one.substring(0, bar);
                long ts;
                try {
                    ts = Long.parseLong(one.substring(bar + 1));
                } catch (Throwable t) {
                    continue;
                }
                for (HistoryStore.Entry e : all) {
                    if ("photo".equals(e.kind) && e.name.equals(name) && e.ts == ts) {
                        items.add(e);
                        break;
                    }
                }
            }
        }
        if (items.isEmpty()) {
            Toast.makeText(this, "图片已经不在了", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        // 只留文件真在盘上的：记录还在、文件被清理软件或相册删掉的，
        // 留在列表里只会让人翻到一张就弹"读不出来了"
        List<HistoryStore.Entry> alive = new ArrayList<>();
        for (HistoryStore.Entry e : items) {
            try {
                if (Store.exists(this, e)) alive.add(e);
            } catch (Throwable ignored) { }
        }
        if (alive.isEmpty()) {
            Toast.makeText(this, "这组图片的文件已经不在了（可能被清理过），记录还在主页",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        items.clear();
        items.addAll(alive);
        idx = 0;
        show(0, 0);
    }

    /**
     * 触摸分发：先问缩放器。两指一落下它就把整段手势接过去，这时要给
     * GestureDetector 补一个 CANCEL —— 否则捏合的过程里还会冒出"单击关闭"
     * 和"长按菜单"，手一松图就没了。
     */
    private boolean onImgTouch(View v, MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) gCanceled = false;
        if (zoomer.onTouchEvent(ev)) {
            if (!gCanceled) {
                gCanceled = true;
                MotionEvent c = MotionEvent.obtain(ev);
                c.setAction(MotionEvent.ACTION_CANCEL);
                gesture.onTouchEvent(c);
                c.recycle();
            }
            return true;
        }
        if (gCanceled) return true;
        return gesture.onTouchEvent(ev);
    }

    /** dir：+1 下一张 / -1 上一张；anim：进场方向（1 从右滑入，-1 从左滑入，0 不动） */
    private void step(int dir, int anim) {
        if (items.size() < 2) {
            Toast.makeText(this, "就这一张", Toast.LENGTH_SHORT).show();
            return;
        }
        idx = (idx + dir + items.size()) % items.size();
        show(idx, anim);
    }

    private void show(int i, int anim) {
        HistoryStore.Entry e = items.get(i);
        // 翻页就回到 100%：上一张放大着看细节，下一张还糊着一块会很怪
        if (zoomer != null) zoomer.reset(false);
        // 大图别在主线程解码（几千像素的图一解就是一两秒，画面会僵住）：
        // 丢到后台线程，回来时核对页码，翻页了就丢弃这次结果
        final int req = ++decodeSeq;
        img.setImageDrawable(null);
        new Thread(() -> {
            Bitmap b = null;
            try {
                if (e.visible) {
                    File f = Store.publicFile(e.kind, e.name);
                    if (f != null && f.exists()) b = decode(f.getAbsolutePath());
                }
                if (b == null) {
                    File f = Store.privateFile(this, e.kind, e.name);
                    if (f != null && f.exists()) b = decode(f.getAbsolutePath());
                }
                if (b == null && e.uri != null && e.uri.startsWith("content://")) {
                    b = BitmapFactory.decodeStream(getContentResolver()
                            .openInputStream(Uri.parse(e.uri)));
                }
            } catch (Throwable ignored) { }
            final Bitmap fb = b;
            runOnUiThread(() -> {
                if (req != decodeSeq || isFinishing()) return;
                if (fb == null) {
                    tvPos.setText((i + 1) + " / " + items.size() + " · 这张读不出来");
                } else {
                    img.setImageBitmap(fb);
                }
            });
        }, "photo-decode").start();
        tvPos.setText((i + 1) + " / " + items.size());

        if (anim != 0) {
            TranslateAnimation a = new TranslateAnimation(
                    Animation.RELATIVE_TO_SELF, anim * 1.0f,
                    Animation.RELATIVE_TO_SELF, 0f,
                    Animation.RELATIVE_TO_SELF, 0f,
                    Animation.RELATIVE_TO_SELF, 0f);
            a.setDuration(220);
            img.startAnimation(a);
        }
    }

    private int decodeSeq;

    /** 长按单张：和主页视频一样的底部卡片菜单（微信 / 其他应用 / 原帖 / 删除） */
    private void photoMenu() {
        if (items.isEmpty()) return;
        final HistoryStore.Entry e = items.get(idx);

        final android.app.Dialog dlg = new android.app.Dialog(this,
                android.R.style.Theme_Translucent_NoTitleBar);
        final View sheet = getLayoutInflater().inflate(R.layout.dialog_entry_menu, null);
        dlg.setContentView(sheet);
        dlg.setCanceledOnTouchOutside(true);
        android.view.Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.66f;
            w.setAttributes(lp);
        }
        int pad = dp(9);
        sheet.setPadding(sheet.getPaddingLeft(), sheet.getPaddingTop(),
                sheet.getPaddingRight(), pad + navBar());

        TextView title = sheet.findViewById(R.id.sheetTitle);
        title.setText((idx + 1) + " / " + items.size() + " · " + e.name);
        sheet.setAlpha(0f);
        sheet.setTranslationY(dp(70));
        sheet.setScaleX(0.95f);
        sheet.setScaleY(0.95f);
        sheet.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(300)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(2.2f))
                .start();

        View close = sheet.findViewById(R.id.sheetClose);
        View actWx = sheet.findViewById(R.id.actWx);
        View actShare = sheet.findViewById(R.id.actShare);
        View actPlay = sheet.findViewById(R.id.actPlay);
        View actPage = sheet.findViewById(R.id.actPage);
        View actMulti = sheet.findViewById(R.id.actMulti);
        View rowHide = sheet.findViewById(R.id.rowHide);
        View rowDelRecord = sheet.findViewById(R.id.rowDelRecord);
        View rowDelFile = sheet.findViewById(R.id.rowDelFile);
        // 已经在看图了，不需要「播放」；「隐藏」对单张分享意义不大
        actPlay.setVisibility(View.GONE);
        rowHide.setVisibility(View.GONE);

        close.setOnClickListener(v -> dlg.dismiss());
        actWx.setOnClickListener(v -> { dlg.dismiss(); share(e, "com.tencent.mm"); });
        actShare.setOnClickListener(v -> { dlg.dismiss(); share(e, null); });
        actMulti.setOnClickListener(v -> { dlg.dismiss(); enterPickMode(); });
        actPage.setOnClickListener(v -> {
            dlg.dismiss();
            if (e.pageUrl != null && !e.pageUrl.isEmpty()) {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(e.pageUrl))); }
                catch (Throwable ignored) { }
            } else {
                Toast.makeText(this, "这张没有原帖链接", Toast.LENGTH_SHORT).show();
            }
        });
        rowDelRecord.setOnClickListener(v -> {
            dlg.dismiss();
            try { HistoryStore.remove(this, e); } catch (Throwable ignored) { }
            Toast.makeText(this, "已移除记录，文件保留", Toast.LENGTH_SHORT).show();
            finish();
        });
        rowDelFile.setOnClickListener(v -> { dlg.dismiss(); confirmDelete(e); });

        dlg.show();
    }

    // ---------------- 多选平铺：勾多张一次分享 ----------------

    private final List<View> pickCellViews = new ArrayList<>();
    private final List<TextView> pickBadges = new ArrayList<>();
    private TextView tvShareBtn;

    private void enterPickMode() {
        pickMode = true;
        picked.clear();
        picked.add(idx);            // 当前这张默认勾上，少点一下
        buildPickScreen();
    }

    private void exitPickMode() {
        pickMode = false;
        picked.clear();
        pickCellViews.clear();
        pickBadges.clear();
        root.removeAllViews();
        root.addView(img, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        plp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        plp.topMargin = dp(24);
        int pad = dp(14);
        tvPos.setPadding(pad, pad, pad, pad);
        root.addView(tvPos, plp);
        show(idx, 0);
    }

    /** 平铺九宫格：顶栏（标题+已选+全选）、三列方格、底部取消/分享 */
    private void buildPickScreen() {
        root.removeAllViews();
        pickCellViews.clear();
        pickBadges.clear();

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackgroundColor(0xFF0B0B0F);
        pickLayer = wrap;

        // ---- 顶栏 ----
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(16), dp(16), dp(12), dp(10));

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("选择图片");
        title.setTextColor(0xFFF5F5FA);
        title.setTextSize(17f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        tvPickCount = new TextView(this);
        tvPickCount.setTextColor(0xFF8E8E99);
        tvPickCount.setTextSize(12f);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(3);
        titleCol.addView(title);
        titleCol.addView(tvPickCount, clp);
        top.addView(titleCol, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final TextView all = new TextView(this);
        all.setText("全选");
        all.setTextSize(12.5f);
        all.setPadding(dp(13), dp(6), dp(13), dp(6));
        all.setBackgroundResource(R.drawable.bg_chip);
        all.setTextColor(0xFFB9B9C4);
        all.setOnClickListener(v -> {
            if (picked.size() >= items.size()) picked.clear();
            else for (int i = 0; i < items.size(); i++) picked.add(i);
            updatePickUi();
        });
        top.addView(all);
        wrap.addView(top);

        // ---- 三列平铺 ----
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(8), dp(2), dp(8), dp(10));

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int gap = dp(5);
        int cellW = (screenW - dp(16) - gap * 2) / 3;
        int cellH = cellW;                 // 正方形，缩略图裁切铺满，整齐
        int cols = 3;

        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                if (i > 0) rlp.topMargin = gap;
                row.setLayoutParams(rlp);
                grid.addView(row);
            }
            View cell = buildPickCell(i, cellW, cellH);
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(cellW, cellH);
            lp.rightMargin = (i % cols == cols - 1) ? 0 : gap;
            row.addView(cell, lp);
        }
        sv.addView(grid);
        wrap.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- 底栏 ----
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(dp(14), dp(10), dp(14), dp(12) + navBar());

        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setTextSize(14f);
        cancel.setTextColor(0xFFC9C9D2);
        cancel.setGravity(Gravity.CENTER);
        cancel.setBackgroundResource(R.drawable.bg_chip);
        cancel.setPadding(0, dp(11), 0, dp(11));
        cancel.setOnClickListener(v -> exitPickMode());
        LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        clp2.rightMargin = dp(10);
        bottom.addView(cancel, clp2);

        tvShareBtn = new TextView(this);
        tvShareBtn.setTextSize(14.5f);
        tvShareBtn.setTextColor(0xFFFFFFFF);
        tvShareBtn.setGravity(Gravity.CENTER);
        tvShareBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        tvShareBtn.setBackgroundResource(R.drawable.bg_primary);
        tvShareBtn.setPadding(0, dp(11), 0, dp(11));
        tvShareBtn.setOnClickListener(v -> sharePicked());
        bottom.addView(tvShareBtn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.6f));
        wrap.addView(bottom);

        root.addView(wrap, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        updatePickUi();
    }

    private View buildPickCell(final int i, int w, int h) {
        final FrameLayout cell = new FrameLayout(this);
        cell.setBackground(roundBg(0xFF1A1A20, 0, 0));
        cell.setClipToOutline(true);

        ImageView iv = new ImageView(this);
        iv.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Uri u = null;
        try {
            u = Store.uriFor(this, items.get(i));
        } catch (Throwable ignored) { }
        ThumbLoader.load(this, iv, u, "photo", items.get(i).thumb);
        cell.addView(iv);

        TextView num = new TextView(this);
        num.setGravity(Gravity.CENTER);
        num.setTextSize(11f);
        num.setTextColor(0xFFFFFFFF);
        num.setTypeface(null, android.graphics.Typeface.BOLD);
        num.setBackground(ovalBg(0xFF2F9BFF));
        num.setVisibility(View.GONE);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(dp(22), dp(22));
        nlp.gravity = Gravity.TOP | Gravity.END;
        nlp.topMargin = dp(6);
        nlp.rightMargin = dp(6);
        cell.addView(num, nlp);

        cell.setOnClickListener(v -> {
            if (picked.contains(i)) picked.remove(i);
            else picked.add(i);
            updatePickUi();
        });
        pickCellViews.add(cell);
        pickBadges.add(num);
        return cell;
    }

    private void updatePickUi() {
        int n = picked.size();
        if (tvPickCount != null) tvPickCount.setText("已选 " + n + " / " + items.size() + " 张");
        if (tvShareBtn != null) {
            tvShareBtn.setText(n == 0 ? "选几张再分享" : "分享 " + n + " 张");
            tvShareBtn.setAlpha(n == 0 ? 0.45f : 1f);
        }
        int order = 0;
        for (int i = 0; i < pickCellViews.size(); i++) {
            boolean on = picked.contains(i);
            View cell = pickCellViews.get(i);
            TextView badge = pickBadges.get(i);
            cell.setBackground(on ? roundBg(0x332F9BFF, 0xFF2F9BFF, 2)
                    : roundBg(0xFF1A1A20, 0, 0));
            if (on) {
                order++;
                badge.setText(String.valueOf(order));
                badge.setVisibility(View.VISIBLE);
            } else {
                badge.setVisibility(View.GONE);
            }
        }
    }

    /** 圆角矩形（fill 必填，stroke 传 0 表示不要描边） */
    private android.graphics.drawable.GradientDrawable roundBg(int fill, int stroke, int strokeDp) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(10));
        if (stroke != 0 && strokeDp > 0) g.setStroke(dp(strokeDp), stroke);
        return g;
    }

    private android.graphics.drawable.GradientDrawable ovalBg(int color) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    /** 把勾上的图一次性丢给系统分享（微信会出现在面板里） */
    private void sharePicked() {
        if (picked.isEmpty()) {
            Toast.makeText(this, "还没选图片", Toast.LENGTH_SHORT).show();
            return;
        }
        ArrayList<Uri> uris = new ArrayList<>();
        for (int i : picked) {
            try {
                if (Store.exists(this, items.get(i))) uris.add(Store.uriFor(this, items.get(i)));
            } catch (Throwable ignored) { }
        }
        if (uris.isEmpty()) {
            Toast.makeText(this, "这些图片的文件不在了", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_SEND_MULTIPLE);
        i.setType("image/*");
        i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(i, "分享 " + uris.size() + " 张图片"));
        } catch (Throwable t) {
            Toast.makeText(this, "没有能接收图片的应用", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (pickMode) {          // 多选态下返回 = 退出多选，回到单张浏览
            exitPickMode();
            return;
        }
        super.onBackPressed();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private int navBar() {
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    /** 分享出去；pkg = com.tencent.mm 直接落到微信的「发送给朋友」 */
    private void share(HistoryStore.Entry e, String pkg) {
        Uri uri;
        try {
            uri = Store.uriFor(this, e);
        } catch (Throwable t) {
            Toast.makeText(this, "读取文件失败", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(Store.mime(e.name, e.kind));
        i.putExtra(Intent.EXTRA_STREAM, uri);
        if (e.pageUrl != null && !e.pageUrl.isEmpty()) i.putExtra(Intent.EXTRA_TEXT, e.pageUrl);
        i.setClipData(android.content.ClipData.newRawUri("DownX", uri));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (pkg != null) {
            try {
                i.setClassName("com.tencent.mm", "com.tencent.mm.ui.tools.ShareImgUI");
                startActivity(i);
                return;
            } catch (Throwable ignored) { }
            i.setPackage(pkg);
            try {
                startActivity(i);
                return;
            } catch (Throwable ignored) {
                Toast.makeText(this, "没装微信，改用系统分享", Toast.LENGTH_SHORT).show();
            }
            i.setPackage(null);
        }
        try {
            startActivity(Intent.createChooser(i, "分享图片"));
        } catch (Throwable t) {
            Toast.makeText(this, "没有能接收图片的应用", Toast.LENGTH_SHORT).show();
        }
    }

    /** 删除当前这张：文件 + 记录一起清，然后自动跳到下一张 */
    private void confirmDelete(final HistoryStore.Entry e) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("删除这张图片？")
                .setMessage("文件和这条记录都会删掉，无法恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> {
                    try { HistoryStore.remove(this, e); } catch (Throwable ignored) { }
                    try {
                        if (e.uri != null && e.uri.startsWith("content://")) {
                            getContentResolver().delete(Uri.parse(e.uri), null, null);
                        }
                    } catch (Throwable ignored) { }
                    File f = e.visible ? Store.publicFile(e.kind, e.name)
                            : Store.privateFile(this, e.kind, e.name);
                    boolean ok = f == null || !f.exists() || f.delete();
                    if (!ok) {
                        File g = Store.legacyPrivateFile(this, e.kind, e.name);
                        if (g.exists()) g.delete();
                    }
                    items.remove(e);
                    if (items.isEmpty()) {
                        Toast.makeText(this, "这组图片已经删完了", Toast.LENGTH_SHORT).show();
                        finish();
                        return;
                    }
                    if (idx >= items.size()) idx = items.size() - 1;
                    show(idx, 0);
                    Toast.makeText(this, "已删除这一张", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    /** 大图别撑爆内存：按屏幕尺寸降采样 */
    private Bitmap decode(String path) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        int target = Math.max(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= target) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        return BitmapFactory.decodeFile(path, o2);
    }
}
