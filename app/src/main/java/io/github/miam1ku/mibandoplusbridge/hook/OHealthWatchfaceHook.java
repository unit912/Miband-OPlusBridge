// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.os.Bundle;

import io.github.miam1ku.mibandoplusbridge.hook.xposed.XC_MethodHook;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XposedHelpers;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.HookRuntime;

/**
 * 设备页表盘区（表盘预览）的宿主钩子。
 *
 * <p>宿主设备页（{@code linkage.ui.DeviceDetailsPanelActivity}）的表盘区数据链：
 * <pre>
 * WatchFaceApi.getWatchFaceDeviceInfoAsync(force, listener)
 *   → WatchFacePreviewService → WatchFaceJsExecutor.getWatchFaceDeviceInfo()
 *     → DMManagerApi.getCurrActiveDevice()        // 宿主 DM 体系里的活跃设备
 *     → WfMessageDistributor.getDataManager(mac)  // 按 mac 找表盘数据管理器
 *     → BaseDataManager.getDeviceInfo() → Proto$DeviceInfo
 *     → WatchFaceStoreJsHelper.getDeviceInfo() → DeviceInfoJsBean
 *     → Gson.toJson() → listener.watchFaceDeviceInfo(state, json)
 * </pre>
 *
 * <p>GT3 走桥接 RFCOMM，宿主 DM 体系里没有这块表 → {@code getCurrActiveDevice()}
 * 拿不到它 → 整条链断 → 设备页表盘区退化成暗色产品图。
 *
 * <p>本钩子在链根补给合成数据：当 {@code getWatchFaceDeviceInfo} 对 GT3 返回空时，
 * 用桥接侧已知信息（band-state 快照）合成一份 {@code DeviceInfoJsBean} 形态的
 * JSON 返回，让宿主认为「这台设备有表盘能力」。
 *
 * <p>已知边界（2026-10-18 调研结论）：
 * <ul>
 *   <li>表盘市场列表是 OPPO 云内容，合成 JSON 只能让区域「活」，市场条目仍为空；</li>
 *   <li>华为协议没有「把当前表盘预览图发过来」的命令（Gadgetbridge 同样只能解析
 *       用户导入的 .hwt 包），真实预览图拿不到；预览只能桥接侧合成；</li>
 *   <li>表盘列表（0x27）与当前表盘名由 {@code GetWatchfacesListRequest} 拉到，
 *       经 band-state 的 {@code watchfaces} 字段供给本钩子。</li>
 * </ul>
 */
public final class OHealthWatchfaceHook {

    private static final String EXECUTOR =
            "com.heytap.health.watchface.provider.home.WatchFaceJsExecutor";

    private static final String BRIDGE_AUTHORITY =
            "io.github.miam1ku.mibandoplusbridge.devices";
    /** 设备页顶部表体图的 view id（宿主布局，运行时按名字解析）。 */
    private static final String TARGET_VIEW_ID = "set_hand_img_band2";

    /** 替换回调里再 setImage 会重入 hook —— 同线程哨兵防死循环。 */
    private static final ThreadLocal<Boolean> BUSY = ThreadLocal.withInitial(() -> false);

    private OHealthWatchfaceHook() {}

