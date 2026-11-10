package com.downx.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

/**
 * 删除音效：撕纸声。单条一撕，批量连撕两串加一声闷响，听感上有"碎掉"的反馈。
 *
 * 为什么用 SoundPool 而不是 MediaPlayer：要的是几十毫秒内起播的短音效，
 * SoundPool 预解码驻留内存，连点也不卡；两个 wav 加起来才 105KB。
 */
public final class Sfx {

    private static SoundPool pool;
    private static int idSingle, idMulti;
    private static boolean ready;
    private static boolean loadedSingle, loadedMulti;

    private Sfx() { }

    private static synchronized void ensure(Context c) {
        if (pool != null) return;
        try {
            AudioAttributes attr = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            pool = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attr).build();
            pool.setOnLoadCompleteListener((sp, id, status) -> {
                if (id == idSingle) loadedSingle = status == 0;
                if (id == idMulti) loadedMulti = status == 0;
                ready = loadedSingle || loadedMulti;
            });
            idSingle = pool.load(c.getApplicationContext(), R.raw.tear_single, 1);
            idMulti = pool.load(c.getApplicationContext(), R.raw.tear_multi, 1);
        } catch (Throwable t) {
            pool = null;
        }
    }

    /** 单条删除：干脆的一撕 */
    public static void tearOne(Context c) {
        play(c, idSingle, 0.9f);
    }

    /** 批量删除：连撕两串 + 闷响，更重一些 */
    public static void tearMany(Context c) {
        play(c, idMulti, 1.0f);
    }

    private static void play(Context c, int id, float vol) {
        try {
            ensure(c.getApplicationContext());
            if (pool == null || id == 0) return;
            // 刚装好前几十毫秒可能还没解码完，跳过即可，下一次就有声音了
            if (id == idSingle && !loadedSingle) return;
            if (id == idMulti && !loadedMulti) return;
            pool.play(id, vol, vol, 1, 0, 1f);
        } catch (Throwable ignored) { }
    }
}
