package com.downx.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HLS 分片并行（滑动窗口）桌面验证台。
 *
 * 重点不是「能不能快」，而是三件手机上查不出来的事：
 *   ① 窗口模式合并出来的字节必须和串行完全一致（顺序错一片 Mux.remux 就整条花）
 *   ② 磁盘峰值必须从「约 2 倍成片」降到「1 倍 + 窗口内几片」
 *   ③ 一片彻底失败 / 主线程取消时，整体能干净收场，不留 worker 线程
 *
 * 分片按片号填充，所以顺序串了会立刻体现在哈希上。
 */
public class Test {

    static final int N = 40;
    static final int SEG = 256 * 1024;
    static final int AHEAD = 8;
    static volatile int delayMs = 25;
    static final AtomicInteger live = new AtomicInteger();
    static final AtomicInteger maxLive = new AtomicInteger();
    static final Set<Integer> broken = ConcurrentHashMap.newKeySet();

    static volatile boolean sampling;
    static volatile long peakBytes, peakParts;
    static File watch;
    static Thread sampler;
    /** 进度平滑性：已合并多少字节、首次合到一半是哪一刻 */
    static long runT0, mergedBytes, halfAt;
    static long totalBytes = (long) N * SEG;
    static int pass, fail;

    public static void main(String[] args) throws Exception {
        File dir = new File(System.getProperty("hlsp.work", "F:/DownX-Studio/tests/hlsp/work"));
        dir.mkdirs();
        watch = dir;

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/seg/", Test::seg);
        server.setExecutor(Executors.newFixedThreadPool(32));
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < N; i++) urls.add(base + "/seg/" + i);

        final String want = shaOrdered(N);
        System.out.println("源： " + N + " 片 × " + (SEG / 1024) + "KB = "
                + (N * SEG / 1024 / 1024) + "MB，sha=" + want.substring(0, 16));

        // ================= ① 旧做法：全部下完再合并（对照基线） =================
        File outA = new File(dir, "mergedA.bin");
        clean(dir);
        resetLive();
        resetPeak();
        startSampler();
        long a0 = System.nanoTime();
        resetProgress(a0);
        runAllThenMerge(urls, 0, 4, dir, "v_", outA);
        long msAll = (System.nanoTime() - a0) / 1_000_000;
        double halfPctA = halfAt * 100.0 / Math.max(1, msAll);
        stopSampler();
        long finalLen = outA.length();
        long peakAll = peakBytes;
        long partsAll = peakParts;
        String shaAll = sha(outA);
        System.out.printf("%n== ① 整段落盘（对照）%d ms，峰值 %.1fMB（%.2fx 成片），part 峰值 %d 片%n",
                msAll, peakAll / 1048576.0, peakAll * 1.0 / finalLen, partsAll);
        check("整段落盘：哈希一致", shaAll.equals(want), "");
        // 实测纠正：合并是「读一片 → 追加 → 删一片」，临时片减少多少、成片就增长多少，
        // 两者互补，所以峰值本来就只有 1 倍，不存在「2 倍磁盘」这回事。
        check("整段落盘：峰值约 1 倍成片（两部分互补）",
                peakAll <= finalLen * 1.15,
                String.format("peak=%.2fx", peakAll * 1.0 / finalLen));
        // 采样是每 2ms 扫一次目录，最后一片可能刚好在「扫到之前被删掉」，
        // 峰值少算 1 片很正常，给 2 片的余量，别拿采样竞态当失败
        check("整段落盘：分片几乎全部堆在磁盘上（≥N-2）", partsAll >= N - 2,
                "part 峰值 " + partsAll);

