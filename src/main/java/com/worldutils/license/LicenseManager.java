package com.worldutils.license;

import net.fabricmc.loader.api.FabricLoader;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * 授权管理：用户输入 Google Authenticator 的 6 位验证码激活，
 * 激活成功后在本地写一份"到期时间 + HMAC 签名"的授权文件，有效期内免再验。
 *
 * <p>安全说明：这是纯客户端方案，密钥打包在 jar 内，能反编译者可提取密钥或移除校验。
 * 授权文件用密钥 HMAC-SHA256 签名，可防止用户直接改文件延长有效期（改了签名对不上）。
 * 需要强防护请改服务端下发/校验。
 */
public final class LicenseManager {
    // ============================================================
    // 【只改这里】把下面替换成你自己的 Base32 密钥（A-Z、2-7）。
    // 然后在手机 Google Authenticator 里“手动输入密钥”，录入同一串，
    // 类型选“基于时间”。生成的 6 位码就是发给用户的激活码。
    // 生成随机密钥示例（16~32 位 Base32）：如 "K5MZ Q3JN P7WB R2XA"。
    // 千万不要用下面这个默认值上线——这是公开示例，人人可算出码。
    private static final String SECRET = "JBSWY3DPEHPK3PXP";
    // ============================================================

    /** 校验容忍窗口：±2 个周期（约 150 秒），给"作者读码→用户输入"留时间。 */
    private static final int WINDOW = 2;
    /** 每次成功激活授予的有效天数。 */
    private static final int GRANT_DAYS = 1;

    private static long cachedExpiry = 0; // 缓存的到期毫秒；0 = 未授权

    private LicenseManager() {}

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("worldutils-license.dat");
    }

    /** mod 启动时调用一次：读取并校验本地授权文件，结果缓存到内存。 */
    public static void load() {
        cachedExpiry = 0;
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            String content = Files.readString(f, StandardCharsets.UTF_8).trim();
            int sep = content.lastIndexOf(':');
            if (sep <= 0) return;
            String expiryStr = content.substring(0, sep);
            String sig = content.substring(sep + 1);
            if (!sign(expiryStr).equals(sig)) return; // 被篡改
            cachedExpiry = Long.parseLong(expiryStr);
        } catch (Exception e) {
            cachedExpiry = 0;
        }
    }

    /** 当前是否已授权（内存判断，无文件 IO，可每 tick 调用）。 */
    public static boolean isAuthorized() {
        return System.currentTimeMillis() < cachedExpiry;
    }

    /** 剩余有效时间的可读描述。 */
    public static String remainingText() {
        if (!isAuthorized()) return "§c未授权";
        long ms = cachedExpiry - System.currentTimeMillis();
        long days = ms / 86_400_000L;
        long hours = (ms % 86_400_000L) / 3_600_000L;
        long minutes = (ms % 3_600_000L) / 60_000L;
        StringBuilder sb = new StringBuilder("§a已授权，剩余 ");
        if (days > 0) sb.append(days).append(" 天 ");
        sb.append(hours).append(" 小时 ").append(minutes).append(" 分钟");
        return sb.toString();
    }

    /**
     * 用验证码激活。成功则写授权文件并返回 true。
     */
    public static boolean activate(String code) {
        if (!TotpAuth.verify(SECRET, code, WINDOW)) return false;
        return activateWithExpiry(System.currentTimeMillis() + GRANT_DAYS * 86_400_000L);
    }

    /**
     * 直接按到期时间授权（账号密码登录用：有效期 = 账号到期日）。
     * 写入与 TOTP 激活相同的本地授权文件。
     */
    public static boolean activateWithExpiry(long expiry) {
        String expiryStr = Long.toString(expiry);
        try {
            Files.writeString(file(), expiryStr + ":" + sign(expiryStr), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return false;
        }
        cachedExpiry = expiry;
        return true;
    }

    public static int grantDays() {
        return GRANT_DAYS;
    }

    /** 用密钥对到期时间做 HMAC-SHA256 签名，防止手改授权文件。 */
    private static String sign(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(TotpAuth.base32Decode(SECRET), "HmacSHA256"));
        byte[] h = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(h);
    }
}
