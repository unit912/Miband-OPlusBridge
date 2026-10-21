// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.integration;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
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
     * Device ids this bridge owns. The Xiaomi chain uses {@code miband11_} plus a
     * 64-hex fingerprint, the Huawei chain {@code huaweigt3_} plus the same shape.
     * Both have to be listed: a control-center tap on the band hands the detail
     * intent to the Health panel, and the check runs on the device id the host
     * knows the card by.
     */
    private static final Pattern BAND = Pattern.compile("(miband11_|huaweigt3_)[0-9a-f]{64}");

    private NativePanel() {}

    public static boolean panelAction(String action) {
        return PANEL.equals(action) || PAGE.equals(action);
    }

    public static boolean bandDevice(String deviceId) {
        return deviceId != null && BAND.matcher(deviceId).matches();
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
        if (!bandDevice(deviceId)) return false;
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
        intent.putExtra("device_id", deviceId);
        intent.putExtra("device_title", name == null ? "" : name);
        intent.putExtra("model_id", BAND_MODEL);
        intent.putExtra("device_mac_info", mac);
        return true;
    }
}
