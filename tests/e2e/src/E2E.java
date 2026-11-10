import com.downx.app.BiliFetcher;
import com.downx.app.ChunkedFetch;
import com.downx.app.IgFetcher;
import com.downx.app.Net;
import com.downx.app.Parts;
import com.downx.app.TikTokFetcher;
import com.downx.app.TweetFetcher;
import com.downx.app.XhsFetcher;
import com.downx.app.YoutubeFetcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 桌面端"真跑"验证台：拿真实网络数据走一遍 App 的解析 + 下载内核。
 *
 * 测什么：
 *   1) 该平台能不能解析出条目（多条 = 多清晰度可选）
 *   2) 每一档直链能不能真的下（Range 探测：206 + 总长 + content-type）
 *   3) 挑最低档完整下一遍，校验字节数/文件头/哈希，再删掉
 *
 * 不测什么：相册入库、前台服务、播放器兼容——那些只能在真机上验收。
 */
public class E2E {

    static final File WORK = new File("work");
    static final String[][] HDR_BILI = {
            {"User-Agent", Net.UA}, {"Referer", "https://www.bilibili.com/"}};
    static final String[][] HDR_TW = {
            {"User-Agent", Net.UA}, {"Referer", "https://x.com/"}};
    static final String[][] HDR_YT = {
            {"User-Agent", Net.UA}, {"Referer", "https://www.youtube.com/"}};
    static final String[][] HDR_TT = {
            {"User-Agent", Net.UA}, {"Referer", "https://www.tiktok.com/"}};

    static int pass = 0, fail = 0;
    static final List<String> notes = new ArrayList<>();

    public static void main(String[] a) throws Exception {
        WORK.mkdirs();
        String only = a.length > 0 ? a[0] : "";

        // 直接传链接：sh tests/e2e/run.sh u "https://..." ["https://..."]
        if ("u".equals(only)) {
            for (int i = 1; i < a.length; i++) oneUrl(a[i]);
            summary();
            return;
        }

        if (only.isEmpty() || "bili".equals(only)) bili();
        if (only.isEmpty() || "x".equals(only)) x();
        if (only.isEmpty() || "tiktok".equals(only)) tiktok();
        if (only.isEmpty() || "yt".equals(only)) youtube();

        summary();
    }

    static void summary() {
        System.out.println("\n================ 汇总 ================");
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        for (String n : notes) System.out.println("  · " + n);
        clean();
    }

    // ---------------- 任意链接 ----------------