    public static void install(Object context, ClassLoader loader) {
        XposedHelpers.findAndHookMethod(EXECUTOR, loader, "getWatchFaceDeviceInfo",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            replaceIfOurs(param);
                        } catch (Throwable failure) {
                            HookRuntime.log("OHEALTH_WATCHFACE_HOOK_FAILED "
                                    + failure.getClass().getSimpleName());
                        }
                    }
                });
        installTopPreview(context, loader);
        HookRuntime.log("OHEALTH_WATCHFACE_HOOK_INSTALLED");
    }

    /**
     * 设备 tab 顶部的大图：宿主按展示模型（W301CN）加载的黑屏产品渲染图。
     *
     * <p>把 {@code set_hand_img_band2} 这个 ImageView 的所有设图入口拦下，
     * 换成桥接实时合成的 GT3 表盘预览
     * （{@code content://<authority>/watchface_preview}，数据/时间随 publish
     * 更新；宿主每次重新设图（进页面、滚动回来）都会 decode 最新 PNG）。
     */
    private static void installTopPreview(Object contextObj, ClassLoader loader) {
        final android.content.Context context =
                (android.content.Context) contextObj;
        final int targetId;
        try {
            targetId = context.getResources().getIdentifier(
                    TARGET_VIEW_ID, "id", context.getPackageName());
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_WATCHFACE_TOP_ID_UNAVAILABLE");
            return;
        }
        if (targetId == 0) {
            HookRuntime.log("OHEALTH_WATCHFACE_TOP_ID_UNAVAILABLE");
            return;
        }
        XC_MethodHook swap = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (BUSY.get()) return;
                Object viewObj = param.thisObject;
                if (!(viewObj instanceof android.widget.ImageView view)) return;
                if (view.getId() != targetId) return;
                try {
                    android.graphics.Bitmap bmp = decodePreview(context);
                    if (bmp == null) return;
                    BUSY.set(true);
                    try {
                        view.setImageBitmap(bmp);
                    } finally {
                        BUSY.set(false);
                    }
                    HookRuntime.log("OHEALTH_WATCHFACE_TOP_SWAPPED");
                } catch (Throwable failure) {
                    HookRuntime.log("OHEALTH_WATCHFACE_TOP_FAILED "
                            + failure.getClass().getSimpleName());
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(android.widget.ImageView.class,
                    "setImageDrawable", android.graphics.drawable.Drawable.class, swap);
            XposedHelpers.findAndHookMethod(android.widget.ImageView.class,
                    "setImageResource", int.class, swap);
            XposedHelpers.findAndHookMethod(android.widget.ImageView.class,
                    "setImageBitmap", android.graphics.Bitmap.class, swap);
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_WATCHFACE_TOP_HOOK_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
    }

    /** 从桥接 provider 读预览 PNG 并 decode；失败返回 null（保留宿主原图）。 */
    private static android.graphics.Bitmap decodePreview(android.content.Context context) {
        try (java.io.InputStream in = context.getContentResolver().openInputStream(
                android.net.Uri.parse("content://" + BRIDGE_AUTHORITY
                        + "/watchface_preview"))) {
            if (in == null) return null;
            android.graphics.BitmapFactory.Options opts =
                    new android.graphics.BitmapFactory.Options();
            opts.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888;
            return android.graphics.BitmapFactory.decodeStream(in, null, opts);
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_WATCHFACE_TOP_DECODE_FAILED "
                    + failure.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 宿主问表盘设备信息时，如果它对我们的 GT3 一无所知，就补一份合成的。
     *
     * <p>判定「是我们的表」：桥接已注册（registered）+ 返回值为空/不含 deviceModel。
     * 空返回值正是宿主链路断裂时的表现（getCurrActiveDevice 为 null 时整条链
     * 逐级返回 null，最终 Gson 序列化出 {@code "null"} 或空串）。
     */
    private static void replaceIfOurs(XC_MethodHook.MethodHookParam param) {
        Object original = param.getResult();
        if (original instanceof String text && text.contains("deviceModel")) return;
        Bundle band = OHealthDeviceHook.registeredSnapshot();
        if (band == null || !band.getBoolean("registered", false)) return;
        String json = synthesize(band);
        if (json == null) return;
        param.setResult(json);
        HookRuntime.log("OHEALTH_WATCHFACE_SYNTHESIZED mac="
                + band.getString("mac", "") + " was="
                + (original == null ? "null" : "empty"));
    }

    /**
     * 合成 {@code DeviceInfoJsBean} 形态 JSON。
     *
     * <p>字段来自反编译（classes3.dex，{@code watchface/business/store/bean/
     * DeviceInfoJsBean}）：deviceAppVersion / deviceId / deviceModel / deviceType /
     * firmwareId / firmwareVersion / healthVersion / lang / os / osVersion /
     * region / screen / shape / skuCode / skuIdUrl / source / statusBarHeight / ch。
     *
     * <p>关键取值：
     * <ul>
     *   <li>{@code screen}：GT3 是 466×466 圆表，对应宿主 Resolution 表的 HWHD09；</li>
     *   <li>{@code deviceModel}：W301CN —— 宿主 DEVICETYPE_TO_MODELS 里 type=3 的
     *       一加圆表，与 OHealthDeviceHook.deviceInfo 用的是同一个展示模型；</li>
     *   <li>{@code deviceType}：与 model 对应的 type（3）。</li>
     * </ul>
     */
    private static String synthesize(Bundle band) {
        try {
            org.json.JSONObject json = new org.json.JSONObject()
                    .put("deviceModel", "W301CN")
                    .put("deviceType", 3)
                    .put("screen", "466*466")
                    .put("shape", "round")
                    .put("deviceId", band.getString("deviceId", ""))
                    .put("firmwareVersion", band.getString("firmware", ""))
                    .put("firmwareId", "")
                    .put("deviceAppVersion", "")
                    .put("healthVersion", "")
                    .put("os", "android")
                    .put("osVersion", "")
                    .put("lang", java.util.Locale.getDefault().getLanguage())
                    .put("region", java.util.Locale.getDefault().getCountry())
                    .put("ch", "")
                    .put("skuCode", "")
                    .put("skuIdUrl", "")
                    .put("source", "")
                    .put("statusBarHeight", 0);
            return json.toString();
        } catch (org.json.JSONException failure) {
            return null;
        }
    }
}
