package com.downx.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 内置播放器：
 * - 默认循环播放，不再停在最后一帧
 * - 自绘半透明细进度条，可拖动定位，控制层自动淡出不再遮挡画面
 * - 按住画面 2 倍速，按住时上滑锁定持续加速，再按一下取消
 * - 双指捏合放大画面（看细节），放大后单指拖动看别处，轻点还原
 */
public class PlayerActivity extends Activity {

    public static final String E_NAME = "name";
    public static final String E_KIND = "kind";
    public static final String E_TS = "ts";
    /** 竖横分区模式下传进来的「同方向清单」：name|ts;name|ts… 左右滑只在这几条里切 */
    public static final String E_PLAYLIST = "playlistIds";

    private static final float BOOST = 2.0f;
    private static final long AUTO_HIDE_MS = 3200;

    private VideoView video;
    private ImageView photo;
    private ImageView btnPlay;
    private TextView tvTitle, tvSub, tvHint, tvCur, tvDur, tvSpeed, tvSpeedTip, tvLock;
    /** 播放键当前显示的是"暂停"图标（正在播放） */
    private boolean playIconPlaying;
    private SeekBar sb;
    private View touchLayer, topBar, ctrl;
    /** 双指捏合放大 / 放大后拖动 / 轻点还原 */
    private Zoomer zoomer;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private HistoryStore.Entry entry;

    private boolean prepared;
    private boolean dragging;
    private boolean controlsShown = true;

    /** 用户自己选的常速 */
    private float baseSpeed = 1.0f;
    /** 按住期间的临时加速 */
    private boolean boosting;
    /** 上滑锁定后的持续加速 */
    private boolean boostLocked;

    private float downY;
    private boolean longFired;
    private boolean swallowed;

    private int durCache;
    /** 本次会话用过的播放地址；出错时用来判断要不要换路径重试 */
    private Uri curUri;
    /** 是否已经用文件路径兜底重试过 */
    private boolean retried;
    /** 横屏播放：用户手动转过就不再用自动判断去覆盖他 */
    private boolean userRotated;
    /**
     * 手动转过之后**锁定的画面框**（宽/高比，0 = 没锁，按视频比例自动摆屏）。
     *
     * 为什么需要它：以前每切一条视频都按新视频的比例重新摆屏，
     * 正在看「竖屏视频摆在横屏里的一条竖画面」，左滑到下一条横拍视频，
     * 画面「啪」一下撑满整个屏幕，眼睛要重新适应一次。
     * 现在只要用户手动转过，就把当时那条视频的画面框锁住，左右滑只换内容、不换框，
     * 后面遇到横拍视频也是缩在同一个框里（上下留黑），画面大小始终一致。
     */
    private float frameAspect;
    /** 最近一条视频的真实像素宽高：转屏、解锁时都要用它 */
    private int lastVw, lastVh;
    /** 左右滑切换：当前播放列表与位置 */
    private java.util.List<HistoryStore.Entry> playlist;
    private int playIndex = -1;
    private float downX;
    private long downAt;
    private boolean swipingX;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        video = findViewById(R.id.video);
        photo = findViewById(R.id.photo);
        tvTitle = findViewById(R.id.tvTitle);
        tvSub = findViewById(R.id.tvSub);
        tvHint = findViewById(R.id.tvHint);
        tvCur = findViewById(R.id.tvCur);
        tvDur = findViewById(R.id.tvDur);
        btnPlay = findViewById(R.id.btnPlay);
        tvSpeed = findViewById(R.id.tvSpeed);
        tvSpeedTip = findViewById(R.id.tvSpeedTip);
        tvLock = findViewById(R.id.tvLock);
        sb = findViewById(R.id.sb);
        touchLayer = findViewById(R.id.touchLayer);
        topBar = findViewById(R.id.topBar);
        ctrl = findViewById(R.id.ctrl);

        String name = getIntent().getStringExtra(E_NAME);
        String kind = getIntent().getStringExtra(E_KIND);
        long ts = getIntent().getLongExtra(E_TS, 0);

        entry = find(name, ts);
        if (entry == null) {
            toast("记录不存在，可能已被删除");
            finish();
            return;
        }
        buildPlaylist();