    /** 按域名分发到对应抓取器，把"解析出了什么"和"能不能下"分开报 */
    static void oneUrl(String link) {
        section("单条链接");
        System.out.println("  链接：" + link);
        String low = link.toLowerCase();
        String tag = "url", plat = "unknown";
        String[][] hdr = new String[][]{{"User-Agent", Net.UA}};
        long t0 = System.currentTimeMillis();
        TweetFetcher.Result r;
        try {
            if (low.contains("instagram.com")) {
                plat = "Instagram"; tag = "ig";
                hdr = new String[][]{{"User-Agent", Net.UA}, {"Referer", "https://www.instagram.com/"}};
                r = IgFetcher.fetchSync(link);
            } else if (low.contains("xiaohongshu.com") || low.contains("xhslink.")) {
                plat = "小红书"; tag = "xhs";
                hdr = new String[][]{{"User-Agent", Net.UA}, {"Referer", "https://www.xiaohongshu.com/"}};
                r = XhsFetcher.fetchSync(link);
            } else if (low.contains("x.com") || low.contains("twitter.com")) {
                plat = "X"; tag = "x"; hdr = HDR_TW;
                r = TweetFetcher.fetchSync(link);
            } else if (low.contains("bilibili.com") || low.contains("b23.tv")) {
                plat = "B 站"; tag = "bili"; hdr = HDR_BILI;
                r = BiliFetcher.fetchSync(link);
            } else if (low.contains("youtube.com") || low.contains("youtu.be")) {
                plat = "YouTube"; tag = "yt"; hdr = HDR_YT;
                r = YoutubeFetcher.fetchSync(link);
            } else if (low.contains("tiktok.com")) {
                plat = "TikTok"; tag = "tiktok"; hdr = HDR_TT;
                r = TikTokFetcher.fetchSync(link);
            } else {
                block("平台识别", "不认识这个域名：" + link);
                return;
            }
        } catch (Throwable t) {
            String diag = "";
            if ("Instagram".equals(plat) && IgFetcher.lastLog != null) diag = "；各通道：" + IgFetcher.lastLog;
            block(plat + " 解析", msg(t) + diag);
            return;
        }
        long ms = System.currentTimeMillis() - t0;
        System.out.println("  解析耗时 " + ms + "ms");
        report(r, hdr);

        int v = videoCount(r);
        Set<String> res = resolutions(r);
        if (v == 0) block(plat + " 解析", "没解析出任何视频/图片条目");
        else if (res.size() >= 2) ok(plat + " 多清晰度", "可选 " + res);
        else if (res.size() == 1) block(plat + " 多清晰度", "只有 " + res + "（这条本身可能只给一档）");
        else ok(plat + " 解析", "共 " + r.items.size() + " 条（无分辨率标签）");

        // 音轨单独探一次：IG 的 DASH 是音视频分离的
        for (TweetFetcher.Media m : r.items) {
            if (m.audioUrl != null && !m.audioUrl.isEmpty()) {
                Probe1 pa = probe(m.audioUrl, hdr);
                System.out.println("       音轨 HTTP=" + pa.code + "  " + mb(pa.total) + "  " + pa.ctype);
                if (pa.code == 200 || pa.code == 206) ok(plat + " 音轨可下", mb(pa.total));
                else block(plat + " 音轨", "HTTP " + pa.code);
                break;
            }
        }

        downloadAll(r, tag, hdr, 60);
    }

    // ---------------- B 站 ----------------

    static void bili() {
        section("B 站");
        String bv = null;
        try {
            String pop = Net.get("https://api.bilibili.com/x/web-interface/popular?ps=20&pn=1",
                    HDR_BILI, 12000);
            JSONArray list = new JSONObject(pop).getJSONObject("data").getJSONArray("list");
            for (int i = 0; i < list.length(); i++) {
                JSONObject v = list.getJSONObject(i);
                String b = v.optString("bvid");
                int dur = v.optInt("duration", 0);
                // 挑一条 3~15 分钟的：太短测不出分片，太长下载费时间
                if (!b.isEmpty() && dur >= 180 && dur <= 900) { bv = b; break; }
            }
        } catch (Throwable t) {
            System.out.println("  取热门列表失败：" + t);
        }
        if (bv == null) { block("B 站", "热门列表里没有合适的视频"); return; }
        String link = "https://www.bilibili.com/video/" + bv;
        System.out.println("  测试链接：" + link);

        TweetFetcher.Result r;
        try {
            r = BiliFetcher.fetchSync(link);
        } catch (Throwable t) {
            block("B 站", "解析抛异常：" + t.getMessage());
            return;
        }
        report(r, HDR_BILI);
        if (videoCount(r) >= 2) ok("B 站列出多个清晰度", "共 " + videoCount(r) + " 档");
        else block("B 站多清晰度", "只列出 " + videoCount(r) + " 档（未登录通常只有 360P/480P）");

        downloadAll(r, "bili", HDR_BILI, 60);
    }

    // ---------------- X ----------------

    static void x() {
        section("X（Twitter）");
        String id = findTweetWithVideo();
        if (id == null) { block("X 解析", "没找到带视频的公开推文"); return; }
        String link = "https://x.com/i/status/" + id;
        System.out.println("  测试链接：" + link);
        TweetFetcher.Result r;
        try {
            r = TweetFetcher.fetchSync(link);
        } catch (Throwable t) {
            block("X 解析", "抛异常：" + t.getMessage());
            return;
        }
        report(r, HDR_TW);
        Set<String> res = resolutions(r);
        if (res.size() >= 2) ok("X 多清晰度", "可选 " + res);
        else block("X 多清晰度", "只有 " + res + "（这条推文本身可能只给了一档）");
        downloadAll(r, "x", HDR_TW, 40);
    }

