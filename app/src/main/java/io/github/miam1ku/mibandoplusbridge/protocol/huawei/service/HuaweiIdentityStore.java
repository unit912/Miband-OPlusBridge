// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol.huawei.service;

import android.content.Context;
import android.content.SharedPreferences;

import io.github.miam1ku.mibandoplusbridge.data.HuaweiLogger;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.GB;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiCrypto;

import java.nio.charset.StandardCharsets;

/**
 * 华为认证所需的本机身份，按手表 MAC 分文件持久化。
 *
 * <p>三个值缺一不可，且都<b>必须跨会话稳定</b> —— 手表把它们当作「这台手机的
 * 身份」记住了，换一个值就会被当成新设备而要求重新配对：
 *
 * <ul>
 *   <li><b>伪 MAC</b> —— 格式 {@code FF:FF:FF:xx:xx:xx}，首次连接随机生成后持久化。
 *       注意这不是手表的真实 MAC，而是本机对协议自称的身份。GB 也是这么做的
 *       （{@code createRandomMacAddress}）。</li>
 *   <li><b>androidID</b> —— 16 字节随机数的十六进制串，作为 HiChain 的
 *       {@code peerAuthId}。</li>
 *   <li><b>secretKey</b> —— HiChain 认证换来的长期密钥。首次配对后每次重连都要用
 *       它做 bind，没有它就只能停留在「每次都要手表点确认」的状态。</li>
 * </ul>
 *
 * <p>存储位置受 libxposed API 102 影响可能落在宿主应用的数据目录，故统一走
 * {@code Context#getSharedPreferences} 并显式指定名称，不依赖默认路径。
 */
public final class HuaweiIdentityStore {

    private static final HuaweiLogger LOG = HuaweiLogger.of(HuaweiIdentityStore.class);

    private static final String PREFS_PREFIX = "oplusband-huawei-";
    private static final String KEY_MAC = "identity_mac";
    private static final String KEY_ANDROID_ID = "identity_android_id";
    private static final String KEY_SECRET = "identity_secret_key";

    private final Context context;
    private final String deviceMac;
    private final SharedPreferences prefs;

    /** 伪 MAC，形如 {@code FF:FF:FF:1A:2B:3C}。 */
    private String macAddress;
    /** HiChain 的 peerAuthId，十六进制字符串。 */
    private String androidId;
    /** HiChain 认证得到的长期密钥，十六进制字符串。 */
    private String secretKeyHex;

    /**
     * @param context     用于读取偏好设置；为 {@code null} 时退化为纯内存模式
     *                    （单次会话有效，重连会失败）
     * @param deviceMac   真实的手表 MAC，作为偏好文件的区分键
     */
    public HuaweiIdentityStore(Context context, String deviceMac) {
        this.context = context;
        this.deviceMac = deviceMac;
        this.prefs = context == null ? null :
                context.getSharedPreferences(PREFS_PREFIX + sanitize(deviceMac), Context.MODE_PRIVATE);

        this.macAddress = read(KEY_MAC);
        this.androidId = read(KEY_ANDROID_ID);
        this.secretKeyHex = read(KEY_SECRET);

        if (this.macAddress == null || this.macAddress.isEmpty()) {
            this.macAddress = generateRandomMac();
            write(KEY_MAC, this.macAddress);
            LOG.debug("generated huawei identity mac {}", this.macAddress);
        }
        if (this.androidId == null || this.androidId.isEmpty()) {
            this.androidId = GB.bytesToHex(HuaweiCrypto.generateNonce());
            write(KEY_ANDROID_ID, this.androidId);
            LOG.debug("generated huawei identity androidID");
        }
    }

