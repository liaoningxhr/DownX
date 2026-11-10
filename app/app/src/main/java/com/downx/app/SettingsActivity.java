package com.downx.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/** 设置：应用锁（二级页）、相册可见、B 站登录、支持作者、使用说明 */
public class SettingsActivity extends Activity {

    private Switch swGallery;
    private TextView tvLockState, tvBiliState, tvIgState, tvGalleryHint, tvFilesState;
    private TextView tvBiliDesc, tvIgDesc;
    private boolean binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        swGallery = findViewById(R.id.swGallery);
        tvLockState = findViewById(R.id.tvLockState);
        tvBiliState = findViewById(R.id.tvBiliState);
        tvIgState = findViewById(R.id.tvIgState);
        tvGalleryHint = findViewById(R.id.tvGalleryHint);
        tvFilesState = findViewById(R.id.tvFilesState);
        tvBiliDesc = findViewById(R.id.tvBiliDesc);
        tvIgDesc = findViewById(R.id.tvIgDesc);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.rowLock).setOnClickListener(v ->
                startActivity(new Intent(this, LockSettingsActivity.class)));
        findViewById(R.id.rowFiles).setOnClickListener(v ->
                startActivity(new Intent(this, FilesActivity.class)));
        findViewById(R.id.rowRescan).setOnClickListener(v -> confirmRescan());

        findViewById(R.id.rowHelp).setOnClickListener(v ->
                startActivity(new Intent(this, HelpActivity.class)));

        // 版本号动态取，以后升级不用再改布局
        try {
            android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            ((android.widget.TextView) findViewById(R.id.tvVerFoot)).setText(
                    "DownX " + pi.versionName + " · 作者 徐徐道来 · 无广告 · 无跟踪");
        } catch (Throwable ignored) { }

        findViewById(R.id.rowBili).setOnClickListener(v -> {
            if (BiliSession.has(this)) {
                new AlertDialog.Builder(this)
                        .setTitle("B 站已登录")
                        .setItems(new String[]{"重新登录", "退出登录"}, (d, w) -> {
                            if (w == 1) {
                                BiliSession.clear(this);
                                refresh();
                                toast("已退出 B 站，下载会回到 720P");
                            } else {
                                startActivity(new Intent(this, BiliLoginActivity.class));
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            } else {
                startActivity(new Intent(this, BiliLoginActivity.class));
            }
        });

        findViewById(R.id.rowIg).setOnClickListener(v -> {
            // 失效状态直接进导入页，别再让用户多点一层
            if (IgSession.has(this) && !IgSession.expired(this)) {
                new AlertDialog.Builder(this)
                        .setTitle("Instagram 已登录")
                        .setItems(new String[]{"重新登录", "退出登录"}, (d, w) -> {
                            if (w == 1) {
                                IgSession.clear(this);
                                refresh();
                                toast("已退出 Instagram：公开帖仍能下，私密内容需要登录");
                            } else {
                                startActivity(new Intent(this, IgLoginActivity.class));
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            } else {
                startActivity(new Intent(this, IgLoginActivity.class));
            }
        });

        findViewById(R.id.rowDonate).setOnClickListener(v -> showDonate());

        swGallery.setOnCheckedChangeListener((b, on) -> {
            if (binding) return;
            onGalleryToggle(on);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    // ---------------- 打赏气泡 ----------------
    // 2026-11-09：去掉了原来的无限循环 ValueAnimator（呼吸光晕）。原因：
    // · 无限动画会让界面永远不进入 idle 状态，无障碍/自动化工具（uiautomator）都会超时；
    // · 设置页挂着循环动画纯耗电，还多一条"忘记 cancel 就泄漏"的隐患；
    // · 视觉收益很小，静态渐变胶囊反而更稳。光晕已经在 bg_donate_bubble 里做死了。

    private void refresh() {
        binding = true;
        swGallery.setChecked(Prefs.galleryVisible(this));
        binding = false;

        boolean on = Prefs.lockEnabled(this) && Prefs.hasPin(this);
        tvLockState.setText(!on ? "未开启"
                : Prefs.bioEnabled(this) && Bio.available(this) ? "已开启 · 密码/指纹" : "已开启 · 密码");

        tvGalleryHint.setText(galleryHint());

        // 登录态：胶囊只写「已登录 / 未登录」，差别写在下面那行，一眼能看懂为什么值得登
        boolean biliOn = BiliSession.has(this);
        setChip(tvBiliState, biliOn, "已登录", "未登录");
        tvBiliDesc.setText(biliOn
                ? "已登录：能取到账号权限内的清晰度，1080P / 大会员 4K 原画"
                : "不登录：最高 720P；登录后：1080P / 大会员 4K 原画");

        // IG：登录态分三种——没登录 / 已登录 / 服务端说失效了（要重新导入）
        boolean igOn = IgSession.has(this);
        boolean igBad = IgSession.expired(this);
        if (igBad) {
            tvIgState.setText("已失效");
            tvIgState.setBackgroundResource(R.drawable.bg_chip);
            tvIgState.setTextColor(getResources().getColor(R.color.warn));
        } else {
            setChip(tvIgState, igOn, "已登录", "未登录");
        }
        tvIgDesc.setText(!igOn
                ? "不登录：公开帖直接能下；登录后：多档清晰度 + 私密内容"
                : igBad
                ? "登录已失效，点这里重新登录（直接输账号密码）"
                : "已登录：可列出多档清晰度，私密 / 受限内容也能试");

        int[] st = Store.stats(this);
        tvFilesState.setText(st[0] == 0 ? "暂无文件"
                : (st[1] == 0 ? "全部可见" : st[1] + " 个已隐藏"));
    }

    private String galleryHint() {
        int[] st = Store.stats(this);
        boolean visible = Prefs.galleryVisible(this);
        String head = visible
                ? "新下载的文件存进相册，相册里能看到"
                : "新下载的文件只在本 App 内可见，相册看不到";
        if (st[0] > 0) {
            head += "\n已有 " + st[0] + " 个文件，其中 " + st[1] + " 个处于隐藏状态";
        }
        if (!visible) {
            // 用户最怕"卸载了文件没了"，这里直接说清楚存在哪、会不会被删
            head += Store.mediaRoot(this) != null
                    ? "\n卸载 App 也不会删：隐藏文件存在 Android/media/"
                        + getPackageName() + "（重装后会自动找回）"
                    : "\n注意：当前系统（Android 10 及以下）隐藏文件只能存在 App 私有目录，卸载会一起删";
        }
        head += "\n只想处理某一个：进「逐条管理文件」单独开关";
        return head;
    }

    // ---------------- 相册可见 ----------------

    private void onGalleryToggle(final boolean on) {
        Prefs.setGalleryVisible(this, on);
        tvGalleryHint.setText(galleryHint());

        int need = 0;
        for (HistoryStore.Entry e : HistoryStore.list(this)) {
            if (e.visible != on && e.name != null && !e.name.isEmpty()) need++;
        }
        if (need == 0) {
            toast("设置已保存，之后下载的文件按新规则保存");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(on ? "把已有文件放回相册？" : "把已有文件移出相册？")
                .setMessage("有 " + need + " 个文件需要"
                        + (on ? "放回相册目录。" : "移动到 App 私有目录，移出后相册里就看不到了。")
                        + "\n\n这是搬家不是复制，文件总数不变，空间占用也不变。"
                        + "\n只想处理某一个，可以进「逐条管理文件」单独开关。")
                .setNegativeButton("只影响以后", (d, w) -> toast("已保存设置，已有文件保持原样"))
                .setPositiveButton("现在搬", (d, w) -> doMigrate(on))
                .show();
    }

    private void doMigrate(final boolean on) {
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setMessage(on ? "正在移动到相册目录…" : "正在从相册移出…")
                .setCancelable(false)
                .create();
        dlg.show();
        new Thread(() -> {
            final int n = Store.migrate(this, on);
            final int bad = Store.failed;
            runOnUiThread(() -> {
                dlg.dismiss();
                String s;
                if (n > 0) {
                    s = on ? "已把 " + n + " 个文件放回相册" : "已把 " + n + " 个文件从相册隐藏";
                } else {
                    s = "设置已保存，之后下载的文件按新规则保存";
                }
                if (bad > 0) {
                    s += "；有 " + bad + " 个旧文件系统不允许移动，需要在相册里手动删除";
                }
                toast(s);
                tvGalleryHint.setText(galleryHint());
            });
        }, "migrate").start();
    }

    // ---------------- 打赏 ----------------

    private void showDonate() {
        View body = LayoutInflater.from(this).inflate(R.layout.dialog_donate, null);
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setView(body)
                .setNegativeButton("关闭", null)
                .create();
        dlg.show();
        body.findViewById(R.id.btnSaveQr).setOnClickListener(v -> {
            saveQr();
            dlg.dismiss();
        });
    }

    /** 赞赏码存进相册 Pictures/DownX，微信扫一扫从相册选就行 */
    private void saveQr() {
        Bitmap bmp = BitmapFactory.decodeResource(getResources(), R.drawable.donate_qr);
        if (bmp == null) {
            toast("赞赏码加载失败");
            return;
        }
        final String name = Store.DONATE_QR_PREFIX + System.currentTimeMillis() + ".jpg";
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/DownX");
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri uri = getContentResolver().insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new Exception("相册目录不可写");
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os == null) throw new Exception("无法写入相册");
                    bmp.compress(Bitmap.CompressFormat.JPEG, 92, os);
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(uri, done, null, null);
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES), "DownX");
                if (!dir.exists()) dir.mkdirs();
                File out = new File(dir, name);
                try (FileOutputStream os = new FileOutputStream(out)) {
                    bmp.compress(Bitmap.CompressFormat.JPEG, 92, os);
                }
                android.media.MediaScannerConnection.scanFile(this,
                        new String[]{out.getAbsolutePath()}, new String[]{"image/jpeg"}, null);
            }
            toast("已保存到相册 Pictures/DownX，微信扫码时从相册选它");
        } catch (Throwable t) {
            toast("保存失败：" + t.getMessage());
        }
    }

    /**
     * 重新扫描相册：先清掉「用户删过的名单」，再从磁盘补记录。
     * 用于记录被误删、或者列表空了的场景 —— 文件还在就都能找回来。
     */
    private void confirmRescan() {
        new AlertDialog.Builder(this)
                .setTitle("重新扫描相册")
                .setMessage("会把手机上还在、但列表里没有的下载补回来。\n\n"
                        + "注意：你以前主动删掉的记录也会一起回来（只限文件还在的那些）。")
                .setNegativeButton("取消", null)
                .setPositiveButton("开始扫描", (d, w) -> doRescan())
                .show();
    }

    private void doRescan() {
        toast("正在扫描…");
        new Thread(() -> {
            int added = 0;
            try {
                HistoryStore.clearBuried(this);       // 允许删掉的记录再补一次
                added = Store.restoreHistory(this);   // 与磁盘对账
            } catch (Throwable ignored) { }
            final int n = added;
            runOnUiThread(() -> toast(n > 0
                    ? "补回 " + n + " 条，回首页就能看到"
                    : "没有可以补回的记录（文件可能已经删掉了）"));
        }, "rescan").start();
    }

    /** 登录状态胶囊：已登录用绿色描边，未登录用中性色 —— 颜色比一串文字更快被看懂 */
    private void setChip(TextView tv, boolean on, String onText, String offText) {
        if (tv == null) return;
        tv.setText(on ? onText : offText);
        tv.setBackgroundResource(on ? R.drawable.bg_chip_ok : R.drawable.bg_chip);
        tv.setTextColor(getResources().getColor(on ? R.color.ok : R.color.text_dim));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
