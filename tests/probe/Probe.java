import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.util.regex.*;

public class Probe {
    static final String ALPH = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    static final String COOKIE = System.getenv("IG_COOKIE");

    static String shortcodeToId(String code) {
        BigInteger v = BigInteger.ZERO;
        for (char ch : code.toCharArray()) {
            int d = ALPH.indexOf(ch);
            if (d < 0) return null;
            v = v.multiply(BigInteger.valueOf(64)).add(BigInteger.valueOf(d));
        }
        return v.toString();
    }

    static String shortcode(String link) {
        Matcher m = Pattern.compile("instagram\\.com/(?:[^/]+/)?(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)").matcher(link);
        return m.find() ? m.group(1) : null;
    }

    static void get(String tag, String url, String ua, String appId) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(20000);
            c.setReadTimeout(25000);
            c.setRequestProperty("User-Agent", ua);
            c.setRequestProperty("x-ig-app-id", appId);
            c.setRequestProperty("Accept", "*/*");
            if (COOKIE != null) c.setRequestProperty("Cookie", COOKIE);
            int code;
            try { code = c.getResponseCode(); } catch (Exception e) { System.out.println("[" + tag + "] EX " + e); return; }
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            StringBuilder sb = new StringBuilder();
            if (is != null) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0 && sb.length() < 200000) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
            String body = sb.toString();
            System.out.println("[" + tag + "] HTTP " + code + " len=" + body.length());
            System.out.println("   head=" + body.substring(0, Math.min(240, body.length())).replaceAll("\\s+", " "));
            System.out.println("   video_versions=" + body.contains("video_versions") + " dash_info=" + body.contains("dash_info") + " challenge=" + body.contains("challenge_required"));
        } catch (Exception e) {
            System.out.println("[" + tag + "] EX " + e);
        }
    }

    public static void main(String[] a) {
        String WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36";
        String APP_UA = "Instagram 269.0.0.18.75 Android (33/13; 420dpi; 1080x2400; samsung; SM-S911B; dm3q; qcom; en_US; 456789123)";
        for (String link : a) {
            String code = shortcode(link);
            String id = shortcodeToId(code);
            System.out.println("==== " + code + " -> " + id);
            get("web", "https://www.instagram.com/api/v1/media/" + id + "/info/", WEB_UA, "936619743392459");
            get("app", "https://i.instagram.com/api/v1/media/" + id + "/info/", APP_UA, "567067343352427");
        }
    }
}
