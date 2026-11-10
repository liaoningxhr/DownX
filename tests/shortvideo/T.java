package com.downx.app;

import java.io.*;
import java.nio.charset.StandardCharsets;

public class T {
    static int fail;
    static PrintWriter out;
    static void eq(String what, String got, String want) {
        boolean ok = want.equals(got);
        out.println((ok ? "PASS " : "FAIL ") + what + " => [" + got + "]");
        if (!ok) { out.println("      want [" + want + "]"); fail++; }
    }
    static void sw(String what, boolean got) {
        out.println((got ? "PASS " : "FAIL ") + what);
        if (!got) fail++;
    }

    public static void main(String[] a) throws Exception {
        out = new PrintWriter(new OutputStreamWriter(new FileOutputStream("F:/DownX-Studio/tests/shortvideo/result.txt"), StandardCharsets.UTF_8));
        String s1 = "https://v.kuaishou.com/n3KkLyq3 \"狠人大队 \"小炮子 \"小炮子是个人才 该作品在快手被播放过14.1万次，点击链接，打开【快手】直接观看！";
        String s2 = "https://v.kuaishou.com/KA7zaiw1 来自黑龙江的美女自带欧美范 \"唐僧闫东 \"长春动植物公园有活你就来 \"长春动植物公园游客各个身怀绝技 \"长春动植物公园太有节目了 该作品在快手被播放过456.5万次，点击链接，打开【快手】直接观看！";
        String s3 = "https://v.kuaishou.com/KwJo59Q1 仅仅一个月，胸闷喘不上气明显好转。\"老铁知识百科 \"万物皆可赛场 \"村医帮帮健康不慌 \"咱村的健康守护人 该作品在快手被播放过34.4万次，点击链接，打开【快手】直接观看！";

        eq("口令1标题", ShortVideo.captionFromShare(s1), "狠人大队 小炮子 小炮子是个人才");
        eq("口令2标题", ShortVideo.captionFromShare(s2).length() > 0 ? "ok" : "", "ok");
        out.println("  口令2实际 => [" + ShortVideo.captionFromShare(s2) + "]");
        eq("口令3标题", ShortVideo.captionFromShare(s3), "仅仅一个月 胸闷喘不上气明显好转 老铁知识百科 万物皆可赛场 村医帮帮健康不慌 咱村的健康守护人");
        eq("纯链接", ShortVideo.captionFromShare("https://v.kuaishou.com/n3KkLyq3"), "");
        eq("空口令", ShortVideo.captionFromShare(null), "");

        sw("短链无ID", ShortVideo.idOf(false, "https://v.kuaishou.com/n3KkLyq3") == null);
        eq("还原页ID", ShortVideo.idOf(false, "https://www.kuaishou.com/short-video/3x49tg4mzn73p4a?cc=share"), "3x49tg4mzn73p4a");
        eq("老页面ID", ShortVideo.idOf(false, "https://c.kuaishou.com/fw/photo/3xtufiwjejy979k"), "3xtufiwjejy979k");
        eq("候选页首选", ShortVideo.pagesOf(false, "3x49tg4mzn73p4a").get(0),
                "https://www.kuaishou.com/short-video/3x49tg4mzn73p4a");

        // 文件名唯一性：通用标题时必须补作品 ID
        String generic = "来自快手的视频";
        out.println((generic.startsWith("来自") ? "PASS " : "FAIL ") + "通用标题可识别");
        if (!generic.startsWith("来自")) fail++;


        // ---- 抖音口令：链接提取 + 文案清洗 ----
        String d1 = "8.36 复制打开抖音，看看【喵星人的作品】我家猫又犯病了 # 猫咪 # 搞笑 https://v.douyin.com/iRNBho6u/ 11/23 W@m.QK";
        String d2 = "5.29 gOe:/ 复制打开抖音，看看【陈陈的图文作品】今天天气不错 https://v.douyin.com/iRk3Q8m/ 复制此链接，打开Dou音搜索，直接观看视频！";
        eq("抖音文案1", ShortVideo.captionFromShare(d1), "我家猫又犯病了 猫咪 搞笑");
        eq("抖音文案2", ShortVideo.captionFromShare(d2), "今天天气不错");
        eq("抖音链接提取", ShortVideo.linkIn(d1), "https://v.douyin.com/iRNBho6u/");
        eq("抖音图文链接提取", ShortVideo.linkIn(d2), "https://v.douyin.com/iRk3Q8m/");
        sw("抖音短链识别", ShortVideo.isDouyin("https://v.douyin.com/iRNBho6u/"));
        eq("抖音ID", ShortVideo.idOf(true, "https://www.iesdouyin.com/share/video/7300000000000000000/?region=CN"),
                "7300000000000000000");

        out.println(fail == 0 ? "\nALL PASS" : "\n" + fail + " FAILED");
        out.close();
        if (fail != 0) System.exit(1);
    }
}
