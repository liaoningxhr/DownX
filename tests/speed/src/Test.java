import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 「下载速度」对比实验台（纯桌面，不参与打包）：
 *
 *   A. 连接复用：20 片 × 2MB，每片结束 disconnect vs 只关流（keep-alive）
 *      —— 验证 DownX 现在每片一次 TCP 握手，改成复用能省多少
 *   B. 批量并行：30 个小文件（300KB，服务端 40ms 延迟），串行 vs 3 路 vs 6 路
 *      —— 验证批量图片一张一张下 vs 几路并行差多少
 *   C. 落盘复制：200MB 文件，64KB vs 1MB 缓冲
 *      —— 验证下载完成后再复制一遍是不是真瓶颈
 *
 * 握手成本用「新连接首字节延迟 60ms」模拟（真机 TLS + RTT 通常 80~200ms）。
 */
public class Test {

    static File SRC;
    static volatile int hsDelayMs = 60;
    static volatile int imgDelayMs = 40;
    static final Set<String> seenConn = ConcurrentHashMap.newKeySet();
    static final AtomicInteger handshakes = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        File dir = new File(System.getProperty("sp.work", "F:/DownX-Studio/tests/speed/work"));
        dir.mkdirs();

        SRC = new File(dir, "src40.bin");
        if (!SRC.exists() || SRC.length() != 40L * 1024 * 1024) {
            Random r = new Random(20261110);
            byte[] buf = new byte[1 << 20];
            try (FileOutputStream out = new FileOutputStream(SRC)) {
                for (int i = 0; i < 40; i++) { r.nextBytes(buf); out.write(buf); }
            }
        }

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/r", Test::handleRange);
        server.createContext("/r3", ex -> handleSized(ex, 3L * 1024 * 1024));
        server.createContext("/img", Test::handleImg);
        server.setExecutor(Executors.newFixedThreadPool(64));
        server.start();
        int port = server.getAddress().getPort();
        String base = "http://127.0.0.1:" + port;
        System.out.println("本地服务 " + base + "  模拟握手 " + hsDelayMs + "ms\n");

        // ---------- A. 连接复用 ----------
        // 三种模式各用独立端口：HttpURLConnection 的连接池按 host:port 复用，
        // 共用端口会让第二轮直接捡第一轮的空闲连接，测出来的是「池预热」而不是「复用」
        System.out.println("== A. 20 片 × 2MB：每片 disconnect vs 连接复用（各自独立端口）==");
        String uA = mkServer("127.0.0.1");
        String uB = mkServer("127.0.0.1");
        String uC = mkServer("127.0.0.1");
        long msDisc = runChunks(uA + "/r", 8, 20, 1);
        int hsDisc = handshakes.get();
        long msKeep = runChunks(uB + "/r", 8, 20, 2);
        int hsKeep = handshakes.get() - hsDisc;
        long msClose = runChunks(uC + "/r", 8, 20, 3);
        int hsClose = handshakes.get() - hsDisc - hsKeep;

        System.out.printf("  disconnect : %5d ms, 新建连接 %2d 条  ← DownX 现状%n", msDisc, hsDisc);
        System.out.printf("  keep-alive : %5d ms, 新建连接 %2d 条%n", msKeep, hsKeep);
        System.out.printf("  强制重连   : %5d ms, 新建连接 %2d 条  ← 对照组（Connection: close）%n", msClose, hsClose);
        System.out.printf("  disconnect-keep 差值: %+5d ms（%+.0f%%）%n%n",
                msKeep - msDisc, (msKeep - msDisc) * 100.0 / Math.max(1, msDisc));

        // ---------- A2. 小文件值不值得并发 ----------
        System.out.println("== A2. 3MB 文件：单连接 vs 4 路（片大小 768KB）==");
        String uS = mkServer("127.0.0.1");
        long one = runSmall(uS + "/r3", 1, 1);
        long four = runSmall(mkServer("127.0.0.1") + "/r3", 4, 1);
        System.out.printf("  单连接 : %5d ms%n", one);
        System.out.printf("  4 路   : %5d ms（%.2fx）%n%n", four, one * 1.0 / four);

