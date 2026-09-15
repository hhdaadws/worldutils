package com.worldutils.license;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;

/**
 * RFC 6238 TOTP（与 Google Authenticator 兼容）：HMAC-SHA1、6 位、30 秒周期。
 * 授权方（作者）在验证器 App 里录入同一个 Base32 密钥，读出 6 位码交给用户输入校验。
 */
public final class TotpAuth {
    private static final String B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final long PERIOD = 30L;
    private static final int DIGITS = 1_000_000; // 6 位

    private TotpAuth() {}

    /** 校验用户输入的 6 位码是否落在当前时间 ±window 个周期内。 */
    public static boolean verify(String secretBase32, String code, int window) {
        String digits = code == null ? "" : code.trim().replaceAll("\\s", "");
        if (!digits.matches("\\d{6}")) return false;
        int target;
        try {
            target = Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return false;
        }
        byte[] key = base32Decode(secretBase32);
        if (key.length == 0) return false;
        long step = System.currentTimeMillis() / 1000L / PERIOD;
        for (int w = -window; w <= window; w++) {
            try {
                if (codeAt(key, step + w) == target) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private static int codeAt(byte[] key, long timeStep) throws Exception {
        byte[] data = new byte[8];
        long v = timeStep;
        for (int i = 7; i >= 0; i--) {
            data[i] = (byte) (v & 0xFF);
            v >>>= 8;
        }
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key, "HmacSHA1"));
        byte[] hash = mac.doFinal(data);
        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
        return binary % DIGITS;
    }

    /** 宽松 Base32 解码（忽略空格、填充、大小写、非法字符）。 */
    static byte[] base32Decode(String s) {
        if (s == null) return new byte[0];
        String up = s.trim().replace(" ", "").replace("=", "").toUpperCase();
        int bits = 0, value = 0;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < up.length(); i++) {
            int idx = B32.indexOf(up.charAt(i));
            if (idx < 0) continue;
            value = (value << 5) | idx;
            bits += 5;
            if (bits >= 8) {
                out.write((value >>> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
