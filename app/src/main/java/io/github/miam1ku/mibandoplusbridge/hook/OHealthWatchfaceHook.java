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
    /**
     * 设备页顶部那块比前景图更大的背景 ImageView：宿主按展示模型（W301CN）把它
     * 设成「设备插画」。早期（v173）我们合成的是一张黑氟橡胶实拍表体，URI 一直是
     * {@code /device_image/miband-body-0.png}；宿主自己的图片加载器（Glide 等）
     * 按 URI 缓存，于是即使桥接侧文件早已换成新机身，宿主缓存里仍留着那张
     * 最早的黑图，叠在前景白图之下、从透明边缘透出来 —— 表现为「黑色在下 /
     * 白色在上、表盘重合但表带不一致」。这里把这个背景 ImageView 清掉，只留
     * 前景（{@code set_hand_img_band2}）上我们换上去的新机身。
     */
    private static final String BG_VIEW_ID = "iv_oobe_watch_bg";
    /** 运行时解析出的背景图 id（解析失败为 0，则跳过）。 */
    private static int bgViewId = 0;

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
     *
     * <p>2026-10-27：设备空间点列表卡跳详情页（{@code DeviceDetailsPanelActivity}
     * 经 {@code hookDetailJump} 重定向后落在健康进程），详情页中央大图
     * 是一个 **没有 resource-id 的 ImageView**（实测 bounds 788x788，
     * view.getId() == NO_ID），原 hook 只认 {@code set_hand_img_band2} 漏掉它。
     * 这里扩两路：
     * <ol>
     *   <li>带 id 的（{@code set_hand_img_band2}）：维持原逻辑；</li>
     *   <li>无 id 的大尺寸 ImageView（width/height 都 &gt; 300px）：
     *       在 setImageDrawable 之后注册一个 OneShot
     *       OnGlobalLayoutListener，等 layout 真正完成拿到尺寸时再判定替换。</li>
     * </ol>
     * 用 BUSY ThreadLocal 防重入；用 WeakHashMap 标记已注册 listener 的 view
     * 避免重复挂 listener。
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
        // 解析背景层 id（宿主原生设备插画 iv_oobe_watch_bg，那张很早之前的黑图）。
        // 即使前景 id 解析失败也要先解析它，便于后续 neutralize。
        int bgId = 0;
        try {
            bgId = context.getResources().getIdentifier(
                    BG_VIEW_ID, "id", context.getPackageName());
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_WATCHFACE_BG_ID_UNAVAILABLE");
        }
        bgViewId = bgId;
        if (targetId == 0) {
            HookRuntime.log("OHEALTH_WATCHFACE_TOP_ID_UNAVAILABLE");
            return;
        }
        // 已挂 listener 的 view（防止重复挂）
        final java.util.Set<android.view.View> pending =
                java.util.Collections.newSetFromMap(
                        new java.util.WeakHashMap<>());
        XC_MethodHook swap = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (BUSY.get()) return;
                Object viewObj = param.thisObject;
                if (!(viewObj instanceof android.widget.ImageView view)) return;
                int id = view.getId();
                // 背景层 iv_oobe_watch_bg（601×896 矩形大框）：宿主设计里它放完整
                // 产品图，前景 520×520 圆框叠在表的屏幕位置上。直接换成桥接全身图。
                if (bgViewId != 0 && id == bgViewId) {
                    swapBg(view, context);
                    return;
                }
                // 第一路：设备页顶部带 id 的 set_hand_img_band2。
                if (id == targetId) {
                    swapNow(view, context, "ID");
                    return;
                }
                // 第二路：详情页无 id 的大图。设图时 view 多半还没 layout，
                // 宽高为 0，无法立即判定尺寸 —— 挂 OneShot
                // OnGlobalLayoutListener，等 layout 完成时看尺寸。
                // 作用域保护：只有「详情页 Activity」里的大图才替换，
                // 避免误伤健康 App 其他页面的无 id 大 ImageView。
                if (id == android.view.View.NO_ID && !pending.contains(view)
                        && isDetailActivity(view.getContext())) {
                    pending.add(view);
                    view.getViewTreeObserver().addOnGlobalLayoutListener(
                            new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                                @Override
                                public void onGlobalLayout() {
                                    try {
                                        int w = view.getWidth();
                                        int h = view.getHeight();
                                        if (w < 300 || h < 300) return;
                                        // 移除自身（OneShot）。API 16+ 用
                                        // removeOnGlobalLayoutListener；这里设备
                                        // 是 API 35，可安全调用。
                                        view.getViewTreeObserver()
                                                .removeOnGlobalLayoutListener(this);
                                        swapNow(view, context, "BIG");
                                    } catch (Throwable ignored) {
                                        // 静默；layout 阶段抛异常不能影响宿主
                                    } finally {
                                        pending.remove(view);
                                    }
                                }
                            });
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

    /**
     * 判断 view 是否处在「设备详情页」Activity 内（健康 App 的
     * {@code DeviceDetailsPanelActivity}）。第二路（无 id 大图）替换只对它生效，
     * 防止误伤健康 App 其他页面的无 id 大 ImageView。
     *
     * <p>view.getContext() 在 Activity 里被包成 {@code ContextThemeWrapper}，
     * 顺着 {@code getBaseContext()} 一路剥到 Activity 再比对类名。
     */
    private static boolean isDetailActivity(android.content.Context ctx) {
        android.content.Context c = ctx;
        while (c != null) {
            if (c instanceof android.app.Activity) {
                String name = c.getClass().getName();
                return name.contains("DeviceDetailsPanel")
                        || name.contains("DeviceDetail");
            }
            if (c instanceof android.content.ContextWrapper) {
                c = ((android.content.ContextWrapper) c).getBaseContext();
            } else {
                break;
            }
        }
        return false;
    }

    /**
     * 实际替换 ImageView 的图为桥接合成的 GT3 表盘 PNG；tag 用于日志区分来源。
     *
     * <p>2026-10-26 v178：健康设备页顶部布局是「背景 601×896 矩形层（完整产品图）
     * + 前景 520×520 圆形层（表盘，叠在表的屏幕位置）」——按 uiautomator bounds
     * 推算，背景层若 FIT_CENTER 显示 720×1145 全身图，其表盘圆心与前景圆框中心
     * 仅差 ~4px（宿主本来就是按这种叠放设计的）。v177 把全身图塞进前景圆框会被
     * 圆形裁剪切掉表带。因此这里改为：<b>前景设为全透明位图</b>（只对带 id 的
     * 顶部图生效；详情页大图 BIG 照旧显示全身图），<b>背景层填入全身实时图</b>
     * （{@link #populateSiblingBg}）——健康页从而显示完整机身，与设备空间一致。
     * 若背景层不可用（id 解析失败/不在同层级），前景回退为 FIT_CENTER 显示全身图
     * （v177 行为，表带被圆框裁掉但仍有可用显示）。
     */
    private static void swapNow(android.widget.ImageView view,
            android.content.Context context, String tag) {
        try {
            android.graphics.Bitmap bmp = decodePreview(context);
            if (bmp == null) return;
            boolean bgReady = false;
            // 先填背景层（populate 内部自带 BUSY 保护，且必须在前景设图前完成：
            // 若前景设图时 BUSY 已被 populate 复位，hook 会重入 swapNow 无限递归 → ANR）。
            if ("ID".equals(tag)) {
                bgReady = populateSiblingBg(view, context, bmp);
            }
            BUSY.set(true);
            try {
                if (bgReady) {
                    // 前景圆框透明化：表盘由背景层全身图承担，避免双重表盘错位叠加。
                    android.graphics.Bitmap clear = android.graphics.Bitmap.createBitmap(
                            466, 466, android.graphics.Bitmap.Config.ARGB_8888);
                    view.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                    view.setImageBitmap(clear);
                } else {
                    // 详情页 BIG 路径（无圆框）或背景层不可用：直接显示全身图。
                    view.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                    view.setImageBitmap(bmp);
                }
            } finally {
                BUSY.set(false);
            }
            HookRuntime.log("OHEALTH_WATCHFACE_TOP_SWAPPED tag=" + tag
                    + " bg=" + (bgReady ? "1" : "0")
                    + " id=" + view.getId() + " w=" + view.getWidth()
                    + " h=" + view.getHeight());
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_WATCHFACE_TOP_FAILED "
                    + failure.getClass().getSimpleName());
        }
    }

    /**
     * 把同层级（递归）的宿主背景 ImageView（{@code iv_oobe_watch_bg}，601×896
     * 矩形）填入桥接全身实时图并保持可见 —— 前景圆框透明后，表盘由它显示。
     *
     * <p>背景层若仍保留宿主原生黑图会从前景透明处透出（v176 之前的叠加 bug），
     * 所以这里必须主动换图而非隐藏。{@code FIT_CENTER} 是对齐关键：720×1145 在
     * 601×896 框内等比缩放后表盘圆心恰好落在前景圆框中心（偏差 ~4px）。
     *
     * @return true = 背景层已成功接管显示；false = 背景层不可用，调用方回退。
     */
    private static boolean populateSiblingBg(android.view.View foreground,
            android.content.Context context, android.graphics.Bitmap bmp) {
        try {
            if (bgViewId == 0) return false;
            android.view.ViewParent parent = foreground.getParent();
            if (!(parent instanceof android.view.ViewGroup)) return false;
            android.view.View bg = ((android.view.ViewGroup) parent).findViewById(bgViewId);
            if (!(bg instanceof android.widget.ImageView)) return false;
            android.widget.ImageView iv = (android.widget.ImageView) bg;
            iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            if (iv.getVisibility() != android.view.View.VISIBLE) {
                iv.setVisibility(android.view.View.VISIBLE);
            }
            BUSY.set(true);
            try {
                iv.setImageBitmap(bmp);
            } finally {
                BUSY.set(false);
            }
            HookRuntime.log("OHEALTH_WATCHFACE_BG_POPULATED id=" + bg.getId()
                    + " w=" + iv.getWidth() + " h=" + iv.getHeight());
            return true;
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_WATCHFACE_BG_POPULATE_FAILED "
                    + failure.getClass().getSimpleName());
            return false;
        }
    }

    /** 背景层被宿主主动设图（进页面/刷新）时，同样换成桥接全身实时图。 */
    private static void swapBg(android.widget.ImageView view, android.content.Context context) {
        try {
            android.graphics.Bitmap bmp = decodePreview(context);
            if (bmp == null) return;
            BUSY.set(true);
            try {
                view.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                if (view.getVisibility() != android.view.View.VISIBLE) {
                    view.setVisibility(android.view.View.VISIBLE);
                }
                view.setImageBitmap(bmp);
            } finally {
                BUSY.set(false);
            }
            HookRuntime.log("OHEALTH_WATCHFACE_BG_SWAPPED id=" + view.getId()
                    + " w=" + view.getWidth() + " h=" + view.getHeight());
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_WATCHFACE_BG_FAILED "
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