    /** 从几个媒体账号的公开时间线里找一条带视频的推文 */
    static String findTweetWithVideo() {
        String[] users = {"NASA", "SpaceX", "NatGeo", "BBCNews"};
        for (String u : users) {
            try {
                String html = Net.get("https://syndication.twitter.com/srv/timeline-profile/screen-name/" + u,
                        new String[][]{{"User-Agent", Net.UA}}, 15000);
                if (html == null || html.isEmpty()) continue;
                Set<String> ids = new LinkedHashSet<>();
                Matcher m = Pattern.compile("\"id_str\"\\s*:\\s*\"(\\d{6,25})\"").matcher(html);
                while (m.find() && ids.size() < 6) ids.add(m.group(1));
                for (String id : ids) {
                    try {
                        String j = Net.get("https://cdn.syndication.twimg.com/tweet-result?id=" + id
                                + "&lang=en&token=a", new String[][]{{"User-Agent", Net.UA}}, 10000);
                        if (j != null && j.contains("video_info")) return id;
                    } catch (Throwable ignored) { }
                }
            } catch (Throwable ignored) { }
        }
        return null;
    }

    // ---------------- TikTok ----------------

    static void tiktok() {
        section("TikTok");
        String id = null;
        try {
            String html = Net.get("https://www.tiktok.com/@tiktok",
                    new String[][]{{"User-Agent", Net.UA}}, 15000);
            Matcher m = Pattern.compile("/@tiktok/video/(\\d{10,25})").matcher(html == null ? "" : html);
            if (m.find()) id = m.group(1);
        } catch (Throwable t) {
            System.out.println("  打开主页失败：" + t);
        }
        if (id == null) { block("TikTok 解析", "主页里没抓到视频 ID（可能需要登录/风控）"); return; }
        String link = "https://www.tiktok.com/@tiktok/video/" + id;
        System.out.println("  测试链接：" + link);
        TweetFetcher.Result r;
        try {
            r = TikTokFetcher.fetchSync(link);
        } catch (Throwable t) {
            block("TikTok 解析", "抛异常：" + t.getMessage());
            return;
        }
        report(r, HDR_TT);
        downloadAll(r, "tiktok", HDR_TT, 40);
    }

    // ---------------- YouTube ----------------

    static void youtube() {
        section("YouTube");
        String link = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
        System.out.println("  测试链接：" + link);
        TweetFetcher.Result r;
        try {
            r = YoutubeFetcher.fetchSync(link);
        } catch (Throwable t) {
            block("YouTube 解析", "抛异常：" + t.getMessage());
            return;
        }
        report(r, HDR_YT);
        boolean hasAudio = false;
        for (TweetFetcher.Media m : r.items) if (m.audioUrl != null && !m.audioUrl.isEmpty()) hasAudio = true;
        if (hasAudio) ok("YouTube 分离音轨", "视频流带 audioUrl，真机会走两遍下载 + 合轨");
        else block("YouTube 分离音轨", "没有 audioUrl（可能是音画合一的档）");
        downloadAll(r, "yt", HDR_YT, 40);
    }

    // ---------------- 通用：报告 / 探测 / 下载 ----------------

    static void report(TweetFetcher.Result r, String[][] hdr) {
        System.out.println("  平台=" + r.platform + " 作者=" + r.author + " @" + r.handle
                + " 条目=" + r.items.size() + " 文本=" + head(r.text, 30));
        int i = 0;
        for (TweetFetcher.Media m : r.items) {
            Probe1 p = probe(m.url, hdr);
            System.out.println("   [" + (i++) + "] " + m.kind + "  " + m.label
                    + "  pick=" + (m.pick == null ? "-" : m.pick)
                    + "  audio=" + (m.audioUrl != null && !m.audioUrl.isEmpty() ? "有" : "无")
                    + "  HTTP=" + p.code + "  " + mb(p.total) + "  " + p.ctype);
            System.out.println("       url: " + shortUrl(m.url));
        }
    }

