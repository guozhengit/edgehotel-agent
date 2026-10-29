/*
 * 飞书回调自测夹具生成器（M4）：按飞书规范生成加密回调体——IV(16B)+AES-CBC-PKCS5，key=SHA-256(encryptKey)；
 * 签名 = sha256(ts + nonce + encryptKey + body)。无需真实平台凭据即可离线自测渠道解密/映射/落库全链路。
 * 用法: java scripts/GenFeishuFixture.java <encryptKey> '<明文JSON>'  → 输出 BODY/TS/NONCE/SIG 四行
 */
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** 按飞书规范生成加密回调夹具：IV(16B)+AES-CBC-PKCS5，key=SHA-256(encryptKey)；sig=sha256(ts+nonce+key+body)。 */
public class GenFeishuFixture {
    public static void main(String[] a) throws Exception {
        String encryptKey = a[0], json = a[1];
        byte[] key = MessageDigest.getInstance("SHA-256").digest(encryptKey.getBytes("UTF-8"));
        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] ct = c.doFinal(json.getBytes("UTF-8"));
        byte[] out = new byte[16 + ct.length];
        System.arraycopy(iv, 0, out, 0, 16);
        System.arraycopy(ct, 0, out, 16, ct.length);
        String body = "{\"encrypt\":\"" + Base64.getEncoder().encodeToString(out) + "\"}";
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        String nonce = "zcode-test-nonce";
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(ts.getBytes("UTF-8")); md.update(nonce.getBytes("UTF-8"));
        md.update(encryptKey.getBytes("UTF-8")); md.update(body.getBytes("UTF-8"));
        StringBuilder hex = new StringBuilder();
        for (byte b : md.digest()) hex.append(String.format("%02x", b));
        System.out.println(body);
        System.out.println("TS=" + ts);
        System.out.println("NONCE=" + nonce);
        System.out.println("SIG=" + hex);
    }
}
