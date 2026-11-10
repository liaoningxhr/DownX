package com.downx.app;

import java.util.Locale;

/**
 * 多图文件名的解析。
 *
 * 同一个帖子的多张图，文件名是 {@code 作者_标题_photo1.jpg … _photoN.jpg}。
 * 正常下载时记录里带原帖链接（pageUrl），分组直接用链接；
 * 但「从磁盘补回来」的老记录没有链接（磁盘上看不出原帖地址），
 * 只能靠这个前缀把它们重新归成一组 —— 否则每张图都会单独占一格，
 * 看上去就像"叠加卡变成了单张图"（2026-11-09 用户反馈）。
 *
 * 纯 Java、不依赖 Android，可在主机上直接验证（tests/history/PN.java）。
 */
class PhotoNaming {

    private PhotoNaming() { }

    /**
     * 取文件名前缀：去掉目录、扩展名，再去掉结尾的 {@code _photoN}。
     * 不符合多图命名（没有 _photoN 结尾）时返回"去扩展名的原文件名"，
     * 保证不同文件仍然得到不同的前缀。
     *
     * 注意：一定要防住"剥完变成空串"的情况（例如 {@code _photo1.jpg}），
     * 否则所有这种名字都会得到同一个空前缀，被错误地并成一组。
     */
    static String base(String name) {
        String n = name == null ? "" : name.trim();
        int slash = n.lastIndexOf('/');
        if (slash >= 0) n = n.substring(slash + 1);
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        String b = n.replaceFirst("(?i)_photo\\d+$", "");
        return b.isEmpty() ? n : b;
    }

    /**
     * 分组键。有空就返回空串 —— 调用方据此判定"这张图不参与归组"。
     * 有原帖链接时以链接为准（最可靠）；没有才退回文件名前缀。
     */
    static String groupKey(String pageUrl, String name) {
        String u = pageUrl == null ? "" : pageUrl.trim();
        if (!u.isEmpty()) return "url:" + u;
        String b = base(name);
        if (b.isEmpty()) return "";
        return "base:" + b.toLowerCase(Locale.ROOT);
    }
}
