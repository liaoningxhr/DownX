package com.downx.app;

import java.util.ArrayList;
import java.util.List;

/**
 * YouTube 失败原因文案验证（纯逻辑，不联网）。
 *
 * 重点是别再让用户看到笼统的"解析失败"：
 *   - 需要登录的视频 → 说清"下不了、换一条"，而不是让用户以为代理坏了；
 *   - 私有/已删除 → 说清是视频本身的问题；
 *   - 真连不上 → 才提代理。
 *
 * 用法：sh tests/yt/run.sh
 */
public class YD {

    private static int pass, fail;

    private static void check(String what, boolean ok) {
        if (ok) pass++;
        else {
            fail++;
            System.out.println("  FAIL  " + what);
        }
    }

    private static void has(String what, String s, String... needles) {
        String miss = null;
        for (String n : needles) {
            if (s == null || !s.contains(n)) {
                miss = n;
                break;
            }
        }
        check(what + (miss == null ? "" : "（缺少「" + miss + "」）实际=" + s), miss == null);
    }

    private static List<String> list(String... items) {
        List<String> l = new ArrayList<>();
        for (String s : items) l.add(s);
        return l;
    }

    public static void main(String[] args) {
        System.out.println("== YouTube 失败原因文案验证 ==");

        // 1) 这两条视频实测的结果：五个客户端全 LOGIN_REQUIRED
        String m1 = YtDeny.message("白嫖5台！免费领 4核8G 独立云虚拟机，实测下行竟然飙到 94 Gbps",
                list("LOGIN_REQUIRED|Sign in to confirm you\u2019re not a bot",
                        "LOGIN_REQUIRED|Sign in to confirm you\u2019re not a bot"), true);
        has("登录验证：说清原因", m1, "要求登录验证");
        has("登录验证：翻出中文原因", m1, "判定当前网络像机器人");
        has("登录验证：给出下一步", m1, "换一条视频");
        has("登录验证：带标题前缀", m1, "《白嫖5台");
        check("长标题被截断（不超过 30 字）", m1.indexOf("》") > 0 && m1.indexOf("》") < 32);

        // 2) 弯引号也要能识别（YouTube 用的是 ’）
        has("弯引号 reason 能翻成中文",
                YtDeny.message("", list("LOGIN_REQUIRED|Sign in to confirm you\u2019re not a bot"), true),
                "机器人");

        // 3) 私有视频
        String m2 = YtDeny.message("", list("UNPLAYABLE|This video is private"), true);
        has("私有：说不可播放", m2, "不可播放", "私有视频");

        // 4) 已删除
        has("已删除", YtDeny.message("", list("ERROR|This video has been removed by the uploader"), true),
                "拒绝提供", "已被删除");

        // 5) 地区限制
        has("地区限制", YtDeny.message("", list("UNPLAYABLE|This video is not available in your country"), true),
                "地区");

        // 6) 年龄验证
        has("年龄验证", YtDeny.message("", list("AGE_CHECK_REQUIRED|Sign in to confirm your age"), true),
                "不可播放", "确认年龄");

        // 7) 根本连不上（一个客户端都没响应）—— 这时才提代理
        String m3 = YtDeny.message("", list(), false);
        has("连不上：提代理", m3, "连不上 YouTube", "代理");
        check("连不上时不要误导成视频问题", !m3.contains("换一条视频"));

        // 8) 响应了但没流（status=OK）
        String m4 = YtDeny.message("", list("OK|只有 VP9/AV1 或需要签名解密的流"), true);
        has("无可用格式：让人重试", m4, "没给出可用的视频格式");
        check("无可用格式时不提代理", !m4.contains("代理"));

        // 9) 混合原因：登录优先，不能被后面的 ERROR 抢走
        has("登录优先", YtDeny.message("", list("ERROR|boom",
                "LOGIN_REQUIRED|Sign in to confirm you\u2019re not a bot"), true), "要求登录验证");

        // 10) reason 为空：不能出现空括号
        String m5 = YtDeny.message("", list("LOGIN_REQUIRED|"), true);
        check("空 reason 不出现空括号", !m5.contains("（）") && m5.contains("要求登录验证"));

        // 11) 没见过的 reason 保留原文（不能吃成空）
        has("陌生 reason 保留原文",
                YtDeny.message("", list("ERROR|Something brand new from YouTube"), true),
                "Something brand new");

        // 12) 被停用的客户端
        has("客户端停用能识别",
                YtDeny.message("", list("ERROR|YouTube is no longer supported in this application or device"), true),
                "客户端版本已被 YouTube 停用");

        // 13) 没有标题时不要出现空的《》
        String m6 = YtDeny.message(null, list("LOGIN_REQUIRED|x"), true);
        check("无标题时不出现《》", !m6.contains("《"));

        // 14) 标题两侧空白要清掉
        check("标题空白被清掉",
                YtDeny.message("  标题  ", list("LOGIN_REQUIRED|x"), true).startsWith("《标题》"));

        System.out.println();
        System.out.println(fail == 0
                ? "全部通过：" + pass + " 项"
                : ("通过 " + pass + " 项，失败 " + fail + " 项"));
        if (fail > 0) System.exit(1);
    }
}
