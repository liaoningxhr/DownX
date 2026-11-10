package com.downx.app;

import android.app.Activity;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** 密码 / 指纹解锁页。三种用途：解锁、设置新密码、验证旧密码 */
public class LockActivity extends Activity {

    public static final String EXTRA_MODE = "mode";
    public static final int MODE_UNLOCK = 0;
    public static final int MODE_SETUP = 1;
    public static final int MODE_VERIFY = 2;

    /** 新密码统一 6 位；老版本设的 4 位密码仍按 4 位解锁，可在设置里改 */
    private static final int SETUP_LEN = 6;

    private int mode = MODE_UNLOCK;
    private int len = SETUP_LEN;
    private final StringBuilder buf = new StringBuilder();
    private String firstPin;
    private boolean busy;

    private TextView title, tips, btnPinOk;
    private LinearLayout dots;
    private View welcomeBox, pinBox, btnBio, btnUsePin, btnBackBio, logoBox, bioRing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mode = getIntent().getIntExtra(EXTRA_MODE, MODE_UNLOCK);
        len = mode == MODE_SETUP ? SETUP_LEN : Prefs.pinLength(this);
        setContentView(R.layout.activity_lock);
        // 锁屏页面禁止截屏/录屏，防止密码被截图工具抓走
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);

        title = findViewById(R.id.lockTitle);
        tips = findViewById(R.id.lockTips);
        dots = findViewById(R.id.lockDots);
        btnPinOk = findViewById(R.id.btnPinOk);
        btnPinOk.setOnClickListener(v -> {
            if (busy) return;
            if (buf.length() >= 4) submit(buf.toString());
        });
        welcomeBox = findViewById(R.id.welcomeBox);
        pinBox = findViewById(R.id.pinBox);
        btnBio = findViewById(R.id.btnBio);
        bioRing = findViewById(R.id.bioRing);
        btnUsePin = findViewById(R.id.btnUsePin);
        btnBackBio = findViewById(R.id.btnBackBio);
        logoBox = findViewById(R.id.logoBox);

        buildDots();
        buildKeypad();

        boolean bio = Prefs.bioEnabled(this) && Bio.available(this);

        if (mode == MODE_UNLOCK) {
            title.setText("输入密码");
            tips.setText("解锁后才能查看下载内容");
            if (bio) {
                // 默认就是欢迎页，键盘收起来，只用指纹
                showWelcome();
                btnBio.setOnClickListener(v -> bio());
                btnUsePin.setOnClickListener(v -> showPin());
                btnBackBio.setOnClickListener(v -> showWelcome());
                btnBackBio.setVisibility(View.VISIBLE);
                startFloat();
                startPulse();
                getWindow().getDecorView().postDelayed(this::bio, 420);
            } else {
                // 没有可用的指纹就老老实实输密码
                showPin();
                btnUsePin.setVisibility(View.GONE);
                btnBackBio.setVisibility(View.GONE);
            }
        } else {
            showPin();
            btnUsePin.setVisibility(View.GONE);
            btnBackBio.setVisibility(View.GONE);
            if (mode == MODE_SETUP) {
                title.setText("设置密码");
                tips.setText("4~6 位都可以：4 位点「确定」，6 位输满自动完成");
            } else {
                title.setText("输入密码");
                tips.setText("验证后才能关闭应用锁");
            }
        }
        fresh();
    }

    // ---------------- 两种界面切换 ----------------

    private void showWelcome() {
        pinBox.setVisibility(View.GONE);
        if (welcomeBox.getVisibility() != View.VISIBLE) {
            welcomeBox.setAlpha(0f);
            welcomeBox.setVisibility(View.VISIBLE);
            welcomeBox.animate().alpha(1f).setDuration(220).start();
        }
        fresh();
    }

    private void showPin() {
        welcomeBox.setVisibility(View.GONE);
        if (pinBox.getVisibility() != View.VISIBLE) {
            pinBox.setAlpha(0f);
            pinBox.setVisibility(View.VISIBLE);
            pinBox.animate().alpha(1f).setDuration(220).start();
        }
        fresh();
    }

    /** Logo 轻轻浮动，界面不至于死板 */
    private void startFloat() {
        ObjectAnimator a = ObjectAnimator.ofFloat(logoBox, "translationY", 0f, -8f);
        a.setDuration(2600);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        a.start();
    }

    /** 指纹按钮外圈呼吸脉冲 */
    private void startPulse() {
        ObjectAnimator s = ObjectAnimator.ofPropertyValuesHolder(bioRing,
                PropertyValuesHolder.ofFloat("scaleX", 0.86f, 1.34f),
                PropertyValuesHolder.ofFloat("scaleY", 0.86f, 1.34f),
                PropertyValuesHolder.ofFloat("alpha", 0.7f, 0f));
        s.setDuration(1700);
        s.setRepeatCount(ValueAnimator.INFINITE);
        s.setInterpolator(new android.view.animation.DecelerateInterpolator());
        s.start();
    }

    private void fresh() {
        buf.setLength(0);
        buildDots();
        updateOk();
    }

    /** 当前这一步允许的最多位：设置时最多 6 位，确认时跟第一遍一样长 */
    private int maxLen() {
        if (mode == MODE_SETUP) return firstPin == null ? SETUP_LEN : firstPin.length();
        return len;
    }

    /** 画几个点：设置时先给 4 个，输到第 5 位自动铺满 6 个 */
    private int shownLen() {
        if (mode == MODE_SETUP && firstPin == null) return buf.length() <= 4 ? 4 : SETUP_LEN;
        return maxLen();
    }

    private void buildDots() {
        dots.removeAllViews();
        int n = shownLen();
        boolean many = n > 4;
        int size = dp(many ? 10 : 12);
        int gap = dp(many ? 10 : 14);
        for (int i = 0; i < n; i++) {
            View d = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.rightMargin = i == n - 1 ? 0 : gap;
            d.setLayoutParams(lp);
            dots.addView(d);
        }
        drawDots();
    }

    private void drawDots() {
        for (int i = 0; i < dots.getChildCount(); i++) {
            dots.getChildAt(i).setBackgroundResource(
                    i < buf.length() ? R.drawable.bg_dot_on : R.drawable.bg_dot_off);
        }
    }

    private void buildKeypad() {
        GridLayout pad = findViewById(R.id.keypad);
        pad.setColumnCount(3);
        pad.setRowCount(4);
        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫"};
        for (int i = 0; i < keys.length; i++) {
            final String k = keys[i];
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = dp(76);
            lp.height = dp(62);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));

            if (k.isEmpty()) {
                View space = new View(this);
                space.setLayoutParams(lp);
                pad.addView(space);
                continue;
            }

            TextView b = new TextView(this);
            b.setLayoutParams(lp);
            b.setText(k);
            b.setGravity(Gravity.CENTER);
            b.setTextSize(k.equals("⌫") ? 17 : 22);
            b.setTextColor(k.equals("⌫") ? 0xFF9C9CA8 : 0xFFF2F2F7);
            b.setBackgroundResource(R.drawable.bg_key);
            android.util.TypedValue tv = new android.util.TypedValue();
            if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
                b.setForeground(getDrawable(tv.resourceId));
            }
            b.setOnClickListener(v -> hit(k));
            pad.addView(b);
        }
    }

    private void hit(String k) {
        if (busy) return;
        if (k.equals("⌫")) {
            if (buf.length() > 0) {
                buf.setLength(buf.length() - 1);
                buildDots();
                updateOk();
            }
            return;
        }
        int limit = maxLen();
        if (buf.length() >= limit) return;
        buf.append(k);
        buildDots();
        updateOk();
        if (buf.length() == limit) submit(buf.toString());
    }

    /** 只有「设置密码」这一遍需要手动点确定（4~5 位时），其余都是输满自动提交 */
    private void updateOk() {
        if (btnPinOk == null) return;
        boolean show = mode == MODE_SETUP && firstPin == null
                && buf.length() >= 4 && buf.length() < SETUP_LEN;
        btnPinOk.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
    }

    private void submit(String pin) {
        busy = true;
        if (mode == MODE_SETUP) {
            if (firstPin == null) {
                if (pin.length() < 4) {
                    Toast.makeText(this, "至少 4 位", Toast.LENGTH_SHORT).show();
                    busy = false;
                    return;
                }
                firstPin = pin;
                title.setText("再输一次");
                tips.setText("确认这组 " + pin.length() + " 位密码");
                fresh();
                busy = false;
                return;
            }
            if (firstPin.equals(pin)) {
                Prefs.setPin(this, pin);
                Prefs.setLockEnabled(this, true);
                Prefs.sessionUnlocked = true;
                done();
            } else {
                firstPin = null;
                title.setText("设置密码");
                tips.setText("两次不一致，重新设置");
                Toast.makeText(this, "两次输入不一致", Toast.LENGTH_SHORT).show();
                fresh();
                busy = false;
            }
            return;
        }
        if (Prefs.checkPin(this, pin)) {
            done();
        } else {
            Toast.makeText(this, "密码不对", Toast.LENGTH_SHORT).show();
            tips.setText(mode == MODE_VERIFY ? "请再试一次" : "解锁后才能查看下载内容");
            fresh();
            busy = false;
        }
    }

    private void bio() {
        if (!Bio.available(this)) {
            if (mode == MODE_UNLOCK) showPin();
            return;
        }
        Bio.auth(this, "解锁 DownX", new Bio.Callback() {
            @Override
            public void onOk() {
                if (mode == MODE_SETUP) return;
                done();
            }

            @Override
            public void onNo() {
                // 指纹没成功（按了取消 / 识别失败）就把密码键盘让出来，不会卡死在欢迎页
                if (mode == MODE_UNLOCK) {
                    showPin();
                    tips.setText("也可以直接输入密码");
                }
            }
        });
    }

    private void done() {
        Prefs.sessionUnlocked = true;
        setResult(RESULT_OK);
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    public void onBackPressed() {
        if (mode == MODE_SETUP) {
            setResult(RESULT_CANCELED);
        }
        super.onBackPressed();
        overridePendingTransition(0, 0);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