    /**
     * 生成一个 {@code FF:FF:FF:xx:xx:xx} 形式的伪 MAC。
     *
     * <p>前三个字节固定为 {@code FF} 是 GB 的约定 —— 让手表侧一眼看出「这是
     * 手机侧自报的身份」而不是某个真实网卡地址。
     */
    private static String generateRandomMac() {
        StringBuilder mac = new StringBuilder("FF:FF:FF");
        java.util.Random random = new java.util.Random();
        for (int i = 0; i < 3; i++) {
            mac.append(String.format(":%02x", random.nextInt(255)));
        }
        return mac.toString().toUpperCase(java.util.Locale.ROOT);
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9]", "_");
    }

    private String read(String key) {
        return prefs == null ? null : prefs.getString(key, null);
    }

    private void write(String key, String value) {
        if (prefs == null) {
            return;
        }
        prefs.edit().putString(key, value).apply();
    }

    /** 协议上报用的 MAC（{@code FF:FF:FF:xx:xx:xx}），非手表真实地址。 */
    public byte[] getMacAddress() {
        return macAddress.getBytes(StandardCharsets.UTF_8);
    }

    public String getMacAddressString() {
        return macAddress;
    }

    /**
     * 客户端序列号。取伪 MAC 去掉分隔符后的后 6 位，与 GB 的
     * {@code getSerial()} 保持一致。
     */
    public byte[] getClientSerial() {
        String digits = macAddress.replace(":", "");
        return digits.substring(6, 12).getBytes(StandardCharsets.UTF_8);
    }

    /** HiChain 的 peerAuthId。 */
    public byte[] getAndroidId() {
        return androidId.getBytes(StandardCharsets.UTF_8);
    }

    /** 已有的长期密钥；从未认证过时返回 {@code null}。 */
    public byte[] getSecretKey() {
        return secretKeyHex == null || secretKeyHex.isEmpty() ? null : GB.hexStringToByteArray(secretKeyHex);
    }

    /** 认证成功后保存长期密钥，下次重连直接用它 bind。 */
    public void setSecretKey(byte[] secretKey) {
        if (secretKey == null || secretKey.length == 0) {
            return;
        }
        this.secretKeyHex = GB.bytesToHex(secretKey);
        write(KEY_SECRET, this.secretKeyHex);
    }

    /** 是否已完成过一次认证（决定能否走快速 bind 而非要用户确认）。 */
    public boolean hasSecretKey() {
        return secretKeyHex != null && !secretKeyHex.isEmpty();
    }

    /** 真实的手表 MAC（偏好文件的键，非协议上报值）。 */
    public String getDeviceMac() {
        return deviceMac;
    }

    // ─────────────────────────────────────────────────────────────
    // 身份快照：让一次性自检不污染既有绑定
    // ─────────────────────────────────────────────────────────────

    /**
     * 三个身份值的原始副本，供 {@link #restore} 还原。
     *
     * <p><b>为什么需要它</b>：HiChain 认证会把手表的绑定槽改写成当前上报的
     * 伪 MAC + androidID + 新 secretKey。一旦自检跑过，别的应用（比如
     * Gadgetbridge）再拿自己的密钥去握手，手表回的是加密给自检身份的数据，
     * 于是报「认证密钥协商失败」—— 手表只能绑一台手机，这是协议约束，
     * 不是 bug。
     *
     * <p>所以自检在<b>开始前</b>快照、<b>结束后</b>还原：本地身份回到原样。
     * 注意这只能还原手机侧；手表侧被改写的绑定槽要靠
     * 「测试期间不真正完成 bind」或事后在手表上重新配对才能复原。
     * 因此自检应尽量走 bind 路径（复用已有 secretKey），避免触发身份改写。
     */
    public String[] snapshot() {
        return new String[]{macAddress, androidId, secretKeyHex};
    }

    /** 还原 {@link #snapshot} 抓到的身份值。传 {@code null} 表示该值当时不存在。 */
    public void restore(String[] snap) {
        if (snap == null || snap.length < 3) {
            return;
        }
        macAddress = snap[0];
        androidId = snap[1];
        secretKeyHex = snap[2];
        write(KEY_MAC, macAddress);
        write(KEY_ANDROID_ID, androidId);
        if (secretKeyHex == null || secretKeyHex.isEmpty()) {
            if (prefs != null) {
                prefs.edit().remove(KEY_SECRET).apply();
            }
        } else {
            write(KEY_SECRET, secretKeyHex);
        }
        LOG.info("identity restored from snapshot (mac={} hasKey={})",
                macAddress, hasSecretKey());
    }
}