package com.downx.app;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 日志文件真实读写验证（纯 Java，在主机临时目录上真写盘、真读回）。
 *
 * 编解码对了不等于落盘对了：这里专门验证
 *   - 追加到不存在的文件不能让调用方以为写成了（否则其余记录会被一条顶掉）；
 *   - 整体重写是原子替换，不留 .tmp/.bak；
 *   - "写一半断掉"的现场能被下一次读取自动修复（残留 tmp / 只剩 bak）。
 *
 * 用法：sh tests/history/run.sh
 */
public class RF {

    private static int pass, fail;

    private static void check(String what, boolean ok) {
        if (ok) pass++;
        else {
            fail++;
            System.out.println("  FAIL  " + what);
        }
    }

    private static void eq(String what, Object a, Object b) {
        check(what + "（实际=" + a + "，期望=" + b + "）", a == null ? b == null : a.equals(b));
    }

    private static String join(List<String> l) {
        StringBuilder b = new StringBuilder();
        for (String s : l) b.append(s).append('\u0001');
        return b.toString();
    }

    private static void write(File f, String s) throws Exception {
        FileOutputStream fo = new FileOutputStream(f);
        try {
            fo.write(s.getBytes("UTF-8"));
        } finally {
            fo.close();
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== 日志文件真实读写验证 ==");
        File dir = new File(System.getProperty("java.io.tmpdir"), "downx-rf-" + System.nanoTime());
        dir.mkdirs();

        // 1) 空目录
        List<String> out = new ArrayList<>();
        check("空目录读到 -1（触发老数据迁移）", RecFile.read(dir, out) == -1);

        // 2) 文件不存在时追加必须失败 —— 只写这一条会让其余记录凭空消失
        check("文件不存在时 append 返回 false", !RecFile.append(dir, "只有这一条"));

        // 3) 整体重写
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            lines.add(RecLog.encode(new String[]{
                    "视频_" + i + "\t标记😀.mp4", "作者\n第二行", "https://x/" + i, "", "video",
                    "", "k" + i, "1", "" + (100 + i), "v"}));
        }
        check("整体重写成功", RecFile.rewrite(dir, lines));
        out.clear();
        eq("读回行数", RecFile.read(dir, out), 10);
        eq("顺序与内容一致", join(out), join(lines));
        check("没有 .tmp 残留", !new File(dir, "items.log.tmp").exists());
        check("没有 .bak 残留", !new File(dir, "items.log.bak").exists());

        // 4) 追加
        String add = RecLog.encode(new String[]{"追加.mp4", "", "", "", "video", "", "", "0", "999", ""});
        check("追加成功", RecFile.append(dir, add));
        out.clear();
        eq("追加后行数", RecFile.read(dir, out), 11);
        eq("追加的在文件末尾（最新）", out.get(10), add);
        eq("老的第一行没被挪动", out.get(0), lines.get(0));

        // 5) 崩溃现场 A：留下写了一半的 tmp —— 原文件必须完好，不能被半份顶掉
        write(new File(dir, "items.log.tmp"), "半截记录\t没写完\n");
        out.clear();
        eq("残留 tmp 不影响读取", RecFile.read(dir, out), 11);
        eq("原文件内容未变", out.get(0), lines.get(0));

        // 6) 崩溃现场 B：主文件没了、只剩 .bak（整体重写恰好在两次改名之间断掉）
        File f = new File(dir, "items.log");
        File bak = new File(dir, "items.log.bak");
        f.renameTo(bak);
        out.clear();
        eq("主文件丢失时自动从 .bak 恢复", RecFile.read(dir, out), 11);
        check("恢复后主文件回来了", f.isFile());
        check("恢复后 bak 已清掉", !bak.exists());

        // 7) 一万行真实写盘 / 读回 / 追加，计时
        List<String> big = new ArrayList<>(10000);
        for (int i = 0; i < 10000; i++) {
            big.add(RecLog.encode(new String[]{
                    "视频_" + i + "\t😀.mp4", "作者\n第二行", "https://x/" + i, "", "video",
                    "", "k" + i, "1", "" + i, "v"}));
        }
        long t0 = System.currentTimeMillis();
        check("一万行整体重写成功", RecFile.rewrite(dir, big));
        long t1 = System.currentTimeMillis();
        out.clear();
        eq("一万行读回", RecFile.read(dir, out), 10000);
        long t2 = System.currentTimeMillis();
        eq("第一行未变", out.get(0), big.get(0));
        eq("最后一行未变", out.get(9999), big.get(9999));

        long t3 = System.currentTimeMillis();
        boolean allAdd = true;
        for (int i = 0; i < 1000; i++) allAdd &= RecFile.append(dir, big.get(i));
        long t4 = System.currentTimeMillis();
        check("追加一千次都成功", allAdd);
        out.clear();
        eq("追加后总行数", RecFile.read(dir, out), 11000);
        System.out.println("  一万行：整体重写 " + (t1 - t0) + " ms，读回 " + (t2 - t1)
                + " ms，追加 1000 行共 " + (t4 - t3) + " ms");

        // 8) 重写把重复行抹掉（不只清内存，文件也要变小）
        check("重写回一万行", RecFile.rewrite(dir, big));
        out.clear();
        eq("压缩后回到一万行", RecFile.read(dir, out), 10000);

        // 9) 空列表 = 空文件（清空列表后不能留下老内容）
        check("空列表重写成功", RecFile.rewrite(dir, new ArrayList<String>()));
        out.clear();
        eq("空文件读到 0 行", RecFile.read(dir, out), 0);
        check("空文件仍在（下次不再走迁移分支）", f.isFile());

        // 10) 目录位置被一个文件占住：不能崩，要返回 false 让调用方知道没写成
        File notDir = new File(dir, "notdir");
        write(notDir, "x");
        check("目录不可用时返回 false 而不是抛异常", !RecFile.rewrite(notDir, big));
        check("目录不可用时 append 也返回 false", !RecFile.append(notDir, "x"));

        System.out.println();
        System.out.println(fail == 0
                ? "全部通过：" + pass + " 项"
                : ("通过 " + pass + " 项，失败 " + fail + " 项"));
        if (fail > 0) System.exit(1);
    }
}
