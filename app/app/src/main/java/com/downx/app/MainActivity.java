package com.downx.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.WebView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_LOCK = 21;
    private static final int REQ_STORAGE = 22;
    private static final int REQ_NOTIFY = 23;

    private EditText input;
    private ImageView btnPaste, btnParse, btnSettings, btnLayout;
    private TextView status, tvAuthor, tvMeta, tvText, btnOpen, btnClearHistory, tvCount, btnDownload, btnUnlock;
    /** 预览框右下角的方向角标（竖屏 1080×1920） */
    private TextView tvOrient;
    private ImageView avatar, ivMedia;
    private LinearLayout skeleton, preview, chips, historyBox, historyHeader, lockCover, dlPanel;
    private LinearLayout selectBar;
    private TextView tvSelCount;
    /** 多选模式：长按菜单点「多选」进入，点缩略图勾选，底部批量操作 */
    private boolean selectMode;
    private final java.util.List<HistoryStore.Entry> picked = new java.util.ArrayList<>();
    private ProgressBar dlBar;
    private TextView tvDlPct, tvDlName;
    /** 下载浮层上的「暂停/继续」和「取消（连临时文件一起删）」 */
    private TextView tvDlPause, tvDlCancel;
    private String dlLabelName;
    private View contentRoot;

    private TweetFetcher.Result result;
    private TweetFetcher.Media selected;
    /**
     * 图片多选：多图帖（图1/图2/图3…）进来就是多选态，点哪张勾哪张、
     * 预览框跟着显示那一张，另有「全选」。下载时一次全排队。
     */
    private boolean multiSel;
    private final java.util.Set<Integer> multi = new java.util.LinkedHashSet<>();
    /** 主页缩略图分组：代表图的 key -> 同一帖子的全部图片（按下载顺序） */
    private final java.util.Map<String, java.util.List<HistoryStore.Entry>> photoGroups
            = new java.util.HashMap<>();
    /**
     * 多图批量下载：图片最多 3 张同时在飞（实测 2.7~4.8x），视频仍独占；
     * 进度按 (已完成张数 + 当前张百分比) / 总张数 合成一条总进度。
     */
    private final List<TweetFetcher.Media> batchQueue = new ArrayList<>();
    private int batchTotal, batchDone;
    /** 已经发起、还没收到完成/失败广播的张数（并行时必须靠它判断整批结束） */
    private int batchInFlight;
    /** 图片同时下几张：DlService 里也有一道 3 的上限，两处要一致 */
    private static final int MAX_BATCH_PARALLEL = 3;
    /** 这一批里因为「之前下过」被跳过的张数：收尾时专门报「重复下载」 */
    private int batchDup;
    /** 这一批里失败的张数（中间不逐条弹提示，收尾一起报） */
    private int batchFail;
    /** 上一次 doDownload 没下起来，是不是因为重复（去重） */
    private boolean lastSkipDup;
    /** 「服务忙」连续重排次数：防止队列卡在一个永远起不来的状态里 */
    private int batchBusyRetry;
    private boolean batchOn;
    /**
     * 批次看门狗：并行时靠「在飞数」判断整批是否结束，万一某张在异步刷新阶段
     * 被判成重复（不会发完成广播），在飞计数就永远不回零，整批会静默卡住。
     * 60 秒没有任何进展就强行收口，宁可少报一张，也不让用户盯着一条不动的进度条。
     */
    private final Runnable batchWatchdog = new Runnable() {
        @Override
        public void run() {
            if (!batchOn) return;
            if (batchInFlight > 0) batchInFlight = 0;
            if (!batchQueue.isEmpty()) {
                startBatchNext();
            } else {
                finishBatch(true);
            }
        }
    };
    private WebView web;
    /** 设备自带（移动版）UA，快手的 WebView 解析必须用它 */
    private String deviceUa = "";
    private IgScraper igScraper;
    private ShortVideoScraper shortScraper;

    private boolean lockShowing;
    private boolean receiverOn;
    /** 抖音/快手/IG/X 直链会过期（CDN 带签名），失败后自动重新解析：已重试次数 */
    private int healCount;
    /** 正在为「重新解析后自动接着下」做准备 */
    private boolean healPending;
    /** 本次解析结果是否已在下载前刷新过地址（IG 惰性取址，只做一次） */
    private boolean igRefreshed;
    /** 用户最近一次粘贴的原始口令，重新解析要用它 */
    private String shareLink;
    private String shareText;

    private final BroadcastReceiver dlReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String msg = intent.getStringExtra("msg");
            String action = intent.getAction();
            if (DlService.A_PROG.equals(action)) {
                int p = 0;
                try {
                    p = Integer.parseInt(msg == null ? "0" : msg.trim());
                } catch (Exception ignored) { }
                showProgress(p);
                return;
            }
            if (DlService.A_PAUSE.equals(action)) {
                setPauseUi("1".equals(msg));
                return;
            }
            if (DlService.A_CANCEL.equals(action)) {
                if (batchOn) {                 // 一批中途取消：整批停下，别再往下排
                    batchOn = false;
                    batchQueue.clear();
                    batchTotal = 0;
                    batchDone = 0;
                    batchDup = 0;
                    batchFail = 0;
                    batchInFlight = 0;
                    batchBusyRetry = 0;
                }
                hideProgress(false);
                toast(msg == null || msg.isEmpty() ? "已取消下载" : msg);
                return;
            }
            // ---- 多图一批：最多 3 张同时在飞，完成一张补一张，进度始终是一条 ----
            if (batchOn && DlService.A_DONE.equals(action)) {
                batchDone++;
                if (batchInFlight > 0) batchInFlight--;
                renderHistory();
                if (!batchQueue.isEmpty() || batchInFlight > 0) {
                    armWatchdog();
                    dlPanel.postDelayed(MainActivity.this::startBatchNext, 120);
                    return;
                }
                finishBatch(true);
                return;
            }
            if (batchOn) {                     // 这一张失败：跳过它，接着下后面的
                // 服务刚切过来的瞬间可能还 busy，这不是失败，重排一次就好
                if (msg != null && msg.contains("还有一条正在下载")) {
                    if (++batchBusyRetry > 6) {
                        batchBusyRetry = 0;
                        batchDone++;
                        batchFail++;
                        if (batchInFlight > 0) batchInFlight--;
                    } else {
                        dlPanel.postDelayed(MainActivity.this::startBatchNext, 400);
                        return;
                    }
                } else {
                    batchBusyRetry = 0;
                    batchDone++;
                    batchFail++;
                    if (batchInFlight > 0) batchInFlight--;
                }
                renderHistory();               // 中间失败不再一条条弹 toast，收尾统一报
                if (!batchQueue.isEmpty() || batchInFlight > 0) {
                    armWatchdog();
                    dlPanel.postDelayed(MainActivity.this::startBatchNext, 120);
                    return;
                }
                finishBatch(false);
                return;
            }
            if (DlService.A_DONE.equals(action)) {
                hideProgress(true);
                toast("已保存");
                clearResult();
            } else {
                hideProgress(false);
                // 抖音/快手直链过期（403/404/410）：自动重新解析一次，别让用户自己再点
                if (!healShortIfExpired(msg) && msg != null && !msg.isEmpty()) toast(msg);
            }
            renderHistory();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BiliSession.init(this);
        IgSession.init(this);
        setContentView(R.layout.activity_main);
        bind();
        wire();
        // 清掉历史上重复的记录（同一帖反复下载会留下一模一样的几条）
        try { HistoryStore.dedupe(this); } catch (Throwable ignored) { }
        // 不做断点续传了：老版本留下的半成品临时文件一次清掉，别白占空间
        cleanLeftoverParts();
        // 这里不再先渲染一次：onResume 紧跟其后一定会渲染，
        // 连续建两遍列表会让整屏缩略图黑闪两下。
        handleIntent(getIntent());
        askNotify();
        upgradeStorageInBackground();
    }

    /**
     * 把老版本藏在 Android/data 里的文件搬到 Android/media（卸载不删），
     * 顺便把"文件还在、历史是空的"（卸载重装）补回列表。
     * 全部在后台线程，搬完再刷一次列表。
     */
    private void upgradeStorageInBackground() {
        new Thread(() -> {
            int[] r;
            try {
                r = Store.upgradeStorage(getApplicationContext());
            } catch (Throwable t) {
                return;
            }
            if (r[0] + r[1] <= 0) return;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                try { renderHistoryIfChanged(); } catch (Throwable ignored) { }
                if (r[0] > 0) {
                    toast("已把 " + r[0] + " 个文件搬到卸载也不删的位置");
                }
            });
        }, "storage-upgrade").start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    public void onBackPressed() {
        if (selectMode) {
            exitSelectMode();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 只有「冷启动」才要指纹：sessionUnlocked 是内存态，进程被系统杀掉后归零。
        // 从后台切回来、静默多久都不会再弹，免得每次都要按一下。
        if (Prefs.needsLock(this) && !Prefs.sessionUnlocked) {
            showLock();
        }
        renderHistoryIfChanged();
        detectClipboard(true);
        // 下载还在跑（可能刚才在别的页面暂停过），把浮层和暂停状态恢复出来
        if (DlService.isRunning() && dlPanel != null
                && dlPanel.getVisibility() != View.VISIBLE) {
            showProgress(DlService.progress());
            setPauseUi(DlService.isPaused());
        }
    }

    /** 这套 App 不再做断点续传：没下完的半成品只会白占空间，启动时一次清干净 */
    private void cleanLeftoverParts() {
        if (DlService.isRunning()) return;         // 正在下的不能动
        try { Parts.clearAll(this); } catch (Throwable ignored) { }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!receiverOn) {
            IntentFilter f = new IntentFilter();
            f.addAction(DlService.A_DONE);
            f.addAction(DlService.A_FAIL);
            f.addAction(DlService.A_PROG);
            f.addAction(DlService.A_CANCEL);
            f.addAction(DlService.A_PAUSE);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(dlReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(dlReceiver, f);
            }
            receiverOn = true;
        }
    }

    @Override
    protected void onDestroy() {
        if (receiverOn) {
            try { unregisterReceiver(dlReceiver); } catch (Throwable ignored) { }
            receiverOn = false;
        }
        super.onDestroy();
    }

    private void bind() {
        contentRoot = findViewById(R.id.contentRoot);
        lockCover = findViewById(R.id.lockCover);
        btnUnlock = findViewById(R.id.btnUnlock);
        input = findViewById(R.id.input);
        status = findViewById(R.id.status);
        btnPaste = findViewById(R.id.btnPaste);
        btnParse = findViewById(R.id.btnParse);
        btnSettings = findViewById(R.id.btnSettings);
        btnLayout = findViewById(R.id.btnLayout);
        skeleton = findViewById(R.id.skeleton);
        preview = findViewById(R.id.preview);
        avatar = findViewById(R.id.avatar);
        tvAuthor = findViewById(R.id.tvAuthor);
        tvMeta = findViewById(R.id.tvMeta);
        tvOrient = findViewById(R.id.tvOrient);
        tvText = findViewById(R.id.tvText);
        ivMedia = findViewById(R.id.ivMedia);
        chips = findViewById(R.id.chips);
        btnDownload = findViewById(R.id.btnDownload);
        btnOpen = findViewById(R.id.btnOpen);
        historyBox = findViewById(R.id.historyBox);
        historyHeader = findViewById(R.id.historyHeader);
        btnClearHistory = findViewById(R.id.btnClearHistory);
        tvCount = findViewById(R.id.tvCount);
        web = findViewById(R.id.web);
        // 先把设备自带的 UA 存下来：IgScraper 的构造会把 WebView 的 UA 改成桌面版，
        // 之后快手再读就是桌面 UA 了（桌面 UA 下快手只给「浏览器版本过低」的空壳页）
        deviceUa = web.getSettings().getUserAgentString();
        igScraper = new IgScraper(web);
        shortScraper = new ShortVideoScraper(web, deviceUa);
        dlPanel = findViewById(R.id.dlPanel);
        dlBar = findViewById(R.id.dlBar);
        tvDlPct = findViewById(R.id.tvDlPct);
        tvDlName = findViewById(R.id.tvDlName);
        tvDlPause = findViewById(R.id.tvDlPause);
        tvDlCancel = findViewById(R.id.tvDlCancel);
        selectBar = findViewById(R.id.selectBar);
        tvSelCount = findViewById(R.id.tvSelCount);

        // 宽度一变（横竖屏切换、分屏）就按新宽度重排，避免从横屏播放器
        // 退回来时列表还是按横屏宽度算的“大图标”
        historyBox.getViewTreeObserver().addOnGlobalLayoutListener(
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        int w = historyBox.getWidth();
                        if (w > 0 && w != lastLaidWidth) {
                            // 布局回调里不能直接动视图树，post 到下一帧再重排
                            historyBox.post(() -> renderHistoryIfChanged());
                        }
                    }
                });
    }

    /** 程序自己往输入框塞链接时置 true，防止自动解析逻辑再触发一遍造成重复解析 */
    private boolean skipAutoParse;

    private void wire() {
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                startParse(input.getText().toString());
                return true;
            }
            return false;
        });

        // 粘贴整段口令（一大段文字、里面确实有链接）→ 自动把框里换成干净链接
        // 并直接开始解析，不用再按一次「解析」。快手口令带一堆中文也能认。
        input.addTextChangedListener(new android.text.TextWatcher() {
            private int before;
            private boolean self;

            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                if (!self) before = s.length();
            }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e) {
                if (self || skipAutoParse) {
                    skipAutoParse = false;
                    return;
                }
                int added = e.length() - before;
                if (added < 12) return;                       // 逐字输入不打扰
                String full = e.toString();
                String link = TweetFetcher.extractLink(full);
                if (link == null) return;
                if (!link.equals(full)) {                     // 框里换成干净的链接
                    self = true;
                    e.replace(0, e.length(), link);
                    self = false;
                }
                hideIme();
                // 传整段文字：口令里的文案以后还能当下载标题用
                startParse(full);
            }
        });

        btnPaste.setOnClickListener(v -> {
            String s = clipText();
            if (s == null || s.isEmpty()) {
                toast("剪贴板里没有内容");
                return;
            }
            String link = TweetFetcher.extractLink(s);
            skipAutoParse = true;                 // setText 会触发自动解析，别再来一遍
            input.setText(link == null ? s : link);
            // 粘贴完立刻收起输入法，别让它一直压在界面下面
            hideIme();
            // 传整段口令：里面的文案以后当下载标题用，只传链接就把文案丢了
            if (link != null) startParse(s);
        });
        btnParse.setOnClickListener(v -> startParse(input.getText().toString()));
        btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        btnLayout.setOnClickListener(v -> LayoutPicker.show(this, mode -> {
            renderHistory();
            toast("已切换到「" + Prefs.layoutName(mode) + "」");
        }));
        btnUnlock.setOnClickListener(v -> showLock());

        // 下载中反悔：暂停（数据留着，继续接着下）/ 取消（连下到一半的文件一起删）
        tvDlPause.setOnClickListener(v -> {
            boolean nowPaused = !DlService.isPaused();
            DlService.toggle(this);
            setPauseUi(nowPaused);
        });
        tvDlCancel.setOnClickListener(v -> askCancelDownload());
        findViewById(R.id.btnSelExit).setOnClickListener(v -> exitSelectMode());
        findViewById(R.id.btnSelShare).setOnClickListener(v -> sharePicked());
        findViewById(R.id.btnSelAll).setOnClickListener(v -> selectAll());
        findViewById(R.id.btnSelDelRecord).setOnClickListener(v -> deletePicked(false));
        findViewById(R.id.btnSelDelFile).setOnClickListener(v -> deletePicked(true));

        // 长按标题看支持的平台
        findViewById(R.id.tvTitle).setOnLongClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("支持的平台")
                    .setMessage("X (Twitter)\nInstagram（Reels 建议登录）\nTikTok（无水印）\n"
                            + "抖音（无水印）\n快手（无水印）\n小红书（无水印）\n"
                            + "B站（最高 720P）\nYouTube（需代理）\n"
                            + "Facebook\nPinterest")
                    .setPositiveButton("知道了", null)
                    .show();
            return true;
        });

        btnDownload.setOnClickListener(v -> beginDownload());
        btnOpen.setOnClickListener(v -> openPage());
        btnClearHistory.setOnClickListener(v -> {
            HistoryStore.clear(this);
            renderHistory();
            toast("已清空列表（相册里的文件不受影响）");
        });
    }

    // ---------------- 应用锁 ----------------

    private void showLock() {
        if (!Prefs.needsLock(this)) return;
        lockCover.setVisibility(View.VISIBLE);
        contentRoot.setVisibility(View.INVISIBLE);
        if (lockShowing) return;
        lockShowing = true;
        Intent i = new Intent(this, LockActivity.class);
        i.putExtra(LockActivity.EXTRA_MODE, LockActivity.MODE_UNLOCK);
        startActivityForResult(i, REQ_LOCK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_LOCK) {
            lockShowing = false;
            if (res == RESULT_OK) {
                Prefs.sessionUnlocked = true;
                lockCover.setVisibility(View.GONE);
                contentRoot.setVisibility(View.VISIBLE);
            } else {
                finish();
            }
        }
    }

    // ---------------- 权限 ----------------

    private void askNotify() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }
    }

    // ---------------- 链接入口 ----------------

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        String url = null;
        if (Intent.ACTION_SEND.equals(action)) {
            // 分享进来的是整段口令：链接进输入框，原文留着取标题。
            // 快手网页标题是空的，标题只能从这段文案里抠（见 startShortVideo）
            String full = intent.getStringExtra(Intent.EXTRA_TEXT);
            url = TweetFetcher.extractLink(full);
            if (full != null) sharedText = full;
        } else if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            url = intent.getData().toString();
        }
        if (url != null && !url.isEmpty()) {
            skipAutoParse = true;             // 下面 startParse 过了，别让监听器再来一遍
            input.setText(url);
            input.clearFocus();
            hideIme();
            startParse(url);
        }
    }

    /** 分享/粘贴进来的原始文字（含标题文案），给快手抖音取标题用 */
    private String sharedText;

    /** 分享链接进来时不要弹输入法，别挡着界面 */
    private void hideIme() {
        try {
            input.clearFocus();
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                if (getCurrentFocus() != null) {
                    imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
                }
                imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
            }
            // 有些输入法（搜狗/小艺）会延迟几百毫秒再弹回来，补一枪
            input.postDelayed(() -> {
                try {
                    InputMethodManager im2 = (InputMethodManager)
                            getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (im2 != null) {
                        im2.hideSoftInputFromWindow(input.getWindowToken(), 0);
                    }
                    input.clearFocus();
                } catch (Throwable ignored) { }
            }, 260);
        } catch (Throwable ignored) { }
    }

    private void detectClipboard(boolean quiet) {
        if (input.getText().length() > 0 || result != null) return;
        String s = clipText();
        String link = s == null ? null : TweetFetcher.extractLink(s);
        if (link != null) {
            skipAutoParse = true;             // 只填框不解析，等用户按「解析」
            input.setText(link);
            if (!quiet) hint("已识别剪贴板链接");
        }
    }

    private String clipText() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return null;
            ClipData cd = cm.getPrimaryClip();
            if (cd == null || cd.getItemCount() == 0) return null;
            return String.valueOf(cd.getItemAt(0).coerceToText(this));
        } catch (Throwable t) {
            return null;
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void hint(String s) {
        status.setText(s);
        status.setVisibility(View.VISIBLE);
    }

    // ---------------- 解析 ----------------

    private void startParse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            toast("先粘贴一条链接");
            return;
        }
        result = null;
        selected = null;
        igRefreshed = false;      // 新的一条解析结果：下载前刷新重新算
        hideIme();
        hint("");
        status.setVisibility(View.GONE);
        skeleton.setVisibility(View.VISIBLE);
        preview.setVisibility(View.GONE);

        String link = TweetFetcher.extractLink(raw.trim());
        // 记下原始链接：X / IG 直链失效时，自愈要用它重新解析
        this.shareLink = link;
        this.shareText = raw.trim();
        if (link != null && (link.contains("instagram.com") || link.contains("instagr.am"))) {
            startIg(link);
            return;
        }
        if (link != null && isShortVideoLink(link)) {
            // 分享进来的整段口令优先（比输入框里的纯链接多一份标题文案）
            String share = raw.trim();
            if (sharedText != null && sharedText.contains(link)) share = sharedText;
            startShortVideo(link, share);
            return;
        }
        if (link != null && isXhsLink(link)) {
            startXhs(link);
            return;
        }
        TweetFetcher.fetch(raw.trim(), new TweetFetcher.Listener() {
            @Override
            public void onDone(TweetFetcher.Result r) {
                render(r);
            }

            @Override
            public void onFail(String msg) {
                skeleton.setVisibility(View.GONE);
                hint(TweetFetcher.beautify(msg));
            }
        });
    }

    /** 抖音 / 快手 */
    private static boolean isShortVideoLink(String link) {
        String l = link.toLowerCase();
        return l.contains("douyin.com") || l.contains("iesdouyin.com")
                || l.contains("kuaishou.com") || l.contains("gifshow.com")
                || l.contains("chenzhongtech.com");
    }

    /** 小红书 */
    private static boolean isXhsLink(String link) {
        String l = link.toLowerCase();
        return l.contains("xiaohongshu.com") || l.contains("xhslink.com")
                || l.contains("xhslink.cn");
    }

    /**
     * 小红书：先用 HTTP 读笔记页里的 __INITIAL_STATE__（快、能直接拿原图），
     * 拿不到再改用内置浏览器打开笔记页，从页面里的 video / img 兜底取地址。
     */
    private void startXhs(final String link) {
        new Thread(() -> {
            String resolved = link;
            try {
                String r = Net.resolve(link, 12000);
                if (r != null && !r.isEmpty()) resolved = r;
            } catch (Throwable ignored) { }
            final String pageUrl = XhsFetcher.pageUrlOf(link, resolved);
            try {
                TweetFetcher.Result r = XhsFetcher.fetchSync(link);
                runOnUiThread(() -> render(r));
                return;
            } catch (Throwable t) {
                final String m = t.getMessage() == null ? "小红书解析失败" : t.getMessage();
                runOnUiThread(() -> {
                    if (pageUrl == null || shortScraper == null) {
                        skeleton.setVisibility(View.GONE);
                        hint(m);
                        return;
                    }
                    hint("网页方式没拿到，改用内置浏览器打开小红书…");
                    shortScraper.scrape("xiaohongshu", pageUrl, new ShortVideoScraper.Callback() {
                        @Override
                        public void onResult(TweetFetcher.Result r) {
                            render(r);
                        }

                        @Override
                        public void onFail(String msg) {
                            skeleton.setVisibility(View.GONE);
                            hint(msg);
                        }
                    });
                });
            }
        }, "xhs").start();
    }

    /**
     * 抖音/快手：先把短链还原成作品页（v.douyin.com → iesdouyin 分享页），
     * 再让内置浏览器打开它、把真正在播的 <video> 地址读回来。
     *
     * @param share 用户粘贴的原始口令（带标题文案）。快手 H5 的 document.title
     *              是空的，标题只能从这段口令里取——不然每条视频都叫
     *              「来自快手的视频」，文件名全撞在一起，还会被判成重复下载。
     */
    private void startShortVideo(final String link, final String share) {
        final boolean douyin = ShortVideo.isDouyin(link);
        this.shareLink = link;
        this.shareText = share;
        if (!healPending) healCount = 0;      // 用户重新粘贴算新的一次，重试次数归零
        new Thread(() -> {
            // 口令里已经带完整作品链接时，根本不用再跳转，省掉最容易卡的那一步
            String raw0 = link.trim();
            String id = ShortVideo.idOf(douyin, raw0);
            if (id == null) {
                String cached = Prefs.svId(this, raw0);
                if (!cached.isEmpty()) id = cached;
            }
            if (id == null) {
                // 只有短链才需要还原；给 5 秒上限，别让用户对着「正在解析」干等
                String resolved = ShortVideo.resolve(raw0, 5000);
                id = ShortVideo.idOf(douyin, resolved);
                if (id == null) id = ShortVideo.idOf(douyin, link);
                if (id != null) Prefs.setSvId(this, raw0, id);
            }
            final String svId = id;
            final String pageUrl = svId == null ? null
                    : ShortVideo.pagesOf(douyin, svId).get(0);
            runOnUiThread(() -> {
                if (pageUrl == null) {
                    skeleton.setVisibility(View.GONE);
                    hint("没有识别到作品 ID，请粘贴从 App 里「分享 → 复制链接」的完整内容");
                    return;
                }
                hint(douyin ? "正在用内置浏览器打开抖音页面…" : "正在用内置浏览器打开快手页面…");
                shortScraper.scrape(douyin ? "douyin" : "kuaishou", pageUrl,
                        new ShortVideoScraper.Callback() {
                            @Override
                            public void onResult(TweetFetcher.Result r) {
                                fillShortMeta(r, share, svId);
                                render(r);
                                // 直链过期重解析回来：新地址到手，自动接着下，不用用户再点
                                if (healPending) {
                                    healPending = false;
                                    dlPanel.postDelayed(() -> {
                                        if (selected != null) doDownload(selected);
                                    }, 900);
                                }
                            }

                            @Override
                            public void onFail(String msg) {
                                skeleton.setVisibility(View.GONE);
                                hint(msg);
                            }
                        });
            });
        }, "resolve").start();
    }

    /**
     * 抖音/快手的标题、作者、ID 收尾：
     *  1. 作品 ID 揣在结果里，下载时用它保证文件名唯一；
     *  2. 页面抓不到标题（快手 H5 页面标题是空的）时，用用户粘贴的口令文案，
     *     把它变成「狠人大队 #小炮子 …」这样的可读标题，而不是「来自快手的视频」。
     */
    private void fillShortMeta(TweetFetcher.Result r, String share, String svId) {
        if (r == null) return;
        if (svId != null && !svId.isEmpty()) r.svId = svId;
        String t = r.text == null ? "" : r.text.trim();
        if (!r.svGeneric && !t.isEmpty()) return;
        String fromShare = ShortVideo.captionFromShare(share);
        if (!fromShare.isEmpty()) {
            // 口令里有真文案（"狠人大队 小炮子…"），用它当标题，同时不再是通用句
            r.text = fromShare;
            r.svGeneric = false;
        } else if (svId != null && !svId.isEmpty()) {
            // 连口令文案都没有：至少用作品 ID 当标题，别让所有视频同名
            r.text = "作品 " + svId;
            r.svGeneric = false;
        }
    }

    /**
     * Instagram 真假两条并行线路（用户定的总规矩，分流开关只有一个布尔，别再串改）：
     * 真（已登录）→ IgFetcher 真线：webapi 拿全档清晰度；
     * 假（未登录）→ IgFetcher 假线：graphql / embed / 镜像，全灭后最后由内置
     * WebView embed 旁听兜底（仍属假线，不跨线借用）。
     */
    private void startIg(final String link) {
        // 记住原始链接：IG 直链过期后要拿它重新取址（下载前刷新 + 失败自愈都靠它）
        this.shareLink = link;
        this.shareText = "";
        TweetFetcher.fetch(link, new TweetFetcher.Listener() {
            @Override
            public void onDone(TweetFetcher.Result r) {
                render(r);
                // 自愈回来的结果：新地址到手，自动接着下
                if (healPending) {
                    healPending = false;
                    dlPanel.postDelayed(() -> {
                        if (selected != null) doDownload(selected);
                    }, 900);
                }
            }

            @Override
            public void onFail(String msg) {
                if (IgFetcher.loggedIn()) {
                    // 真线终点：不借假线的道，如实报错引导重登
                    skeleton.setVisibility(View.GONE);
                    String m = TweetFetcher.beautify(msg);
                    if (!m.contains("代理") && !m.contains("连不上")) {
                        m += "（登录线路：可能是私密帖或会话过期，去设置重新登录）";
                    }
                    hint(m);
                    return;
                }
                // 假线最后一步：WebView embed（与 DOM 无关的网络层旁听）
                igScraper.scrape(link, new IgScraper.Callback() {
                    @Override
                    public void onResult(TweetFetcher.Result r) {
                        render(r);
                    }

                    @Override
                    public void onFail(String msg2) {
                        skeleton.setVisibility(View.GONE);
                        hint(msg2.contains("代理") || msg2.contains("打不开")
                                ? msg2 : TweetFetcher.beautify(msg2));
                    }
                });
            }
        });
    }

    private void render(TweetFetcher.Result r) {
        this.result = r;
        multiSel = false;          // 新的一条解析结果，多选状态清零
        multi.clear();
        skeleton.setVisibility(View.GONE);
        preview.setVisibility(View.VISIBLE);
        status.setVisibility(View.GONE);

        tvAuthor.setText(r.author == null || r.author.isEmpty() ? "@" + r.handle : r.author);
        tvMeta.setText("@" + r.handle + " · " + r.date);
        tvText.setText(r.text == null || r.text.isEmpty() ? "" : r.text);
        tvText.setVisibility(r.text == null || r.text.isEmpty() ? View.GONE : View.VISIBLE);
        ImageLoader.load(r.avatar, avatar);

        chips.removeAllViews();
        resetChipsScroll();
        // 换了一条帖子：先把上一条的画面清掉，等新封面加载好再显示，
        // 否则缩略图没回来的那一会儿还挂着上一帖的图，看着像"没复位"
        ivMedia.setImageDrawable(null);
        // 按钮上只写「1080P / 720P / 480P」这种短标签：像 B 站的
        // 「P1 · 分P标题 · 1080P · 带声音」会把后面的画质整个挤出屏幕，
        // 只看得见第一档，看着就像"这条只有一个分辨率"
        String[] tags = chipTags(r.items);
        int photos = 0;
        for (TweetFetcher.Media m : r.items) {
            if ("photo".equals(m.kind)) photos++;
        }
        // 纯图片帖（图1/图2/图3…）：进来就是多选，默认勾上图1
        boolean purePhotos = photos >= 2 && photos == r.items.size();

        // 「全选」放在最前面：多图帖一下就能全要，不用一张张点
        if (photos >= 2) {
            TextView all = new TextView(this);
            all.setTag(-1);
            all.setText("全选");
            all.setTextSize(12.5f);
            all.setPadding(dp(13), dp(6), dp(13), dp(6));
            all.setBackgroundResource(R.drawable.bg_chip);
            all.setTextColor(0xFFB9B9C4);
            all.setOnClickListener(v -> toggleSelectAll());
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            alp.rightMargin = dp(8);
            all.setLayoutParams(alp);
            chips.addView(all);
        }

        for (int i = 0; i < r.items.size(); i++) {
            final int idx = i;
            TextView c = new TextView(this);
            c.setTag(i);
            c.setText(tags[i]);
            c.setTextSize(12.5f);
            c.setPadding(dp(13), dp(6), dp(13), dp(6));
            c.setBackgroundResource(R.drawable.bg_chip);
            c.setTextColor(0xFFB9B9C4);
            c.setOnClickListener(v -> {
                if (multiSel && "photo".equals(r.items.get(idx).kind)) {
                    toggleMulti(idx);
                    return;
                }
                select(idx);
            });
            // 完整画质说明（码率、带不带声音、分P标题）挪到长按里，信息不丢
            final String full = r.items.get(i).label;
            c.setOnLongClickListener(v -> {
                toast(full == null || full.isEmpty() ? "这一档" : full);
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            c.setLayoutParams(lp);
            chips.addView(c);
        }
        if (purePhotos) {
            // 默认「图1」在预览框里，按钮已经写着「下载 1 张图片」；
            // 再点图2/图3 就一张张加上去
            multiSel = true;
            multi.clear();
            multi.add(0);
            selected = r.items.get(0);
            showPreview(0);
            refreshChips();
        } else {
            // 默认选中最高的视频画质（没有视频就选第一项）
            multiSel = false;
            multi.clear();
            int def = 0;
            for (int i = 0; i < r.items.size(); i++) {
                if (!"photo".equals(r.items.get(i).kind)) {
                    def = i;
                    break;
                }
            }
            select(def);
        }
    }

    private void select(int idx) {
        if (result == null || result.items.isEmpty()) return;
        selected = result.items.get(idx);
        for (int i = 0; i < chips.getChildCount(); i++) {
            TextView c = (TextView) chips.getChildAt(i);
            int ci = chipIndex(c);
            if (ci < 0) continue;                 // 「全选」那颗不参与单选高亮
            boolean on = ci == idx;
            c.setBackgroundResource(on ? R.drawable.bg_chip_on : R.drawable.bg_chip);
            c.setTextColor(on ? 0xFFFFFFFF : 0xFFB9B9C4);
            String t = c.getText().toString();
            if (t.startsWith("✓ ")) c.setText(t.substring(2));
        }
        btnDownload.setText("下载 · " + shortQuality(selected.label));
        showPreview(idx);
    }

    /** 只换预览框里的画面（多选时点哪张看哪张），不动选中态 */
    private void showPreview(int idx) {
        if (result == null || idx < 0 || idx >= result.items.size()) return;
        TweetFetcher.Media m = result.items.get(idx);
        String thumb = m.thumb == null || m.thumb.isEmpty() ? result.avatar : m.thumb;
        // 知道视频真实宽高时（B 站这类封面统一 16:9 的平台），预览框按**视频**的比例摆，
        // 竖拍的片子就是一条竖着的画面 —— 否则封面是横图，看着全是横屏，分不出横竖
        boolean byVideo = m.vw > 0 && m.vh > 0;
        ivMedia.setScaleType(byVideo ? ImageView.ScaleType.CENTER_CROP
                : ImageView.ScaleType.FIT_CENTER);
        if (thumb == null || thumb.isEmpty()) {
            // 这条没有封面：清空画面，别把上一条的图带过来显得驴唇不对马嘴
            ivMedia.setImageDrawable(null);
            fitPreview(byVideo ? m.vw * 1f / m.vh : 0);
        } else {
            // 竖版竖着看、横版横着看：按图的真实宽高比摆预览框，
            // 选封面图时才能看清是哪一张，不会把竖图裁成一条横缝
            if (byVideo) {
                fitPreview(m.vw * 1f / m.vh);
                ImageLoader.load(thumb, ivMedia);
            } else {
                ImageLoader.load(thumb, ivMedia, this::fitPreview);
            }
        }
        // 预览框右下角挂一个「竖屏 1080×1920」的角标，一眼看出这条是横拍还是竖拍
        applyOrientBadge(m);
    }

    /** 预览框上的方向角标：不知道尺寸就不显示 */
    private void applyOrientBadge(TweetFetcher.Media m) {
        if (tvOrient == null) return;
        String o = m == null ? "" : m.orient();
        if (o.isEmpty()) {
            tvOrient.setVisibility(View.GONE);
            return;
        }
        tvOrient.setText(o + " " + m.vw + "×" + m.vh);
        tvOrient.setVisibility(View.VISIBLE);
    }

    /** chip 上挂的条目下标；「全选」那颗是 -1 */
    private static int chipIndex(View v) {
        Object t = v.getTag();
        return t instanceof Integer ? (Integer) t : -2;
    }

    /**
     * 画质/图片 chip 是放在 HorizontalScrollView 里的，它自己会记住上次的滚动位置。
     * 换一条帖子如果滚到过图7，新的一排就还停在那儿，图1 被推到屏幕外——
     * 看着就像"没复位到图1"。这里把横向滚动拉回最左边。
     */
    private void resetChipsScroll() {
        chips.scrollTo(0, 0);
        final View parent = (View) chips.getParent();
        if (parent != null) {
            parent.scrollTo(0, 0);
            parent.post(() -> parent.scrollTo(0, 0));
        }
    }

    /** 预览框高度跟随内容比例：ratio = 宽/高，0 = 还不知道比例（恢复默认） */
    private void fitPreview(float ratio) {
        int w = ivMedia.getWidth();
        if (w <= 0) {
            ivMedia.post(() -> fitPreview(ratio));
            return;
        }
        ViewGroup.LayoutParams lp = ivMedia.getLayoutParams();
        int h;
        if (ratio <= 0.01f) {
            h = dp(172);
        } else {
            h = Math.round(w / ratio);
            h = Math.max(dp(140), Math.min(dp(360), h));
        }
        if (lp.height != h) {
            lp.height = h;
            ivMedia.setLayoutParams(lp);
        }
    }

    // ---------------- 图片多选下载 ----------------

    /** 图1/图2/图3…点一下就勾上，再点一下取消；预览框始终跟着刚点的那张走 */
    private void toggleMulti(int idx) {
        if (result == null || !"photo".equals(result.items.get(idx).kind)) return;
        if (multi.contains(idx)) multi.remove(idx);
        else multi.add(idx);
        // 预览跟着点的那张，这样能看清自己勾的是哪一张（排除作者发了重复图）
        selected = result.items.get(idx);
        showPreview(idx);
        refreshChips();
    }

    /** 「全选 / 取消全选」：多图帖一键全要 */
    private void toggleSelectAll() {
        if (result == null) return;
        int n = 0;
        for (TweetFetcher.Media m : result.items) {
            if ("photo".equals(m.kind)) n++;
        }
        if (multi.size() >= n && n > 0) {
            multi.clear();
        } else {
            for (int i = 0; i < result.items.size(); i++) {
                if ("photo".equals(result.items.get(i).kind)) multi.add(i);
            }
        }
        refreshChips();
    }

    /** 按当前模式刷新 chips 的选中态和下载按钮文字 */
    private void refreshChips() {
        if (result == null) return;
        int photos = 0;
        for (TweetFetcher.Media m : result.items) {
            if ("photo".equals(m.kind)) photos++;
        }
        boolean allOn = photos > 0 && multi.size() >= photos;
        for (int i = 0; i < chips.getChildCount(); i++) {
            TextView c = (TextView) chips.getChildAt(i);
            int ci = chipIndex(c);
            if (ci == -1) {
                // 「全选」：全勾上了就变成「取消全选」
                if (multiSel && allOn) {
                    c.setText("取消全选");
                    c.setBackgroundResource(R.drawable.bg_chip_on);
                    c.setTextColor(0xFFFFFFFF);
                } else {
                    c.setText("全选");
                    c.setBackgroundResource(R.drawable.bg_chip);
                    c.setTextColor(0xFFB9B9C4);
                }
                continue;
            }
            if (ci < 0) continue;
            if (multiSel) {
                boolean on = multi.contains(ci);
                c.setBackgroundResource(on ? R.drawable.bg_chip_on : R.drawable.bg_chip);
                c.setTextColor(on ? 0xFFFFFFFF : 0xFFB9B9C4);
                // 勾选标记不能反复叠加：先剥掉旧的 ✓ 再按状态加
                String t = c.getText().toString();
                while (t.startsWith("✓ ")) t = t.substring(2);
                c.setText(on ? "✓ " + t : t);
            } else {
                String t = c.getText().toString();
                while (t.startsWith("✓ ")) t = t.substring(2);
                c.setText(t);
            }
        }
        if (multiSel) {
            btnDownload.setText(multi.isEmpty()
                    ? "下载（还没选图片）"
                    : (allOn ? "下载全部 " + multi.size() + " 张" : "下载 " + multi.size() + " 张图片"));
        }
    }

    /** "最高 · 1080p" -> "1080p"；图片 -> "原图" */
    private String shortQuality(String label) {
        if (label == null || label.isEmpty()) return "所选";
        if (label.contains("原图")) return "原图";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d{3,4})p").matcher(label);
        if (m.find()) return m.group(1) + "p";
        String[] parts = label.split(" · ");
        return parts.length >= 2 ? parts[1] : parts[0];
    }

    /**
     * 画质按钮的短标签：分辨率（必要时前面带分P号），最多「P1 · 1080P」这么长。
     * 完整标签（码率、带不带声音、分P标题）在长按时用 toast 显示。
     */
    private String[] chipTags(java.util.List<TweetFetcher.Media> items) {
        int photos = 0;
        for (TweetFetcher.Media m : items) {
            if ("photo".equals(m.kind)) photos++;
        }
        String[] tags = new String[items.size()];
        int pi = 0;
        for (int i = 0; i < items.size(); i++) {
            TweetFetcher.Media m = items.get(i);
            if ("photo".equals(m.kind)) {
                pi++;
                tags[i] = photos > 1 ? "图" + pi : "原图";
            } else {
                tags[i] = videoTag(m.label);
            }
        }
        // 同名（同一分辨率出现多档 / 多段视频）补 ①②，否则两个按钮长得一模一样
        java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        for (int i = 0; i < tags.length; i++) {
            String base = tags[i];
            int n = seen.containsKey(base) ? seen.get(base) + 1 : 1;
            seen.put(base, n);
            if (n > 1) tags[i] = base + circled(n);
        }
        return tags;
    }

    /** 「P1 · 分P标题 · 1080P · 带声音」→「P1 · 1080P」 */
    private static String videoTag(String label) {
        String s = label == null ? "" : label;
        String tag = null;
        java.util.regex.Matcher d = java.util.regex.Pattern
                .compile("(\\d{3,4})\\s*[pP]").matcher(s);
        if (d.find()) tag = d.group(1) + "P";
        if (tag == null) {
            if (s.contains("原片")) tag = "原片";
            else if (s.contains("网页版")) tag = "网页版";
            else if (s.contains("音频")) tag = "音频";
            else if (s.contains("GIF")) tag = "GIF";
            else tag = "视频";
        }
        java.util.regex.Matcher p = java.util.regex.Pattern
                .compile("^(P\\d+|视频\\s?\\d+)").matcher(s);
        if (p.find()) {
            String pre = p.group(1).replace(" ", "");
            // 分P/多段视频：有前缀时别写成「视频2 · 视频」这种废话
            tag = "视频".equals(tag) ? pre : pre + " · " + tag;
        }
        return tag;
    }

    private static String circled(int n) {
        String cs = "①②③④⑤⑥⑦⑧⑨";
        // n=2 要给 ②，所以下标是 n-1
        return n >= 2 && n <= 9 ? String.valueOf(cs.charAt(n - 1)) : "#" + n;
    }

    // ---------------- 下载 ----------------

    private void beginDownload() {
        if (selected == null) return;
        hideIme();      // 点下载时可能还留着键盘，先收起来，进度条才不会被顶
        if (Prefs.galleryVisible(this) && Build.VERSION.SDK_INT <= 28
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        // 图片多选：整批排队，界面只显示一条总进度，全部下完才到 100%
        if (multiSel) {
            if (result == null || multi.isEmpty()) {
                toast("先点选要下载的图片");
                return;
            }
            batchQueue.clear();
            for (int idx : multi) batchQueue.add(result.items.get(idx));
            batchTotal = batchQueue.size();
            batchDone = 0;
            batchOn = batchTotal > 1;
            multiSel = false;
            multi.clear();
            // 先退回单张态，避免下完一条后 chips 上还挂着 ✓
            if (!result.items.isEmpty()) select(0);
            startBatchNext();
            return;
        }
        doDownload(selected);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] grants) {
        if (code == REQ_STORAGE) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) doDownload();
            else toast("没有存储权限，请在设置里关闭「保存到相册」后重试");
        }
    }

    /** B 站/小红书的 CDN 会校验 Referer，不带就 403 */
    /**
     * 抖音/快手直链是带签名的 CDN 地址，放一会儿或换个网络就失效，表现为下载时报
     * HTTP 403 / 404 / 410。这里不再把错误甩给用户：拿原始口令重新走一遍内置浏览器解析，
     * 拿到新地址后自动接着下（最多两次）。这就是"自己重新解析出最新地址"的实现。
     */
    private boolean healShortIfExpired(String msg) {
        if (result == null || shareLink == null) return false;
        boolean sv = "douyin".equals(result.platform) || "kuaishou".equals(result.platform);
        boolean ig = "instagram".equals(result.platform);
        boolean tx = "twitter".equals(result.platform);
        boolean bili = "bilibili".equals(result.platform);
        if (!sv && !ig && !tx && !bili) return false;
        String low = msg == null ? "" : msg.toLowerCase();
        boolean expired = low.contains("403") || low.contains("404") || low.contains("410")
                || low.contains("过期") || low.contains("拒绝");
        if (!expired || healCount >= 2) return false;
        healCount++;
        healPending = true;
        toast("直链已过期，正在重新解析（第 " + healCount + " 次）…");
        hint("正在刷新视频地址…");
        if (sv) {
            startShortVideo(shareLink, shareText);
        } else if (ig) {
            // IG：拿短码重新取一次 media info（地址重新签发），失败仍走原来的假线兜底
            igRefreshed = false;
            startIg(shareLink);
        } else if (bili) {
            // B 站：直链带 deadline 会过期，节点也可能拒绝；重新 playurl 拿新签名地址
            BiliFetcher.fetch(shareLink, new TweetFetcher.Listener() {
                @Override
                public void onDone(TweetFetcher.Result r) {
                    render(r);
                    if (healPending) {
                        healPending = false;
                        dlPanel.postDelayed(() -> {
                            if (selected != null) doDownload(selected);
                        }, 900);
                    }
                }

                @Override
                public void onFail(String m2) {
                    healPending = false;
                    hint(TweetFetcher.beautify(m2));
                }
            });
        } else {
            // X：直链本身不会过期，但接口风控/改版会让旧地址失效，按推文 ID 重取 variants
            TweetFetcher.fetch(shareLink, new TweetFetcher.Listener() {
                @Override
                public void onDone(TweetFetcher.Result r) {
                    render(r);
                    if (healPending) {
                        healPending = false;
                        dlPanel.postDelayed(() -> {
                            if (selected != null) doDownload(selected);
                        }, 900);
                    }
                }

                @Override
                public void onFail(String m2) {
                    healPending = false;
                    hint(TweetFetcher.beautify(m2));
                }
            });
        }
        return true;
    }

    private static String refererFor(String platform) {        if ("instagram".equals(platform)) return "https://www.instagram.com/";
        if ("bilibili".equals(platform)) return "https://www.bilibili.com/";
        if ("xiaohongshu".equals(platform)) return "https://www.xiaohongshu.com/";
        // 抖音/快手的 CDN 直链认 Referer：不带就 403/404（实测 douyinvod 无 Referer=403、
        // 带上=206）。以前这里返回 null，于是「解析成功、点下载 404」——根因就在这。
        if ("douyin".equals(platform)) return "https://www.douyin.com/";
        if ("kuaishou".equals(platform)) return "https://www.kuaishou.com/";
        if ("tiktok".equals(platform)) return "https://www.tiktok.com/";
        if ("twitter".equals(platform)) return "https://x.com/";
        if ("weibo".equals(platform)) return "https://weibo.com/";
        if ("tuchong".equals(platform)) return "https://tuchong.com/";
        if ("facebook".equals(platform)) return "https://www.facebook.com/";
        if ("pinterest".equals(platform)) return "https://www.pinterest.com/";
        return null;
    }

    private void doDownload() {
        doDownload(selected);
    }

    /**
     * 批量调度：最多 3 张图片同时在飞，完成一张立刻补一张；
     * 视频/大文件仍然独占（DlService 那边也只放一条，硬闯会被拒）。
     * 已经下过的自动跳过，不让整批卡在那里等一个永远不来的完成广播。
     */
    private void startBatchNext() {
        if (!batchOn) return;
        while (batchInFlight < MAX_BATCH_PARALLEL && !batchQueue.isEmpty()) {
            TweetFetcher.Media m = batchQueue.get(0);
            // 视频一条就吃 8 条 Range 连接：等图片都收尾了再下它，别互相抢带宽
            if (batchInFlight > 0 && !"photo".equals(m.kind)) break;
            batchQueue.remove(0);
            dlLabelName = "已完成 " + batchDone + "/" + batchTotal + " 张";
            if (doDownload(m)) {
                batchInFlight++;
                continue;
            }
            batchDone++;          // 这张跳过（已下过 / 参数缺失）
            if (lastSkipDup) batchDup++;
        }
        if (batchQueue.isEmpty() && batchInFlight == 0) finishBatch(true);
        else if (batchInFlight > 0) armWatchdog();
    }

    private void armWatchdog() {
        if (dlPanel == null) return;
        dlPanel.removeCallbacks(batchWatchdog);
        dlPanel.postDelayed(batchWatchdog, 60000L);
    }

    /** 一批结束：batchOn 关掉，界面收尾 */
    private void finishBatch(boolean ok) {
        int done = batchDone;
        int dup = batchDup;
        int fail = batchFail;
        int saved = Math.max(0, done - dup - fail);
        batchOn = false;
        batchQueue.clear();
        batchTotal = 0;
        batchDone = 0;
        batchDup = 0;
        batchFail = 0;
        batchInFlight = 0;
        batchBusyRetry = 0;
        if (dlPanel != null) dlPanel.removeCallbacks(batchWatchdog);
        hideProgress(ok);
        // 整批都被去重跳过时，说「已保存」是骗人：明确告诉用户这是重复下载
        if (dup > 0 && saved == 0 && fail == 0) {
            toast("重复下载：这 " + dup + " 张之前都已经存过了");
        } else if (dup > 0) {
            toast("已保存 " + saved + " 张，" + dup + " 张是重复下载"
                    + (fail > 0 ? "，" + fail + " 张失败" : ""));
        } else if (done > 1) {
            toast(fail > 0 ? "已保存 " + saved + " 张，" + fail + " 张失败"
                    : "全部 " + done + " 张已保存");
        }
        clearResult();
        renderHistory();
    }

    /** @return true 表示真的开始下载了；false 表示跳过 */
    private boolean doDownload(final TweetFetcher.Media m) {
        final TweetFetcher.Result r = result;
        lastSkipDup = false;
        if (m == null || r == null) return false;

        // Instagram 的直链带 oh/oe 签名，解析完放一会儿就废（YunX 的做法是
        // 「下载那一刻才取址」）。这里下载前先刷新一次；**刷新失败照样用旧地址下**，
        // 我们的老逻辑当兜底，两层保险。
        if ("instagram".equals(r.platform) && !igRefreshed && shareLink != null) {
            igRefreshed = true;
            final TweetFetcher.Media target = m;
            hint("正在刷新 Instagram 地址…");
            new Thread(() -> {
                TweetFetcher.Result fresh = null;
                try { fresh = TweetFetcher.fetchSync(shareLink); } catch (Throwable ignored) { }
                final TweetFetcher.Result fr = fresh;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    TweetFetcher.Media fresh2 = freshestMedia(fr, target);
                    if (fresh2 != null) {
                        if (fresh2.url != null && !fresh2.url.isEmpty()) target.url = fresh2.url;
                        // IG 的 DASH 是音视频两路，音轨地址同样是签名地址，必须一起换
                        if (fresh2.audioUrl != null && !fresh2.audioUrl.isEmpty()) {
                            target.audioUrl = fresh2.audioUrl;
                        }
                    }
                    hint("");
                    doDownload(target);
                });
            }, "ig-refresh").start();
            return true;
        }

        String id = "instagram".equals(r.platform)
                ? IgFetcher.shortcode(r.tweetUrl)
                : TweetFetcher.extractTweetId(r.tweetUrl);

        // 文件名：作者_标题_清晰度 —— 标题是"这条作品是什么"，作者是"谁的"，
        // 两者都要有，否则相册里几十条只能靠 (1)(2) 区分。
        String author = r.author == null ? "" : r.author.trim();
        if (author.isEmpty()) author = r.handle == null ? "" : r.handle;
        String who = safeName(author, 16);
        if (who.isEmpty()) who = r.platform == null ? "downx" : r.platform;
        // 解析器自己编的通用句（「来自快手的视频」）不算标题：同一作者的所有作品
        // 会拼成一模一样的文件名，写盘时互相覆盖、相册里也全是一个名字。
        String title = Namer.titleFor(r.text);
        if (!title.isEmpty()) title = safeName(title, 26);
        // 同一帖的多张图以前全叫 xxx_photo.jpg，第二张起会被去重逻辑当成
        // 「已经下过」直接丢掉——所以多选也只落一张。这里给图片编上号。
        String tag = "photo".equals(m.kind) ? photoTagFor(m, r) : tagFor(m);
        String name = Namer.fileName(who, title, r.svId, id, tag, m.ext);
        // 多图帖的图统一放进相册的「多图」文件夹：手机上一眼能找到，整组删除也方便
        // 去重一律用不带目录的文件名比：以前下过的（在 DownX 根目录）也算重复，
        // 不然换个目录就会又存一份，列表里出现两条一模一样的
        if (batchOn && "photo".equals(m.kind)) name = "多图/" + name;
        String key = (r.tweetUrl == null ? "" : r.tweetUrl) + "|" + m.label;

        // 去重：同一条原帖的同一清晰度已经下过就不再存一份（目录前缀不参与比较）
        String plainName = name.startsWith("多图/") ? name.substring(3) : name;
        if (Store.isDuplicate(this, key, plainName)) {
            lastSkipDup = true;
            if (!batchOn) toast("重复下载：这张之前已经存过了");
            return false;
        }

        // 备用直链：同一条作品的另一个视频流（小红书原片被 403 时自动换网页版，
        // 文件名也跟着改成「网页版」，不能把带水印的存成「原片」）
        // B 站：解析器直接给了同签名的 backupUrl（m.altUrl），主节点拒时换它，文件名不变
        String alt = m.altUrl != null && !m.altUrl.isEmpty() ? m.altUrl : null;
        String altName = null;
        if (alt == null) {
            for (TweetFetcher.Media x : r.items) {
                if (x == m || x.url == null || x.url.isEmpty()) continue;
                if (!"video".equals(x.kind)) continue;
                if (x.url.equals(m.url)) continue;
                alt = x.url;
                altName = Namer.fileName(who, title, r.svId, id, tagFor(x), x.ext);
                break;
            }
        }

        // 批量：把「第几张 / 共几张 / 整批叫什么」一起交给服务，
        // 服务里只维护一条通知，进度按整批折算，通知栏不会一张一张跳
        int bIdx = batchOn ? batchDone : 0;
        int bTotal = batchOn ? Math.max(1, batchTotal) : 1;
        String bLabel = null;
        if (batchOn) {
            String h = r.handle == null ? "" : r.handle.trim();
            bLabel = (h.isEmpty() ? "多图" : "@" + h) + " · 共 " + bTotal + " 张";
        }

        // Referer 兜底：没给平台 Referer 的，用作品页当 Referer ——
        // CDN 的防盗链白名单里一定有作品页所在域名，比空 Referer 强
        String ref = refererFor(r.platform);
        if (ref == null && r.tweetUrl != null && r.tweetUrl.startsWith("http")) ref = r.tweetUrl;

        // B 站：CDN 按登录态给码率，带上下载 Cookie（参照 CYQawa/YunX 的 mediaHeaders）
        DlService.setCookie("bilibili".equals(r.platform) && BiliSession.has(this)
                ? BiliSession.cookie(this) : "");

        // 列表里的标题：作者 + 作品标题。以前这里放的是 m.label（「视频 · 无水印」），
        // 结果所有快手视频在列表里长得一模一样，根本分不出是哪条。
        String sub = Namer.subTitle(r.handle, title, "photo".equals(m.kind));

        DlService.start(this, m.url, m.audioUrl, name, m.kind,
                ref,
                Prefs.galleryVisible(this),
                m.thumb == null || m.thumb.isEmpty() ? r.avatar : m.thumb,
                r.tweetUrl,
                sub,
                key, alt, altName, m.pick, m.fallbackUrl, bIdx, bTotal, bLabel);

        if (!batchOn) {
            dlLabelName = m.label;
            toast("开始下载");
        }
        showProgress(0);
        return true;
    }

    /** 底部半透明下载进度浮层 */
    private void showProgress(int pct) {
        if (dlPanel == null) return;
        // 多图一批：整批合成一条进度（已完成张数 + 当前张的百分比）/ 总张数
        if (batchOn && batchTotal > 1) {
            if (dlPanel.getVisibility() != View.VISIBLE) {
                tvDlName.setText(dlLabelName == null || dlLabelName.isEmpty()
                        ? "正在下载" : dlLabelName);
                dlPanel.setAlpha(0f);
                dlPanel.setVisibility(View.VISIBLE);
                dlBar.setProgress(0);
                tvDlPct.setText("0%");
                setPauseUi(false);
                dlPanel.animate().alpha(1f).setDuration(160).start();
            }
            if (pct < 0) pct = 0;
            if (pct > 100) pct = 100;
            int all = (batchDone * 100 + pct) / batchTotal;
            if (all < dlBar.getProgress()) all = dlBar.getProgress();
            dlBar.setProgress(all);
            if (!DlService.isPaused()) tvDlPct.setText(all + "%");
            if (tvDlName != null) {
                tvDlName.setText("已完成 " + Math.min(batchDone, batchTotal) + "/" + batchTotal
                        + " 张" + (batchInFlight > 1 ? " · " + batchInFlight + " 路并行" : ""));
            }
            return;
        }
        if (dlPanel.getVisibility() != View.VISIBLE) {
            tvDlName.setText(dlLabelName == null || dlLabelName.isEmpty()
                    ? "正在下载" : dlLabelName);
            dlPanel.setAlpha(0f);
            dlPanel.setVisibility(View.VISIBLE);
            dlBar.setProgress(0);
            tvDlPct.setText("0%");
            setPauseUi(false);          // 新的一条从「暂停」按钮开始
            dlPanel.animate().alpha(1f).setDuration(160).start();
        }
        if (pct < 0) pct = 0;
        if (pct > 100) pct = 100;
        // 只许往前走：阶段切换时哪怕算错一点，也别让数字当着人面往回掉
        if (pct < dlBar.getProgress()) pct = dlBar.getProgress();
        dlBar.setProgress(pct);
        // 暂停中就别让进度数字盖掉「已暂停」提示
        if (!DlService.isPaused()) tvDlPct.setText(pct + "%");
    }

    /** 暂停状态同步到浮层按钮：暂停中按钮变「继续」并高亮 */
    private void setPauseUi(boolean paused) {
        if (tvDlPause == null) return;
        tvDlPause.setText(paused ? "继续" : "暂停");
        tvDlPause.setTextColor(paused ? 0xFFFFC65C : 0xFFF2F2F7);
        if (paused) {
            tvDlPct.setText("已暂停");
        } else if (dlBar != null) {
            tvDlPct.setText(dlBar.getProgress() + "%");
        }
    }

    /** 取消下载前先问一句，因为它会把下到一半的文件一起删掉 */
    private void askCancelDownload() {
        new AlertDialog.Builder(this)
                .setTitle("取消这次下载？")
                .setMessage("下到一半的文件会一起删掉，不会在相册或临时目录里留下残余。")
                .setNegativeButton("继续下载", null)
                .setPositiveButton("取消并删除", (d, w) -> {
                    DlService.cancel(this);
                    if (dlPanel != null && dlPanel.getVisibility() == View.VISIBLE) {
                        tvDlName.setText("正在取消…");
                        tvDlPct.setText("");
                    }
                })
                .show();
    }

    private void hideProgress(boolean success) {
        if (dlPanel == null || dlPanel.getVisibility() != View.VISIBLE) return;
        dlBar.setProgress(success ? 100 : dlBar.getProgress());
        if (success) tvDlPct.setText("100%");
        dlPanel.animate().alpha(0f).setDuration(240).withEndAction(() -> {
            dlPanel.setVisibility(View.GONE);
            dlPanel.setAlpha(1f);
        }).start();
    }

    /** 下载成功后把解析结果清掉，下次直接粘新链接，不用手动删 */
    private void clearResult() {
        result = null;
        selected = null;
        if (input != null) input.setText("");
        preview.setVisibility(View.GONE);
        status.setVisibility(View.GONE);
        skeleton.setVisibility(View.GONE);
    }

    /** 多图帖的文件名后缀：photo1 / photo2 …；单张图仍旧是 photo */
    private static String photoTagFor(TweetFetcher.Media m, TweetFetcher.Result r) {
        int n = 0, at = -1;
        for (TweetFetcher.Media x : r.items) {
            if (!"photo".equals(x.kind)) continue;
            n++;
            if (x == m) at = n;
        }
        if (n <= 1 || at <= 0) return "photo";
        return "photo" + at;
    }

    private String tagFor(TweetFetcher.Media m) {
        if ("photo".equals(m.kind)) return "photo";
        if (m.label != null && m.label.contains("原片")) return "原片";
        if (m.label != null && m.label.contains("网页版")) return "网页版";
        if (m.label != null && m.label.contains("带声音")) return "带声音";
        // 备用线路（快手两条 CDN）：名字要跟主链区分开，否则同一条作品互相覆盖
        if (m.label != null && m.label.contains("备用")) return "备用";
        // 「HLS · 720P」这类，先摘出画质再加个 hls 标记，便于区分来源
        if (m.label != null && m.label.contains("HLS")) {
            java.util.regex.Matcher hm = java.util.regex.Pattern
                    .compile("(\\d{3,4})\\s*[pP]").matcher(m.label);
            return hm.find() ? hm.group(1) + "p-hls" : "hls";
        }
        // 大写 720P / 小写 720p 都要认，不然全落到 _video
        java.util.regex.Matcher mm = java.util.regex.Pattern
                .compile("(\\d{3,4})\\s*[pP]").matcher(m.label);
        if (mm.find()) return mm.group(1) + "p";
        if (m.label.contains("GIF")) return "gif";
        // 普通视频不再补「video」：文件名里「_video」纯噪音，还占长度。
        // 没标签就是空串，调用方拼名字时会自动少一个下划线。
        return "";
    }

    /**
     * 从刷新后的解析结果里挑出「同一条媒体」：先按类型 + 清晰度标签精确匹配，
     * 匹配不到退而取同类型的第一条；都没有就返回 null —— 调用方继续用旧地址（兜底）。
     * 返回整条 Media 而不是 URL，是因为 IG 的 DASH 还有一条音轨地址同样会过期。
     */
    private static TweetFetcher.Media freshestMedia(TweetFetcher.Result fresh, TweetFetcher.Media want) {
        if (fresh == null || want == null || fresh.items == null || fresh.items.isEmpty()) return null;
        String label = want.label == null ? "" : want.label.trim();
        for (TweetFetcher.Media x : fresh.items) {
            if (x == null || x.url == null || x.url.isEmpty()) continue;
            if (!want.kind.equals(x.kind)) continue;
            if (!label.isEmpty() && label.equals(x.label == null ? "" : x.label.trim())) return x;
        }
        for (TweetFetcher.Media x : fresh.items) {
            if (x == null || x.url == null || x.url.isEmpty()) continue;
            if (want.kind.equals(x.kind)) return x;
        }
        return null;
    }

    /**
     * 文件名安全化：文件名里中文、日文都能用，只干掉系统不允许的 / \ : * ? " < > |
     * 和换行，空白折成下划线；太长就截断（不然相册里显示一长串挤在一起）。
     */
    private static String safeName(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", " ")
                .replaceAll("[#%&{}$!'@`+=~^]", "")
                .replaceAll("\\s+", "_")
                .trim();
        while (t.startsWith("_") || t.startsWith(".")) t = t.substring(1);
        while (t.endsWith("_") || t.endsWith(".")) t = t.substring(0, t.length() - 1);
        if (t.length() > max) t = t.substring(0, max);
        return t;
    }

    private String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private String ts() {
        return String.valueOf(System.currentTimeMillis() / 1000);
    }

    // ---------------- 下载记录（多种排列样式 + 长按拖动排序） ----------------

    private static final int STYLE_TILE = 0;  // 普通缩略图
    private static final int STYLE_WIDE = 1;  // 单列大卡：底部叠作者信息
    private static final int STYLE_FIT = 2;   // 瀑布流：按图片原始比例

    private int cellW, cellH;
    private final List<View> cellViews = new java.util.ArrayList<>();
    /** 每个卡片 / 列表行对应的勾选角标，多选时只改这些，不重建列表 */
    private final List<SelSlot> selSlots = new java.util.ArrayList<>();
    /** 上次渲染的内容指纹：内容没变就不重建，避免整屏缩略图重刷 */
    private String renderSig;
    /** 上次渲染时列表的实际宽度：横竖屏切换后宽度变了要重排，不然卡片尺寸全错 */
    private int lastLaidWidth;
    /** 「竖横分区」排列时当前分出的两组，打开播放器时把同方向的列表传过去 */
    private List<HistoryStore.Entry> splitVert, splitHorz;
    private ImageView ghost;
    private int dragIndex = -1;
    /** 抽屉样式当前看到第几张：从播放器返回重建列表后靠它滚回原位 */
    private int drawerIndex = 0;
    /** 当前按方向筛过之后真正显示的这一批：播放器左右滑就用它，保证只滑同方向 */
    private List<HistoryStore.Entry> shownList = new ArrayList<>();
    private String shownFilter = Prefs.DIR_ALL;
    /** 本次长按是否真的拖动过：没动就是"按住松手"，弹菜单（分享等）；动了就是排序 */
    private boolean dragMoved;
    /** 起拖提示只弹一次，之后每次长按不再弹（每次都弹很吵） */
    private boolean dragHintShown;
    /** 拖动中被高亮的落点格（实时预览松手会落在哪） */
    private int lastDropHint = -1;
    private final int[] rootLoc = new int[2];

    private static class SelSlot {
        final HistoryStore.Entry entry;
        final View cell;
        final TextView badge;
        final boolean row;

        SelSlot(HistoryStore.Entry e, View c, TextView b, boolean r) {
            entry = e;
            cell = c;
            badge = b;
            row = r;
        }
    }

    /** onResume 用：内容、排列、宽度都没变就直接返回，不重建视图 */
    private void renderHistoryIfChanged() {
        List<HistoryStore.Entry> list = HistoryStore.list(this);
        int avail = historyBox.getWidth() > 0
                ? historyBox.getWidth()
                : getResources().getDisplayMetrics().widthPixels - dp(36);
        String sig = sigOf(list, avail);
        if (sig.equals(renderSig)) return;
        renderHistory();
    }

    private String sigOf(List<HistoryStore.Entry> list, int avail) {
        StringBuilder sb = new StringBuilder();
        int mode0 = Prefs.layoutMode(this);
        sb.append(mode0).append('#').append(Prefs.dirFilter(this, mode0))
          .append('#').append(avail).append('#');
        for (HistoryStore.Entry e : list) {
            sb.append(keyOf(e)).append(e.visible ? '1' : '0').append(e.kind)
              .append(e.orientation == null ? "" : e.orientation).append(';');
        }
        return sb.toString();
    }

    /**
     * 重建列表但保住当前位置。
     *
     * 以前隐藏/放回相册之后整页会跳回最顶上（最新下载那格），东西一多就得重新
     * 往下翻一遍，很反人类。原因是 removeAllViews 之后内容高度瞬间变小，
     * ScrollView 会把 scrollY 夹到 0，再填回去就停在顶上了。这里在拆之前
     * 记下竖向位置和每个横滑抽屉的位置，重建后按原样塞回去。
     */
    private void renderHistory() {
        final int keepY = contentRoot == null ? 0 : contentRoot.getScrollY();
        final int[] keepX = grabHScrollX();
        renderHistoryInner();
        if (contentRoot == null) return;
        if (keepY <= 0 && keepX.length == 0) return;
        // post 到下一帧：等这一次 layout 把高度算完，scrollTo 才不会被夹掉
        historyBox.post(() -> {
            if (contentRoot.getScrollY() != keepY) contentRoot.scrollTo(0, keepY);
            applyHScrollX(historyBox, keepX, new int[]{0});
        });
    }

    /** 收集列表里所有横滑容器（抽屉/横排）的 scrollX，顺序与重建后一致 */
    private int[] grabHScrollX() {
        List<Integer> tmp = new ArrayList<>();
        collectHScrollX(historyBox, tmp);
        int[] out = new int[tmp.size()];
        for (int i = 0; i < out.length; i++) out[i] = tmp.get(i);
        return out;
    }

    private void collectHScrollX(View v, List<Integer> out) {
        if (v instanceof HorizontalScrollView) {
            out.add(((HorizontalScrollView) v).getScrollX());
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectHScrollX(g.getChildAt(i), out);
        }
    }

    private void applyHScrollX(View v, int[] xs, int[] at) {
        if (v instanceof HorizontalScrollView) {
            int i = at[0]++;
            if (i < xs.length && xs[i] > 0) ((HorizontalScrollView) v).scrollTo(xs[i], 0);
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) applyHScrollX(g.getChildAt(i), xs, at);
        }
    }

    private void renderHistoryInner() {
        historyBox.removeAllViews();
        cellViews.clear();
        selSlots.clear();
        dragIndex = -1;
        List<HistoryStore.Entry> list = HistoryStore.list(this);
        historyHeader.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
        if (selectMode) {
            // 记录可能被删掉了，清掉失效的勾选
            List<HistoryStore.Entry> keep = new ArrayList<>();
            for (HistoryStore.Entry p : picked) {
                for (HistoryStore.Entry x : list) {
                    if (keyOf(x).equals(keyOf(p))) { keep.add(x); break; }
                }
            }
            picked.clear();
            picked.addAll(keep);
            if (list.isEmpty()) selectMode = false;
            updateSelectBar();
        }
        final int mode = Prefs.layoutMode(this);
        deadCount = list.isEmpty() ? 0 : countDead(list);
        String countText = list.isEmpty() ? ""
                : "已下载 " + list.size() + " · " + Prefs.layoutName(mode);
        if (deadCount > 0) countText += " · " + deadCount + " 个失效";
        tvCount.setText(countText);
        if (deadCount > 0) {
            tvCount.setOnClickListener(v -> askCleanDead());
        } else {
            tvCount.setOnClickListener(null);
        }
        if (list.isEmpty()) {
            renderSig = null;
            return;
        }

        int gap = dp(7);
        int avail = historyBox.getWidth() > 0
                ? historyBox.getWidth()
                : getResources().getDisplayMetrics().widthPixels - dp(36);
        lastLaidWidth = historyBox.getWidth() > 0 ? historyBox.getWidth() : -1;
        renderSig = sigOf(list, avail);

        // ---- 方向筛选：横滑抽屉默认只看竖屏，其余样式默认看全部 ----
        // 竖横分区本来就分好了段落，不再叠一层筛选
        String filter = mode == Prefs.LAYOUT_SPLIT
                ? Prefs.DIR_ALL : Prefs.dirFilter(this, mode);
        shownFilter = filter;
        shownList = filterList(list, filter);
        // 同一帖子的多张图在主页只占一个位置（叠加缩略图），点开再左右滑着看
        shownList = collapsePhotoGroups(shownList);
        if (mode != Prefs.LAYOUT_SPLIT) historyBox.addView(dirSwitch(list, mode, filter));

        if (shownList.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("这个方向还没有下载的内容，点上面的「全部」看看");
            empty.setTextColor(0xFF8B909A);
            empty.setTextSize(12.5f);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(10), dp(46), dp(10), dp(46));
            historyBox.addView(empty);
            updateSelectVisuals();
            return;
        }

        switch (mode) {
            case Prefs.LAYOUT_GRID_4:
                renderGrid(shownList, 4, 16f / 9f, gap, avail);
                break;
            case Prefs.LAYOUT_GRID_2:
                renderGrid(shownList, 2, 16f / 9f, gap, avail);
                break;
            case Prefs.LAYOUT_SQUARE:
                renderGrid(shownList, 3, 1f, gap, avail);
                break;
            case Prefs.LAYOUT_WIDE:
                renderWide(shownList, gap, avail);
                break;
            case Prefs.LAYOUT_LIST:
                renderList(shownList, gap);
                break;
            case Prefs.LAYOUT_MASONRY:
                renderMasonry(shownList, gap, avail);
                break;
            case Prefs.LAYOUT_DRAWER:
                renderDrawer(shownList, gap, avail);
                break;
            case Prefs.LAYOUT_SPLIT:
                renderSplit(collapsePhotoGroups(list), gap, avail);
                break;
            default:
                renderGrid(shownList, 3, 16f / 9f, gap, avail);
                break;
        }
        updateSelectVisuals();
    }

    /** 按方向筛一遍：图片和方向未知的算竖屏那边，切到横屏时就不显示了 */
    private List<HistoryStore.Entry> filterList(List<HistoryStore.Entry> list, String filter) {
        if (filter == null || Prefs.DIR_ALL.equals(filter)) return list;
        List<HistoryStore.Entry> out = new ArrayList<>();
        for (HistoryStore.Entry e : list) {
            if (Prefs.DIR_HORZ.equals(filter)) {
                if ("h".equals(e.orientation)) out.add(e);
            } else {
                if (!"h".equals(e.orientation)) out.add(e);
            }
        }
        return out;
    }

    /**
     * 排序样式上方那条「竖屏 / 横屏 / 全部」切换。
     * 每一档都带上数量，一眼知道有没有东西可看。
     */
    private View dirSwitch(List<HistoryStore.Entry> all, int mode, final String cur) {
        int nv = 0, nh = 0;
        for (HistoryStore.Entry e : all) {
            if ("h".equals(e.orientation)) nh++;
            else nv++;
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(2);
        rlp.bottomMargin = dp(6);
        row.setLayoutParams(rlp);

        row.addView(dirChip(mode, Prefs.DIR_VERT, "竖屏 " + nv, Prefs.DIR_VERT.equals(cur)));
        row.addView(dirChip(mode, Prefs.DIR_HORZ, "横屏 " + nh, Prefs.DIR_HORZ.equals(cur)));
        row.addView(dirChip(mode, Prefs.DIR_ALL, "全部 " + all.size(), Prefs.DIR_ALL.equals(cur)));
        return row;
    }

    private TextView dirChip(final int mode, final String value, String text, boolean on) {
        TextView t = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(7);
        t.setLayoutParams(lp);
        t.setText(text);
        t.setTextSize(12f);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        t.setTextColor(on ? 0xFFFFFFFF : 0xFFB9BDC6);
        t.setBackgroundResource(on ? R.drawable.bg_speed_on : R.drawable.bg_chip);
        t.setPadding(dp(13), dp(6), dp(13), dp(6));
        t.setOnClickListener(v -> {
            if (value.equals(Prefs.dirFilter(this, mode))) return;
            Prefs.setDirFilter(this, mode, value);
            drawerIndex = 0;
            renderHistory();
        });
        return t;
    }

    /** 上次渲染时发现「文件已经被删掉」的记录条数 */
    private int deadCount;

    /** 记录还在、文件已经没了（在相册删掉或被清理软件清掉）的条数 */
    private int countDead(List<HistoryStore.Entry> list) {
        int n = 0;
        for (HistoryStore.Entry e : list) {
            try {
                if (!Store.exists(this, e)) n++;
            } catch (Throwable ignored) { }
        }
        return n;
    }

    /** 一键清掉那些「文件已经不在手机里」的记录，免得首页一直挂着一张打不开的缩略图 */
    private void askCleanDead() {
        new AlertDialog.Builder(this)
                .setTitle("清理失效记录")
                .setMessage("有 " + deadCount + " 条记录对应的文件已经不在手机里了——"
                        + "可能是在相册里删掉了，或者被清理软件清掉了。\n\n"
                        + "清理只会移除这些记录和缩略图，不会动其他文件。")
                .setNegativeButton("先留着", null)
                .setPositiveButton("清理 " + deadCount + " 条", (d, w) -> {
                    List<HistoryStore.Entry> all = HistoryStore.list(this);
                    List<HistoryStore.Entry> keep = new ArrayList<>();
                    for (HistoryStore.Entry e : all) {
                        boolean alive;
                        try {
                            alive = Store.exists(this, e);
                        } catch (Throwable t) {
                            alive = true;
                        }
                        if (alive) keep.add(e);
                    }
                    int gone = all.size() - keep.size();
                    HistoryStore.saveAll(this, keep);
                    renderSig = null;
                    renderHistory();
                    toast("已清理 " + gone + " 条失效记录");
                })
                .show();
    }

    /** 网格类：cols 列，hRatio 是高 / 宽（16/9 竖版，1 方形） */    private void renderGrid(List<HistoryStore.Entry> list, int cols, float hRatio, int gap, int avail) {
        cellW = (avail - gap * (cols - 1)) / cols;
        cellH = Math.round(cellW * hRatio);
        gridInto(historyBox, list, cols, cellW, cellH, gap, 0);
    }

    /** 往指定容器里铺网格：分区排列要往两个区里分别铺 */
    private void gridInto(LinearLayout box, List<HistoryStore.Entry> list, int cols,
                          int cw, int ch, int gap, int startIdx) {
        LinearLayout row = null;
        for (int i = 0; i < list.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rlp.topMargin = gap;
                row.setLayoutParams(rlp);
                box.addView(row);
            }
            View cell = buildCell(list.get(i), startIdx + i, cw, ch, STYLE_TILE);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) cell.getLayoutParams();
            lp.rightMargin = (i % cols == cols - 1) ? 0 : gap;
            row.addView(cell);
            cellViews.add(cell);
        }
    }

    /**
     * 竖横分区：上面一段全是竖屏（含图片），下面一段全是横屏。
     * 打开播放器时只把同一段里的条目传过去，所以左右滑动不会串到另一个方向。
     */
    private void renderSplit(List<HistoryStore.Entry> list, int gap, int avail) {
        splitVert = new ArrayList<>();
        splitHorz = new ArrayList<>();
        int nv = 0, nh = 0, nu = 0;
        for (HistoryStore.Entry e : list) {
            if ("photo".equals(e.kind)) { splitVert.add(e); continue; }
            if ("h".equals(e.orientation)) { splitHorz.add(e); nh++; }
            else if ("v".equals(e.orientation)) { splitVert.add(e); nv++; }
            else { splitVert.add(e); nu++; }
        }

        // ---- 上排抽屉：竖屏 ----
        if (!splitVert.isEmpty()) {
            historyBox.addView(sectionTitle("竖屏 · " + splitVert.size() + " 个 · 左右滑动"));
            int cw = Math.round(avail * 0.42f);
            int ch = Math.round(cw * 16f / 9f);
            addDrawer(splitVert, cw, ch, gap, 0);
        }

        // ---- 下排抽屉：横屏 ----
        if (!splitHorz.isEmpty()) {
            historyBox.addView(sectionTitle("横屏 · " + splitHorz.size() + " 个 · 左右滑动"));
            int cw = Math.round(avail * 0.62f);
            int ch = Math.round(cw * 9f / 16f);
            addDrawer(splitHorz, cw, ch, gap, splitVert.size());
        }

        ensureOrientations(list);
    }

    /** 一条横向抽屉：一排卡片，左右滑着翻，和「横滑抽屉」样式同一种卡片 */
    private void addDrawer(List<HistoryStore.Entry> list, int cw, int ch, int gap, int startIdx) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hs.setClipToPadding(false);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(2);
        hlp.bottomMargin = dp(6);
        historyBox.addView(hs, hlp);

        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(strip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < list.size(); i++) {
            View cell = buildDrawerCell(list.get(i), startIdx + i, cw, ch);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cw, ch);
            lp.rightMargin = gap;
            cell.setLayoutParams(lp);
            strip.addView(cell);
            cellViews.add(cell);
        }
    }

    private TextView sectionTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFF8B909A);
        tv.setTextSize(12);
        tv.setPadding(dp(4), dp(12), dp(4), dp(8));
        return tv;
    }

    /**
     * 老记录没存过方向：后台读一次视频宽高补上，然后刷一次列表把它们归位。
     * 每个文件几十毫秒，读完就不再读（方向存进历史记录里了）。
     */
    private void ensureOrientations(List<HistoryStore.Entry> list) {
        final List<HistoryStore.Entry> todo = new ArrayList<>();
        for (HistoryStore.Entry e : list) {
            if ("photo".equals(e.kind)) continue;
            if ("v".equals(e.orientation) || "h".equals(e.orientation)) continue;
            todo.add(e);
        }
        if (todo.isEmpty()) {
            return;
        }
        new Thread(() -> {
            boolean changed = false;
            for (HistoryStore.Entry e : todo) {
                if (!Store.exists(this, e)) continue;
                String o = Store.probeOrientation(this, e);
                if (o != null) {
                    HistoryStore.updateOrientation(this, e.name, e.ts, o);
                    changed = true;
                }
            }
            if (changed) runOnUiThread(this::renderHistoryIfChanged);
        }, "orient").start();
    }

    /** 单列大卡：整行宽，横屏 16:9、竖屏给方一点的高度，少裁画面 */
    private void renderWide(List<HistoryStore.Entry> list, int gap, int avail) {
        cellW = avail;
        cellH = Math.round(avail * 9f / 16f);
        for (int i = 0; i < list.size(); i++) {
            HistoryStore.Entry e = list.get(i);
            int h = cellH;
            if ("v".equals(e.orientation)) {
                h = Math.min(Math.round(avail * 1.2f),
                        Math.round(getResources().getDisplayMetrics().heightPixels * 0.52f));
            }
            View cell = buildCell(e, i, cellW, h, STYLE_WIDE);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) cell.getLayoutParams();
            lp.bottomMargin = gap;
            historyBox.addView(cell);
            cellViews.add(cell);
        }
    }

    /** 明细列表：左缩略图、右边两行文字 */
    private void renderList(List<HistoryStore.Entry> list, int gap) {
        for (int i = 0; i < list.size(); i++) {
            View row = buildListRow(list.get(i), i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = gap;
            row.setLayoutParams(lp);
            historyBox.addView(row);
            cellViews.add(row);
        }
    }

    /** 双列瀑布流：左右两列各自往下堆，图片解码后按真实比例修正高度 */
    private void renderMasonry(List<HistoryStore.Entry> list, int gap, int avail) {
        final int cols = 2;
        cellW = (avail - gap) / cols;
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        historyBox.addView(wrap);

        LinearLayout[] colViews = new LinearLayout[cols];
        for (int i = 0; i < cols; i++) {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    cellW, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = gap;
            col.setLayoutParams(lp);
            wrap.addView(col);
            colViews[i] = col;
        }
        for (int i = 0; i < list.size(); i++) {
            View cell = buildCell(list.get(i), i, cellW, Math.round(cellW * 1.3f), STYLE_FIT);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) cell.getLayoutParams();
            lp.bottomMargin = gap;
            colViews[i % cols].addView(cell);
            cellViews.add(cell);
        }
    }

    /**
     * 横滑抽屉：一张缩略图占大半屏，横向往后拉，右侧露出下一张的边；
     * 松手自动吸附到整张，下面显示 3 / 9 的页码。
     */
    private void renderDrawer(List<HistoryStore.Entry> list, int gap, int avail) {
        final int cw = Math.max(dp(120), avail - dp(26));
        int screenH = getResources().getDisplayMetrics().heightPixels;
        cellW = cw;
        if (Prefs.DIR_HORZ.equals(shownFilter)) {
            // 只看横屏时按 16:9 出画，别再用竖长卡片把画面裁掉两边
            cellH = Math.min(Math.round(cw * 9f / 16f), Math.round(screenH * 0.46f));
        } else {
            cellH = Math.min(Math.round(cw * 1.45f), Math.round(screenH * 0.58f));
        }
        final int step = cw + gap;
        final int total = list.size();

        final HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hs.setClipToPadding(false);
        historyBox.addView(hs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(strip, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < total; i++) {
            View cell = buildDrawerCell(list.get(i), i, cw, cellH);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cw, cellH);
            lp.rightMargin = gap;
            cell.setLayoutParams(lp);
            strip.addView(cell);
            cellViews.add(cell);
        }

        final TextView page = new TextView(this);
        final int start = Math.max(0, Math.min(drawerIndex, total - 1));
        page.setText((start + 1) + " / " + total);
        page.setTextColor(0xFF5E5E68);
        page.setTextSize(11f);
        page.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(9);
        page.setLayoutParams(plp);
        historyBox.addView(page);

        // 松手吸附到整张
        final boolean[] settling = new boolean[1];
        final Runnable snap = new Runnable() {
            @Override
            public void run() {
                int p = Math.round(hs.getScrollX() / (float) step);
                if (p < 0) p = 0;
                if (p > total - 1) p = total - 1;
                drawerIndex = p;
                settling[0] = true;
                hs.smoothScrollTo(p * step, 0);
                hs.postDelayed(() -> settling[0] = false, 420);
            }
        };

        // 列表重建（比如刚从播放器返回）后滚回刚才那一张，不要跳回第一张
        settling[0] = true;
        hs.post(() -> {
            hs.scrollTo(start * step, 0);
            hs.postDelayed(() -> settling[0] = false, 160);
        });

        hs.setOnScrollChangeListener((v, sx, sy, osx, osy) -> {
            int p = Math.round(sx / (float) step) + 1;
            if (p < 1) p = 1;
            if (p > total) p = total;
            drawerIndex = p - 1;
            String s = p + " / " + total;
            if (!s.contentEquals(page.getText())) page.setText(s);
            if (!settling[0]) {
                hs.removeCallbacks(snap);
                hs.postDelayed(snap, 260);
            }
        });
    }

    /** 抽屉里的一张：大图 + 底部渐变 + 标题，视频中间一颗大播放键 */
    // ---------------- 失效记录的统一外观（所有排列样式共用） ----------------

    /** 缩略图去色 + 压暗。要在缩略图 load 之后、其他浮层之前调用 */
    private void dimDead(FrameLayout cell) {
        for (int i = 0; i < cell.getChildCount(); i++) {
            View v = cell.getChildAt(i);
            if (v instanceof ImageView) {
                // 灰度滤镜挂在 ImageView 上，后面缩略图异步加载也照样是灰的
                ((ImageView) v).setColorFilter(new android.graphics.ColorMatrixColorFilter(
                        new android.graphics.ColorMatrix(new float[]{
                                0.33f, 0.59f, 0.11f, 0f, 0f,
                                0.33f, 0.59f, 0.11f, 0f, 0f,
                                0.33f, 0.59f, 0.11f, 0f, 0f,
                                0f, 0f, 0f, 1f, 0f})));
                break;
            }
        }
        View dim = new View(this);
        dim.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dim.setBackgroundColor(0x8C000000);
        dim.setClickable(false);
        cell.addView(dim);
    }

    /** 居中「已失效」标签 + 左上角红色清理键。放在最后，保证压在最上层 */
    private void deadBadge(final FrameLayout cell, final HistoryStore.Entry e) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setClickable(false);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.CENTER;
        box.setLayoutParams(blp);

        TextView tag = new TextView(this);
        tag.setText("已失效");
        tag.setTextSize(11f);
        tag.setTextColor(0xFFFFFFFF);
        tag.setTypeface(null, android.graphics.Typeface.BOLD);
        tag.setPadding(dp(10), dp(4), dp(10), dp(4));
        tag.setBackgroundResource(R.drawable.bg_dead_tag);
        box.addView(tag);

        TextView sub = new TextView(this);
        sub.setText("文件已删除");
        sub.setTextSize(10f);
        sub.setTextColor(0xFFEDEDF2);
        sub.setShadowLayer(5f, 0f, 1f, 0xCC000000);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(5);
        sub.setLayoutParams(slp);
        box.addView(sub);
        cell.addView(box);

        // 左上角垃圾桶：一眼能找到、一下就能清掉这条
        ImageView del = new ImageView(this);
        int s = dp(26);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(s, s);
        dlp.gravity = Gravity.TOP | Gravity.START;
        dlp.topMargin = dp(6);
        dlp.leftMargin = dp(6);
        del.setLayoutParams(dlp);
        del.setBackgroundResource(R.drawable.bg_del_btn);
        del.setImageResource(R.drawable.ic_trash_solid);
        del.setPadding(dp(6), dp(6), dp(6), dp(6));
        del.setContentDescription("清理这条失效记录");
        del.setOnClickListener(v -> askDeleteDead(e));
        cell.addView(del);
    }

    /** 单条失效记录：问一句再清掉 */
    private void askDeleteDead(final HistoryStore.Entry e) {
        new AlertDialog.Builder(this)
                .setTitle("清理这条失效记录？")
                .setMessage("文件已经从手机里删掉了，但首页还留着这条记录。\n\n"
                        + "清理只会移除记录和缩略图，不会删除任何文件。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清理", (d, w) -> {
                    try {
                        HistoryStore.remove(this, e);
                    } catch (Throwable ignored) { }
                    renderSig = null;
                    renderHistory();
                    toast("已清理这条失效记录");
                })
                .show();
    }

    private View buildDrawerCell(final HistoryStore.Entry e, final int index, int w, int hgt) {
        final FrameLayout cell = new FrameLayout(this);
        cell.setBackgroundResource(R.drawable.bg_media_lg);
        cell.setClipToOutline(true);

        ImageView img = new ImageView(this);
        img.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cell.addView(img);

        Uri uri = null;
        boolean dead = false;
        try {
            if (Store.exists(this, e)) uri = Store.uriFor(this, e);
            else dead = true;
        } catch (Throwable ignored) { }
        ThumbLoader.load(this, img, uri, e.kind, e.thumb);
        if (dead) dimDead(cell);

        View scrim = new View(this);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(140));
        slp.gravity = Gravity.BOTTOM;
        scrim.setLayoutParams(slp);
        scrim.setBackgroundResource(R.drawable.bg_scrim);
        cell.addView(scrim);

        if (!dead && !"photo".equals(e.kind)) {
            TextView play = new TextView(this);
            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(58), dp(58));
            plp.gravity = Gravity.CENTER;
            play.setLayoutParams(plp);
            play.setBackgroundResource(R.drawable.bg_play);
            play.setGravity(Gravity.CENTER);
            play.setText("▶");
            play.setTextSize(16f);
            play.setTextColor(0xFFFFFFFF);
            cell.addView(play);
        }

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.gravity = Gravity.BOTTOM;
        ilp.leftMargin = dp(15);
        ilp.rightMargin = dp(15);
        ilp.bottomMargin = dp(15);
        info.setLayoutParams(ilp);

        TextView t1 = new TextView(this);
        List<HistoryStore.Entry> dgrp = photoGroups.get(keyOf(e));
        String title = dgrp != null && dgrp.size() >= 2 ? stackTitle(e, dgrp)
                : displaySub(e);
        if (title.isEmpty()) title = e.name;
        t1.setText(title);
        t1.setTextColor(0xFFFFFFFF);
        t1.setTextSize(14.5f);
        t1.setTypeface(null, android.graphics.Typeface.BOLD);
        t1.setMaxLines(2);
        t1.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t1.setShadowLayer(6f, 0f, 1f, 0x99000000);
        info.addView(t1);

        StringBuilder sb = new StringBuilder();
        String size = fileSize(e);
        if (!size.isEmpty()) sb.append(size).append(" · ");
        sb.append(HistoryStore.fmtTs(e.ts));
        sb.append(" · ").append("photo".equals(e.kind) ? "图片" : "视频");
        if (!e.visible) sb.append(" · 已隐藏");

        TextView t2 = new TextView(this);
        t2.setText(sb.toString());
        t2.setTextColor(0xFFCFCFD8);
        t2.setTextSize(11f);
        t2.setSingleLine(true);
        t2.setShadowLayer(6f, 0f, 1f, 0x99000000);
        LinearLayout.LayoutParams t2lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        t2lp.topMargin = dp(4);
        t2.setLayoutParams(t2lp);
        info.addView(t2);
        cell.addView(info);

        if (dead) deadBadge(cell, e);

        TextView badge = addCheckBadge(cell);
        selSlots.add(new SelSlot(e, cell, badge, false));
        final List<HistoryStore.Entry> grp = photoGroups.get(keyOf(e));
        final boolean stacked = grp != null && grp.size() >= 2;
        if (stacked) {
            // 抽屉里的缩略图太小，叠三层看不清，改成角标标出张数。
            // 放左上角：右上角留给多选勾选角标，两者叠一起会把"✓"挡住看不清
            TextView n = new TextView(this);
            n.setText(grp.size() + " 张");
            n.setTextSize(9.5f);
            n.setTextColor(0xFF0D0D11);
            n.setBackgroundResource(R.drawable.bg_chip_on);
            n.setPadding(dp(5), dp(2), dp(5), dp(2));
            FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nlp.gravity = Gravity.TOP | Gravity.START;
            nlp.topMargin = dp(8);
            nlp.leftMargin = dp(8);
            cell.addView(n, nlp);
        }
        cell.setOnClickListener(v -> {
            if (selectMode) {
                if (stacked) togglePickedGroup(grp); else togglePicked(e);
                return;
            }
            if (stacked) {
                openPhotoGroup(grp);
                return;
            }
            drawerIndex = index;
            openDownloaded(e);
        });
        if (!stacked) attachDrag(cell, index, e);
        else cell.setOnLongClickListener(v -> { groupMenu(grp); return true; });
        return cell;
    }

    /**
     * 把同一原帖（pageUrl 相同）的图片收成一组：列表里只保留一张代表，
     * 其余并进 photoGroups，渲染时叠在它后面。不同帖子的 pageUrl 不同，
     * 所以每一组各叠各的，不会互相串。
     * 多选（批量删除）时逐条显示，方便单独勾选。
     */
    private List<HistoryStore.Entry> collapsePhotoGroups(List<HistoryStore.Entry> src) {
        photoGroups.clear();

        java.util.Map<String, List<HistoryStore.Entry>> byPage = new java.util.LinkedHashMap<>();
        List<HistoryStore.Entry> out = new ArrayList<>();
        for (HistoryStore.Entry e : src) {
            // 有原帖链接就按链接分组；从磁盘补回来的老记录没有链接，
            // 退回按「文件名前缀」分组（同帖就是 前缀_photo1..N，见 PhotoNaming）。
            // 以前只认链接，补回来的图每张都落单，看着就是"叠加卡变单张图"。
            String gk = "photo".equals(e.kind) ? PhotoNaming.groupKey(e.pageUrl, e.name) : "";
            if (gk.isEmpty()) {
                out.add(e);
                continue;
            }
            List<HistoryStore.Entry> g = byPage.get(gk);
            if (g == null) {
                g = new ArrayList<>();
                g.add(e);
                byPage.put(gk, g);
                out.add(e);            // 代表图放在最新一张的位置，时间顺序不乱
            } else {
                g.add(e);              // 同一帖的后续图片只并入组，不再单独占位
            }
        }
        for (List<HistoryStore.Entry> g : byPage.values()) {
            if (g.size() < 2) continue;
            HistoryStore.Entry rep = g.get(0);
            // 下载顺序（ts 升序）= 图1、图2、图3；叠在最上面的就是图1
            java.util.Collections.sort(g, new java.util.Comparator<HistoryStore.Entry>() {
                @Override
                public int compare(HistoryStore.Entry a, HistoryStore.Entry b) {
                    return Long.compare(a.ts, b.ts);
                }
            });
            photoGroups.put(keyOf(rep), g);
        }
        return out;
    }

    /**
     * 多图组的菜单：和单条视频一模一样的底部卡片（微信 / 其他应用 / 打开 / 原帖 /
     * 删除记录 / 删除文件），只是"删除"作用到整组。
     */
    private void groupMenu(final List<HistoryStore.Entry> g) {
        if (g == null || g.isEmpty()) return;
        final HistoryStore.Entry first = g.get(0);

        final Dialog dlg = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        final View sheet = LayoutInflater.from(this).inflate(R.layout.dialog_entry_menu, null);
        dlg.setContentView(sheet);
        dlg.setCanceledOnTouchOutside(true);

        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.66f;
            w.setAttributes(lp);
        }
        sheet.setPadding(sheet.getPaddingLeft(), sheet.getPaddingTop(),
                sheet.getPaddingRight(), dp(9) + navBarHeight());

        TextView title = sheet.findViewById(R.id.sheetTitle);
        title.setText(stackTitle(first, g) + " · 共 " + g.size() + " 张");

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

        // 整组操作：隐藏/放回整组
        TextView txtHide = sheet.findViewById(R.id.txtHide);
        TextView hintHide = sheet.findViewById(R.id.hintHide);
        ImageView iconHide = sheet.findViewById(R.id.iconHide);
        if (first.visible) {
            txtHide.setText("从相册隐藏这一组");
            hintHide.setText("相册里不再出现，App 内还能看");
            iconHide.setImageResource(R.drawable.ic_eye_off);
        } else {
            txtHide.setText("把这一组放回相册");
            hintHide.setText("相册里重新出现");
            iconHide.setImageResource(R.drawable.ic_eye);
        }

        close.setOnClickListener(v -> dismissSheet(dlg, sheet));
        pressScale(actWx);
        pressScale(actShare);
        pressScale(actPlay);
        pressScale(actPage);

        actWx.setOnClickListener(v -> { hideSheetNow(dlg); shareGroup(g, "com.tencent.mm"); });
        actMulti.setOnClickListener(v -> {
            hideSheetNow(dlg);
            // 进多选：整组先勾上；列表仍保持「一摞」，点别的叠卡也是整组加选
            selectMode = true;
            picked.clear();
            picked.addAll(g);
            renderHistory();
            toast("多选已开启：" + g.size() + " 张已勾上，可继续点选其他缩略图");
        });
        actShare.setOnClickListener(v -> { hideSheetNow(dlg); shareGroup(g, null); });
        rowHide.setOnClickListener(v -> { hideSheetNow(dlg); toggleGroupVisible(g); });
        actPlay.setOnClickListener(v -> { hideSheetNow(dlg); openPhotoGroup(g); });
        actPage.setOnClickListener(v -> {
            hideSheetNow(dlg);
            if (first.pageUrl != null && !first.pageUrl.isEmpty()) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(first.pageUrl)));
                } catch (Throwable ignored) { }
            } else {
                toast("这组图没有原帖链接");
            }
        });
        rowDelRecord.setOnClickListener(v -> {
            hideSheetNow(dlg);
            for (HistoryStore.Entry e : new ArrayList<>(g)) {
                try { HistoryStore.remove(this, e); } catch (Throwable ignored) { }
            }
            renderHistory();
            toast("已移除这一组 " + g.size() + " 条的记录，文件保留");
        });
        rowDelFile.setOnClickListener(v -> { hideSheetNow(dlg); askDeleteGroup(g); });

        dlg.show();
    }

    /** 整组分享：一次带上全部图片；pkg=微信就落到「发送给朋友」界面 */
    private void shareGroup(final List<HistoryStore.Entry> g, final String pkg) {
        final ArrayList<Uri> uris = new ArrayList<>();
        for (HistoryStore.Entry e : g) {
            try {
                if (Store.exists(this, e)) uris.add(Store.uriFor(this, e));
            } catch (Throwable ignored) { }
        }
        if (uris.isEmpty()) {
            toast("这组图片的文件都不在了");
            return;
        }
        Intent i = new Intent(Intent.ACTION_SEND_MULTIPLE);
        i.setType("image/*");
        i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        String page = g.get(0).pageUrl;
        if (page != null && !page.isEmpty()) i.putExtra(Intent.EXTRA_TEXT, page);
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
                toast("没装微信，改用系统分享");
            }
            i.setPackage(null);
        }
        try {
            startActivity(Intent.createChooser(i, "分享 " + uris.size() + " 张图片"));
        } catch (Throwable t) {
            toast("没有能接收图片的应用");
        }
    }

    /** 整组隐藏 / 放回相册（逐条搬文件，进度用个不可取消的提示框） */
    private void toggleGroupVisible(final List<HistoryStore.Entry> g) {
        final boolean to = !g.get(0).visible;
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setMessage(to ? "正在把这一组放回相册…" : "正在把这一组从相册移出…")
                .setCancelable(false)
                .create();
        dlg.show();
        new Thread(() -> {
            int ok = 0;
            for (HistoryStore.Entry e : g) {
                try {
                    if (Store.moveOne(this, e, to)) ok++;
                } catch (Throwable ignored) { }
            }
            final int done = ok;
            runOnUiThread(() -> {
                dlg.dismiss();
                renderHistory();
                if (done == g.size()) {
                    toast(to ? "已放回相册，现在相册里能看到这一组"
                            : "已隐藏，相册里不会再出现这一组");
                } else if (!to && done == 0) {
                    toast("隐藏失败：文件系统不允许 App 改动，请在相册里手动删除");
                } else {
                    toast("处理了 " + done + "/" + g.size() + " 张，其余可能被系统占用");
                }
            });
        }, "move-group").start();
    }

    /** 整组删除：文件和记录一起清（相册里的也要删干净） */
    private void askDeleteGroup(final List<HistoryStore.Entry> g) {
        new AlertDialog.Builder(this)
                .setTitle("删除这一组 " + g.size() + " 张？")
                .setMessage("这一组图片的文件和下载记录都会删掉，无法恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("全部删除", (d, w) -> {
                    for (HistoryStore.Entry e : new ArrayList<>(g)) {
                        try { HistoryStore.remove(this, e); } catch (Throwable ignored) { }
                    }
                    renderHistory();
                    toast("已删除这一组 " + g.size() + " 张");
                    new Thread(() -> {
                        for (HistoryStore.Entry e : g) deleteFileOf(e);
                    }, "del-group").start();
                })
                .show();
    }

    /** 点开一组图：PhotoViewer 左右滑着看 */
    private void openPhotoGroup(List<HistoryStore.Entry> group) {
        if (group == null || group.isEmpty()) return;
        if (group.size() == 1) { openDownloaded(group.get(0)); return; }
        StringBuilder ids = new StringBuilder();
        for (HistoryStore.Entry e : group) {
            if (ids.length() > 0) ids.append(';');
            ids.append(e.name).append('|').append(e.ts);
        }
        android.content.Intent i = new android.content.Intent(this, PhotoViewerActivity.class);
        i.putExtra(PhotoViewerActivity.E_IDS, ids.toString());
        startActivity(i);
    }

    /**
     * 叠加缩略图：最前面是图1，图2/图3 往右下错位垫在后面，
     * 一眼就能看出这条帖子是"一摞图"而不是单张。
     */
    private void addStackLayers(final FrameLayout cell, final List<HistoryStore.Entry> g) {
        int layers = Math.min(3, g.size());
        int off = dp(5);
        for (int k = layers - 1; k >= 0; k--) {      // 先垫底层，再压上层
            final HistoryStore.Entry e = g.get(k);
            ImageView iv = new ImageView(this);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            lp.leftMargin = off * k;
            lp.topMargin = off * k;
            lp.rightMargin = off * (layers - 1 - k);
            lp.bottomMargin = off * (layers - 1 - k);
            iv.setLayoutParams(lp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundResource(R.drawable.bg_media);
            iv.setClipToOutline(true);
            iv.setContentDescription("第 " + (k + 1) + " 张");
            cell.addView(iv);
            Uri u = null;
            try {
                if (Store.exists(this, e)) u = Store.uriFor(this, e);
            } catch (Throwable ignored) { }
            ThumbLoader.load(this, iv, u, e.kind, e.thumb);
        }

        TextView badge = new TextView(this);
        badge.setText(g.size() + " 张");
        badge.setTextSize(9.5f);
        badge.setTextColor(0xFF0D0D11);
        badge.setBackgroundResource(R.drawable.bg_chip_on);
        badge.setPadding(dp(5), dp(2), dp(5), dp(2));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.BOTTOM | Gravity.END;
        blp.rightMargin = dp(3);
        blp.bottomMargin = dp(3);
        cell.addView(badge, blp);
    }

    /** 叠加格子的标题：作者 · N 张图（别再显示"图片 3"这种单张编号） */
    private static String stackTitle(HistoryStore.Entry e, List<HistoryStore.Entry> g) {
        String s = e.sub == null ? "" : e.sub.trim();
        int bar = s.indexOf(" · ");
        String who = bar > 0 ? s.substring(0, bar) : s;
        if (who.isEmpty()) who = e.name;
        return who + " · " + g.size() + " 张图";
    }

    /**
     * 列表里显示的标题：新记录直接用；旧记录（{@code sub} 只剩「视频 · 无水印」这类标签）
     * 从文件名反推标题，不改动已存的数据（迁移有风险）。规则见 {@link Namer}。
     */
    private static String displaySub(HistoryStore.Entry e) {
        return Namer.displaySub(e.sub, e.name);
    }

    private View buildCell(final HistoryStore.Entry e, final int index, int w, int hgt, final int style) {
        final FrameLayout cell = new FrameLayout(this);
        cell.setLayoutParams(new LinearLayout.LayoutParams(w, hgt));
        cell.setBackgroundResource(R.drawable.bg_media);
        cell.setClipToOutline(true);

        ImageView img = new ImageView(this);
        img.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cell.addView(img);

        List<HistoryStore.Entry> grp = photoGroups.get(keyOf(e));
        final boolean stacked = grp != null && grp.size() >= 2;
        if (stacked) {
            // 一摞图：这张代表图让位给叠加层（图1 在最上面）
            img.setVisibility(View.GONE);
            addStackLayers(cell, grp);
        }

        Uri uri = null;
        boolean dead = false;
        try {
            if (Store.exists(this, e)) uri = Store.uriFor(this, e);
            else dead = true;
        } catch (Throwable ignored) { }

        if (stacked) {
            // 叠加层已经各自加载了缩略图，这里不再重复加载
        } else if (style == STYLE_FIT) {
            final int cw = cellW;
            ThumbLoader.load(this, img, uri, e.kind, e.thumb, new ThumbLoader.OnSize() {
                @Override
                public void onSize(int bw, int bh) {
                    if (bw <= 0 || bh <= 0 || cw <= 0) return;
                    int target = Math.round(cw * (float) bh / bw);
                    int min = Math.round(cw * 0.8f);
                    int max = Math.round(cw * 2.3f);
                    if (target < min) target = min;
                    if (target > max) target = max;
                    ViewGroup.LayoutParams lp = cell.getLayoutParams();
                    if (lp != null && Math.abs(lp.height - target) > dp(3)) {
                        lp.height = target;
                        cell.setLayoutParams(lp);
                    }
                }
            });
        } else {
            ThumbLoader.load(this, img, uri, e.kind, e.thumb);
        }
        if (dead) dimDead(cell);

        if (!dead && !"photo".equals(e.kind)) {
            TextView play = new TextView(this);
            int s = style == STYLE_WIDE ? dp(40) : dp(26);
            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(s, s);
            plp.gravity = Gravity.CENTER;
            play.setLayoutParams(plp);
            play.setBackgroundResource(R.drawable.bg_play);
            play.setGravity(Gravity.CENTER);
            play.setText("▶");
            play.setTextSize(style == STYLE_WIDE ? 12f : 9f);
            play.setTextColor(0xFFFFFFFF);
            cell.addView(play);
        }

        if (style == STYLE_WIDE) {
            String sub = stacked ? stackTitle(e, grp) : displaySub(e);
            if (sub.isEmpty()) sub = e.name;
            TextView t = new TextView(this);
            FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.gravity = Gravity.BOTTOM | Gravity.START;
            tlp.leftMargin = dp(8);
            tlp.bottomMargin = dp(8);
            tlp.rightMargin = dp(56);
            t.setLayoutParams(tlp);
            t.setText(sub);
            t.setTextSize(11.5f);
            t.setTextColor(0xFFFFFFFF);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setPadding(dp(8), dp(4), dp(8), dp(4));
            t.setBackgroundResource(R.drawable.bg_tag);
            cell.addView(t);
        }

        if (!e.visible) {
            TextView tag = new TextView(this);
            FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.gravity = Gravity.BOTTOM | Gravity.END;
            tlp.rightMargin = dp(6);
            tlp.bottomMargin = dp(6);
            tag.setLayoutParams(tlp);
            tag.setText("已隐藏");
            tag.setTextSize(9f);
            tag.setTextColor(0xFFFFFFFF);
            tag.setPadding(dp(4), dp(2), dp(4), dp(2));
            tag.setBackgroundResource(R.drawable.bg_tag);
            cell.addView(tag);
        }

        if (dead) deadBadge(cell, e);

        TextView badge = addCheckBadge(cell);
        selSlots.add(new SelSlot(e, cell, badge, false));
        cell.setOnClickListener(v -> {
            if (selectMode) {
                // 一组图当一个整体勾：不把 20 张拆成 20 个格子，列表保持干净
                if (stacked) togglePickedGroup(grp);
                else togglePicked(e);
                return;
            }
            if (stacked) {
                openPhotoGroup(grp);        // 一摞图 → 左右滑着看
                return;
            }
            openDownloaded(e);
        });
        // 叠加组占一个位置但底下是多条记录，拖动重排的 index 对不上，
        // 这种格子就不接拖拽了；长按留给"整组分享 / 整组删除"菜单
        if (!stacked) attachDrag(cell, index, e);
        else cell.setOnLongClickListener(v -> { groupMenu(grp); return true; });
        return cell;
    }

    /** 明细列表的一行 */
    private View buildListRow(final HistoryStore.Entry e, final int index) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(9), dp(9), dp(13), dp(9));
        row.setBackgroundResource(R.drawable.bg_card);

        ImageView img = new ImageView(this);
        int s = dp(56);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(s, s);
        ilp.rightMargin = dp(12);
        img.setLayoutParams(ilp);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackgroundResource(R.drawable.bg_media);
        img.setClipToOutline(true);
        row.addView(img);

        Uri uri = null;
        boolean dead = false;
        try {
            if (Store.exists(this, e)) uri = Store.uriFor(this, e);
            else dead = true;
        } catch (Throwable ignored) { }
        ThumbLoader.load(this, img, uri, e.kind, e.thumb);
        if (dead) {
            img.setAlpha(0.28f);
            img.setColorFilter(new android.graphics.ColorMatrixColorFilter(
                    new android.graphics.ColorMatrix(new float[]{
                            0.33f, 0.59f, 0.11f, 0f, 0f,
                            0.33f, 0.59f, 0.11f, 0f, 0f,
                            0.33f, 0.59f, 0.11f, 0f, 0f,
                            0f, 0f, 0f, 1f, 0f})));
        }

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t1 = new TextView(this);
        List<HistoryStore.Entry> lgrp = photoGroups.get(keyOf(e));
        String title = lgrp != null && lgrp.size() >= 2 ? stackTitle(e, lgrp)
                : displaySub(e);
        if (title.isEmpty()) title = e.name;
        t1.setText(title);
        t1.setTextColor(0xFFF2F2F7);
        t1.setTextSize(14f);
        t1.setSingleLine(true);
        t1.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(t1);

        StringBuilder sb = new StringBuilder(HistoryStore.fmtTs(e.ts));
        String size = fileSize(e);
        if (!size.isEmpty()) sb.append(" · ").append(size);
        sb.append(" · ").append("photo".equals(e.kind) ? "图片" : "视频");
        if (!e.visible) sb.append(" · 已隐藏");
        if (dead) sb.setLength(0);
        if (dead) sb.append("已失效 · 文件已删除 · 点这里清理");

        TextView t2 = new TextView(this);
        t2.setText(sb.toString());
        t2.setTextColor(dead ? 0xFFFF6B6B : 0xFF8E8E99);
        t2.setTextSize(11.5f);
        t2.setSingleLine(true);
        LinearLayout.LayoutParams t2lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        t2lp.topMargin = dp(3);
        t2.setLayoutParams(t2lp);
        col.addView(t2);
        row.addView(col);

        TextView chk = new TextView(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(24), dp(24));
        clp.leftMargin = dp(8);
        chk.setLayoutParams(clp);
        chk.setBackgroundResource(R.drawable.bg_check_off);
        chk.setGravity(Gravity.CENTER);
        chk.setTextColor(0xFFFFFFFF);
        chk.setTextSize(13f);
        chk.setTypeface(null, android.graphics.Typeface.BOLD);
        chk.setVisibility(View.GONE);
        row.addView(chk);
        selSlots.add(new SelSlot(e, row, chk, true));

        final List<HistoryStore.Entry> grp = photoGroups.get(keyOf(e));
        final boolean stacked = grp != null && grp.size() >= 2;
        if (stacked) {
            // 列表模式空间够：直接标出「N 张 · 点开左右滑」
            TextView n = new TextView(this);
            n.setText(grp.size() + " 张");
            n.setTextSize(10f);
            n.setTextColor(0xFF0D0D11);
            n.setBackgroundResource(R.drawable.bg_chip_on);
            n.setPadding(dp(6), dp(2), dp(6), dp(2));
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nlp.leftMargin = dp(6);
            row.addView(n, nlp);
        }
        row.setOnClickListener(v -> {
            if (selectMode) {
                if (stacked) togglePickedGroup(grp); else togglePicked(e);
                return;
            }
            if (stacked) {
                openPhotoGroup(grp);
                return;
            }
            openDownloaded(e);
        });
        if (!stacked) attachDrag(row, index, e);
        else row.setOnLongClickListener(v -> { groupMenu(grp); return true; });
        return row;
    }

    private String fileSize(HistoryStore.Entry e) {
        try {
            java.io.File f = e.visible
                    ? Store.publicFile(e.kind, e.name)
                    : Store.privateFile(this, e.kind, e.name);
            if (f == null || !f.exists()) return "";
            long len = f.length();
            if (len < 1024) return len + " B";
            if (len < 1024 * 1024) return (len / 1024) + " KB";
            return String.format(java.util.Locale.CHINA, "%.1f MB", len / 1024f / 1024f);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 长按 350ms 进入拖动。
     * 松手时：动过 = 按落点重排；没动 = 弹出菜单（分享/隐藏/删除）。
     */
    private void attachDrag(final View cell, final int index, final HistoryStore.Entry e) {
        cell.setOnTouchListener(new View.OnTouchListener() {
            final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            final Runnable starter = () -> beginDrag(cell, index);
            float downX, downY;
            boolean moved;

            @Override
            public boolean onTouch(View v, android.view.MotionEvent ev) {
                // 多选状态下不接管触摸：交给普通点击去勾选，长按也不会误拖动
                if (selectMode) return false;
                switch (ev.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downX = ev.getRawX();
                        downY = ev.getRawY();
                        moved = false;
                        dragMoved = false;
                        h.postDelayed(starter, 350);
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        if (dragIndex >= 0) {
                            if (Math.abs(ev.getRawX() - downX) > dp(10)
                                    || Math.abs(ev.getRawY() - downY) > dp(10)) {
                                dragMoved = true;
                            }
                            moveGhost(ev.getRawX(), ev.getRawY());
                            return true;
                        }
                        if (Math.abs(ev.getRawX() - downX) > dp(8)
                                || Math.abs(ev.getRawY() - downY) > dp(8)) {
                            h.removeCallbacks(starter);
                            moved = true;
                            // 还没起拖就想滑动：把事件还给外层滚动容器。
                            // 以前这里继续 return true 吞掉事件，长按等待期间手指一抖，
                            // 既不进入拖动、也滚不动列表，表现就是"这次没拖成"。
                            return false;
                        }
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                        h.removeCallbacks(starter);
                        if (dragIndex >= 0) {
                            if (dragMoved) {
                                endDrag(ev.getRawX(), ev.getRawY());
                            } else {
                                cancelDrag();
                                entryMenu(e);
                            }
                            return true;
                        }
                        if (!moved) v.performClick();
                        return true;
                    case android.view.MotionEvent.ACTION_CANCEL:
                        h.removeCallbacks(starter);
                        if (dragIndex >= 0) cancelDrag();
                        return true;
                }
                return false;
            }
        });
    }

    private void beginDrag(View cell, int index) {
        if (dragIndex >= 0 || cell.getWidth() <= 0) return;
        dragIndex = index;
        int w = cell.getWidth();
        int hgt = cell.getHeight();
        Bitmap bmp = Bitmap.createBitmap(w, hgt, Bitmap.Config.ARGB_8888);
        cell.draw(new Canvas(bmp));

        ghost = new ImageView(this);
        ghost.setImageBitmap(bmp);
        ghost.setAlpha(0.92f);
        ViewGroup root = findViewById(android.R.id.content);
        root.addView(ghost, new FrameLayout.LayoutParams(w, hgt));
        root.getLocationOnScreen(rootLoc);

        int[] loc = new int[2];
        cell.getLocationOnScreen(loc);
        ghost.setX(loc[0] - rootLoc[0]);
        ghost.setY(loc[1] - rootLoc[1]);

        cell.setAlpha(0.3f);
        cell.getParent().requestDisallowInterceptTouchEvent(true);
        cell.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        if (!dragHintShown) {
            dragHintShown = true;
            toast("拖动可调整顺序 · 原地松手打开菜单");
        }
    }

    private void moveGhost(float rawX, float rawY) {
        if (ghost == null) return;
        ghost.setX(rawX - rootLoc[0] - ghost.getWidth() / 2f);
        ghost.setY(rawY - rootLoc[1] - ghost.getHeight() / 2f);
        // 拖动中实时高亮落点格：松手会落在哪一目了然，不再是"放了才知道"
        int t = indexAt(rawX, rawY);
        if (t != lastDropHint) {
            restoreDropHint();
            if (t >= 0 && t != dragIndex) {
                View v = cellViews.get(t);
                v.setPivotX(v.getWidth() / 2f);
                v.setPivotY(v.getHeight() / 2f);
                v.animate().scaleX(0.93f).scaleY(0.93f).setDuration(70).start();
                lastDropHint = t;
            }
        }
    }

    private void restoreDropHint() {
        if (lastDropHint >= 0 && lastDropHint < cellViews.size()) {
            View v = cellViews.get(lastDropHint);
            v.animate().scaleX(1f).scaleY(1f).setDuration(70).start();
        }
        lastDropHint = -1;
    }

    private void endDrag(float rawX, float rawY) {
        int from = dragIndex;
        int target = indexAt(rawX, rawY);
        cancelDrag();
        if (from < 0) return;
        if (shownList == null || from >= shownList.size()
                || target < 0 || target >= shownList.size() || target == from) {
            renderHistory();
            return;
        }
        // ★ 格子 index 是「方向筛选 + 多图折叠后」的 shownList 的 index，
        //   HistoryStore 存的是全量列表 —— 必须先映射回真实位置再动。
        //   以前直接拿格子 index 删全量列表：列表里有多图组、或开着方向筛选时
        //   必错位（拖 A 实际动 B），这就是"要拖好几次才对一次"的根因。
        List<HistoryStore.Entry> all = HistoryStore.list(this);
        int fromPos = posInAll(all, shownList.get(from));
        int toPos = posInAll(all, shownList.get(target));
        if (fromPos < 0 || toPos < 0) {
            renderHistory();
            return;
        }
        HistoryStore.Entry moved = all.remove(fromPos);
        if (fromPos < toPos) toPos--;       // 先删后插，目标在后面要往前挪一位
        all.add(Math.max(0, Math.min(toPos, all.size())), moved);
        HistoryStore.saveAll(this, all);
        renderHistory();
        toast("顺序已保存");
    }

    /** 按 key 在全量列表里找真实位置（HistoryStore.list 每次反序列化都是新对象） */
    private int posInAll(List<HistoryStore.Entry> all, HistoryStore.Entry e) {
        String k = keyOf(e);
        for (int i = 0; i < all.size(); i++) {
            if (keyOf(all.get(i)).equals(k)) return i;
        }
        return -1;
    }

    private void cancelDrag() {
        restoreDropHint();
        if (dragIndex >= 0 && dragIndex < cellViews.size()) {
            View v = cellViews.get(dragIndex);
            v.setAlpha(1f);
            try { v.getParent().requestDisallowInterceptTouchEvent(false); } catch (Throwable ignored) { }
        }
        if (ghost != null) {
            ViewGroup parent = (ViewGroup) ghost.getParent();
            if (parent != null) parent.removeView(ghost);
            ghost = null;
        }
        dragIndex = -1;
    }

    private int indexAt(float rawX, float rawY) {
        int[] loc = new int[2];
        int best = -1;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < cellViews.size(); i++) {
            View v = cellViews.get(i);
            if (v.getWidth() == 0) continue;
            v.getLocationOnScreen(loc);
            if (rawX >= loc[0] && rawX <= loc[0] + v.getWidth()
                    && rawY >= loc[1] && rawY <= loc[1] + v.getHeight()) {
                return i;
            }
            // 指尖落在格子之间的缝隙上（格子只有 7dp 间距，正好压缝很常见）：
            // 吸附到中心最近的格子，不再判成"没落在任何格子上"导致松手恢复原状
            double cx = loc[0] + v.getWidth() / 2.0;
            double cy = loc[1] + v.getHeight() / 2.0;
            double d = (rawX - cx) * (rawX - cx) + (rawY - cy) * (rawY - cy);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    // ---------------- 多选：批量分享 / 批量删除 ----------------

    private static String keyOf(HistoryStore.Entry e) {
        return (e.name == null ? "" : e.name) + "|" + e.ts;
    }

    private boolean isPicked(HistoryStore.Entry e) {
        String k = keyOf(e);
        for (int i = 0; i < picked.size(); i++) {
            if (keyOf(picked.get(i)).equals(k)) return true;
        }
        return false;
    }

    /** 从长按菜单点「多选」进来，顺手把当前这条选上 */
    private void enterSelectMode(HistoryStore.Entry first) {
        selectMode = true;
        picked.clear();
        if (first != null) picked.add(first);
        updateSelectVisuals();
        updateSelectBar();
        toast("多选已开启：点缩略图勾选，底部可批量分享或删除");
    }

    private void exitSelectMode() {
        selectMode = false;
        picked.clear();
        updateSelectVisuals();
        updateSelectBar();
    }

    private void togglePicked(HistoryStore.Entry e) {
        String k = keyOf(e);
        for (int i = 0; i < picked.size(); i++) {
            if (keyOf(picked.get(i)).equals(k)) {
                picked.remove(i);
                updateSelectVisuals();
                updateSelectBar();
                return;
            }
        }
        picked.add(e);
        updateSelectVisuals();
        updateSelectBar();
    }

    /** 整组勾选 / 取消：一组图当一个整体，底部批量分享/删除也是整组走 */
    private void togglePickedGroup(List<HistoryStore.Entry> g) {
        boolean all = true;
        for (HistoryStore.Entry e : g) {
            if (!isPicked(e)) { all = false; break; }
        }
        if (all) {
            for (int i = picked.size() - 1; i >= 0; i--) {
                if (contains(g, picked.get(i))) picked.remove(i);
            }
        } else {
            for (HistoryStore.Entry e : g) {
                if (!isPicked(e)) picked.add(e);
            }
        }
        updateSelectVisuals();
        updateSelectBar();
    }

    /** 全选 / 再点一次取消全选 */
    private void selectAll() {
        List<HistoryStore.Entry> all = HistoryStore.list(this);
        if (all.isEmpty()) return;
        if (picked.size() == all.size()) {
            picked.clear();
            toast("已取消全选");
        } else {
            picked.clear();
            picked.addAll(all);
            toast("已全选 " + all.size() + " 项");
        }
        updateSelectVisuals();
        updateSelectBar();
    }

    /** 只改角标和行底色，不动视图树——这是"多选不闪"的关键 */
    private void updateSelectVisuals() {
        for (SelSlot s : selSlots) {
            boolean on = selectMode && isPicked(s.entry);
            if (s.badge != null) {
                s.badge.setVisibility(selectMode ? View.VISIBLE : View.GONE);
                s.badge.setBackgroundResource(on ? R.drawable.bg_check_on : R.drawable.bg_check_off);
                s.badge.setText(on ? "✓" : "");
            }
            if (s.row) {
                s.cell.setBackgroundResource(on ? R.drawable.bg_card_sel : R.drawable.bg_card);
            }
        }
    }

    private void updateSelectBar() {
        if (selectBar == null) return;
        selectBar.setVisibility(selectMode ? View.VISIBLE : View.GONE);
        if (tvSelCount != null) {
            // 一摞多图会被整组勾进来，直接报"项数"会变成 20 项，看不出是 1 组，这里分开数
            int nPhoto = 0, nVideo = 0;
            for (HistoryStore.Entry e : picked) {
                if ("photo".equals(e.kind)) nPhoto++; else nVideo++;
            }
            if (nPhoto == 0 && nVideo == 0) {
                tvSelCount.setText("未选择 · 点缩略图勾选");
            } else if (nVideo == 0) {
                tvSelCount.setText("已选 " + nPhoto + " 张图");
            } else if (nPhoto == 0) {
                tvSelCount.setText("已选 " + nVideo + " 个视频");
            } else {
                tvSelCount.setText("已选 " + nVideo + " 个视频 · " + nPhoto + " 张图");
            }
        }
    }

    /** 勾选角标：一直存在，非多选时隐藏，切换多选就不用重建列表了 */
    private TextView addCheckBadge(FrameLayout cell) {
        TextView t = new TextView(this);
        int s = dp(24);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(s, s);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.topMargin = dp(6);
        lp.rightMargin = dp(6);
        t.setLayoutParams(lp);
        t.setBackgroundResource(R.drawable.bg_check_off);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(13f);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setVisibility(View.GONE);
        cell.addView(t);
        return t;
    }

    /** 批量分享：多个文件一起发给微信，发不了就弹系统面板 */
    private void sharePicked() {
        if (picked.isEmpty()) {
            toast("先勾选要分享的文件");
            return;
        }
        ArrayList<Uri> uris = new ArrayList<>();
        boolean anyVideo = false, anyPhoto = false;
        for (HistoryStore.Entry e : picked) {
            try {
                if (!Store.exists(this, e)) continue;
                uris.add(Store.uriFor(this, e));
                if ("photo".equals(e.kind)) anyPhoto = true;
                else anyVideo = true;
            } catch (Throwable ignored) { }
        }
        if (uris.isEmpty()) {
            toast("选中的文件都不在了");
            return;
        }
        String mime = (anyVideo && anyPhoto) ? "*/*" : (anyPhoto ? "image/*" : "video/*");
        ClipData clip = null;
        for (Uri u : uris) {
            if (clip == null) clip = ClipData.newRawUri("DownX", u);
            else clip.addItem(new ClipData.Item(u));
        }

        Intent i = new Intent(Intent.ACTION_SEND_MULTIPLE);
        i.setType(mime);
        i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        i.setClipData(clip);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        // 注意：不能用 ShareImgUI 直发——多个文件时微信会直接发进最近的聊天，容易误发。
        // 先试微信自己的多文件分享入口，失败再弹系统面板。
        try {
            i.setPackage("com.tencent.mm");
            startActivity(i);
            return;
        } catch (Throwable ignored) { }

        Intent c = new Intent(Intent.ACTION_SEND_MULTIPLE);
        c.setType(mime);
        c.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        c.setClipData(clip);
        c.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(c, "分享 " + uris.size() + " 个文件"));
        } catch (Throwable t) {
            toast("没有可用的分享应用");
        }
    }

    /** 批量删除：withFile=true 连文件一起删，真正释放空间 */
    private void deletePicked(final boolean withFile) {
        if (picked.isEmpty()) {
            toast("先勾选要删除的内容");
            return;
        }
        final int n = picked.size();
        String msg = withFile
                ? "选中的 " + n + " 个文件会被彻底删除，连同下载记录一起，释放占用的存储空间，删除后无法恢复。"
                : "只把选中的 " + n + " 条记录从 DownX 列表里移除，视频文件仍留在手机上，不会释放空间。";
        new AlertDialog.Builder(this)
                .setTitle(withFile ? "批量删除文件" : "批量删除记录")
                .setMessage(msg)
                .setNegativeButton("取消", null)
                .setPositiveButton(withFile ? "彻底删除" : "删除记录", (d, w) -> {
                    final List<HistoryStore.Entry> copy = new ArrayList<>(picked);

                    // 先在界面线程把记录抹掉、立刻重建列表：缩略图马上消失，不用等文件删完。
                    // 之前是同步删文件（240MB 的视频要好几秒），界面就卡在那儿像"没反应"。
                    List<HistoryStore.Entry> all = HistoryStore.list(this);
                    List<HistoryStore.Entry> keep = new ArrayList<>();
                    for (HistoryStore.Entry x : all) {
                        boolean hit = false;
                        for (HistoryStore.Entry e : copy) {
                            if (keyOf(x).equals(keyOf(e))) { hit = true; break; }
                        }
                        if (!hit) keep.add(x);
                    }
                    HistoryStore.saveAll(this, keep);
                    exitSelectMode();
                    renderHistory();

                    toast(withFile ? ("已删除 " + n + " 项") : ("已移除 " + n + " 条记录，文件保留"));
                    if (!withFile) return;

                    // 文件在后台慢慢删，删不掉再提示
                    new Thread(() -> {
                        int fail = 0;
                        for (HistoryStore.Entry e : copy) {
                            if (!deleteFileOf(e)) fail++;
                        }
                        if (fail > 0) {
                            final int f = fail;
                            runOnUiThread(() -> toast("有 " + f
                                    + " 个文件系统不允许删除，请到相册里手动删除"));
                        }
                    }, "del").start();
                })
                .show();
    }

    /** 删掉一条记录对应的文件（相册里的或 App 私有的） */
    private boolean deleteFileOf(HistoryStore.Entry e) {
        boolean ok = false;
        try {
            // 1) 有 MediaStore uri 就按 uri 删，系统会连记录一起清掉
            if (e.uri != null && e.uri.startsWith("content://")) {
                try {
                    if (getContentResolver().delete(Uri.parse(e.uri), null, null) > 0) ok = true;
                } catch (Throwable ignored) { }
            }
            if (e.visible) {
                // 2) 相册目录：老记录可能没有 uri、或 uri 已失效，按文件名再删一遍
                if (Store.removePublic(this, e)) ok = true;
            } else {
                // 3) 私有目录：直接删文件
                java.io.File f = Store.privateFile(this, e.kind, e.name);
                if (f == null || !f.exists() || f.delete()) ok = true;
            }
        } catch (Throwable ignored) { }
        return ok;
    }

    /** 按住缩略图松手后弹出的底部卡片菜单：上面四个彩色快捷操作，下面三条带说明的列表 */
    private void entryMenu(final HistoryStore.Entry e) {
        final Dialog dlg = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        final View sheet = LayoutInflater.from(this).inflate(R.layout.dialog_entry_menu, null);
        dlg.setContentView(sheet);
        dlg.setCanceledOnTouchOutside(true);

        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.66f;
            w.setAttributes(lp);
        }
        // 避开底部导航条
        sheet.setPadding(sheet.getPaddingLeft(), sheet.getPaddingTop(),
                sheet.getPaddingRight(), dp(9) + navBarHeight());

        // 标题显示这是哪一条文件
        TextView title = sheet.findViewById(R.id.sheetTitle);
        String nm = e.name == null ? "" : e.name;
        if (nm.isEmpty()) nm = "video".equals(e.kind) ? "已下载的视频" : "已下载的图片";
        title.setText(nm);

        // 入场上滑 + 轻微放大，看起来更"弹"
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

        // 隐藏 / 放回：文案和图标跟着当前状态走
        TextView txtHide = sheet.findViewById(R.id.txtHide);
        TextView hintHide = sheet.findViewById(R.id.hintHide);
        ImageView iconHide = sheet.findViewById(R.id.iconHide);
        if (e.visible) {
            txtHide.setText("从相册隐藏这一条");
            hintHide.setText("相册里不再出现");
            iconHide.setImageResource(R.drawable.ic_eye_off);
        } else {
            txtHide.setText("放回相册");
            hintHide.setText("相册里重新出现");
            iconHide.setImageResource(R.drawable.ic_eye);
        }

        close.setOnClickListener(v -> dismissSheet(dlg, sheet));
        pressScale(actWx);
        pressScale(actShare);
        pressScale(actPlay);
        pressScale(actPage);
        pressScale(actMulti);

        actMulti.setOnClickListener(v -> {
            hideSheetNow(dlg);
            enterSelectMode(e);
        });

        actWx.setOnClickListener(v -> {
            hideSheetNow(dlg);
            shareTo(e, "com.tencent.mm");
        });
        actShare.setOnClickListener(v -> {
            hideSheetNow(dlg);
            shareTo(e, null);
        });
        actPlay.setOnClickListener(v -> {
            hideSheetNow(dlg);
            openDownloaded(e);
        });
        actPage.setOnClickListener(v -> {
            hideSheetNow(dlg);
            if (e.pageUrl != null && !e.pageUrl.isEmpty()) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(e.pageUrl)));
                } catch (Throwable ignored) { }
            } else {
                toast("这条没有原帖链接");
            }
        });
        rowHide.setOnClickListener(v -> {
            hideSheetNow(dlg);
            toggleVisible(e);
        });
        rowDelRecord.setOnClickListener(v -> {
            hideSheetNow(dlg);
            removeRecordOnly(e);
        });
        rowDelFile.setOnClickListener(v -> {
            hideSheetNow(dlg);
            deleteEntry(e);
        });

        dlg.show();
    }

    /** 点取消：等收起动画播完再关 */
    private void dismissSheet(final Dialog dlg, final View sheet) {
        sheet.animate().alpha(0f).translationY(dp(70)).scaleX(0.96f).scaleY(0.96f)
                .setDuration(180)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .start();
        sheet.postDelayed(() -> {
            try { dlg.dismiss(); } catch (Throwable ignored) { }
        }, 185);
    }

    /** 点了具体操作：立刻收起并发起动作，衔接更利落 */
    private void hideSheetNow(final Dialog dlg) {
        try { dlg.dismiss(); } catch (Throwable ignored) { }
    }

    /** 按下缩一下，松开弹回，手感更"实体" */
    private void pressScale(final View v) {
        v.setOnTouchListener((view, ev) -> {
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                view.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).start();
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
            }
            return false;
        });
    }

    private int navBarHeight() {
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    /**
     * 把文件分享出去。pkg 传 "com.tencent.mm" 就是直接调起微信的发送界面；
     * 传 null 则弹系统分享面板。
     */
    private void shareTo(final HistoryStore.Entry e, final String pkg) {
        if (!Store.exists(this, e)) {
            toast("文件不存在，可能已被删除");
            return;
        }
        Uri uri;
        try {
            uri = Store.uriFor(this, e);
        } catch (Throwable t) {
            toast("读取文件失败");
            return;
        }
        String mime = Store.mime(e.name, e.kind);
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(mime);
        i.putExtra(Intent.EXTRA_STREAM, uri);
        if (e.pageUrl != null && !e.pageUrl.isEmpty()) {
            i.putExtra(Intent.EXTRA_TEXT, e.pageUrl);
        }
        // 让接收方（微信等）真的能读到 content:// 内容
        android.content.ClipData clip = android.content.ClipData.newRawUri("DownX", uri);
        i.setClipData(clip);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        if (pkg != null) {
            // 微信「发送给朋友」页直接定位，不再弹"微信状态/收藏"选择
            try {
                i.setClassName("com.tencent.mm", "com.tencent.mm.ui.tools.ShareImgUI");
                startActivity(i);
                return;
            } catch (Throwable ignored) { }
            try {
                i.setPackage(pkg);
                startActivity(i);
                return;
            } catch (Throwable notFound) { }
            toast("没能直接唤起微信，改用系统分享");
        }

        Intent c = new Intent(Intent.ACTION_SEND);
        c.setType(mime);
        c.putExtra(Intent.EXTRA_STREAM, uri);
        if (e.pageUrl != null && !e.pageUrl.isEmpty()) c.putExtra(Intent.EXTRA_TEXT, e.pageUrl);
        c.setClipData(clip);
        c.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(c, "分享到"));
        } catch (Throwable t) {
            toast("没有可用的分享应用");
        }
    }
    /** 单条在「相册可见」和「隐藏」之间移动，其余文件不动 */
    private void toggleVisible(final HistoryStore.Entry e) {
        final boolean to = !e.visible;
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setMessage(to ? "正在放回相册…" : "正在从相册移出…")
                .setCancelable(false)
                .create();
        dlg.show();
        new Thread(() -> {
            final boolean ok = Store.moveOne(this, e, to);
            runOnUiThread(() -> {
                dlg.dismiss();
                if (ok) {
                    renderHistory();
                    toast(to ? "已放回相册，现在相册里能看到" : "已隐藏，相册里不会再出现");
                } else {
                    toast(to
                            ? "放回相册失败，可能是存储空间不足"
                            : "隐藏失败：这个文件系统不允许 App 改动，请在相册里手动删除");
                }
            });
        }, "moveOne").start();
    }

    /** 只把记录从列表里去掉，视频文件原封不动留在手机上 */
    private void removeRecordOnly(final HistoryStore.Entry e) {
        String tip = e.visible
                ? "只从 DownX 的下载列表里移除这条记录。\n视频文件仍留在相册（Movies/DownX），不会占用额外空间。"
                : "只从 DownX 的下载列表里移除这条记录。\n视频文件仍留在 App 私有目录，卸载 DownX 时会一起被系统删除。";
        new AlertDialog.Builder(this)
                .setTitle("删除这条记录")
                .setMessage(tip)
                .setNegativeButton("取消", null)
                .setPositiveButton("只删记录", (d, w) -> {
                    HistoryStore.remove(this, e);
                    renderHistory();
                    toast("已从列表移除，文件还在手机里");
                })
                .show();
    }

    /** 记录 + 文件一起删掉，真正释放存储空间 */
    private void deleteEntry(final HistoryStore.Entry e) {
        new AlertDialog.Builder(this)
                .setTitle("删除这条记录及文件")
                .setMessage("视频文件和下载记录都会被删除，释放占用的存储空间，删除后无法恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("彻底删除", (d, w) -> {
                    // 记录先撤掉，列表立刻更新；文件丢后台删，别让界面等它
                    HistoryStore.remove(this, e);
                    renderHistory();
                    toast("已彻底删除");
                    new Thread(() -> {
                        if (!deleteFileOf(e)) {
                            runOnUiThread(() -> toast("文件没能删掉，请到相册里手动删除"));
                        }
                    }, "del").start();
                })
                .show();
    }

    private void openDownloaded(HistoryStore.Entry e) {
        if (!Store.exists(this, e)) {
            // 文件已经被删了：直接给个清理入口，别让人点了没反应
            askDeleteDead(e);
            return;
        }
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra(PlayerActivity.E_NAME, e.name);
        i.putExtra(PlayerActivity.E_KIND, e.kind);
        i.putExtra(PlayerActivity.E_TS, e.ts);
        // 竖横分区排列：只把同一方向的条目传过去，左右滑不会串到另一个方向
        String ids = sameGroupIds(e);
        if (ids != null) i.putExtra(PlayerActivity.E_PLAYLIST, ids);
        try {
            startActivity(i);
        } catch (Throwable ex) {
            toast("打不开这个文件");
        }
    }

    /**
     * 播放器左右滑的清单：
     * 「竖横分区」按这条所在的那一段；其他样式按当前方向筛选的结果（只看竖屏就只在竖屏里滑）。
     */
    private String sameGroupIds(HistoryStore.Entry e) {
        if (Prefs.layoutMode(this) == Prefs.LAYOUT_SPLIT) {
            List<HistoryStore.Entry> g = null;
            if (contains(splitVert, e)) g = splitVert;
            else if (contains(splitHorz, e)) g = splitHorz;
            if (g == null || g.size() < 2) return null;
            return idsOf(g);
        }
        if (!Prefs.DIR_ALL.equals(shownFilter) && shownList.size() >= 2
                && contains(shownList, e)) {
            return idsOf(shownList);
        }
        return null;
    }

    private static String idsOf(List<HistoryStore.Entry> g) {
        StringBuilder sb = new StringBuilder();
        for (HistoryStore.Entry x : g) {
            if (sb.length() > 0) sb.append(';');
            sb.append(x.name).append('|').append(x.ts);
        }
        return sb.toString();
    }

    private static boolean contains(List<HistoryStore.Entry> list, HistoryStore.Entry e) {
        if (list == null || e == null) return false;
        String k = keyOf(e);
        for (HistoryStore.Entry x : list) {
            if (keyOf(x).equals(k)) return true;
        }
        return false;
    }

    private void openPage() {
        if (result == null || result.tweetUrl == null) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(result.tweetUrl)));
        } catch (Throwable ignored) { }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
