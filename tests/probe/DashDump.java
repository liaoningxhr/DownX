import java.io.*; import java.net.*; import java.nio.charset.StandardCharsets; import java.math.BigInteger; import java.util.regex.*;
public class Dump5 {
  static final String ALPH="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
  public static void main(String[] a) throws Exception {
    Matcher m = Pattern.compile("instagram\\.com/(?:[^/]+/)?(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)").matcher(a[0]);
    m.find(); String code=m.group(1);
    BigInteger v=BigInteger.ZERO; for(char ch: code.toCharArray()) v=v.multiply(BigInteger.valueOf(64)).add(BigInteger.valueOf(ALPH.indexOf(ch)));
    HttpURLConnection c=(HttpURLConnection)new URL("https://www.instagram.com/api/v1/media/"+v+"/info/").openConnection();
    String cookie=System.getenv("IG_COOKIE");
    c.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");
    c.setRequestProperty("x-ig-app-id","936619743392459");
    c.setRequestProperty("x-asbd-id","129477");
    c.setRequestProperty("x-ig-www-claim","0");
    c.setRequestProperty("x-requested-with","XMLHttpRequest");
    c.setRequestProperty("Referer","https://www.instagram.com/p/"+code+"/");
    c.setRequestProperty("Cookie", cookie);
    Matcher cm=Pattern.compile("csrftoken=([^;]+)").matcher(cookie); if(cm.find()) c.setRequestProperty("X-CSRFToken", cm.group(1));
    StringBuilder sb=new StringBuilder(); char[] b=new char[8192]; int n;
    BufferedReader br=new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
    while((n=br.read(b))>0) sb.append(b,0,n);
    String body=sb.toString();
    Matcher km=Pattern.compile("\"video_dash_manifest\":\"(.*?)\",\"", Pattern.DOTALL).matcher(body);
    if(!km.find()){ System.out.println("no manifest; keys with dash: "); Matcher km2=Pattern.compile("\"[a-z_]*dash[a-z_]*\"").matcher(body); while(km2.find()) System.out.println("  "+km2.group()); return; }
    String xml=km.group(1).replace("\\\"","\"");
    Matcher am=Pattern.compile("<AdaptationSet[^>]*>").matcher(xml); while(am.find()) System.out.println("SET "+am.group());
    Matcher rm=Pattern.compile("<Representation[^>]*>").matcher(xml); while(rm.find()) System.out.println("REP "+rm.group());
  }
}
