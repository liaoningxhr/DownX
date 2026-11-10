package com.downx.app;

/**
 * 桌面验证台的 Parts 桩：只保留 ChunkedFetch 真正读写的那几个字段。
 * 真机版要把 state.txt 写进私有目录，主机上不需要持久化（续传场景另有权重测试）。
 */
public final class Parts {

    public static final class State {
        public String id = "";
        public String name = "", kind = "video", thumb = "", page = "", sub = "";
        public String key = "", referer = "", pick = "", alt = "", altName = "";
        public String platform = "", label = "";
        public boolean visible = true;
        public String sig = "";
        public String segs = "";
        public long lenV, lenA;
        public long totalV = -1, totalA = -1;
        public boolean vDone, aDone;
        /** 分片位图（ChunkedFetch 用） */
        public String ranges = "";
        public long cs;
        public long ts;
    }

    public static String idOf(String key, String pick) {
        String k = (key == null ? "" : key) + "|" + (pick == null ? "" : pick);
        return Integer.toHexString(k.hashCode());
    }

    /** 把文件截到指定长度（真机版还会落盘状态，主机上只需要这一步） */
    public static void truncate(java.io.File f, long len) {
        if (f == null || !f.exists()) return;
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "rw")) {
            if (raf.length() > len) raf.setLength(len);
        } catch (Throwable ignored) { }
    }
}
