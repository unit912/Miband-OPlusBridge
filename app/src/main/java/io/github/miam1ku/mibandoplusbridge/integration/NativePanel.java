// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.integration;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * A control-center tap on the band hands the detail intent to the Health panel that handles the action.
 * The target class is resolved from the action, so one build's class name is not pinned.
 */
public final class NativePanel {
    public static final String PANEL = "com.oplus.mydevices.ACTION_DEVICE_DETAILED_PANEL";
    public static final String PAGE = "com.oplus.mydevices.ACTION_DEVICE_DETAILED_PAGE";
    public static final String HEALTH = "com.heytap.health";
    /** Health's native page accepts this OPPO band model. */
    public static final String BAND_MODEL = "OB19B1";
    /**
     * 设备空间卡片主键（MAC 的 MD5 小写 hex）。合一后设备空间卡片的 device_id 即为此值，
     * 不再带 {@code huaweigt3_} 前缀。mydevices 进程里由 {@link #resolveBridgeId}
     * 从本机桥接卡实时算出并填入，使 {@link #bandDevice} 仍能识别这张卡。
     */
    public static volatile String BRIDGE_CARD_ID = null;
    private static final Uri BRIDGE_URI =
            Uri.parse("content://io.github.miam1ku.mibandoplusbridge.devices");
    /**
     * Device ids this bridge owns. The Xiaomi chain uses {@code miband11_} plus a
     * 64-hex fingerprint, the Huawei chain {@code huaweigt3_} plus the same shape.
     * Both have to be listed: a control-center tap on the band hands the detail
     * intent to the Health panel, and the check runs on the device id the host
     * knows the card by. The merged space card keeps the MD5 id, matched via
     * {@link #BRIDGE_CARD_ID}.
     */
    private static final Pattern BAND = Pattern.compile("(miband11_|huaweigt3_)[0-9a-f]{64}");

    private NativePanel() {}

    public static boolean panelAction(String action) {
        return PANEL.equals(action) || PAGE.equals(action);
    }

    public static boolean bandDevice(String deviceId) {
        if (deviceId == null) return false;
        if (BAND.matcher(deviceId).matches()) return true;
        return deviceId.equals(BRIDGE_CARD_ID);
    }

    /**
     * 合一后设备空间卡片 device_id 为 MAC 的 MD5，不再带 {@code huaweigt3_} 前缀，
     * {@link #bandDevice} 的前缀匹配失效。这里用本机桥接实时主键兜底：本机已注册且
     * {@code deviceId} 等于本机卡的 MD5 即视为桥接卡（例如点卡片跳转健康详情页时）。
     * 命中后顺手缓存到 {@link #BRIDGE_CARD_ID}，后续调用免跨进程。
     */
    public static boolean resolveBridgeId(Context context, String deviceId) {
        if (deviceId == null) return false;
        if (BRIDGE_CARD_ID != null) return deviceId.equals(BRIDGE_CARD_ID);
        try {
            Bundle display = context.getContentResolver().call(BRIDGE_URI, "bandDisplay", null, null);
            if (display != null && display.getBoolean("registered", false)) {
                String mac = display.getString("mac", "");
                if (!mac.isBlank()) {
                    // 必须与 DeviceCardProvider.bridgeCardId 用同一规范（大写冒号 MAC 的 MD5），
                    // 否则算出的主键与健康/欢太卡不一致，点卡片无法路由到健康详情页。
                    BRIDGE_CARD_ID = md5Hex(normalizeMac(mac));
                    return deviceId.equals(BRIDGE_CARD_ID);
                }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private static String md5Hex(String text) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("MD5")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            return "";
        }
    }

    /** 与 DeviceCardProvider.normalizeMac 一致：MAC 归一化为「大写 + 冒号」规范串；非法返回 null。 */
    private static String normalizeMac(String mac) {
        if (mac == null) return null;
        String s = mac.trim().toUpperCase(java.util.Locale.ROOT)
                .replace(":", "").replace("-", "").replace(".", "");
        if (s.length() != 12) return null;
        StringBuilder out = new StringBuilder(17);
        for (int i = 0; i < 12; i++) {
            if (i != 0 && i % 2 == 0) out.append(':');
            out.append(s.charAt(i));
        }
        return out.toString();
    }

    public static ComponentName resolve(Context context) {
        Intent probe = new Intent(PANEL);
        probe.setPackage(HEALTH);
        probe.addCategory(Intent.CATEGORY_DEFAULT);
        ResolveInfo info = context.getPackageManager().resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY);
        if (info == null || info.activityInfo == null || info.activityInfo.name == null) return null;
        return new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
    }

    /** Rewrites the intent onto the Health panel. An intent already aimed there, or not this band, stays as it is. */
    public static boolean redirect(Context context, Intent intent) {
        if (intent == null || !panelAction(intent.getAction())) return false;
        String deviceId = intent.getStringExtra("device_id");
        if (deviceId == null) deviceId = intent.getStringExtra("key_device_id");
        if (!bandDevice(deviceId) && !resolveBridgeId(context, deviceId)) return false;
        ComponentName panel = resolve(context);
        if (panel == null || panel.equals(intent.getComponent())) return false;
        String mac = intent.getStringExtra("device_mac_info");
        String name = intent.getStringExtra("device_title");
        if (mac == null || mac.isBlank() || name == null || name.isBlank()) {
            try (Cursor cursor = context.getContentResolver().query(DeviceCardProvider.URI,
                    new String[]{"device_mac", "device_data"}, "device_id=?", new String[]{deviceId}, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    if (mac == null || mac.isBlank()) {
                        mac = cursor.getString(cursor.getColumnIndexOrThrow("device_mac"));
                    }
                    if (name == null || name.isBlank()) {
                        name = new JSONObject(cursor.getString(cursor.getColumnIndexOrThrow("device_data")))
                                .optString("mDeviceName", "");
                    }
                }
            } catch (RuntimeException | JSONException ignored) {
                if (mac == null) mac = "";
            }
        }
        if (mac == null || mac.isBlank()) return false;
        intent.setComponent(panel);
        intent.setPackage(panel.getPackageName());
        // 合一后设备空间卡片 device_id 为 MAC 的 MD5；跳转健康详情页时改用桥接内部主键
        // (huaweigt3_)，健康 App 才能按既有逻辑命中本表（顶部图/数据），否则 MD5 主键健康端不认。
        String jumpId = deviceId;
        try {
            Bundle display = context.getContentResolver().call(BRIDGE_URI, "bandDisplay", null, null);
            if (display != null && display.getBoolean("registered", false)) {
                String internal = display.getString("deviceId", "");
                if (!internal.isBlank()) jumpId = internal;
            }
        } catch (Throwable ignored) { }
        intent.putExtra("device_id", jumpId);
        intent.putExtra("device_title", name == null ? "" : name);
        intent.putExtra("model_id", BAND_MODEL);
        intent.putExtra("device_mac_info", mac);
        return true;
    }
}