        tvTitle.setText(entry.sub == null || entry.sub.isEmpty() ? entry.name : entry.sub);
        tvSub.setText(HistoryStore.fmtTs(entry.ts) + " · " + (entry.visible ? "相册可见" : "已隐藏"));

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnMore).setOnClickListener(v -> more());
        findViewById(R.id.btnRotate).setOnClickListener(v -> toggleOrientation());
        btnPlay.setOnClickListener(v -> togglePlay());
        tvSpeed.setOnClickListener(v -> pickSpeed());

        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int p, boolean fromUser) {
                if (fromUser && durCache > 0) {
                    tvCur.setText(fmt((int) ((long) p * durCache / bar.getMax())));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                dragging = true;
                ui.removeCallbacks(hideRunnable);
                showControls();
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                if (durCache > 0) {
                    video.seekTo((int) ((long) bar.getProgress() * durCache / bar.getMax()));
                }
                scheduleHide();
            }
        });

        touchLayer.setOnTouchListener((v, e) -> onGesture(e));
        zoomer = new Zoomer(video);

        if (!Store.exists(this, entry)) {
            tvHint.setText("文件已经不在手机里了");
            tvHint.setVisibility(View.VISIBLE);
            hideBars();
            askCleanDead();
            return;
        }

        Uri uri = Store.playUri(this, entry);
        if ("photo".equals(kind != null ? kind : entry.kind)) {
            showPhoto(uri);
        } else {
            showVideo(uri);
        }
    }

    /** 文件被删了但记录还留着：说明白原因，顺手问一句要不要清掉这条记录 */
    private void askCleanDead() {
        new AlertDialog.Builder(this)
                .setTitle("文件已经不在手机里了")
                .setMessage("这条下载记录还在，但视频文件已经被删除（在相册里删掉、或被清理软件清掉了）。\n\n"
                        + "要删掉这条失效记录和它的缩略图吗？")
                .setNegativeButton("先留着", null)
                .setPositiveButton("删除记录", (d, w) -> {
                    HistoryStore.remove(this, entry);
                    toast("已清理这条失效记录");
                    finish();
                })
                .show();
    }

    // ---------------- 手势：轻点显隐 / 按住加速 / 上滑锁定 / 双指放大 ----------------
    private boolean onGesture(MotionEvent e) {
        // 双指一落下，这次手势整段归缩放管：加速计时器要撤掉，抬手也不能算点击
        if (zoomer != null && zoomer.onTouchEvent(e)) {
            swallowed = true;
            ui.removeCallbacks(boostRunnable);
            if (longFired) {
                longFired = false;
                if (!boostLocked) endBoost();
            }
            return true;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = e.getY();
                downX = e.getX();
                downAt = System.currentTimeMillis();
                swipingX = false;
                longFired = false;
                swallowed = false;
                if (boostLocked) {
                    // 锁定状态下再按一下 = 取消加速
                    unlockBoost();
                    swallowed = true;
                } else if (prepared) {
                    ui.postDelayed(boostRunnable, 240);
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (Math.abs(e.getX() - downX) > dp(16)
                        && Math.abs(e.getX() - downX) > Math.abs(e.getY() - downY)) {
                    // 横向滑动：取消加速计时，留给“切上一个/下一个”
                    swipingX = true;
                    ui.removeCallbacks(boostRunnable);
                }
                if (longFired && !boostLocked && downY - e.getY() > dp(42)) {
                    boostLocked = true;
                    longFired = false;         // 已锁定：抬手不再结束加速
                    // 提示只闪一下就消失，别老挂在画面上
                    showTip("已锁定 2.0x 加速", false);
                    tvLock.setText("锁定 2.0x");
                    tvLock.setVisibility(View.VISIBLE);
                    buzz();
                } else if (!longFired && Math.abs(e.getY() - downY) > dp(14)) {
                    ui.removeCallbacks(boostRunnable);
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                ui.removeCallbacks(boostRunnable);
                boolean wasBoost = longFired;
                longFired = false;
                if (wasBoost && !boostLocked) endBoost();
                // 横向快滑：左 = 下一个，右 = 上一个
                float dx = e.getX() - downX;
                if (swipingX && !wasBoost && !swallowed && Math.abs(dx) > dp(64)
                        && System.currentTimeMillis() - downAt < 650) {
                    switchVideo(dx < 0 ? 1 : -1);
                    return true;
                }
                if (!wasBoost && !swallowed) toggleControls();
                return true;
        }
        return true;
    }

    private final Runnable boostRunnable = () -> {
        if (!prepared) return;
        longFired = true;
        beginBoost();
    };

    private void beginBoost() {
        boosting = true;
        applySpeed(BOOST);
        showTip("正在 2.0x 加速 · 上滑锁定", false);
        buzz();
    }

    private void endBoost() {
        if (boostLocked) return;
        boosting = false;
        applySpeed(baseSpeed);
        ui.removeCallbacks(hideTip);
        ui.postDelayed(hideTip, 500);
    }

    private void unlockBoost() {
        boostLocked = false;
        boosting = false;
        applySpeed(baseSpeed);
        tvLock.setVisibility(View.GONE);
        showTip("已取消 2.0x 加速", false);
    }

    /** 到点就收起 —— 不管当前是不是锁着加速，锁定的状态由下方控制条的「锁定 2.0x」表示 */
    private final Runnable hideTip = () -> tvSpeedTip.setVisibility(View.GONE);

    /**
     * 顶部浮层提示，显示一小会儿自己消失。
     * sticky 只用来区分停留时间长短（锁定这类状态给 1.8s，普通提示 1.3s），不会常驻。
     */
    private void showTip(String s, boolean sticky) {
        tvSpeedTip.setText(s);
        tvSpeedTip.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideTip);
        ui.postDelayed(hideTip, sticky ? 1800 : 1300);
    }

    private void buzz() {
        try {
            touchLayer.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        } catch (Throwable ignored) { }
    }

    // ---------------- 播放控制 ----------------

    private void showVideo(Uri uri) {
        curUri = uri;
        video.setVisibility(View.VISIBLE);
        if (zoomer != null) zoomer.setTarget(video);
        video.setVideoURI(uri);
        video.setOnPreparedListener(mp -> {
            prepared = true;
            tvHint.setVisibility(View.GONE);
            durCache = mp.getDuration();
            try {
                mp.setLooping(true);   // 默认循环播放
            } catch (Throwable ignored) { }
            lastVw = mp.getVideoWidth();
            lastVh = mp.getVideoHeight();
            if (frameAspect < 0 && lastVw > 0 && lastVh > 0) {
                // 点旋转时视频还没准备好、不知道比例：等它出来再按它的比例锁
                frameAspect = lastVw * 1f / lastVh;
            }
            if (frameAspect > 0) {
                // 已锁定画面框：只换内容，框不动（左滑到横拍视频也不会突然撑满）
                applyFrame();
                applyImmersive();
            } else {
                releaseFrame();
                // 按视频比例自动摆屏：横的视频转横屏，竖的视频转回竖屏
                if (!userRotated) fitOrientation(lastVw, lastVh);
                applyImmersive();
            }
            video.start();
            applySpeed(baseSpeed);
            updatePlayIcon();
            ui.post(tick);
            scheduleHide();
        });
        video.setOnCompletionListener(mp -> {
            // 极少数机型 setLooping 不生效，这里兜底再来一遍
            try {
                video.seekTo(0);
                video.start();
            } catch (Throwable ignored) { }
        });
        video.setOnErrorListener((mp, what, extra) -> {
            // 缓存的相册 uri 打不开（文件被重新下载/清理过）：换文件路径再试一次
            if (!retried) {
                retried = true;
                Uri alt = Store.fallbackUri(this, entry);
                if (alt != null && !alt.equals(curUri)) {
                    try { video.stopPlayback(); } catch (Throwable ignored) { }
                    prepared = false;
                    showVideo(alt);
                    return true;
                }
            }
            tvHint.setText("这个视频无法播放（编码不受系统支持）");
            tvHint.setVisibility(View.VISIBLE);
            hideBars();
            return true;
        });
    }

    // ---------------- 横竖屏 ----------------

    /**
     * 手动转屏：竖着拿也能看横屏视频，或者反过来。
     * 同时把**当前这条视频的画面框锁住** —— 之后左右滑只换内容，画面大小不再跳。
     */
    private void toggleOrientation() {
        userRotated = true;
        if (lastVw > 0 && lastVh > 0) {
            frameAspect = lastVw * 1f / lastVh;     // 按当前这条视频的比例锁
        } else if (frameAspect <= 0) {
            frameAspect = -1f;                      // 还没准备好：等 onPrepared 再锁
        }
        boolean land = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        setRequestedOrientation(land
                ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        toast(land ? "竖屏播放 · 已锁定画面大小" : "横屏播放 · 已锁定画面大小");
        ui.postDelayed(() -> {
            applyImmersive();
            applyFrame();
            showControls();
        }, 260);
    }

    /** 把画面按锁定的框摆好（宽高等比缩放，居中，放不下就按边贴住） */
    private void applyFrame() {
        if (frameAspect <= 0 || video == null) return;
        View parent = (View) video.getParent();
        if (parent == null) return;
        int pw = parent.getWidth(), ph = parent.getHeight();
        if (pw <= 0 || ph <= 0) {
            video.post(this::applyFrame);      // 刚转屏时尺寸还没算好，等一帧
            return;
        }
        int[] box = FitBox.of(pw, ph, frameAspect);
        int w = box[0], h = box[1];
        ViewGroup.LayoutParams lp = video.getLayoutParams();
        if (lp.width == w && lp.height == h) return;
        lp.width = w;
        lp.height = h;
        if (lp instanceof android.widget.FrameLayout.LayoutParams) {
            ((android.widget.FrameLayout.LayoutParams) lp).gravity = Gravity.CENTER;
        }
        video.setLayoutParams(lp);
        // 画面尺寸变了，之前的放大/位移按老尺寸算的，直接归位最稳
        if (zoomer != null) zoomer.reset(false);
    }

    /** 解除锁定：回到「铺满屏幕 + 按视频比例自动转屏」的老行为 */
    private void releaseFrame() {
        if (video == null) return;
        ViewGroup.LayoutParams lp = video.getLayoutParams();
        if (lp.width == ViewGroup.LayoutParams.MATCH_PARENT
                && lp.height == ViewGroup.LayoutParams.MATCH_PARENT) return;
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        video.setLayoutParams(lp);
        if (zoomer != null) zoomer.reset(false);
    }

    /** 菜单里手动恢复自动摆屏 */
    private void unlockFrame() {
        frameAspect = 0;
        userRotated = false;
        releaseFrame();
        fitOrientation(lastVw, lastVh);
        toast("已恢复：画面按视频比例自动摆屏");
    }

    /** 视频宽高比决定屏幕方向：横视频横屏、竖视频竖屏；照片保持当前方向 */
    private void fitOrientation(int vw, int vh) {
        if (vw <= 0 || vh <= 0) return;
        boolean land = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        boolean wantLand = vw > vh;
        if (wantLand == land) return;
        setRequestedOrientation(wantLand
                ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
    }

    /** 横屏全屏沉浸，竖屏把状态栏还回来 */
    private void applyImmersive() {
        boolean land = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        Window w = getWindow();
        View decor = w.getDecorView();
        if (land) {
            if (Build.VERSION.SDK_INT >= 30) {
                w.setDecorFitsSystemWindows(false);
                android.view.WindowInsetsController c = w.getInsetsController();
                if (c != null) {
                    c.hide(android.view.WindowInsets.Type.systemBars());
                    c.setSystemBarsBehavior(android.view.WindowInsetsController
                            .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            }
        } else {
            if (Build.VERSION.SDK_INT >= 30) {
                w.setDecorFitsSystemWindows(true);
                android.view.WindowInsetsController c = w.getInsetsController();
                if (c != null) c.show(android.view.WindowInsets.Type.systemBars());
            } else {
                decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            }
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration cfg) {
        super.onConfigurationChanged(cfg);
        applyImmersive();
        // 转屏后画面尺寸变了，之前的放大/位移按老尺寸算的，直接归位最稳
        if (zoomer != null) zoomer.reset(false);
        applyFrame();          // 锁定了画面框的话，按新的屏幕尺寸重新算一次
        showControls();
    }

    private void togglePlay() {
        if (!prepared) return;
        if (video.isPlaying()) {
            video.pause();
            updatePlayIcon();
            showControls();
            ui.removeCallbacks(hideRunnable);
        } else {
            video.start();
            applySpeed(boosting || boostLocked ? BOOST : baseSpeed);
            updatePlayIcon();
            scheduleHide();
        }
    }

    private void updatePlayIcon() {
        playIconPlaying = video.isPlaying();
        btnPlay.setImageResource(playIconPlaying ? R.drawable.ic_ctrl_pause : R.drawable.ic_ctrl_play);
    }

    private void applySpeed(float s) {
        if (!prepared) return;
        PlaybackParams p = new PlaybackParams();
        p.setSpeed(s);
        p.setPitch(1f);
        // VideoView 没有公开的变速接口，直接对内部的 MediaPlayer 设
        try {
            java.lang.reflect.Field f = VideoView.class.getDeclaredField("mMediaPlayer");
            f.setAccessible(true);
            Object mp = f.get(video);
            if (mp instanceof MediaPlayer) {
                ((MediaPlayer) mp).setPlaybackParams(p);
                return;
            }
        } catch (Throwable ignored) { }
        try {
            java.lang.reflect.Method m = VideoView.class.getMethod("setPlaybackParams", PlaybackParams.class);
            m.invoke(video, p);
        } catch (Throwable ignored) { }
    }

    /** 倍速选择：和长按菜单同一套底部卡片风格，六个倍速排成 3×2 的发光格子 */
    private void pickSpeed() {
        final String[] labels = {"0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x"};
        final float[] values = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
        int cur = 2;
        for (int i = 0; i < values.length; i++) {
            if (Math.abs(values[i] - baseSpeed) < 0.01f) cur = i;
        }

        // 面板打开期间别让控制层自己淡出
        ui.removeCallbacks(hideRunnable);

        final Dialog dlg = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        final View sheet = LayoutInflater.from(this).inflate(R.layout.dialog_speed_menu, null);
        dlg.setContentView(sheet);
        dlg.setCanceledOnTouchOutside(true);

        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.55f;
            w.setAttributes(lp);
        }
        sheet.setPadding(sheet.getPaddingLeft(), sheet.getPaddingTop(),
                sheet.getPaddingRight(), dp(9) + navBarHeight());

        TextView tvCurSpeed = sheet.findViewById(R.id.tvCurSpeed);
        tvCurSpeed.setText("当前 " + labels[cur]);

        final LinearLayout rows = sheet.findViewById(R.id.speedRows);
        final TextView[] chips = new TextView[labels.length];
        LinearLayout row = null;
        for (int i = 0; i < labels.length; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                rows.addView(row);
            }
            TextView c = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1f);
            int m = dp(4);
            lp.setMargins(m, m, m, m);
            c.setLayoutParams(lp);
            c.setGravity(Gravity.CENTER);
            c.setText(labels[i]);
            c.setTextSize(15);
            c.setClickable(true);
            c.setFocusable(true);
            row.addView(c);
            chips[i] = c;

            final int idx = i;
            c.setOnClickListener(v -> {
                baseSpeed = values[idx];
                tvSpeed.setText(labels[idx]);
                if (!boosting && !boostLocked) applySpeed(baseSpeed);
                dismissSpeedSheet(dlg, sheet);
                scheduleHide();
            });
        }
        paintChips(chips, cur);

        sheet.setAlpha(0f);
        sheet.setTranslationY(dp(70));
        sheet.setScaleX(0.95f);
        sheet.setScaleY(0.95f);
        sheet.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(300)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(2.2f))
                .start();

        View close = sheet.findViewById(R.id.speedClose);
        close.setOnClickListener(v -> {
            dismissSpeedSheet(dlg, sheet);
            scheduleHide();
        });

        dlg.show();
    }

    /** 选中那颗高亮成蓝色渐变胶囊，其余是半透明格子 */
    private void paintChips(TextView[] chips, int sel) {
        for (int i = 0; i < chips.length; i++) {
            boolean on = i == sel;
            chips[i].setBackgroundResource(on ? R.drawable.bg_speed_on : R.drawable.bg_speed_off);
            chips[i].setTextColor(on ? 0xFFFFFFFF : 0xFFE6E6EE);
            chips[i].setTypeface(null, on ? android.graphics.Typeface.BOLD
                    : android.graphics.Typeface.NORMAL);
        }
    }

    private void dismissSpeedSheet(final Dialog dlg, final View sheet) {
        sheet.animate().alpha(0f).translationY(dp(70)).scaleX(0.96f).scaleY(0.96f)
                .setDuration(180)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .start();
        sheet.postDelayed(() -> {
            try { dlg.dismiss(); } catch (Throwable ignored) { }
        }, 185);
    }

    private int navBarHeight() {
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (prepared && !dragging) {
                int dur = durCache > 0 ? durCache : video.getDuration();
                if (dur > 0) {
                    durCache = dur;
                    int pos = video.getCurrentPosition();
                    if (pos < 0) pos = 0;
                    if (pos > dur) pos = dur;
                    sb.setProgress((int) ((long) pos * sb.getMax() / dur));
                    tvCur.setText(fmt(pos));
                    tvDur.setText(fmt(dur));
                }
                int st = video.isPlaying() ? 1 : 0;
                if (st == 1 && !playIconPlaying) updatePlayIcon();
                if (st == 0 && playIconPlaying) updatePlayIcon();
            }
            ui.postDelayed(this, 250);
        }
    };

    // ---------------- 控制条显隐 ----------------

    private final Runnable hideRunnable = () -> {
        if (prepared && video.isPlaying() && !dragging && !boosting) fadeBars(false);
    };

    private void scheduleHide() {
        ui.removeCallbacks(hideRunnable);
        if (prepared && video.isPlaying()) ui.postDelayed(hideRunnable, AUTO_HIDE_MS);
    }

    private void toggleControls() {
        if (controlsShown) fadeBars(false);
        else {
            showControls();
            scheduleHide();
        }
    }

    private void showControls() {
        fadeBars(true);
    }

    private void hideBars() {
        topBar.setVisibility(View.GONE);
        ctrl.setVisibility(View.GONE);
        controlsShown = false;
    }

    private void fadeBars(boolean show) {
        controlsShown = show;
        fadeView(topBar, show);
        fadeView(ctrl, show);
        if (!show) {
            ui.removeCallbacks(hideRunnable);
            ui.removeCallbacks(hideTip);
            tvSpeedTip.setVisibility(View.GONE);
        }
    }

    private void fadeView(final View v, boolean show) {
        v.animate().cancel();
        if (show) {
            v.setVisibility(View.VISIBLE);
            v.animate().alpha(1f).setDuration(180).start();
        } else {
            v.animate().alpha(0f).setDuration(180)
                    .withEndAction(() -> {
                        if (v.getAlpha() < 0.05f) v.setVisibility(View.GONE);
                    })
                    .start();
        }
    }

    // ---------------- 图片 ----------------

    private void showPhoto(final Uri uri) {
        photo.setVisibility(View.VISIBLE);
        if (zoomer != null) zoomer.setTarget(photo);
        hideBars();
        tvHint.setText("加载中…");
        tvHint.setVisibility(View.VISIBLE);
        new Thread(() -> {
            final Bitmap b = decode(uri);
            runOnUiThread(() -> {
                if (b == null) {
                    tvHint.setText("图片打不开，文件可能已损坏");
                    return;
                }
                tvHint.setVisibility(View.GONE);
                photo.setImageBitmap(b);
            });
        }, "decode").start();
    }

    private Bitmap decode(Uri uri) {
        ContentResolver cr = getContentResolver();
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            InputStream in = cr.openInputStream(uri);
            if (in == null) return null;
            BitmapFactory.decodeStream(in, null, bounds);
            in.close();
            if (bounds.outWidth <= 0) return null;
            int max = 1800;
            int s = 1;
            while (bounds.outWidth / (s * 2) >= max && s < 8) s *= 2;

            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = s;
            in = cr.openInputStream(uri);
            if (in == null) return null;
            Bitmap b = BitmapFactory.decodeStream(in, null, o);
            in.close();
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------- 菜单 / 隐藏 / 删除 ----------------

    /** 播放列表：按主页排列顺序，左右滑就在这里面切 */
    private void buildPlaylist() {
        List<HistoryStore.Entry> all = HistoryStore.list(this);
        // 图片不进播放列表（用户要求：左滑右滑只切视频，别滑到图片上）
        List<HistoryStore.Entry> videos = new ArrayList<>();
        for (HistoryStore.Entry e : all) {
            if (!"photo".equals(e.kind)) videos.add(e);
        }
        // 「竖横分区」排列时主页会传一份同方向的清单过来，只在这几条里循环
        String ids = getIntent().getStringExtra(E_PLAYLIST);
        if (ids != null && !ids.isEmpty()) {
            playlist = new ArrayList<>();
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
                for (HistoryStore.Entry e : videos) {
                    if (name.equals(e.name) && e.ts == ts) {
                        playlist.add(e);
                        break;
                    }
                }
            }
        }
        if (playlist == null || playlist.isEmpty()) playlist = videos;

        playIndex = -1;
        for (int i = 0; i < playlist.size(); i++) {
            HistoryStore.Entry e = playlist.get(i);
            if (e.name != null && e.name.equals(entry.name) && e.ts == entry.ts) {
                playIndex = i;
                break;
            }
        }
    }

    /** 左滑下一个 / 右滑上一个，跳过文件已经不在的条目 */
    private void switchVideo(int dir) {
        if (playlist == null || playlist.size() < 2) {
            toast("列表里只有这一个");
            return;
        }
        int n = playlist.size();
        for (int step = 1; step <= n; step++) {
            int idx = ((playIndex + dir * step) % n + n) % n;
            HistoryStore.Entry e = playlist.get(idx);
            if (e == null || !Store.exists(this, e)) continue;
            playIndex = idx;
            loadEntry(e, dir);
            return;
        }
        toast(dir > 0 ? "后面没有能播的了" : "前面没有能播的了");
    }

    /** 切换到另一条：换标题、换地址，横竖屏按新视频重新判断 */
    private void loadEntry(HistoryStore.Entry e, int dir) {
        entry = e;
        prepared = false;
        dragging = false;
        retried = false;
        // 换了视频：画面缩放归位，不然新视频一上来还是放大着的
        if (zoomer != null) zoomer.reset(false);
        // 注意：这里**不再**把 userRotated 清零。
        // 用户手动转过屏（并锁定了画面框）之后，左右滑只换内容、不换框 ——
        // 以前清零会让下一条视频按自己的比例重新摆屏，横拍视频一上来就撑满屏幕。
        ui.removeCallbacks(hideRunnable);
        try { video.stopPlayback(); } catch (Throwable ignored) { }
        tvTitle.setText(e.sub == null || e.sub.isEmpty() ? e.name : e.sub);
        tvSub.setText(HistoryStore.fmtTs(e.ts) + " · " + (e.visible ? "相册可见" : "已隐藏"));
        // 切视频时不弹「下一个 · 某某」的提示：标题栏已经显示片名了，
        // 画面正中再冒一个框反而挡画面、影响观看

        Uri uri = Store.playUri(this, e);
        if ("photo".equals(e.kind)) {
            video.setVisibility(View.GONE);
            showPhoto(uri);
        } else {
            photo.setVisibility(View.GONE);
            showVideo(uri);
        }
        showControls();
        scheduleHide();
    }

    private HistoryStore.Entry find(String name, long ts) {
        for (HistoryStore.Entry e : HistoryStore.list(this)) {
            if (name != null && name.equals(e.name) && (ts == 0 || ts == e.ts)) return e;
        }
        return null;
    }

    private void more() {
        String toggle = entry.visible ? "从相册隐藏这一条" : "放回相册（相册里能看到）";
        String zoom = zoomer != null && zoomer.isZoomed() ? "画面还原 100%" : "画面放大 2 倍";
        String[] items = {"分享到微信", "分享到其他应用", "打开原帖", toggle, zoom,
                "删除这条记录", "删除这条记录及文件",
                frameAspect != 0 ? "恢复按视频比例自动摆屏" : "锁定当前画面大小"};
        new AlertDialog.Builder(this)
                .setItems(items, (d, w) -> {
                    if (w == 0) {
                        share("com.tencent.mm");
                    } else if (w == 1) {
                        share(null);
                    } else if (w == 2) {
                        if (entry.pageUrl != null && !entry.pageUrl.isEmpty()) {
                            try {
                                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(entry.pageUrl)));
                            } catch (Throwable ignored) { }
                        }
                    } else if (w == 3) {
                        toggleVisible();
                    } else if (w == 4) {
                        if (zoomer.isZoomed()) zoomer.reset(true);
                        else zoomer.zoomTo(2f, true);
                    } else if (w == 5) {
                        removeRecordOnly();
                    } else if (w == 6) {
                        confirmDelete();
                    } else {
                        if (frameAspect != 0) unlockFrame();
                        else toggleOrientation();
                    }
                })
                .show();
    }

    /** pkg = "com.tencent.mm" 直接进微信发送界面；null 走系统分享面板 */
    private void share(final String pkg) {
        if (!Store.exists(this, entry)) {
            toast("文件不存在，可能已被删除");
            return;
        }
        Uri uri;
        try {
            uri = Store.uriFor(this, entry);
        } catch (Throwable t) {
            toast("读取文件失败");
            return;
        }
        String mime = Store.mime(entry.name, entry.kind);
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(mime);
        i.putExtra(Intent.EXTRA_STREAM, uri);
        if (entry.pageUrl != null && !entry.pageUrl.isEmpty()) {
            i.putExtra(Intent.EXTRA_TEXT, entry.pageUrl);
        }
        i.setClipData(android.content.ClipData.newRawUri("DownX", uri));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        if (pkg != null) {
            // 直接进微信「发送给朋友」，不再出现"微信状态/收藏"的中间选择
            try {
                i.setClassName("com.tencent.mm", "com.tencent.mm.ui.tools.ShareImgUI");
                startActivity(i);
                return;
            } catch (Throwable ignored) { }
            try {
                i.setPackage(pkg);
                startActivity(i);
                return;
            } catch (Throwable ignored) {
                toast("没能直接唤起微信，改用系统分享");
            }
        }
        Intent c = new Intent(Intent.ACTION_SEND);
        c.setType(mime);
        c.putExtra(Intent.EXTRA_STREAM, uri);
        if (entry.pageUrl != null && !entry.pageUrl.isEmpty()) c.putExtra(Intent.EXTRA_TEXT, entry.pageUrl);
        c.setClipData(android.content.ClipData.newRawUri("DownX", uri));
        c.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(c, "分享到"));
        } catch (Throwable t) {
            toast("没有可用的分享应用");
        }
    }

    private void toggleVisible() {
        final boolean to = !entry.visible;
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setMessage(to ? "正在放回相册…" : "正在从相册移出…")
                .setCancelable(false)
                .create();
        dlg.show();
        new Thread(() -> {
            final boolean ok = Store.moveOne(this, entry, to);
            runOnUiThread(() -> {
                dlg.dismiss();
                if (ok) {
                    entry.visible = to;
                    entry.uri = to ? entry.uri : "";
                    toast(to ? "已放回相册" : "已隐藏，相册里不再显示");
                    tvSub.setText(HistoryStore.fmtTs(entry.ts) + " · "
                            + (entry.visible ? "相册可见" : "已隐藏"));
                } else {
                    toast("操作失败，系统不允许改动这个文件");
                }
            });
        }, "moveOne").start();
    }

    /** 只删记录，文件留在手机里 */
    private void removeRecordOnly() {
        String tip = entry.visible
                ? "只从 DownX 的下载列表里移除这条记录。\n视频文件仍留在相册（Movies/DownX）。"
                : "只从 DownX 的下载列表里移除这条记录。\n视频文件仍留在 App 私有目录，卸载 DownX 时会一起被删掉。";
        new AlertDialog.Builder(this)
                .setTitle("删除这条记录")
                .setMessage(tip)
                .setNegativeButton("取消", null)
                .setPositiveButton("只删记录", (d, w) -> {
                    HistoryStore.remove(this, entry);
                    toast("已从列表移除，文件还在手机里");
                    finish();
                })
                .show();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("删除这条记录及文件")
                .setMessage("视频文件和下载记录都会被删除，释放占用的存储空间，删除后无法恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("彻底删除", (d, w) -> {
                    try {
                        if (entry.uri != null && entry.uri.startsWith("content://")) {
                            getContentResolver().delete(Uri.parse(entry.uri), null, null);
                        } else {
                            java.io.File f = entry.visible
                                    ? Store.publicFile(entry.kind, entry.name)
                                    : Store.privateFile(this, entry.kind, entry.name);
                            if (f.exists()) f.delete();
                        }
                    } catch (Throwable ignored) { }
                    HistoryStore.remove(this, entry);
                    toast("已删除");
                    finish();
                })
                .show();
    }

    // ---------------- 生命周期 ----------------

    @Override
    protected void onPause() {
        super.onPause();
        if (video != null && video.isPlaying()) video.pause();
        boosting = false;
        boostLocked = false;
        updatePlayIcon();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        try { video.stopPlayback(); } catch (Throwable ignored) { }
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String fmt(int ms) {
        if (ms < 0) ms = 0;
        int s = ms / 1000;
        int h = s / 3600;
        int m = (s % 3600) / 60;
        int sec = s % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, sec);
        return String.format("%02d:%02d", m, sec);
    }

    private void toast(String s) {
        Toast t = Toast.makeText(this, s, Toast.LENGTH_SHORT);
        t.setGravity(Gravity.BOTTOM, 0, 120);
        t.show();
    }
}
