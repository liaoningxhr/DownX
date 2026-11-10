package com.downx.app;

/**
 * 文件名与列表标题的拼装规则。
 *
 * <p>单独抽出来的原因：这里全是纯字符串处理，不碰 Android API，
 * 可以直接在主机上用 {@code javac + java} 跑确定性断言（{@code tests/naming}）。
 * 以前这套规则长在 {@code MainActivity} 里，只能靠装机才能验证，出了
 * 「所有快手视频都叫一个名字」这种问题也发现不了。
 *
 * <p>两条规则：
 * <ol>
 *   <li><b>文件名</b>：{@code 作者_标题_清晰度.ext}。标题缺失（解析器只给了
 *       「来自快手的视频」这类通用句）时用「作品+ID 尾号」，保证同一作者的不同作品
 *       不会拼成同一个名字（同名会互相覆盖，相册里只能靠 (1)(2) 区分）。</li>
 *   <li><b>列表标题</b>：{@code @作者 · 标题}。以前放的是「视频 · 无水印」这种标签，
 *       几十条视频全一样；旧记录（已经存成标签的）在渲染时从文件名反推标题，不改数据。</li>
 * </ol>
 */
final class Namer {

    private Namer() { }

    /** 解析器兜底造句：「来自XX的视频」「作品 12345」这类不是标题，不能拿来当文件名 */
    static boolean genericTitle(String t) {
        if (t == null) return true;
        String s = t.trim();
        if (s.isEmpty() || "视频".equals(s) || "图片".equals(s)) return true;
        return s.matches("^来自.{0,24}的视频$") || s.matches("^作品\\s*\\d+$");
    }

    /** 取 ID 末几位当文件名里的唯一标记（数字、字母都留，别把短 ID 切没了） */
    static String idTail(String id, int n) {
        if (id == null) return "";
        String s = id.replaceAll("[^0-9A-Za-z]", "");
        if (s.length() <= n) return s;
        return s.substring(s.length() - n);
    }

    /**
     * 拼文件名。
     *
     * @param who       作者（已清洗、可为空）
     * @param rawTitle  解析出来的正文/文案
     * @param svId      短视频作品 ID（抖音/快手），可为空
     * @param otherId   X 推文 ID / IG 短码，可为空
     * @param tag       清晰度/来源标签（原片、网页版、1080p…），可为空串
     * @param ext       扩展名，不带点
     */
    static String fileName(String who, String rawTitle, String svId, String otherId,
                           String tag, String ext) {
        String w = who == null ? "" : who.trim();
        String mid = titleFor(rawTitle);
        String vid = svId == null ? "" : svId.trim();
        if (vid.isEmpty() && otherId != null) vid = otherId;
        if (mid.isEmpty()) {
            String tail = idTail(vid, 6);
            mid = tail.isEmpty() ? String.valueOf(System.currentTimeMillis() / 1000) : "作品" + tail;
        }
        String t = tag == null ? "" : tag.trim();
        return w + "_" + mid + (t.isEmpty() ? "" : "_" + t) + "." + ext;
    }

    /** 标题（已判定可用才返回；通用句返回空串） */
    static String titleFor(String rawTitle) {
        if (genericTitle(rawTitle)) return "";
        return rawTitle.trim();
    }

    /** 列表里「 · 」后面那半句：有标题用标题，没标题才退回「视频 / 图片」 */
    static String subTitle(String handle, String title, boolean photo) {
        String h = handle == null ? "" : handle.trim();
        String t = title == null ? "" : title.trim();
        String tail = t.isEmpty() ? (photo ? "图片" : "视频") : t;
        return (h.isEmpty() ? "" : "@" + h + " · ") + tail;
    }

    /** 通用标签词：sub 里只有这些，说明是旧记录（「@kuaishou · 视频 · 无水印」） */
    private static final java.util.regex.Pattern SUB_LABEL = java.util.regex.Pattern.compile(
            "^(视频|图片|图片\\s*\\d+|原片|网页版|带声音|无水印|原图|同画质|备用|备选\\s*\\d*|最高|高清|流畅|标准"
                    + "|HLS|GIF|\\d{3,4}\\s*[pP](-hls)?)$");

    /** sub 里「 · 」之后全是通用标签？ */
    static boolean labelOnlySub(String sub) {
        if (sub == null || sub.isEmpty()) return false;
        int bar = sub.indexOf(" · ");
        if (bar < 0) return false;
        String rest = sub.substring(bar + 3);
        boolean any = false;
        for (String part : rest.split(" · ")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            any = true;
            if (!SUB_LABEL.matcher(p).matches()) return false;
        }
        return any;
    }

    /** 从「作者_标题_清晰度.jpg」里把标题抠出来；抠不到（通用句/时间戳/纯标签）返回空串 */
    static String titleFromName(String name) {
        String s = name == null ? "" : name.trim();
        int slash = s.lastIndexOf('/');
        if (slash >= 0) s = s.substring(slash + 1);
        int dot = s.lastIndexOf('.');
        if (dot > 0) s = s.substring(0, dot);
        String[] p = s.split("_");
        if (p.length < 2) return "";
        String mid = p[1].trim();
        if (mid.isEmpty() || genericTitle(mid)) return "";
        if (mid.matches("(?i)video|photo\\d*|gif|hls|\\d{3,4}p(-hls)?|原片|网页版|备用|带声音")) return "";
        if (mid.matches("^\\d{9,}$")) return "";       // 纯时间戳
        return mid.replace('_', ' ');
    }

    /**
     * 列表里显示的标题：新记录直接用；旧记录（sub 只剩标签）从文件名反推。
     * 返回空串表示无从判断，调用方自己决定回退成什么。
     */
    static String displaySub(String sub, String name) {
        String s = sub == null ? "" : sub.trim();
        if (!labelOnlySub(s)) return s;
        String fromName = titleFromName(name);
        if (fromName.isEmpty()) return s;
        int bar = s.indexOf(" · ");
        String who = bar > 0 ? s.substring(0, bar) : "";
        return who.isEmpty() ? fromName : who + " · " + fromName;
    }
}