        // ================= ② 滑动窗口 =================
        File outB = new File(dir, "mergedB.bin");
        clean(dir);
        resetLive();
        resetPeak();
        startSampler();
        long b0 = System.nanoTime();
        resetProgress(b0);
        final FileOutputStream osB = new FileOutputStream(outB, false);
        try {
            new ParallelSegs(4).runWindow(urls, 0, dir, "v_", AHEAD, Test::fetch, (i, p) -> {
                appendTo(p, osB);
                //noinspection ResultOfMethodCallIgnored
                p.delete();
            });
            osB.flush();
        } finally {
            osB.close();
        }
        long msWin = (System.nanoTime() - b0) / 1_000_000;
        double halfPctB = halfAt * 100.0 / Math.max(1, msWin);
        stopSampler();
        long peakWin = peakBytes;
        long partsWin = peakParts;
        System.out.printf("== ② 滑动窗口（ahead=%d）%d ms，峰值 %.1fMB（%.2fx 成片），part 峰值 %d 片%n%n",
                AHEAD, msWin, peakWin / 1048576.0, peakWin * 1.0 / outB.length(), partsWin);

        check("窗口：哈希与串行完全一致", sha(outB).equals(want), "");
        check("窗口：同时在飞不超过 4", maxLive.get() <= 4, "maxLive=" + maxLive.get());
        check("窗口：临时分片从不堆积（≤ ahead）", partsWin <= AHEAD,
                "part 峰值 " + partsWin + " > " + AHEAD);
        check("窗口：磁盘峰值不高于整段落盘（这条优化不解决磁盘）",
                peakWin <= peakAll * 1.05,
                String.format("%.2fx vs %.2fx", peakWin * 1.0 / outB.length(),
                        peakAll * 1.0 / finalLen));
        System.out.printf("  峰值磁盘：%.1fMB → %.1fMB（持平：合并边读边删，两部分互补）%n",
                peakAll / 1048576.0, peakWin / 1048576.0);
        System.out.printf("  磁盘上的临时片：%d 片 → %d 片（窗口只留 ahead 以内）%n",
                partsAll, partsWin);
        System.out.printf("  耗时：%d ms → %d ms（%+.0f%%，合并与下载重叠）%n",
                msAll, msWin, (msWin - msAll) * 100.0 / Math.max(1, msAll));
        check("窗口：不比整段落盘慢（合并被重叠掉了）", msWin <= msAll * 1.15,
                msAll + " vs " + msWin);
        // 进度条是这次改动真正的体感收益：整段模式要等全下完才开始合，
        // 也就是大半程进度都停在 0，最后几秒冲到 100（用户以为卡了）。
        System.out.printf("  进度条：合到 50%% 的时刻 —— 整段落盘在 %.0f%% 时间点，窗口在 %.0f%% 时间点%n",
                halfPctA, halfPctB);
        check("整段落盘：进度严重滞后（50% 拖到 70% 之后）",
                halfPctA >= 70, String.format("%.0f%%", halfPctA));
        check("窗口：进度与下载同步（50% 落在 35~70% 时间点）",
                halfPctB >= 35 && halfPctB <= 70, String.format("%.0f%%", halfPctB));

        // ================= ③ 串行（workers=1）走顺序分支 =================
        File outC = new File(dir, "mergedC.bin");
        clean(dir);
        resetLive();
        measureTo(urls, 0, 1, outC);
        check("串行：哈希一致", sha(outC).equals(want), "");
        check("串行：一次只有 1 个请求", maxLive.get() == 1, "maxLive=" + maxLive.get());

        // ================= ④ 续传：从第 20 片开始 =================
        File outD = new File(dir, "mergedD.bin");
        clean(dir);
        measureTo(urls, 20, 4, outD);
        String tailHit = tailMatches(outD, 20);
        check("续传：只下 from 之后的分片", !ParallelSegs.part(dir, "v_", 0).exists()
                && outD.length() == (N - 20L) * SEG, "len=" + outD.length());
        check("续传：后半段内容正确", tailHit == null, String.valueOf(tailHit));

        // ================= ⑤ 一片始终 404：整体抛错 =================
        clean(dir);
        broken.add(7);
        Throwable e404 = null;
        long d0 = System.nanoTime();
        try {
            new ParallelSegs(4).runWindow(urls, 0, dir, "v_", AHEAD, Test::fetch,
                    (i, p) -> {
                        //noinspection ResultOfMethodCallIgnored
                        p.delete();
                    });
        } catch (Throwable t) {
            e404 = t;
        }
        long ms404 = (System.nanoTime() - d0) / 1_000_000;
        broken.clear();
        check("有片失败：整体抛错（不静默少片）", e404 != null, "没有抛异常");
        check("有片失败：快速收场不卡死", ms404 < 2000, ms404 + "ms");
        checkNoWorkerLeft("失败后");

