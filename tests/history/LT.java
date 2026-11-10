package com.downx.app;

import java.util.ArrayList;
import java.util.List;

/**
 * 追加写日志的编解码 / 合并验证（纯逻辑，不依赖 Android、不联网）。
 *
 * 覆盖三个最容易出事的点：
 *   1. 转义：标题里带换行、制表符、反斜杠、emoji 时，不能把"一行"写坏（否则整份记录错位）；
 *   2. 合并：同一条记录被更新多次后，后写要覆盖先写，位置仍按第一次出现；
 *   3. 万条：一万条记录编码→解码→合并，一条不丢、顺序不乱。
 *
 * 用法：sh tests/history/run.sh
 */
public class LT {

    private static int pass, fail;

    private static void check(String what, boolean ok) {
        if (ok) {
            pass++;
        } else {
            fail++;
            System.out.println("  FAIL  " + what);
        }
    }

    private static void eq(String what, Object a, Object b) {
        check(what + "（实际=" + a + "，期望=" + b + "）", a == null ? b == null : a.equals(b));
    }

    /** 一条记录的最小载体：只保留能验证编解码的字段 */
    static class Rec {
        String name, sub, pageUrl, thumb, kind, uri, key, orientation;
        boolean visible;
        long ts;

        Rec(String name, long ts) {
            this.name = name;
            this.ts = ts;
            this.sub = "";
            this.pageUrl = "";
            this.thumb = "";
            this.kind = "video";
            this.uri = "";
            this.key = "";
            this.orientation = "";
            this.visible = true;
        }

        String[] f() {
            return new String[]{name, sub, pageUrl, thumb, kind, uri, key,
                    visible ? "1" : "0", Long.toString(ts), orientation};
        }

        Rec copy() {
            Rec r = new Rec(name, ts);
            r.sub = sub;
            r.pageUrl = pageUrl;
            r.thumb = thumb;
            r.kind = kind;
            r.uri = uri;
            r.key = key;
            r.orientation = orientation;
            r.visible = visible;
            return r;
        }
    }

    private static String key(Rec r) {
        return (r.name == null ? "" : r.name) + "\u0000" + r.ts;
    }

    public static void main(String[] args) {
        System.out.println("== 追加写日志验证 ==");
        testRoundTrip();
        testBadLines();
        testMerge();
        testTenThousand();
        testMigrationOrder();
        testWriteAmplification();
        System.out.println();
        System.out.println(fail == 0
                ? "全部通过：" + pass + " 项"
                : ("通过 " + pass + " 项，失败 " + fail + " 项"));
        if (fail > 0) System.exit(1);
    }

    // ---------------------------------------------------------------- 1. 往返

    private static void testRoundTrip() {
        Rec r = new Rec("普通名字.mp4", 1L);
        String[] f = RecLog.decode(RecLog.encode(r.f()));
        check("普通字段能解出来", f != null);
        eq("文件名", f[0], "普通名字.mp4");
        eq("kind", f[4], "video");
        eq("visible", f[7], "1");
        eq("ts", f[8], "1");

        // 标题里什么都可能有：换行、制表符、反斜杠、emoji
        Rec nasty = new Rec("这是标题\n第二行\t带制表符\\反斜杠😀🎬.mp4", 1700000000123L);
        nasty.sub = "作者\\n不是换行";
        nasty.thumb = "https://x/y?a=1&b=2#片段\r\n尾巴";
        nasty.orientation = "v";
        nasty.visible = false;
        String line = RecLog.encode(nasty.f());
        check("一行里不能有真实换行", line.indexOf('\n') < 0);
        check("一行里不能有真实回车", line.indexOf('\r') < 0);
        String[] g = RecLog.decode(line);
        check("脏字段能解出来", g != null);
        eq("换行+制表符+反斜杠+emoji 原样还原", g[0], nasty.name);
        eq("字面量反斜杠 n 不被当换行", g[1], "作者\\n不是换行");
        eq("封面里的换行还原", g[3], nasty.thumb);
        eq("visible=0", g[7], "0");
        eq("orientation", g[9], "v");

        // 空字段 + 全空记录
        Rec empty = new Rec("", 0L);
        String[] h = RecLog.decode(RecLog.encode(empty.f()));
        check("空文件名也能解（由 HistoryStore 决定丢不丢）", h != null && "".equals(h[0]));

        // 超长标题（社交平台的文案可以很长）
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) sb.append("长");
        Rec big = new Rec(sb + ".mp4", 2L);
        String[] k = RecLog.decode(RecLog.encode(big.f()));
        eq("2000 字标题长度不变", k[0].length(), big.name.length());
        eq("2000 字标题内容一致", k[0], big.name);

