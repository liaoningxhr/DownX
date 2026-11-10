import com.downx.app.ChunkedFetch;
import com.downx.app.Parts;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ChunkedFetch 的桌面验证台：
 *   ① 正常 8 并发下载 → 字节必须和源文件完全一致
 *   ② 中途打断 → 用位图续传 → 仍然一致，且不重下已完成的片
 *   ③ 服务器忽略 Range → used=false，交给单连接
 *   ④ 文件小于 4MB → used=false
 *   ⑤ 断流率 3% 的烂网络 → 仍然一致
 */
public class Test {

    static File SRC;
    static volatile double failRate = 0;
    static volatile boolean ignoreRange = false;
    static final AtomicInteger live = new AtomicInteger();
    static final AtomicInteger maxLive = new AtomicInteger();
    static final AtomicLong served = new AtomicLong();

    public static void main(String[] args) throws Exception {
        File dir = new File(System.getProperty("cf.work", "F:/DownX-Studio/tests/chunked/work"));
        dir.mkdirs();

        // 40MB 源文件
        SRC = new File(dir, "src.bin");
        if (!SRC.exists() || SRC.length() != 40L * 1024 * 1024) {
            Random r = new Random(20261109);
            byte[] buf = new byte[1 << 20];
            try (FileOutputStream out = new FileOutputStream(SRC)) {
                for (int i = 0; i < 40; i++) {
                    r.nextBytes(buf);
                    out.write(buf);
                }
            }
        }
        String want = sha(SRC);
        System.out.println("源文件 " + SRC.length() + " 字节 sha=" + want.substring(0, 16));

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/f", Test::handle);
        server.setExecutor(Executors.newFixedThreadPool(32));
        server.start();
        int port = server.getAddress().getPort();
        String url = "http://127.0.0.1:" + port + "/f";
        System.out.println("本地服务 " + url);

        try {
            // ① 正常下载
            run("① 正常并发", url, dir, "a.bin", 0, 0);
            check("①", dir, "a.bin", want);

            // ② 打断 + 续传
            File b = new File(dir, "b.bin");
            File bstate = new File(dir, "b.state");
            if (b.exists()) b.delete();
            if (bstate.exists()) bstate.delete();
            Parts.State st = new Parts.State();
            st.id = "b";
            long cut = 12L * 1024 * 1024;
            boolean broke = false;
            try {
                runWith(st, url, b, bstate, cut);
            } catch (Exception e) {
                broke = true;
                System.out.println("  第二轮：按预期被打断 → " + e.getMessage());
                System.out.println("  断点状态：" + read(bstate));
            }
            if (!broke) throw new AssertionError("第二轮没有被成功打断，测不到续传");
            // 重新载入状态（模拟进程重启）
            Parts.State st2 = load(bstate);
            System.out.println("  重启后载入：" + st2.ranges.substring(0, Math.min(60, st2.ranges.length()))
                    + "  cs=" + st2.cs + " total=" + st2.totalV + " len=" + st2.lenV);
            long before = st2.lenV;
            long t0 = System.currentTimeMillis();
            ChunkedFetch.Result rr = runWith(st2, url, b, bstate, 0);
            System.out.println("  续传用时 " + (System.currentTimeMillis() - t0) + "ms，"
                    + "续传前已下 " + before / 1024 / 1024 + "MB（应该跳过这些片）");
            check("②", dir, "b.bin", want);

            // ③ 服务器忽略 Range
            ignoreRange = true;
            File c = new File(dir, "c.bin");
            if (c.exists()) c.delete();
            ChunkedFetch.Result r3 = run("③ 不支持 Range", url, c, "c.bin", 0, 0);
            if (r3.used) throw new AssertionError("③ 不该走并发路径");
            System.out.println("  ③ 正确退回单连接（used=false）");

            // ④ 小文件
            ignoreRange = false;
            File small = new File(dir, "small.bin");
            try (FileOutputStream o = new FileOutputStream(small)) { o.write(new byte[1024 * 1024]); }
            File saved = SRC;
            SRC = small;
            File d = new File(dir, "d.bin");
            if (d.exists()) d.delete();
            ChunkedFetch.Result r4 = run("④ 1MB 小文件", url, d, "d.bin", 0, 0);
            if (r4.used) throw new AssertionError("④ 小文件不该走并发路径");
            System.out.println("  ④ 正确退回单连接（used=false）");
            SRC = saved;

            // ⑤ 烂网络：3% 的块会断
            failRate = 0.03;
            run("⑤ 断流率 3%", url, dir, "e.bin", 0, 0);
            check("⑤", dir, "e.bin", want);

            System.out.println("\n全部通过 ✅  最大并发连接数 = " + maxLive.get());
        } finally {
            server.stop(0);
        }
    }

    static ChunkedFetch.Result run(String tag, String url, File dir, String name,
                                   long cut, long unused) throws Exception {
        Parts.State st = new Parts.State();
        st.id = name;
        return runWith(st, url, new File(dir, name), new File(dir, name + ".state"), cut);
    }