        // ================= ⑥ 只剩 2 片：走顺序分支 =================
        clean(dir);
        File outE = new File(dir, "mergedE.bin");
        measureTo(urls, N - 2, 4, outE);
        check("只剩 2 片也能下完", outE.length() == 2L * SEG, "len=" + outE.length());

        // ================= ⑦ 主线程取消（sink 抛异常） =================
        clean(dir);
        final AtomicInteger seen = new AtomicInteger();
        Throwable cancel = null;
        long f0 = System.nanoTime();
        try {
            new ParallelSegs(4).runWindow(urls, 0, dir, "v_", AHEAD, Test::fetch, (i, p) -> {
                //noinspection ResultOfMethodCallIgnored
                p.delete();
                if (seen.incrementAndGet() >= 5) throw new IllegalStateException("用户取消");
            });
        } catch (Throwable t) {
            cancel = t;
        }
        long msCancel = (System.nanoTime() - f0) / 1_000_000;
        check("取消：原样抛出（Canceled 语义不被包丢）",
                cancel instanceof IllegalStateException && "用户取消".equals(cancel.getMessage()),
                String.valueOf(cancel));
        check("取消：立刻返回，不卡在窗口上", msCancel < 1500, msCancel + "ms");
        checkNoWorkerLeft("取消后");

        server.stop(0);
        System.out.println("\n结果: " + pass + " PASS, " + fail + " FAIL");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void measureTo(List<String> urls, int from, int workers, File out) throws Exception {
        final FileOutputStream os = new FileOutputStream(out, false);
        try {
            new ParallelSegs(workers).runWindow(urls, from, out.getParentFile(), "v_", AHEAD,
                    Test::fetch, (i, p) -> {
                        appendTo(p, os);
                        //noinspection ResultOfMethodCallIgnored
                        p.delete();
                    });
            os.flush();
        } finally {
            os.close();
        }
    }

    /** 旧做法：全部临时片落盘，之后才按序合并 —— 仅作对照，App 已不用 */
    static void runAllThenMerge(List<String> urls, int from, int workers, File dir, String prefix,
                                File out) throws Exception {
        final int n = urls.size();
        final AtomicInteger next = new AtomicInteger(from);
        final AtomicReference<Throwable> err = new AtomicReference<>();
        List<Thread> ts = new ArrayList<>();
        for (int k = 0; k < workers; k++) {
            Thread t = new Thread(() -> {
                while (true) {
                    int i = next.getAndIncrement();
                    if (i >= n) return;
                    try {
                        fetch(urls.get(i), ParallelSegs.part(dir, prefix, i));
                    } catch (Throwable e) {
                        err.compareAndSet(null, e);
                        return;
                    }
                }
            }, "old" + k);
            ts.add(t);
            t.start();
        }
        for (Thread t : ts) t.join();
        if (err.get() != null) throw new Exception(err.get());
        try (FileOutputStream os = new FileOutputStream(out, false)) {
            for (int i = from; i < n; i++) {
                File p = ParallelSegs.part(dir, prefix, i);
                appendTo(p, os);
                //noinspection ResultOfMethodCallIgnored
                p.delete();
            }
        }
    }

    // ---------------- 被测算的抓取实现（和 DlService.downloadSeg 同构） ----------------

