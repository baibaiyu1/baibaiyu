package com.gmd.faq.server.main.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * 描述：加密工具类
 *
 **/
public class CryptoUtils {

    /** 随机密码字符集：去掉了 0/O、1/l/I 等易混淆字符，便于管理员口头或截图转达 */
    private static final String PASSWORD_UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String PASSWORD_LOWER = "abcdefghijkmnopqrstuvwxyz";
    private static final String PASSWORD_DIGIT = "23456789";
    private static final char[] PASSWORD_CHARSET =
            (PASSWORD_UPPER + PASSWORD_LOWER + PASSWORD_DIGIT).toCharArray();

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return toHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 algorithm not available", e);
        }
    }

    /**
     * 使用默认的加盐值进行MD5加密
     * @param input  原始密码
     * @param salt 盐
     * @return 加密后的字符串
     */
    public static String md5(String input, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(salt.getBytes(StandardCharsets.UTF_8));
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return toHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 algorithm not available", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    /**
     * 获取一个随机的 UUID 字符串
     * @return 随机的 UUID 字符串
     */
    public static String uuid() {
         return UUID.randomUUID().toString().replace("-","");
    }

    /**
     * 生成随机初始密码（管理员新增用户 / 重置密码时使用）
     * <p>
     * 长度为 12 位，字符集已排除 0/O、1/l/I 等易混淆字符，并保证同时包含大小写字母与数字，
     * 以满足前端「至少 6 位」的校验与常见密码强度要求。
     *
     * @return 12 位随机明文密码
     */
    public static String randomPassword() {
        return randomPassword(12);
    }

    /**
     * 生成指定长度的随机密码
     *
     * @param length 密码长度（小于 6 时按 6 处理）
     * @return 随机明文密码
     */
    public static String randomPassword(int length) {
        int size = Math.max(length, 6);
        char[] password = new char[size];
        for (int i = 0; i < size; i++) {
            password[i] = PASSWORD_CHARSET[SECURE_RANDOM.nextInt(PASSWORD_CHARSET.length)];
        }
        // 首字符固定为大写字母，避免部分客户端把纯数字开头的密码当作数值处理；
        // 第 2、3 位分别固定为小写字母与数字，保证密码同时包含大小写与数字。
        password[0] = pick(PASSWORD_UPPER);
        password[1] = pick(PASSWORD_LOWER);
        password[2] = pick(PASSWORD_DIGIT);
        return new String(password);
    }

    private static char pick(String candidates) {
        return candidates.charAt(SECURE_RANDOM.nextInt(candidates.length()));
    }


    /**
     * Rot13加密流程：
     * 数据 -> Base64 编码 -> ROT13 替换
     * @param str 要加密的字符串。
     * @return 加密后的字符串。
     */
    public static String encryptRot13(String str) {
        if (str == null) {
            return null;
        }
        try {
            // 先 Base64 编码，再 ROT13
            String base64Encoded = encode(str);
            return rot13(base64Encoded);
        } catch (Exception e) {
            return str;
        }
    }

    /**
     * Rot13解密流程：
     * ROT13 -> Base64 -> 数据对象
     * @param encrypted 要解密的字符串
     * @return 解密后的字符串
     */
    public static String decryptRot13(String encrypted) {
        if (encrypted == null) {
            return null;
        }
        try {
            // 先 ROT13 还原，再 Base64 解码
            String rot13Decoded = rot13(encrypted);
            return decode(rot13Decoded);
        } catch (Exception e) {
            return encrypted;
        }
    }

    /**
     * ROT13 函数：对给定字符串执行 ROT13 编码。
     * @param str - 要编码的字符串。
     * @return 编码后的字符串。
     */
    private static String rot13(String str) {
        StringBuilder result = new StringBuilder();

        for (char c : str.toCharArray()) {
            if (c >= 'a' && c <= 'z') {
                // 小写字母处理
                c = (char) (((c - 'a' + 13) % 26) + 'a');
            } else if (c >= 'A' && c <= 'Z') {
                // 大写字母处理
                c = (char) (((c - 'A' + 13) % 26) + 'A');
            }
            // 非字母字符保持不变
            result.append(c);
        }

        return result.toString();
    }

    /**
     * 对输入字符串进行 Base64 编码
     *
     * @param input 输入字符串
     * @return 编码后的字符串
     */
    public static String encode(String input) {
        // 将字符串转换为字节数组
        byte[] encodedBytes = Base64.getEncoder().encode(input.getBytes());
        // 转换回字符串并返回
        return new String(encodedBytes);
    }

    /**
     * 对 Base64 编码的字符串进行解码
     *
     * @param encodedString 已编码的字符串
     * @return 解码后的原始字符串
     */
    public static String decode(String encodedString) {
        // 将字符串转换为字节数组
        byte[] decodedBytes = Base64.getDecoder().decode(encodedString.getBytes());
        // 转换回字符串并返回
        return new String(decodedBytes);
    }
}
