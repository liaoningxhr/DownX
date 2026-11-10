import com.downx.app.HlsMaster;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.sun.net.httpserver.HttpServer;

/**
 * HlsMaster 的桌面验证台（不参与打包）。
 * 只测两件事：主清单能不能正确列出各档、读不到时会不会安静地返回空表。
 */
public class TestHls {

    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        // ① 标准主清单：三档 + 一个重复的 720P（不同码率）
        String master = "#EXTM3U\n"
                + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"en\",URI=\"audio/en.m3u8\"\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS=\"avc1.640028\"\n"
                + "v/1080/index.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720\n"
                + "v/720/index.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=2100000,RESOLUTION=1280x720\n"
                + "v/720b/index.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=854x480\n"
                + "v/480/index.m3u8\n";
        check("三档去重降序", fmt(HlsMaster.parse(master)), "1920x1080,1280x720,854x480");

        // ② 分片清单（不是主清单）：没有 STREAM-INF，应该返回空
        String media = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4.0,\nseg1.ts\n#EXTINF:4.0,\nseg2.ts\n";
        check("分片清单不误判", String.valueOf(HlsMaster.parse(media).size()), "0");

        // ③ 主清单但没写 RESOLUTION：返回空，调用方会退回单条直链
        String noRes = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=900000\nv/a.m3u8\n";
        check("无 RESOLUTION", String.valueOf(HlsMaster.parse(noRes).size()), "0");

        // ④ 竖屏：清单里写的是 1080x1920，parse 必须保留真实宽高（下载按 height 匹配）
        String portrait = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1080x1920\np.m3u8\n";
        check("竖屏保留真实宽高", fmt(HlsMaster.parse(portrait)), "1080x1920");

        // ④b 命名一律取短边：竖屏不叫 1920P，横屏也不重复
        check("竖屏叫 1080P", HlsMaster.label(1080, 1920), "1080p");
        check("横屏叫 1080P", HlsMaster.label(1920, 1080), "1080p");
        check("4K 叫 2160P", HlsMaster.label(3840, 2160), "2160p");

        // ⑤ 真发一次 HTTP：本地服务器给主清单
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/m.m3u8", ex -> {
            byte[] b = master.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/vnd.apple.mpegurl");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        srv.createContext("/404.m3u8", ex -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        srv.start();
        int port = srv.getAddress().getPort();
        List<int[]> got = HlsMaster.renditions("http://127.0.0.1:" + port + "/m.m3u8", null);
        check("HTTP 读主清单", fmt(got), "1920x1080,1280x720,854x480");

        List<int[]> miss = HlsMaster.renditions("http://127.0.0.1:" + port + "/404.m3u8", null);
        check("404 安静返回空", String.valueOf(miss.size()), "0");
        srv.stop(0);

        System.out.println(failed == 0 ? "\n全部通过（9 项）" : "\n有 " + failed + " 项失败");
        if (failed != 0) System.exit(1);
    }

    private static String fmt(List<int[]> rs) {
        StringBuilder sb = new StringBuilder();
        for (int[] r : rs) {
            if (sb.length() > 0) sb.append(',');
            sb.append(r[0]).append('x').append(r[1]);
        }
        return sb.toString();
    }

    private static void check(String name, String got, String want) {
        boolean ok = want.equals(got);
        if (!ok) failed++;
        System.out.println((ok ? "  OK   " : "  FAIL ") + name
                + (ok ? "" : "  期望 " + want + "，实际 " + got));
    }
}
