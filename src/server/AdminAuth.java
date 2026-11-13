package server;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 管理员密钥：PBKDF2 verifier 保存与 HMAC 挑战-响应。
 *
 * <p>线格式统一使用 Base64 URL 无填充。客户端计算：
 * verifier = PBKDF2WithHmacSHA256(adminKey, salt, iterations, 256)，
 * proof = HMAC-SHA256(verifierBytes, nonceBytes)。
 * HMAC key 使用 verifier 的原始 32 字节，而不是 Base64 文本。</p>
 */
public final class AdminAuth {

    public static final int DEFAULT_ITERATIONS = 120000;
    public static final int KEY_BYTES = 18;
    public static final int SALT_BYTES = 16;
    public static final int NONCE_BYTES = 16;
    public static final int VERIFIER_BITS = 256;

    private static final SecureRandom RANDOM = new SecureRandom();

    private AdminAuth() {
    }

    public static String generateKey() {
        byte[] bytes = new byte[KEY_BYTES];
        RANDOM.nextBytes(bytes);
        return encode(bytes);
    }

    public static String generateSalt() {
        byte[] bytes = new byte[SALT_BYTES];
        RANDOM.nextBytes(bytes);
        return encode(bytes);
    }

    public static String generateNonce() {
        byte[] bytes = new byte[NONCE_BYTES];
        RANDOM.nextBytes(bytes);
        return encode(bytes);
    }

    public static String createVerifier(String adminKey,
                                        String saltBase64,
                                        int iterations) throws GeneralSecurityException {
        if (adminKey == null || adminKey.isEmpty()) {
            throw new IllegalArgumentException("管理员密钥不能为空");
        }
        if (iterations <= 0) {
            throw new IllegalArgumentException("admin.iterations 必须大于 0");
        }
        byte[] salt = decode(saltBase64);
        char[] password = adminKey.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(
                password, salt, iterations, VERIFIER_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(
                    "PBKDF2WithHmacSHA256");
            return encode(factory.generateSecret(spec).getEncoded());
        } finally {
            spec.clearPassword();
            java.util.Arrays.fill(password, '\0');
        }
    }

    /**
     * 客户端侧计算 proof，便于测试与命令行工具复用。
     */
    public static String createProof(String verifierBase64,
                                     String nonceBase64) throws GeneralSecurityException {
        byte[] verifier = decode(verifierBase64);
        byte[] nonce = decode(nonceBase64);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(verifier, "HmacSHA256"));
        return encode(mac.doFinal(nonce));
    }

    /**
     * 服务端用保存的 verifier 作为 HMAC key 校验客户端 proof。
     */
    public static boolean verifyProof(String verifierBase64,
                                      String nonceBase64,
                                      String proofBase64) {
        if (verifierBase64 == null || verifierBase64.isEmpty()
                || nonceBase64 == null || nonceBase64.isEmpty()
                || proofBase64 == null || proofBase64.isEmpty()) {
            return false;
        }
        try {
            byte[] verifier = decode(verifierBase64);
            byte[] nonce = decode(nonceBase64);
            byte[] actual = decode(proofBase64);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(verifier, "HmacSHA256"));
            byte[] expected = mac.doFinal(nonce);
            return MessageDigest.isEqual(expected, actual);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }
}
