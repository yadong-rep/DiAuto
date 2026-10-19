package com.hongqi.flighting.util;

import java.io.UnsupportedEncodingException;

/**
 * 运行时字符串解密器 —— 与 build.gradle 中 ASM 加密过程对称。
 *
 * <p>仅 release 构建中才会在字节码层面产生调用（由 ASM 注入）；
 * debug 构建所有字符串保留为普通 LDC，此类中方法不会被代码显式调用（仍保留以确保链接）。
 *
 * <p>PROGUARD 规则中显式 keep { public String g(int[]) } 防止被 R8 改名/裁掉。
 */
public final class StringGuard {

    /** 与 build.gradle 中 STRING_KEY 保持 16 字节完全一致。 */
    private static final byte[] K = {
        0x5A, 0x31, (byte)0x9F, 0x77, (byte)0xD2, 0x0B, 0x4E, (byte)0xC3,
        0x18, (byte)0x8A, (byte)0xA9, 0x6D, 0x2C, (byte)0xF5, (byte)0xE4, (byte)0x91
    };

    private StringGuard() {}

    /**
     * ASM 注入器真正调用的唯一入口。
     * @param enc 编码后的 int[]（长度等于 UTF-8 原始字节数）
     * @return 还原后的字符串
     */
    public static String g(int[] enc) {
        if (enc == null) {
            return "";
        }
        int n = enc.length;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            int k = K[(i ^ 0x7B) % K.length] & 0xFF;
            int v = enc[i];
            // 反向：先还原 i 扰动，再还原 key XOR
            v ^= (i * 0x1001) & 0xFF;
            v = (v ^ k) & 0xFF;
            out[i] = (byte) v;
        }
        try {
            return new String(out, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return new String(out);
        }
    }

    /**
     * 源码级的轻量包装：当源码中有少量敏感常量想直接加密时使用。
     * 注意：请先把字符串改成 int[] 密文，再调用 {@link #g(int[])} 而不是这里。
     * 本方法仅提供给开发者在日志里打印密文用。
     */
    @Deprecated
    public static String s(String raw) {
        if (raw == null) {
            return "";
        }
        byte[] src;
        try {
            src = raw.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            src = raw.getBytes();
        }
        StringBuilder sb = new StringBuilder("new int[]{");
        for (int i = 0; i < src.length; i++) {
            int k = K[(i ^ 0x7B) % K.length] & 0xFF;
            int v = ((src[i] & 0xFF) ^ k) ^ ((i * 0x1001) & 0xFF);
            if (i > 0) sb.append(',');
            sb.append(v);
        }
        sb.append('}');
        // 开发者：把此行 dump 到控制台后复制到源码中
        //   String key = StringGuard.g( <数组> );
        // 注意：不要把明文直接写在代码里，也不要把 s() 的返回值当结果用。
        return sb.toString();
    }
}
