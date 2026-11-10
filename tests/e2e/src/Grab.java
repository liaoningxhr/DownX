import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** 把一个 URL 原样存到文件，方便肉眼/正则分析返回内容 */
public class Grab {
    public static void main(String[] a) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(a[0]).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", com.downx.app.Net.UA);
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        if (a.length > 2) c.setRequestProperty("Referer", a[2]);
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        File out = new File(a[1]);
        long n = 0;
        if (in != null) {
            try (FileOutputStream fo = new FileOutputStream(out)) {
                byte[] b = new byte[65536];
                int r;
                while ((r = in.read(b)) > 0) { fo.write(b, 0, r); n += r; }
            }
        }
        System.out.println(code + "  " + n + " bytes -> " + out.getAbsolutePath());
    }
}
