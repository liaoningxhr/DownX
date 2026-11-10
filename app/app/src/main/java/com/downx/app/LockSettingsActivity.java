package com.downx.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 应用锁的二级页：总开关、修改密码、指纹解锁。
 * 从设置页「应用锁」进来，没开锁时后两项是灰的，避免让人以为设了密码却没生效。
 */
public class LockSettingsActivity extends Activity {

    private static final int REQ_SETUP = 11;
    private static final int REQ_VERIFY = 12;

    private Switch swLock, swBio;
    private TextView tvLockHint, tvBioHint;
    private View rowChangePin;
    private boolean binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lock_settings);

        swLock = findViewById(R.id.swLock);
        swBio = findViewById(R.id.swBio);
        tvLockHint = findViewById(R.id.tvLockHint);
        tvBioHint = findViewById(R.id.tvBioHint);
        rowChangePin = findViewById(R.id.rowChangePin);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        swLock.setOnCheckedChangeListener((b, on) -> {
            if (binding) return;
            onLockToggle(on);
        });
        swBio.setOnCheckedChangeListener((b, on) -> {
            if (binding) return;
            Prefs.setBioEnabled(this, on);
            toast(on ? "解锁时可以用指纹" : "已关闭指纹，只能输密码解锁");
            refresh();
        });
        rowChangePin.setOnClickListener(v -> {
            if (!Prefs.hasPin(this)) {
                toast("先打开应用锁并设置密码");
                return;
            }
            Intent i = new Intent(this, LockActivity.class);
            i.putExtra(LockActivity.EXTRA_MODE, LockActivity.MODE_SETUP);
            startActivityForResult(i, REQ_SETUP);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean on = Prefs.lockEnabled(this) && Prefs.hasPin(this);
        boolean hw = Bio.available(this);

        binding = true;
        swLock.setChecked(on);
        swBio.setChecked(Prefs.bioEnabled(this) && hw);
        swBio.setEnabled(hw && on);
        binding = false;

        tvLockHint.setText(on
                ? "已开启 · 重新打开 App 时需要验证"
                : "打开 DownX 需要密码或指纹");

        // 没开锁时，修改密码/指纹都置灰：它们只对已开启的锁有意义
        rowChangePin.setEnabled(on);
        rowChangePin.setAlpha(on ? 1f : 0.4f);
        swBio.setAlpha(on ? 1f : 0.4f);

        tvBioHint.setText(!hw
                ? "这台手机不支持指纹，只能输密码"
                : !on
                        ? "先打开上面的应用锁，才能用指纹"
                        : "只在重新打开 App 时验证一次，切后台、放多久回来都不用再按");
    }

    private void onLockToggle(boolean on) {
        if (on) {
            if (Prefs.hasPin(this)) {
                Prefs.setLockEnabled(this, true);
                Prefs.sessionUnlocked = true;
                toast("应用锁已开启");
                refresh();
                return;
            }
            Intent i = new Intent(this, LockActivity.class);
            i.putExtra(LockActivity.EXTRA_MODE, LockActivity.MODE_SETUP);
            startActivityForResult(i, REQ_SETUP);
        } else {
            // 关之前先验证一次：手机被别人拿着时不能直接关掉
            Intent i = new Intent(this, LockActivity.class);
            i.putExtra(LockActivity.EXTRA_MODE, LockActivity.MODE_VERIFY);
            startActivityForResult(i, REQ_VERIFY);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_SETUP) {
            if (res == RESULT_OK) {
                Prefs.setLockEnabled(this, true);
                Prefs.sessionUnlocked = true;
                toast(Prefs.hasPin(this) ? "密码已保存" : "应用锁已开启");
            }
        } else if (req == REQ_VERIFY) {
            if (res == RESULT_OK) {
                Prefs.setLockEnabled(this, false);
                Prefs.setBioEnabled(this, false);
                toast("应用锁已关闭");
            }
        }
        refresh();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