        // 只有换行的标题
        Rec nl = new Rec("\n\r\n", 3L);
        eq("整串都是换行的字段", RecLog.decode(RecLog.encode(nl.f()))[0], "\n\r\n");
    }

    // ---------------------------------------------------------------- 2. 坏行

    private static void testBadLines() {
        check("null 行返回 null", RecLog.decode(null) == null);
        check("空行返回 null", RecLog.decode("") == null);
        check("版本号不对返回 null", RecLog.decode("2\ta\tb\tc\td\te\tf\n1\t0\t1\t") == null);
        check("字段不足返回 null", RecLog.decode("1\ta\tb\tc") == null);
        check("没有版本号返回 null", RecLog.decode("a\tb\tc") == null);
        // 多出字段（将来加了新字段、用老版本 App 读）应该能读，且忽略多余部分
        String more = RecLog.encode(new Rec("x.mp4", 9L).f()) + "\t新字段";
        check("多余字段被忽略而不是报错", RecLog.decode(more) != null);
        check("多余字段被忽略后名字还对", "x.mp4".equals(RecLog.decode(more)[0]));
    }

    // ---------------------------------------------------------------- 3. 合并

    private static void testMerge() {
        // 文件顺序 旧→新，合并后应 新→前
        List<Rec> file = new ArrayList<>();
        Rec a = new Rec("a.mp4", 1L);
        Rec b = new Rec("b.mp4", 2L);
        Rec c = new Rec("c.mp4", 3L);
        file.add(a);
        file.add(b);
        file.add(c);
        List<Rec> merged = RecLog.merge(file, LT::key);
        eq("合并后条数", merged.size(), 3);
        eq("最新的在最前", merged.get(0).name, "c.mp4");
        eq("最老的挤到最后", merged.get(2).name, "a.mp4");

        // 同一条记录更新了两次（补竖屏标记）：后写的胜出，位置不变
        Rec b2 = b.copy();
        b2.orientation = "h";
        Rec b3 = b.copy();
        b3.orientation = "v";
        file.add(b2);
        file.add(b3);
        List<Rec> m2 = RecLog.merge(file, LT::key);
        eq("更新不增加条数", m2.size(), 3);
        eq("位置仍在中间", m2.get(1).name, "b.mp4");
        eq("取最后一次写入的值", m2.get(1).orientation, "v");

        // 边界：同 ts 不同名，不能互相覆盖
        List<Rec> same = new ArrayList<>();
        same.add(new Rec("x1.mp4", 100L));
        same.add(new Rec("x2.mp4", 100L));
        eq("同时间戳的不同文件互不覆盖", RecLog.merge(same, LT::key).size(), 2);

        // 边界：同名但不同时间（重新下载过），是两条，都要留
        List<Rec> again = new ArrayList<>();
        again.add(new Rec("dup.mp4", 100L));
        again.add(new Rec("dup.mp4", 200L));
        eq("同名不同次是两条", RecLog.merge(again, LT::key).size(), 2);

        // 空
        eq("空日志", RecLog.merge(new ArrayList<Rec>(), LT::key).size(), 0);
    }

    // ---------------------------------------------------------------- 4. 一万条

    private static void testTenThousand() {
        final int N = 10000;
        StringBuilder file = new StringBuilder(N * 300);
        List<Rec> expect = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            Rec r = new Rec("视频_" + i + "\t带制表符😀.mp4", 1700000000000L + i);
            r.sub = "作者 " + i + "\n第二行";
            r.orientation = (i % 3 == 0) ? "v" : (i % 3 == 1 ? "h" : "");
            r.visible = (i % 7) != 0;
            expect.add(r);
            file.append(RecLog.encode(r.f())).append('\n');
        }

        // 逐行解回（模拟真实加载路径）
        List<Rec> fileOrder = new ArrayList<>();
        int bad = 0;
        for (String line : file.toString().split("\n")) {
            if (line.isEmpty()) continue;
            String[] f = RecLog.decode(line);
            if (f == null) {
                bad++;
                continue;
            }
            Rec r = new Rec(f[0], Long.parseLong(f[8]));
            r.sub = f[1];
            r.orientation = f[9];
            r.visible = "1".equals(f[7]);
            fileOrder.add(r);
        }
        eq("一万行没有一行解析失败", bad, 0);
        eq("解回一万条", fileOrder.size(), N);

        List<Rec> merged = RecLog.merge(fileOrder, LT::key);
        eq("合并后仍是一万条", merged.size(), N);
        eq("第一条是最新写入的", merged.get(0).name, expect.get(N - 1).name);
        eq("最后一条是最早写入的", merged.get(N - 1).name, expect.get(0).name);
        boolean allOk = true;
        for (int i = 0; i < N; i++) {
            Rec got = merged.get(i);
            Rec want = expect.get(N - 1 - i);
            if (!got.name.equals(want.name) || got.ts != want.ts
                    || !got.orientation.equals(want.orientation)
                    || got.visible != want.visible || !got.sub.equals(want.sub)) {
                allOk = false;
                System.out.println("  第一条对不上：i=" + i + " got=" + got.name + " want=" + want.name);
                break;
            }
        }
        check("一万条顺序与字段全对", allOk);
        System.out.println("  一万条日志体积约 " + (file.length() / 1024) + " KB，"
                + "平均每条 " + (file.length() / N) + " 字节");
    }

    // ---------------------------------------------------------------- 5. 迁移等价

    private static void testMigrationOrder() {
        // 老格式：SharedPreferences 里一个数组，最新在前
        List<Rec> legacy = new ArrayList<>();
        for (int i = 0; i < 50; i++) legacy.add(new Rec("old_" + i + ".mp4", 1600000000000L + i));

        // 迁移时按倒序写进日志（文件=旧→新），加载时 merge 回来
        List<Rec> fileOrder = new ArrayList<>();
        for (int i = legacy.size() - 1; i >= 0; i--) fileOrder.add(legacy.get(i));
        List<Rec> back = RecLog.merge(fileOrder, LT::key);

        eq("迁移后条数不变", back.size(), legacy.size());
        boolean same = true;
        for (int i = 0; i < legacy.size(); i++) {
            if (!back.get(i).name.equals(legacy.get(i).name) || back.get(i).ts != legacy.get(i).ts) {
                same = false;
                break;
            }
        }
        check("迁移后顺序与内容完全一致（不会倒过来）", same);
    }

    // ---------------------------------------------------------------- 6. 写放大

    private static void testWriteAmplification() {
        final int N = 10000;
        long per = RecLog.encode(new Rec("示例视频_标题有点长度.mp4", 1700000000000L).f())
                .getBytes(java.nio.charset.Charset.forName("UTF-8")).length + 1;
        // 老做法：每加一条，重写当时全部记录
        long old = 0;
        for (int i = 1; i <= N; i++) old += (long) i * per;
        // 新做法：每加一条，只写这一条
        long now = (long) N * per;
        System.out.println("  按每条 " + per + " 字节算，下满 " + N + " 条时的累计写入：");
        System.out.println("    老做法（每条全量重写）: " + (old / 1024 / 1024) + " MB");
        System.out.println("    新做法（只追加一行）  : " + (now / 1024) + " KB");
        check("新做法的写入量至少小三个数量级", old / Math.max(now, 1) > 1000);
    }
}
