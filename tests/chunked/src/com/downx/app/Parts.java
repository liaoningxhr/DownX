package com.downx.app;

import java.io.File;
import java.io.RandomAccessFile;

/** 桌面测试用的最小替身：只保留 ChunkedFetch 用到的那几个成员 */
public class Parts {

    public static class State {
        public String id = "", name = "", kind = "video", thumb = "", page = "", sub = "";
        public String key = "", referer = "", pick = "", alt = "", altName = "";
        public String platform = "", label = "";
        public boolean visible = true;
        public String sig = "";
        public String offs = "";
        public String ranges = "";
        public int segV, segA, segCountV;
        public long lenV, lenA, totalV = -1, totalA = -1, cs;
        public boolean vDone, aDone;
        public long ts;
    }

    public static void truncate(File f, long len) {
        try {
            if (!f.exists()) return;
            if (f.length() == len) return;
            RandomAccessFile r = new RandomAccessFile(f, "rw");
            r.setLength(Math.max(0, len));
            r.close();
        } catch (Throwable ignored) { }
    }
}
