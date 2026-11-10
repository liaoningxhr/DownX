import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** 出网探针：参数是一串 URL，只打印状态码/耗时/长度，用来判断哪些平台在本机可测 */
public class Probe {
    public static void main(String[] a) throws Exception {
        for (String u : a) {
            long t0 = System.currentTimeMillis();
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                        + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
                c.setInstanceFollowRedirects(false);
                int code = c.getResponseCode();
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                String body = in == null ? "" : read(in);
                System.out.println(code + "  " + (System.currentTimeMillis() - t0) + "ms  " + u + "  len=" + body.length());
                if (body.length() > 0 && code < 400) {
                    System.out.println("     " + body.substring(0, Math.min(80, body.length())).replace('\n', ' '));
                }
            } catch (Throwable t) {
                System.out.println("ERR  " + (System.currentTimeMillis() - t0) + "ms  " + u + "  " + t.getMessage());
            }
        }
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) {
            bo.write(b, 0, n);
            if (bo.size() > 120000) break;
        }
        in.close();
        return new String(bo.toByteArray(), "UTF-8");
    }
}
