package io.github.miam1ku.mibandoplusbridge.protocol.huawei.core;

/**
 * 华为协议栈所需的字节工具。
 *
 * <p>原Gadgetbridge 中这些方法位于 {@code GB} 与 {@code StringUtils} 两个类，
 * 但它们本身都深度依赖 GB 框架（{@code GBApplication}、{@code GBDevice}、
 * {@code R} 等十余个类），无法直接搬入。因此把实际用到的实现提取到此处。
 *
 * <p>本类是从Gadgetbridge 提取的最小实现，行为与原实现保持一致。
 */
public final class GB {

    private GB() {
    }

    /** 十六进制字符串转字节数组（原 GB.hexStringToByteArray，逐字节行为一致）。 */
    public static byte[] hexStringToByteArray(CharSequence s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    /** GB 原版的 hex 字符表，大写。 */
    public static final char[] HEX_CHARS = "0123456789ABCDEF".toCharArray();

    /** 字节数组转十六进制字符串（替代原 StringUtils.bytesToHex → GB.hexdump）。 */
    public static String bytesToHex(byte[] array) {
        if (array == null) {
            return "null";
        }
        final StringBuilder sb = new StringBuilder(array.length * 2);
        for (byte b : array) {
            final String hex = Integer.toHexString(b & 0xFF);
            if (hex.length() == 1) {
                sb.append('0');
            }
            sb.append(hex);
        }
        return sb.toString();
    }

    /**
     * 十六进制转储，行为与 GB 的 {@code GB.hexdump(byte[], int, int)} 逐字节一致。
     *
     * <p>这三个细节必须与 GB 完全相同，否则 HiChain 密钥派生会算错：
     * <ul>
     *   <li><b>无分隔符</b> —— 不是 {@code "aa bb"} 而是 {@code "aabb"}；</li>
     *   <li><b>大写 hex</b> —— GB 用 {@code "0123456789ABCDEF"}，不是小写；</li>
     *   <li><b>length == -1</b> 表示"从 offset 到末尾"，而不是"越界截断"。</li>
     * </ul>
     *
     * <p>验证依据：{@code HuaweiCrypto.computeDigestHiChainLite} 把本方法的输出
     * 直接 {@code getBytes()} 后送进 HKDF。只要有一个字节不同，派生的 session key
     * 就不同，手表在 Step3/Step4 会校验失败。
     */
    public static String hexdump(byte[] buffer, int offset, int length) {
        if (buffer == null) {
            return "(null)";
        }
        if (length == -1) {
            length = buffer.length - offset;
        }
        char[] hexChars = new char[length * 2];
        for (int i = 0; i < length; i++) {
            int v = buffer[i + offset] & 0xFF;
            hexChars[i * 2] = HEX_CHARS[v >>> 4];
            hexChars[i * 2 + 1] = HEX_CHARS[v & 0x0F];
        }
        return new String(hexChars);
    }

    /** 全量转储。GB 的这个重载在 null 时返回 {@code "(null)"} 而非 {@code "null"}。 */
    public static String hexdump(byte[] buffer) {
        if (buffer == null) {
            return "(null)";
        }
        return hexdump(buffer, 0, buffer.length);
    }

    /** 十六进制字符串转字节数组，奇数长度自动前置补零（替代原 StringUtils.hexToBytes）。 */
    public static byte[] hexToBytes(String hexString) {
        String hex = hexString;
        if ((hex.length() % 2) == 1) {
            hex = "0" + hex;
        }
        return hexStringToByteArray(hex);
    }

    /** RSSI 转可读字符串。 */
    public static String formatRssi(short rssi) {
        return String.valueOf(rssi);
    }
}