        // ---------- B. 批量并行 ----------
        System.out.println("== B. 30 个小文件（300KB + 40ms 延迟）==");
        long s1 = runImgs(base + "/img", 30, 1);
        long s3 = runImgs(base + "/img", 30, 3);
        long s6 = runImgs(base + "/img", 30, 6);
        System.out.printf("  串行 1 路 : %5d ms%n", s1);
        System.out.printf("  并行 3 路 : %5d ms（%.1fx）%n", s3, s1 * 1.0 / s3);
        System.out.printf("  并行 6 路 : %5d ms（%.1fx）%n%n", s6, s1 * 1.0 / s6);

        // ---------- C. 落盘复制 ----------
        System.out.println("== C. 200MB 本地复制：64KB vs 1MB 缓冲 ==");
        File big = new File(dir, "big200.bin");
        if (!big.exists() || big.length() != 200L * 1024 * 1024) {
            big.delete();
            try (RandomAccessFile raf = new RandomAccessFile(big, "rw")) { raf.setLength(200L * 1024 * 1024); }
        }
        long c64 = copyBench(big, new File(dir, "c64.bin"), 64 * 1024);
        long c1m = copyBench(big, new File(dir, "c1m.bin"), 1024 * 1024);
        System.out.printf("  64KB : %5d ms%n", c64);
        System.out.printf("  1MB  : %5d ms%n", c1m);
        new File(dir, "c64.bin").delete();
        new File(dir, "c1m.bin").delete();

