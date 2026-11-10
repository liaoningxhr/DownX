package com.downx.app;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 真实 CDN 上的对比（Apple 官方测试流，分片很大，接近真机上的长视频）：
 *   A. 整段落盘：全部下完，再按序合并
 *   B. 滑动窗口：一边下一边合
 * 两边的合并结果必须字节一致，比较耗时。
 * 外网不通时明确报错，不静默通过。
 */
public class RealHls {

    static final String MASTER =
            "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8";
    /** 每片约 150MB，6 片约 900MB：够体现「合并阶段被重叠掉多少」 */
    static final int TAKE = 6;

    public static void main(String[] args) throws Exception {
        String base = MASTER.substring(0, MASTER.lastIndexOf('/') + 1);
        String variant = base + "v5/prog_index.m3u8";
        String pl = get(variant, 20000);
        String vbase = variant.substring(0, variant.lastIndexOf('/') + 1);
        List<String> segs = new ArrayList<>();
        for (String line : pl.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            segs.add(t.startsWith("http") ? t : vbase + t);
        }
        if (segs.size() < 3) throw new IllegalStateException("清单里的分片太少：" + segs.size());
        final List<String> take = new ArrayList<>(segs.subList(0, Math.min(TAKE, segs.size())));
        System.out.println("真实清单：共 " + segs.size() + " 片，本次取前 " + take.size() + " 片");

        File root = new File(System.getProperty("hlsp.real", "F:/DownX-Studio/tests/hlsp/work-real"));
        del(root);
        root.mkdirs();

        // ---------- A. 整段落盘 ----------
        File da = new File(root, "a");
        da.mkdirs();
        File outA = new File(root, "all.mp4");
        long t1 = System.nanoTime();
        new ParallelSegs(4).runWindow(take, 0, da, "v_", take.size(), RealHls::fetch,
                (i, p) -> { });                 // 先只下、不合（sink 空转），模拟"全下完再合并"
        long dlA = (System.nanoTime() - t1) / 1_000_000;
        long t2 = System.nanoTime();
        try (FileOutputStream os = new FileOutputStream(outA, false)) {
            for (int i = 0; i < take.size(); i++) appendTo(ParallelSegs.part(da, "v_", i), os);
        }
        long mgA = (System.nanoTime() - t2) / 1_000_000;
        long msA = dlA + mgA;
        String hA = sha(outA);
        long lenA = outA.length();
        del(da);

        // ---------- B. 滑动窗口 ----------
        File db = new File(root, "b");
        db.mkdirs();
        File outB = new File(root, "win.mp4");
        long t3 = System.nanoTime();
        final FileOutputStream osB = new FileOutputStream(outB, false);
        try {
            new ParallelSegs(4).runWindow(take, 0, db, "v_", 8, RealHls::fetch, (i, p) -> {
                appendTo(p, osB);
                //noinspection ResultOfMethodCallIgnored
                p.delete();
            });
            osB.flush();
        } finally {
            osB.close();
        }
        long msB = (System.nanoTime() - t3) / 1_000_000;
        String hB = sha(outB);
        long lenB = outB.length();
        del(db);

        System.out.printf("%n整段落盘 : 下载 %6d ms + 合并 %5d ms = %6d ms, %d 字节%n", dlA, mgA, msA, lenA);
        System.out.printf("滑动窗口 : %6d ms（合并与下载重叠）, %d 字节%n", msB, lenB);
        System.out.printf("耗时     : %+.0f%% （省下 %d ms）%n",
                (msB - msA) * 100.0 / Math.max(1, msA), msA - msB);
        System.out.printf("合并阶段占总耗时 %.0f%%%n", mgA * 100.0 / Math.max(1, msA));

        boolean ok = hA.equals(hB) && lenA == lenB && lenA > 0;
        System.out.println(ok ? "PASS 两种方式字节完全一致" : "FAIL 两种方式不一致");
        System.out.println("sha=" + hA.substring(0, 16));

        del(root);                              // 900MB 临时，测完就删
        System.exit(ok ? 0 : 1);
    }

    static long fetch(String url, File dest) throws Exception {
        File tmp = new File(dest.getAbsolutePath() + ".tmp");
        for (int t = 0; ; t++) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(20000);
                c.setRequestProperty("User-Agent", "DownX-HlsTest");
                int code = c.getResponseCode();
                if (code >= 400) throw new IOException("HTTP " + code);
                long n = 0;
                try (InputStream in = c.getInputStream();
                     OutputStream os = new FileOutputStream(tmp, false)) {
                    byte[] b = new byte[64 * 1024];
                    int k;
                    while ((k = in.read(b)) > 0) { os.write(b, 0, k); n += k; }
                }
                if (n <= 0) throw new IOException("empty");
                if (!tmp.renameTo(dest)) throw new IOException("rename");
                return n;
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                if (t >= 2) throw e;
                Thread.sleep(300L * (t + 1));
            } finally {
                if (c != null) c.disconnect();
            }
        }
    }

    static void appendTo(File f, OutputStream os) throws IOException {
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = new FileInputStream(f)) {
            int k;
            while ((k = in.read(buf)) > 0) os.write(buf, 0, k);
        }
    }

    static String get(String u, int timeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(timeout);
        int code = c.getResponseCode();
        if (code >= 400) throw new IOException("HTTP " + code);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (InputStream in = c.getInputStream()) {
            byte[] b = new byte[8192];
            int k;
            while ((k = in.read(b)) > 0) bos.write(b, 0, k);
        } finally {
            c.disconnect();
        }
        return new String(bos.toByteArray(), "UTF-8");
    }

    static String sha(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = new FileInputStream(f)) {
            int k;
            while ((k = in.read(buf)) > 0) md.update(buf, 0, k);
        }
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest()) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static void del(File f) {
        File[] fs = f.listFiles();
        if (fs != null) for (File x : fs) del(x);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
