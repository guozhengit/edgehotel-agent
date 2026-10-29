/*
 * 钉钉回调自测夹具生成器（M4）：sign = Base64(HmacSHA256(appSecret, timestamp + "\n" + appSecret))。
 * 用法: java scripts/GenDingTalkFixture.java <appSecret> '<载荷JSON>'
 * 输出: TS/NONCE(未用，占位)/SIGN 三行——请求头带 timestamp 与 sign，载荷原样作为 POST body。
 */
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

public class GenDingTalkFixture {
    public static void main(String[] a) throws Exception {
        String secret = a[0];
        String ts = String.valueOf(System.currentTimeMillis());
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"));
        byte[] out = mac.doFinal((ts + "\n" + secret).getBytes("UTF-8"));
        System.out.println("TS=" + ts);
        System.out.println("SIGN=" + Base64.getEncoder().encodeToString(out));
    }
}