        server.stop(0);
        System.out.println("\n完成");
        // HttpServer 自己的线程池不是守护线程，不显式退出会一直挂着
        System.exit(0);
    }

    // ---------------- A ----------------

    /** 起一个独立端口的 Range 服务，返回 base url */
    static String mkServer(String host) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress(host, 0), 0);
        s.createContext("/r", Test::handleRange);
        s.createContext("/r3", ex -> handleSized(ex, 3L * 1024 * 1024));
        s.setExecutor(Executors.newFixedThreadPool(32));
        s.start();
        return "http://" + host + ":" + s.getAddress().getPort();
    }

    /** 8 个 worker 争抢 20 片；mode 1=disconnect(DownX 现状) 2=只关流 3=强制重连(对照) */
    static long runChunks(String url, int workers, int chunks, int mode) throws Exception {
        final AtomicInteger next = new AtomicInteger();
        final long cs = 2L * 1024 * 1024;
        final long total = chunks * cs;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        long t0 = System.nanoTime();
        for (int w = 0; w < workers; w++) {
            pool.submit(() -> {
                while (true) {
                    int i = next.getAndIncrement();
                    if (i >= chunks) return;
                    long start = i * cs;
                    long end = Math.min(start + cs, total) - 1;
                    HttpURLConnection c = null;
                    try {
                        c = (HttpURLConnection) new URL(url).openConnection();
                        c.setConnectTimeout(20000);
                        c.setReadTimeout(30000);
                        c.setUseCaches(false);
                        c.setRequestProperty("User-Agent", "DownX-SpeedTest");
                        c.setRequestProperty("Accept-Encoding", "identity");
                        if (mode == 3) c.setRequestProperty("Connection", "close");
                        c.setRequestProperty("Range", "bytes=" + start + "-" + end);
                        int code = c.getResponseCode();
                        if (code != 206 && code != 200) throw new java.io.IOException("HTTP " + code);
                        try (InputStream in = c.getInputStream()) {
                            byte[] buf = new byte[64 * 1024];
                            long got = 0;
                            int n;
                            while ((n = in.read(buf)) > 0) got += n;
                            if (got != end - start + 1) throw new java.io.IOException("short " + got);
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        if (c != null) {
                            if (mode == 1) c.disconnect();   // DownX 现状
                        }
                    }
                }
            });
        }
        pool.shutdown();
        if (!pool.awaitTermination(10, TimeUnit.MINUTES)) throw new IllegalStateException("timeout");
        return (System.nanoTime() - t0) / 1_000_000L;
    }

    static void handleRange(HttpExchange ex) throws java.io.IOException {
        handleSized(ex, SRC.length());
    }

    /** 按指定总长返回 SRC 的前 N 字节，支持 Range */
    static void handleSized(HttpExchange ex, long total) throws java.io.IOException {
        markConn(ex);
        try {
            String r = ex.getRequestHeaders().getFirst("Range");
            long a = 0, b = total - 1;
            if (r != null && r.startsWith("bytes=")) {
                String s = r.substring(6);
                int dash = s.indexOf('-');
                a = Long.parseLong(s.substring(0, dash));
                b = Long.parseLong(s.substring(dash + 1).isEmpty() ? String.valueOf(total - 1) : s.substring(dash + 1));
                if (b >= total) b = total - 1;
            }
            long len = b - a + 1;
            ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
            ex.getResponseHeaders().add("Accept-Ranges", "bytes");
            if (r != null) ex.getResponseHeaders().add("Content-Range", "bytes " + a + "-" + b + "/" + total);
            ex.sendResponseHeaders(r != null ? 206 : 200, len);
            try (RandomAccessFile raf = new RandomAccessFile(SRC, "r");
                 OutputStream os = ex.getResponseBody()) {
                raf.seek(a);
                byte[] buf = new byte[64 * 1024];
                long left = len;
                while (left > 0) {
                    int want = (int) Math.min(buf.length, left);
                    int n = raf.read(buf, 0, want);
                    if (n < 0) break;
                    os.write(buf, 0, n);
                    left -= n;
                }
            }
        } catch (Exception ignored) {
        }
    }

    // ---------------- A2 ----------------

    /** workers=1 走单连接整文件；>1 按等分 Range 并发 */
    static long runSmall(String url, int workers, int unused) throws Exception {
        final long total = 3L * 1024 * 1024;
        final long cs = total / workers;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        long t0 = System.nanoTime();
        for (int i = 0; i < workers; i++) {
            final long a = i * cs;
            final long b = (i == workers - 1) ? total - 1 : (i + 1) * cs - 1;
            pool.submit(() -> {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(20000);
                    c.setReadTimeout(30000);
                    c.setRequestProperty("Range", workers == 1 ? "bytes=0-" : "bytes=" + a + "-" + b);
                    try (InputStream in = c.getInputStream()) {
                        byte[] buf = new byte[64 * 1024];
                        long got = 0;
                        while (in.read(buf) > 0) got++;
                        if (got == 0) throw new java.io.IOException("empty");
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    if (c != null) c.disconnect();
                }
            });
        }
        pool.shutdown();
        if (!pool.awaitTermination(10, TimeUnit.MINUTES)) throw new IllegalStateException("timeout");
        return (System.nanoTime() - t0) / 1_000_000L;
    }

    // ---------------- B ----------------

    static long runImgs(String url, int count, int parallel) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(parallel);
        long t0 = System.nanoTime();
        for (int i = 0; i < count; i++) {
            pool.submit(() -> {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(20000);
                    c.setReadTimeout(30000);
                    c.setRequestProperty("User-Agent", "DownX-SpeedTest");
                    try (InputStream in = c.getInputStream()) {
                        byte[] buf = new byte[32 * 1024];
                        while (in.read(buf) > 0) { /* 丢弃 */ }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        pool.shutdown();
        if (!pool.awaitTermination(10, TimeUnit.MINUTES)) throw new IllegalStateException("timeout");
        return (System.nanoTime() - t0) / 1_000_000L;
    }

    static void handleImg(HttpExchange ex) throws java.io.IOException {
        markConn(ex);
        try {
            Thread.sleep(imgDelayMs);
            byte[] body = new byte[300 * 1024];
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        } catch (Exception ignored) {
        }
    }

    // ---------------- C ----------------

    static long copyBench(File src, File dst, int buf) throws Exception {
        long t0 = System.nanoTime();
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            byte[] b = new byte[buf];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        }
        return (System.nanoTime() - t0) / 1_000_000L;
    }

    /** 同一个「ip:port」第二次出现就算复用；新出现说明又握了一次手 */
    static void markConn(HttpExchange ex) {
        String key = ex.getRemoteAddress().toString();
        if (seenConn.add(key)) {
            handshakes.incrementAndGet();
            try { Thread.sleep(hsDelayMs); } catch (InterruptedException ignored) { }
        }
    }
}
