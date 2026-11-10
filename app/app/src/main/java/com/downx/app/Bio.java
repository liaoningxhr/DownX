package com.downx.app;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;

/** 指纹 / 生物识别，全部用系统 API，不引第三方库 */
public class Bio {

    public interface Callback {
        void onOk();

        void onNo();
    }

    /** 设备是否具备可用的指纹/人脸，并且已录入 */
    public static boolean available(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                BiometricManager bm = c.getSystemService(BiometricManager.class);
                return bm != null && bm.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS;
            }
            return fingerprintReady(c);
        } catch (Throwable t) {
            return fingerprintReady(c);
        }
    }

    private static boolean fingerprintReady(Context c) {
        try {
            FingerprintManager fm = (FingerprintManager) c.getSystemService(Context.FINGERPRINT_SERVICE);
            return fm != null && fm.isHardwareDetected() && fm.hasEnrolledFingerprints();
        } catch (Throwable t) {
            return false;
        }
    }

    public static void auth(final Activity a, final String title, final Callback cb) {
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                BiometricPrompt prompt = new BiometricPrompt.Builder(a)
                        .setTitle(title)
                        .setNegativeButton("用密码", a.getMainExecutor(), (d, w) -> cb.onNo())
                        .build();
                prompt.authenticate(new CancellationSignal(), a.getMainExecutor(),
                        new BiometricPrompt.AuthenticationCallback() {
                            @Override
                            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                                cb.onOk();
                            }

                            @Override
                            public void onAuthenticationError(int code, CharSequence msg) {
                                cb.onNo();
                            }
                        });
                return;
            } catch (Throwable ignored) {
                // 落到指纹通道
            }
        }
        try {
            FingerprintManager fm = (FingerprintManager) a.getSystemService(Context.FINGERPRINT_SERVICE);
            if (fm == null) {
                cb.onNo();
                return;
            }
            final Handler h = new Handler(Looper.getMainLooper());
            fm.authenticate(null, new CancellationSignal(), 0,
                    new FingerprintManager.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(FingerprintManager.AuthenticationResult r) {
                            h.post(cb::onOk);
                        }

                        @Override
                        public void onAuthenticationError(int code, CharSequence msg) {
                            h.post(cb::onNo);
                        }
                    }, h);
        } catch (Throwable t) {
            cb.onNo();
        }
    }

    /** 设备是否设了锁屏密码（有些机器没有任何锁屏，指纹不可用） */
    public static boolean deviceSecure(Context c) {
        try {
            KeyguardManager km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && km.isDeviceSecure();
        } catch (Throwable t) {
            return false;
        }
    }
}
