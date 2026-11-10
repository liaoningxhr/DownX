package com.downx.app;

import java.util.ArrayList;
import java.util.List;

/**
 * 记录裁剪规则验证（纯逻辑，不依赖 Android、不联网）：
 * 重点是回归今天的事故 —— 下 40 张多图，把 30 多个老视频记录挤没了。
 *
 * 用法：sh tests/history/run.sh
 */
public class HT {

    private static int pass, fail;

    /** 模拟一条记录：名字 + key + 文件还在不在 */
    static class Rec {
        String name;
        String key;
        boolean alive;

        Rec(String name, String key, boolean alive) {
            this.name = name;
            this.key = key;
            this.alive = alive;
        }
    }

    /** 和 HistoryStore.MAX 保持一致 */
    private static final int MAX = 5000;

    public static void main(String[] args) {
        // ---- 1. 今天的事故现场：30 个老视频 + 40 张新图，全部文件都在 ----
        List<Rec> list = new ArrayList<>();
        for (int i = 0; i < 40; i++) list.add(0, new Rec("多图/新帖_photo" + i + ".jpg", "k-img" + i, true));
        for (int i = 0; i < 30; i++) list.add(new Rec("抖音_老视频" + i + "_video.mp4", "k-vid" + i, true));
        RecRules.trim(list, MAX, r -> r.alive);
        check("70 条活记录一条都不能少（旧版 60 上限会吃掉 10 条）", list.size() == 70);
        check("最老的那个视频还在", list.get(list.size() - 1).name.startsWith("抖音_老视频"));

        // ---- 2. 下载量大的情况：2000 条也不掉 ----
        List<Rec> big = new ArrayList<>();
        for (int i = 0; i < 2000; i++) big.add(new Rec("v" + i + ".mp4", "k" + i, true));
        RecRules.trim(big, MAX, r -> r.alive);
        check("2000 条记录一条不掉", big.size() == 2000);

        // ---- 3. 真到上限时，先淘汰"文件已经不在"的老记录 ----
        List<Rec> over = new ArrayList<>();
        for (int i = 0; i < 2000; i++) over.add(new Rec("live" + i + ".mp4", "kl" + i, true));
        for (int i = 0; i < 60; i++) over.add(new Rec("dead" + i + ".mp4", "kd" + i, false));  // 尾部 60 条失效
        List<Rec> keepOver = new ArrayList<>(over);
        RecRules.trim(keepOver, 2000, r -> r.alive);
        check("超上限时先删失效记录", keepOver.size() == 2000 && noDead(keepOver));
        check("活记录一条没删", countAlive(keepOver) == 2000);

        // ---- 4. 全活着又真超上限：一条都不许删（宁可超，也不能让记录消失） ----
        List<Rec> full = new ArrayList<>();
        for (int i = 0; i < 700; i++) full.add(new Rec("x" + i + ".mp4", "kx" + i, true));
        RecRules.trim(full, 500, r -> r.alive);
        check("全活且超上限：活记录一条不删", full.size() == 700);
        check("最老的那条也还在", full.get(699).name.equals("x699.mp4"));

        // ---- 4b. 上万张图的极端情况：10000 条活记录照样一条不丢 ----
        List<Rec> huge = new ArrayList<>();
        for (int i = 0; i < 10000; i++) huge.add(new Rec("多图/帖子_photo" + i + ".jpg", "p" + i, true));
        RecRules.trim(huge, 5000, r -> r.alive);
        check("10000 条活记录一条不丢（上万张图也不用怕）", huge.size() == 10000);

        // ---- 5. 去重：同一帖同 key、或同名，都只留最新 ----
        List<Rec> dup = new ArrayList<>();
        dup.add(new Rec("多图/帖子A_photo1.jpg", "postA", true));   // 最新
        dup.add(new Rec("帖子A_photo1.jpg", "postA", true));        // 同 key 同图（旧）
        dup.add(new Rec("快手_来自快手的视频_video.mp4", "postB", true));
        dup.add(new Rec("快手_来自快手的视频_video.mp4", "postC", true));  // 同名不同帖
        List<Rec> d = RecRules.dedupe(dup, r -> r.key, r -> strip(r.name));
        check("同 key 只留一条", d.size() == 2);
        check("留下的是最新那条（带 多图/ 前缀的原记录）", d.get(0).name.startsWith("多图/"));

        // ---- 6. 去重不能误伤：名字和 key 都不同 ----
        List<Rec> diff = new ArrayList<>();
        diff.add(new Rec("a.mp4", "k1", true));
        diff.add(new Rec("b.mp4", "k2", true));
        diff.add(new Rec("多图/a.jpg", "", true));   // 没有 key 的图片
        diff.add(new Rec("多图/b.jpg", "", true));
        check("不同名字/不同 key 全部保留", RecRules.dedupe(diff, r -> r.key, r -> strip(r.name)).size() == 4);

        System.out.println("PASS " + pass + " / FAIL " + fail);
        if (fail > 0) System.exit(1);
    }

    private static String strip(String name) {
        if (name == null) return "";
        int i = name.lastIndexOf('/');
        return i >= 0 ? name.substring(i + 1) : name;
    }

    private static int countAlive(List<Rec> l) {
        int n = 0;
        for (Rec r : l) if (r.alive) n++;
        return n;
    }

    private static boolean noDead(List<Rec> l) {
        for (Rec r : l) if (!r.alive) return false;
        return true;
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + name);
        } else {
            fail++;
            System.out.println("  FAIL  " + name);
        }
    }
}