    static void downloadAll(TweetFetcher.Result r, String tag, String[][] hdr, int maxMb) {
        List<TweetFetcher.Media> vids = new ArrayList<>();
        for (TweetFetcher.Media m : r.items) if ("video".equals(m.kind) && m.pick == null) vids.add(m);
        if (vids.isEmpty()) { block(tag + " 下载", "没有可直接下载的视频流（可能全是 HLS 多档）"); return; }

        // 按探测到的体积排序，先下最小的那档
        List<long[]> sizes = new ArrayList<>();
        for (TweetFetcher.Media m : vids) {
            Probe1 p = probe(m.url, hdr);
            sizes.add(new long[]{p.total, vids.indexOf(m)});
        }
        sizes.sort((a, b) -> Long.compare(a[0], b[0]));

        int done = 0;
        for (long[] s : sizes) {
            if (done >= 2) break;                        // 最多下两档，够证明"换档能下到不同的流"
            TweetFetcher.Media m = vids.get((int) s[1]);
            if (s[0] <= 0 || s[0] > maxMb * 1024L * 1024L) continue;
            String name = tag + "_" + done + "_" + safe(m.label) + ".mp4";
            boolean one = download(m, hdr, name, s[0]);
            if (one) done++;
        }
        if (done == 0) block(tag + " 下载", "没有任何档位下载成功（体积上限 " + maxMb + "MB，具体失败原因见上）");
    }

