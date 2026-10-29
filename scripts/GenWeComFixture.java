/*
 * 企业微信回调自测夹具生成器（M4）：按企微规范加密——key=Base64Decode(EncodingAESKey+"=")（32B），
 * IV=key[0:16]，明文=16B随机 + 4B消息长度(网络序) + 消息 + receiveId；签名 = SHA-1(排序[token,timestamp,nonce,encrypt])。
 * 用法: java scripts/GenWeComFixture.java <token> <encodingAesKey(43字符)> <receiveId> '<消息XML或echostr明文>'
 * 输出: BODY(XML信封)/TS/NONCE/SIG 四行（GET URL 验证时 BODY 即 encrypt 串放入 echostr 参数）。
 */
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

public class GenWeComFixture {
    public static void main(String[] a) throws Exception {
        String token = a[0], aesKeyB64 = a[1], receiveId = a[2], msg = a[3];
        byte[] key = Base64.getDecoder().decode(aesKeyB64 + "=");
        byte[] iv = Arrays.copyOf(key, 16);
        byte[] msgBytes = msg.getBytes("UTF-8");
        byte[] rand = new byte[16];
        new SecureRandom().nextBytes(rand);
        byte[] plain = new byte[16 + 4 + msgBytes.length + receiveId.getBytes("UTF-8").length];
        System.arraycopy(rand, 0, plain, 0, 16);
        System.arraycopy(ByteBuffer.allocate(4).putInt(msgBytes.length).array(), 0, plain, 16, 4);
        System.arraycopy(msgBytes, 0, plain, 20, msgBytes.length);
        System.arraycopy(receiveId.getBytes("UTF-8"), 0, plain, 20 + msgBytes.length, receiveId.getBytes("UTF-8").length);
        // PKCS7 到 32 倍数
        int pad = 32 - (plain.length % 32);
        byte[] padded = Arrays.copyOf(plain, plain.length + pad);
        Arrays.fill(padded, plain.length, padded.length, (byte) pad);
        Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        String encrypt = Base64.getEncoder().encodeToString(c.doFinal(padded));
        String body = "<xml><ToUserName><![CDATA[" + receiveId + "]]></ToUserName><Encrypt><![CDATA[" + encrypt + "]]></Encrypt></xml>";
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        String nonce = "zcode-nonce";
        String[] sorted = {token, ts, nonce, encrypt};
        Arrays.sort(sorted);
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        for (String s : sorted) md.update(s.getBytes("UTF-8"));
        StringBuilder hex = new StringBuilder();
        for (byte b : md.digest()) hex.append(String.format("%02x", b));
        System.out.println(body);
        System.out.println("TS=" + ts);
        System.out.println("NONCE=" + nonce);
        System.out.println("SIG=" + hex);
    }
}
