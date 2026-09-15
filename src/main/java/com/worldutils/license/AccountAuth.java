package com.worldutils.license;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

/**
 * 账号密码授权（与 TOTP 验证码并存）：/miner login 或 /farm login <账号> <密码>。
 *
 * <p>服务端只是 nginx 静态文件（tools/authserver/ 一键部署）：每个账号一个文件，
 * 文件名 = SHA-256(账号) 十六进制，内容 = {@code salt:SHA-256(salt:密码):到期毫秒}。
 * mod 按账号哈希拉取文件、本地比对密码哈希、检查到期时间，通过后写入与 TOTP
 * 相同的本地授权文件（有效期 = 账号到期日）。
 *
 * <p>安全性质：密码本身从不上网（只有账号哈希出现在 URL 里）；账号文件公开可拉，
 * 拿到的人可对 salt+hash 离线爆破——给用户发强随机密码即可。与 TOTP 一样属于
 * 纯客户端方案，防普通用户不防逆向。
 */
public final class AccountAuth {
    /** 授权服务器地址（nginx 静态目录，见 tools/authserver/worldutils-auth.sh setup）。 */
    private static final String BASE_URL = "http://104.62.94.44:10000/auth/";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private AccountAuth() {}

    /** 异步登录，结果通过聊天消息反馈（可从命令线程直接调）。 */
    public static void login(String username, String password) {
        String user = username.trim();
        if (user.isEmpty() || password.isEmpty()) {
            msg("§c用法: login <账号> <密码>");
            return;
        }
        msg("§7正在验证账号 §e" + user + "§7 ...");
        Thread t = new Thread(() -> doLogin(user, password), "worldutils-login");
        t.setDaemon(true);
        t.start();
    }

    private static void doLogin(String user, String password) {
        String body;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + sha256Hex(user) + ".dat"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) {
                msg("§c账号不存在，请检查账号拼写（区分大小写）");
                return;
            }
            if (resp.statusCode() != 200) {
                msg("§c授权服务器返回错误: HTTP " + resp.statusCode());
                return;
            }
            body = resp.body();
        } catch (Exception e) {
            msg("§c无法连接授权服务器: " + e.getClass().getSimpleName()
                    + "§7（检查网络，稍后重试）");
            return;
        }

        // 格式: salt:passhash:expiryMs（.dat 内单行）
        String[] parts = body.trim().split(":");
        if (parts.length != 3) {
            msg("§c账号数据格式错误，请联系作者");
            return;
        }
        String expectedHash;
        try {
            expectedHash = sha256Hex(parts[0] + ":" + password);
        } catch (Exception e) {
            msg("§c本地哈希计算失败: " + e.getMessage());
            return;
        }
        if (!expectedHash.equalsIgnoreCase(parts[1])) {
            msg("§c密码错误");
            return;
        }
        long expiry;
        try {
            expiry = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            msg("§c账号数据格式错误，请联系作者");
            return;
        }
        if (System.currentTimeMillis() >= expiry) {
            msg("§c该账号已到期，请联系作者续期");
            return;
        }
        if (LicenseManager.activateWithExpiry(expiry)) {
            msg("§a登录成功！" + LicenseManager.remainingText());
        } else {
            msg("§c授权文件写入失败（检查磁盘/权限）");
        }
    }

    private static String sha256Hex(String input) throws RuntimeException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 网络线程安全的聊天反馈：切回客户端主线程发消息。 */
    private static void msg(String text) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.sendMessage(Text.literal("§7[授权]§r " + text), false);
            }
        });
    }
}