    /** 走真实并发引擎下一遍，校验长度 + mp4 文件头 + SHA-256 */
    static boolean download(TweetFetcher.Media m, String[][] hdr, String name, long want) {
        File out = new File(WORK, name);
        if (out.exists()) out.delete();
        Parts.State st = new Parts.State();
        st.id = name;
        final long[] last = {0};
        ChunkedFetch.Ctl ctl = new ChunkedFetch.Ctl() {
            public void gate() { }
            public void progress(long d, long t) { last[0] = d; }
            public void persist() { }
            public void log(String s) { }
        };
        long t0 = System.currentTimeMillis();
        String mode = "分片并发";
        try {
            ChunkedFetch.Result rr = new ChunkedFetch(out, m.url, referer(hdr), st, ctl).run();
            if (!rr.used) {
                // 和 DlService 一样：并发引擎判定不适合（<4MB / 不支持 Range）就退回单连接
                mode = "单连接（引擎退回）";
                single(m.url, hdr, out, 0);
            }
            long ms = System.currentTimeMillis() - t0;
            long got = out.length();
            String sha = sha256(out);
            String head4 = headBytes(out, 12);
            boolean sizeOk = got == want && got > 0;
            boolean mp4 = head4.contains("ftyp") || head4.contains("moov") || head4.contains("styp");
            System.out.println("   下载 " + name + "：" + mb(got) + " / 期望 " + mb(want)
                    + "  " + mode + "  用时=" + ms + "ms  sha=" + sha.substring(0, Math.min(12, sha.length()))
                    + "  头=" + head4);
            if (sizeOk && mp4) {
                ok("下载完整（" + m.label + "）", mb(got) + "，" + mode);
                out.delete();
                return true;
            }
            block("下载校验（" + m.label + "）",
                    "sizeOk=" + sizeOk + " mp4=" + mp4 + " got=" + got + " want=" + want);
            out.delete();
            return false;
        } catch (Throwable t) {
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            String[] lines = sw.toString().split("\n");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(6, lines.length); i++) sb.append("\n      ").append(lines[i].trim());
            block("下载异常（" + m.label + "）", "已写 " + out.length() + " 字节" + sb);
            out.delete();
            return false;
        }
    }

    /** 单连接下载：并发引擎判定不适合时的退路，行为与 DlService.fetch 一致 */
    static void single(String url, String[][] hdr, File out, long from) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        for (String[] h : hdr) c.setRequestProperty(h[0], h[1]);
        if (from > 0) c.setRequestProperty("Range", "bytes=" + from + "-");
        int code = c.getResponseCode();
        if (code >= 400) throw new Exception("单连接 HTTP " + code);
        try (InputStream in = c.getInputStream();
             FileOutputStream fo = new FileOutputStream(out, from > 0)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) {
                fo.write(b, 0, n);
                if (from == 0) { /* 不记进度，验证台只求下完 */ }
            }
        } finally {
            c.disconnect();
        }
    }

    static final class Probe1 {
        int code;
        long total;
        String ctype = "";
    }

    /** Range: bytes=0-0 探测：206 说明支持分片，Content-Range 里带总长 */
    static Probe1 probe(String url, String[][] hdr) {
        Probe1 p = new Probe1();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setRequestProperty("Range", "bytes=0-0");
            for (String[] h : hdr) c.setRequestProperty(h[0], h[1]);
            p.code = c.getResponseCode();
            p.ctype = String.valueOf(c.getContentType());
            String cr = c.getHeaderField("Content-Range");
            if (cr != null) {
                Matcher m = Pattern.compile("/(\\d+)").matcher(cr);
                if (m.find()) p.total = Long.parseLong(m.group(1));
            } else if (c.getContentLengthLong() > 0) {
                p.total = c.getContentLengthLong();
            }
        } catch (Throwable t) {
            p.code = -1;
            p.ctype = String.valueOf(t.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
        return p;
    }

    static int videoCount(TweetFetcher.Result r) {
        int n = 0;
        for (TweetFetcher.Media m : r.items) if (!"photo".equals(m.kind)) n++;
        return n;
    }

    static Set<String> resolutions(TweetFetcher.Result r) {
        Set<String> s = new LinkedHashSet<>();
        for (TweetFetcher.Media m : r.items) {
            if ("photo".equals(m.kind)) continue;
            Matcher mm = Pattern.compile("(\\d{3,4})\\s*[pP]").matcher(m.label == null ? "" : m.label);
            if (mm.find()) s.add(mm.group(1) + "P");
        }
        return s;
    }

    static String referer(String[][] hdr) {
        for (String[] h : hdr) if ("Referer".equalsIgnoreCase(h[0])) return h[1];
        return null;
    }

    static String sha256(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Throwable t) {
            return "ERR";
        }
    }

    static String headBytes(File f, int n) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[n];
            int r = in.read(b);
            if (r <= 0) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < r; i++) {
                char ch = (char) (b[i] & 0xFF);
                sb.append(ch >= 32 && ch < 127 ? ch : '.');
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    static void clean() {
        File[] fs = WORK.listFiles();
        long bytes = 0;
        if (fs != null) {
            for (File f : fs) { bytes += f.length(); f.delete(); }
        }
        System.out.println("work 目录已清空（下载产物在每项验证后即删，残留 "
                + (fs == null ? 0 : fs.length) + " 个 / " + bytes + " 字节）");
    }

    static void section(String name) {
        System.out.println("\n===== " + name + " =====");
    }

    static void ok(String what, String detail) {
        pass++;
        System.out.println("  ✔ " + what + "：" + detail);
    }

    static void block(String what, String why) {
        fail++;
        System.out.println("  ✘ " + what + "：" + why);
        notes.add(what + " —— " + why);
    }

    static String mb(long b) {
        if (b <= 0) return "?";
        return String.format("%.1fMB", b / 1048576.0);
    }

    static String msg(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.toString() : m;
    }

    /** 只留 host + 路径尾部，够看分辨率片段又不刷屏 */
    static String shortUrl(String u) {
        if (u == null || u.length() < 60) return String.valueOf(u);
        return u.substring(0, 34) + " … " + u.substring(Math.max(0, u.length() - 42));
    }

    static String head(String s, int n) {
        if (s == null) return "";
        s = s.replace('\n', ' ');
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    static String safe(String s) {
        if (s == null) return "x";
        return s.replaceAll("[^\\w\\u4e00-\\u9fa5]+", "_");
    }
}