    static long fetch(String url, File dest) throws Exception {
        if (dest.exists() && dest.length() > 0) return dest.length();
        File tmp = new File(dest.getAbsolutePath() + ".tmp");
        int tries = 0;
        while (true) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(10000);
                int code = c.getResponseCode();
                if (code >= 400) throw new IOException("HTTP " + code);
                long n = 0;
                try (InputStream in = c.getInputStream();
                     OutputStream os = new FileOutputStream(tmp, false)) {
                    byte[] b = new byte[64 * 1024];
                    int k;
                    while ((k = in.read(b)) > 0) {
                        os.write(b, 0, k);
                        n += k;
                    }
                }
                if (n <= 0) throw new IOException("empty");
                if (!tmp.renameTo(dest)) throw new IOException("rename failed");
                return n;
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                if (++tries >= 3) throw e;
                Thread.sleep(150L * tries);
            } finally {
                if (c != null) c.disconnect();
            }
        }
    }

    // ---------------- 本地分片服务 ----------------

    static void seg(HttpExchange ex) throws IOException {
        int now = live.incrementAndGet();
        maxLive.accumulateAndGet(now, Math::max);
        try {
            String p = ex.getRequestURI().getPath();
            int i = Integer.parseInt(p.substring(p.lastIndexOf('/') + 1));
            if (broken.contains(i)) {
                ex.sendResponseHeaders(404, -1);
                return;
            }
            Thread.sleep(delayMs);
            byte[] body = new byte[SEG];
            java.util.Arrays.fill(body, (byte) i);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        } catch (Exception ignored) {
        } finally {
            live.decrementAndGet();
        }
    }

    // ---------------- 磁盘峰值采样 ----------------

    static void startSampler() {
        sampling = true;
        sampler = new Thread(() -> {
            while (sampling) {
                long b = 0;
                int c = 0;
                File[] fs = watch.listFiles();
                if (fs != null) {
                    for (File f : fs) {
                        b += f.length();
                        if (f.getName().endsWith(".part")) c++;
                    }
                }
                if (b > peakBytes) peakBytes = b;
                if (c > peakParts) peakParts = c;
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        sampler.setDaemon(true);
        sampler.start();
    }

    static void stopSampler() {
        sampling = false;
        if (sampler != null) {
            try {
                sampler.join(500);
            } catch (InterruptedException ignored) { }
        }
    }

    static void resetPeak() {
        peakBytes = 0;
        peakParts = 0;
    }

    // ---------------- 工具 ----------------

    static void appendTo(File f, OutputStream os) throws IOException {
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = new FileInputStream(f)) {
            int k;
            while ((k = in.read(buf)) > 0) {
                os.write(buf, 0, k);
                mergedBytes += k;
                if (halfAt == 0 && mergedBytes >= totalBytes / 2) {
                    halfAt = (System.nanoTime() - runT0) / 1_000_000;
                }
            }
        }
    }

    static void resetProgress(long t0) {
        runT0 = t0;
        mergedBytes = 0;
        halfAt = 0;
    }

    /** 校验 from 之后的每一片内容；返回 null 表示都对 */
    static String tailMatches(File out, int from) throws IOException {
        try (InputStream in = new FileInputStream(out)) {
            byte[] buf = new byte[SEG];
            for (int i = from; i < N; i++) {
                int read = 0;
                while (read < SEG) {
                    int k = in.read(buf, read, SEG - read);
                    if (k < 0) return "第 " + i + " 片读取不足";
                    read += k;
                }
                for (int j = 0; j < SEG; j++) {
                    if (buf[j] != (byte) i) return "第 " + i + " 片在第 " + j + " 字节处错位";
                }
            }
        }
        return null;
    }

    static String shaOrdered(int n) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] body = new byte[SEG];
        for (int i = 0; i < n; i++) {
            java.util.Arrays.fill(body, (byte) i);
            md.update(body);
        }
        return hex(md.digest());
    }

    static String sha(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = new FileInputStream(f)) {
            int k;
            while ((k = in.read(buf)) > 0) md.update(buf, 0, k);
        }
        return hex(md.digest());
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static void clean(File dir) {
        File[] fs = dir.listFiles();
        if (fs != null) {
            for (File f : fs) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    static void resetLive() {
        maxLive.set(0);
    }

    static void checkNoWorkerLeft(String when) {
        try {
            Thread.sleep(400);
        } catch (InterruptedException ignored) { }
        int left = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().startsWith("seg") && t.isAlive()) left++;
        }
        check(when + "：worker 线程全部退出（没有卡在窗口上）", left == 0, "残留 " + left);
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + name);
        } else {
            fail++;
            System.out.println("  FAIL  " + name + "  → " + detail);
        }
    }
}
