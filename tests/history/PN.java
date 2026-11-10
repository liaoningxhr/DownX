package com.downx.app;

/**
 * 多图归组规则验证（纯逻辑，不联网、不依赖 Android）
 * 回归：从磁盘补回来的多图记录，必须能和正常记录一样叠成一组，
 *       而不是每张图单独占一格（用户 2026-11-09 反馈）。
 */
public class PN {

    private static int pass = 0, fail = 0;

    private static void check(String what, boolean ok) {
        if (ok) {
            pass++;
        } else {
            fail++;
            System.out.println("  FAIL " + what);
        }
    }

    private static void eq(String what, String a, String b) {
        boolean ok = a == null ? b == null : a.equals(b);
        if (!ok) System.out.println("  FAIL " + what + "  期望[" + b + "] 实际[" + a + "]");
        check(what, ok);
    }

    public static void main(String[] a) {
        System.out.println("== 多图文件名前缀 ==");
        eq("标准命名", PhotoNaming.base("David_xxx_photo1.jpg"), "David_xxx");
        eq("两位序号", PhotoNaming.base("David_xxx_photo12.jpg"), "David_xxx");
        eq("带目录", PhotoNaming.base("多图/a_photo3.jpg"), "a");
        eq("没有扩展名", PhotoNaming.base("a_photo5"), "a");
        eq("不是多图命名", PhotoNaming.base("holiday.mp4"), "holiday");
        eq("_photo 后面没数字", PhotoNaming.base("abc_photo.jpg"), "abc_photo");
        eq("_photo1 不在结尾", PhotoNaming.base("a_photo1x.jpg"), "a_photo1x");
        eq("开头就是 photo1（不剥）", PhotoNaming.base("photo1.jpg"), "photo1");
        eq("剥完变空要回退原名", PhotoNaming.base("_photo1.jpg"), "_photo1");
        eq("大写也认", PhotoNaming.base("A_PHOTO9.JPG"), "A");

        System.out.println("== 分组键 ==");
        String u = PhotoNaming.groupKey("https://www.instagram.com/p/ABC/", "a_photo1.jpg");
        eq("有链接用链接", u, "url:https://www.instagram.com/p/ABC/");
        eq("链接前后空格要去掉",
                PhotoNaming.groupKey("  https://x.com/i/status/1  ", "a_photo1.jpg"),
                "url:https://x.com/i/status/1");
        eq("没链接用前缀",
                PhotoNaming.groupKey("", "David_xxx_photo3.jpg"), "base:david_xxx");
        eq("null 链接也不炸", PhotoNaming.groupKey(null, "David_xxx_photo3.jpg"), "base:david_xxx");

        System.out.println("== 真正的归组效果 ==");
        String k1 = PhotoNaming.groupKey("", "多图/David_Sczesny_Diese_photo1.jpg");
        String k2 = PhotoNaming.groupKey("", "David_Sczesny_Diese_photo14.jpg");
        eq("同一帖第 1 张和第 14 张同组", k1, k2);
        check("同组键非空", !k1.isEmpty());

        String o1 = PhotoNaming.groupKey("", "Another_Post_photo1.jpg");
        check("不同帖不同组", !o1.equals(k1));

        String c1 = PhotoNaming.groupKey("", "ABC_photo1.jpg");
        String c2 = PhotoNaming.groupKey("", "abc_photo2.jpg");
        eq("大小写不同也归一组", c1, c2);

        eq("空文件名不归组", PhotoNaming.groupKey("", ""), "");

        // 真实场景：补回来的 14 张，只有 1 个代表图 + 1 个组
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (int i = 1; i <= 14; i++) {
            keys.add(PhotoNaming.groupKey("", "David_Sczesny_Diese_photo" + i + ".jpg"));
        }
        eq("14 张图只产生 1 个组", String.valueOf(keys.size()), "1");

        // 三个不同帖混在一起：应当恰好 3 组
        java.util.Set<String> mixed = new java.util.HashSet<>();
        for (int i = 1; i <= 5; i++) mixed.add(PhotoNaming.groupKey("", "PostA_photo" + i + ".jpg"));
        for (int i = 1; i <= 3; i++) mixed.add(PhotoNaming.groupKey("", "PostB_photo" + i + ".jpg"));
        for (int i = 1; i <= 2; i++) mixed.add(PhotoNaming.groupKey("", "PostC_photo" + i + ".jpg"));
        eq("三帖混排仍是 3 组", String.valueOf(mixed.size()), "3");

        System.out.println("\n结果：" + pass + " 项通过，" + fail + " 项失败");
        if (fail > 0) System.exit(1);
    }
}
