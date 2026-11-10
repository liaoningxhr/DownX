package com.downx.app;

/**
 * Namer 的主机验证台（纯 Java，不联网、不依赖 Android）。
 *
 * 跑法：sh tests/naming/run.sh
 */
public class NamingTest {

    private static int pass = 0, fail = 0;

    private static void eq(String what, String got, String want) {
        if (want.equals(got)) {
            pass++;
        } else {
            fail++;
            System.out.println("FAIL " + what);
            System.out.println("     期望: " + want);
            System.out.println("     实际: " + got);
        }
    }

    private static void ok(String what, boolean cond) {
        if (cond) pass++;
        else { fail++; System.out.println("FAIL " + what); }
    }

    public static void main(String[] a) {
        // ---------- 1. 文件名：标题可用 ----------
        eq("快手有标题 → 作者_标题.mp4（不再有 _video 噪音）",
                Namer.fileName("老王", "今天钓到一条大鲤鱼", "3xabc123", null, "", "mp4"),
                "老王_今天钓到一条大鲤鱼.mp4");

        eq("有清晰度标签 → 带上标签",
                Namer.fileName("NASA", "Launch", "1234567890123456789", null, "1080p", "mp4"),
                "NASA_Launch_1080p.mp4");

        eq("原片标签",
                Namer.fileName("小明", "旅行vlog", null, "C8g7WIZM6Z", "原片", "mp4"),
                "小明_旅行vlog_原片.mp4");

        eq("备用线路标签（不能和主链同名，否则互相覆盖）",
                Namer.fileName("小明", "旅行vlog", "3xabc123", null, "备用", "mp4"),
                "小明_旅行vlog_备用.mp4");

        eq("多图编号",
                Namer.fileName("小明", "旅行vlog", null, "C8g7WIZM6Z", "photo3", "jpg"),
                "小明_旅行vlog_photo3.jpg");

        // ---------- 2. 文件名：通用句要换成作品 ID ----------
        eq("通用句「来自X的视频」→ 作品+ID末6位",
                Namer.fileName("老王", "来自老王的视频", "7621502438541050472", null, "", "mp4"),
                "老王_作品050472.mp4");

        ok("通用句不再进文件名",
                !Namer.fileName("老王", "来自老王的视频", "7621502438541050472", null, "", "mp4")
                        .contains("来自"));

        eq("「作品 12345」也算通用句",
                Namer.fileName("老王", "作品 12345", "7621502438541050472", null, "", "mp4"),
                "老王_作品050472.mp4");

        eq("没有 svId 时用 otherId（X 推文 ID）",
                Namer.fileName("NASA", "来自NASA的视频", null, "1234567890123456789", "", "mp4"),
                "NASA_作品456789.mp4");

        // 核心：同一作者、都抓不到标题、不同作品 → 必须不同名
        String a1 = Namer.fileName("老王", "来自老王的视频", "7621502438541050472", null, "", "mp4");
        String a2 = Namer.fileName("老王", "来自老王的视频", "7621502438541059999", null, "", "mp4");
        ok("同作者不同作品的文件名必须不同（否则互相覆盖）", !a1.equals(a2));

        // 连 ID 都没有 → 时间戳兜底，也不能是空名
        String a3 = Namer.fileName("老王", "", null, null, "", "mp4");
        ok("无标题无 ID → 时间戳兜底且带扩展名", a3.matches("老王_\\d{9,}\\.mp4"));

        ok("不会出现连续下划线", !a1.contains("__") && !a3.contains("__"));

        // ---------- 3. 列表标题 ----------
        eq("有标题 → @作者 · 标题",
                Namer.subTitle("kuaishou", "今天钓到一条大鲤鱼", false),
                "@kuaishou · 今天钓到一条大鲤鱼");

        eq("没标题 → @作者 · 视频（不再是「视频 · 无水印」）",
                Namer.subTitle("kuaishou", "", false),
                "@kuaishou · 视频");

        eq("图片 → @作者 · 图片",
                Namer.subTitle("instagram", "", true),
                "@instagram · 图片");

        eq("handle 为空时只留标题", Namer.subTitle("", "标题", false), "标题");

        // ---------- 4. 旧记录渲染（从文件名反推标题） ----------
        eq("旧 sub 是标签 → 用文件名里的标题",
                Namer.displaySub("@kuaishou · 视频 · 无水印", "老王_今天钓到一条大鲤鱼_video.mp4"),
                "@kuaishou · 今天钓到一条大鲤鱼");

        eq("带「多图/」前缀也能反推",
                Namer.displaySub("@instagram · 图片 3 · 原图", "多图/小明_旅行vlog_photo3.jpg"),
                "@instagram · 旅行vlog");

        eq("旧文件名是通用句 → 保持原样（没有更好的信息可用）",
                Namer.displaySub("@kuaishou · 视频 · 无水印", "老王_来自老王的视频_video.mp4"),
                "@kuaishou · 视频 · 无水印");

        eq("旧文件名是时间戳 → 保持原样",
                Namer.displaySub("@kuaishou · 视频 · 无水印", "老王_1699999999_video.mp4"),
                "@kuaishou · 视频 · 无水印");

        eq("新 sub 已经带标题 → 原样返回",
                Namer.displaySub("@kuaishou · 今天钓到一条大鲤鱼", "老王_今天钓到一条大鲤鱼.mp4"),
                "@kuaishou · 今天钓到一条大鲤鱼");

        eq("sub 为空 → 原样返回", Namer.displaySub("", "老王_标题.mp4"), "");

        eq("X 的旧 sub「@handle · 最高 · 1080p」也能反推",
                Namer.displaySub("@NASA · 最高 · 1080p", "NASA_Launch_1080p.mp4"),
                "@NASA · Launch");

        // ---------- 5. 边界 ----------
        ok("null 标题算通用句", Namer.genericTitle(null));
        ok("null 名字不炸", Namer.titleFromName(null).isEmpty());
        eq("ID 短于 n 时不切", Namer.idTail("abc", 6), "abc");
        eq("ID 带非字母数字时先清洗", Namer.idTail("ab-cd:123456", 4), "3456");

        System.out.println();
        System.out.println("结果: " + pass + " PASS, " + fail + " FAIL");
        if (fail > 0) System.exit(1);
    }
}