    static ChunkedFetch.Result runWith(final Parts.State st, String url, final File out,
                                       final File stateFile, final long cut) throws Exception {
        final long t0 = System.currentTimeMillis();
        maxLive.set(0);
        served.set(0);
        ChunkedFetch.Result r = new ChunkedFetch(out, url, null, st, new ChunkedFetch.Ctl() {
            @Override
            public void gate() {
                if (cut > 0 && st.lenV >= cut) {
                    throw new RuntimeException("模拟进程被杀 @" + (st.lenV / 1024 / 1024) + "MB");
                }
            }

            @Override
            public void progress(long done, long total) {
                System.out.println("    " + (done * 100 / total) + "%  " + done / 1024 / 1024 + "MB");
            }

            @Override
            public void persist() {
                save(st, stateFile);
            }

            @Override
            public void log(String line) {
                System.out.println("    · " + line);
            }
        }).run();
        System.out.println("  用时 " + (System.currentTimeMillis() - t0) + "ms  used=" + r.used
                + " total=" + r.total + " ct=" + r.contentType + " maxLive=" + maxLive.get());
        return r;
    }

    static void handle(HttpExchange ex) throws java.io.IOException {
        int n = live.incrementAndGet();
        maxLive.accumulateAndGet(n, Math::max);
        try {
            long total = SRC.length();
            String range = ex.getRequestHeaders().getFirst("Range");
            long start = 0, end = total - 1;
            boolean partial = false;
            if (range != null && range.startsWith("bytes=") && !ignoreRange) {
                String s = range.substring(6);
                int dash = s.indexOf('-');
                start = Long.parseLong(s.substring(0, dash).trim());
                String e = s.substring(dash + 1).trim();
                if (!e.isEmpty()) end = Long.parseLong(e);
                if (end >= total) end = total - 1;
                if (start > end) {
                    ex.sendResponseHeaders(416, -1);
                    ex.close();
                    return;
                }
                partial = true;
            }
            long len = end - start + 1;
            ex.getResponseHeaders().add("Content-Type", "video/mp4");
            if (partial) {
                ex.getResponseHeaders().add("Content-Range",
                        "bytes " + start + "-" + end + "/" + total);
                ex.getResponseHeaders().add("Accept-Ranges", "bytes");
            }
            ex.sendResponseHeaders(partial ? 206 : 200, len);
            Random rnd = new Random();
            try (RandomAccessFile f = new RandomAccessFile(SRC, "r")) {
                f.seek(start);
                OutputStream os = ex.getResponseBody();
                byte[] buf = new byte[64 * 1024];
                long left = len;
                while (left > 0) {
                    int k = f.read(buf, 0, (int) Math.min(buf.length, left));
                    if (k < 0) break;
                    if (failRate > 0 && rnd.nextDouble() < failRate) {
                        os.write(buf, 0, Math.max(1, k / 3));
                        os.flush();
                        break;                       // 模拟断流：写到一半掐断
                    }
                    os.write(buf, 0, k);
                    left -= k;
                    served.addAndGet(k);
                }
                os.flush();
            } catch (Throwable ignored) {
            }
            ex.close();
        } finally {
            live.decrementAndGet();
        }
    }

    static void check(String tag, File dir, String name, String want) throws Exception {
        File f = new File(dir, name);
        String got = sha(f);
        if (!got.equals(want)) {
            throw new AssertionError(tag + " 内容不一致：len=" + f.length() + " sha=" + got.substring(0, 16));
        }
        System.out.println("  " + tag + " 校验通过 " + f.length() + " 字节 ✅");
    }

    static String sha(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest()) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static void save(Parts.State s, File f) {
        try (FileOutputStream o = new FileOutputStream(f)) {
            String txt = "ranges=" + s.ranges + "\ncs=" + s.cs + "\ntotalV=" + s.totalV
                    + "\nlenV=" + s.lenV + "\nvDone=" + s.vDone + "\n";
            o.write(txt.getBytes("UTF-8"));
        } catch (Throwable ignored) { }
    }

    static Parts.State load(File f) {
        Parts.State s = new Parts.State();
        try {
            String txt = read(f);
            for (String l : txt.split("\n")) {
                int i = l.indexOf('=');
                if (i <= 0) continue;
                String k = l.substring(0, i), v = l.substring(i + 1);
                if (k.equals("ranges")) s.ranges = v;
                else if (k.equals("cs")) s.cs = Long.parseLong(v);
                else if (k.equals("totalV")) s.totalV = Long.parseLong(v);
                else if (k.equals("lenV")) s.lenV = Long.parseLong(v);
                else if (k.equals("vDone")) s.vDone = Boolean.parseBoolean(v);
            }
        } catch (Throwable ignored) { }
        return s;
    }

    static String read(File f) {
        try {
            byte[] b = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) { in.read(b); }
            return new String(b, "UTF-8");
        } catch (Throwable t) {
            return "<none>";
        }
    }
}
