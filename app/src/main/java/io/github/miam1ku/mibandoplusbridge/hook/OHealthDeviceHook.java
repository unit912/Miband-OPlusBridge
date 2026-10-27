// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import io.github.miam1ku.mibandoplusbridge.hook.xposed.HookRuntime;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import java.lang.ref.WeakReference;
import java.lang.reflect.Modifier;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XC_MethodHook;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XposedBridge;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XposedHelpers;
import io.github.miam1ku.mibandoplusbridge.HostIdentity;
import io.github.miam1ku.mibandoplusbridge.integration.DeviceCardProvider;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Projects the registered Xiaomi device into OHealth without modifying other device identities. */
public final class OHealthDeviceHook {
    private static final String HOST = "com.heytap.health";
    private static final String CONTROLLER = "com.heytap.health.device.tab.controller.DeviceTabAdapterController";
    private static final String INFO = "com.heytap.health.devicemanager.processor.bean.UserDeviceInfo";
    private static final String WEARABLE = "com.heytap.health.device.tab.bean.WearableDevice";
    private static final String CONSTANTS = "com.heytap.health.device_manager_base.DeviceConstants";

    private static volatile Bundle snapshot;
    private static volatile String remembered;
    /** 快照循环的周期重查间隔：健康卡的 WatchProvider 行异步发布，只能靠低频重扫兜底。 */
    private static final long REFRESH_INTERVAL_MS = 60_000L;
    private static int snapshotAttempts;
    private static boolean snapshotTraced;

    private static Handler worker;
    private static Handler main;
    private static final List<WeakReference<Object>> CONTROLLERS = new ArrayList<>();
    private static final ThreadLocal<Boolean> APPENDING = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> REFRESHING = ThreadLocal.withInitial(() -> false);

    private OHealthDeviceHook() {}
    private static Context hostContext;
    public static void install(Context context, ClassLoader loader) throws Exception {
        hostContext = context.getApplicationContext();
        // Application.attach runs before ActivityThread publishes the Application instance.
        if (hostContext == null) hostContext = context;
        if (!HOST.equals(context.getPackageName())) return;
        main = HookRuntime.main();
        XposedHelpers.findAndHookMethod(CONTROLLER, loader, "refreshWearableDeviceList",
                List.class, List.class, String.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        remember(param.thisObject);
                        if (APPENDING.get() || !(param.args[0] instanceof List<?> current)) return;
                        APPENDING.set(true);
                        try {
                            Bundle shown = snapshot;
                            if (shown == null) return;
                            Object info = shown.getBoolean("registered", false) ? deviceInfo(loader, shown) : null;
                            ArrayList<Object> combined = new ArrayList<>(current.size() + (info == null ? 0 : 1));
                            for (Object item : current) if (!matches(item, shown)) combined.add(item);
                            if (info != null) combined.add(info);
                            param.args[0] = combined;
                        } catch (Throwable failure) {
                            HookRuntime.log("OHEALTH_DEVICE_ROW_UNAVAILABLE "
                                    + failure.getClass().getSimpleName());
                        } finally {
                            APPENDING.set(false);
                        }
                    }
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.getThrowable() != null) {
                            HookRuntime.log("OHEALTH_DEVICE_REFRESH_FAILED "
                                    + param.getThrowable().getClass().getSimpleName());
                        } else {
                            project(param.thisObject, loader, null, false);
                        }
                    }
                });
        Class<?> eventType = XposedHelpers.findClass(
                "com.heytap.health.device.flexadapter.refresh.EventType", loader);
        XposedHelpers.findAndHookMethod(CONTROLLER, loader, "refreshView", eventType, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                remember(param.thisObject);
                project(param.thisObject, loader, null, false);
            }
        });
        Class<?> infoClass = XposedHelpers.findClass(INFO, loader);
        XposedHelpers.findAndHookMethod(CONTROLLER, loader, "checkDeviceIsDisable", infoClass,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (isBand(param.args.length == 0 ? null : param.args[0])) param.setResult(false);
                    }
                });
        XposedHelpers.findAndHookMethod(CONTROLLER, loader, "doConnect", infoClass, String.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isBand(param.args.length == 0 ? null : param.args[0])) return;
                        param.setResult(null);
                        requestSync();
                    }
                });
        XposedHelpers.findAndHookMethod(
                "com.heytap.health.devicemanagerimpl.host.HDM2Connect", loader, "retryConnect", String.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Object mac = XposedHelpers.getObjectField(param.thisObject, "mCurrActiveMac");
                        Bundle shown = snapshot;
                        if (shown != null && io.github.miam1ku.mibandoplusbridge.data.MacIds.same(
                                shown.getString("mac", ""), String.valueOf(mac))) {
                            param.setResult(null);
                        }
                    }
                });
        XposedHelpers.findAndHookMethod(CONTROLLER, loader, "resetCurrSelectMac", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                remember(param.thisObject);
                // Must run before the host discards a selected MAC absent from its list.
                project(param.thisObject, loader, null, false);
            }
        });
        installDevicePages(loader);
        installNativePanel(loader);
        installEmptyCloudCleanup(loader);
        observeSnapshots(loader);
        hookWatchProviderIcon(loader);
    }

    /** 桥接渲染图的 content URI（桥接 provider 的白名单本来就放行 mydevices 读取）。 */
    private static final String BRIDGE_DEVICE_IMAGE_URI =
            "content://io.github.miam1ku.mibandoplusbridge.devices/device_image";

    /**
     * 2026-10-26：yield=true 时健康卡顶上（正确的单卡状态），但健康卡 device_data 的
     * 面型是展示模型 W301CN，mydevices 按 modelId 映射出「白色金属链带」的内置默认
     * 渲染图——列表卡显示的手表和 GT3 毫无关系。这里 hook 健康 WatchProvider 的
     * query 出口，把这台表的行的 device_data 里 iconUrl/mIconUrl 改写成桥接渲染图。
     *
     * <p>必须重建 MatrixCursor 而不是包 CursorWrapper：跨进程 cursor 走
     * CursorWindow 原生填充，Java 层 getString 改写不影响传输内容；
     * MatrixCursor 的行数据会被逐行拷进窗口，改写值能真正到达 mydevices。
     * 无命中行时原样返回，零额外开销。
     */
    private static void hookWatchProviderIcon(ClassLoader loader) {
        try {
            Class<?> provider = XposedHelpers.findClass(
                    "com.heytap.health.linkage.watch.WatchProvider", loader);
            // query 可能在任意一层父类声明（WatchProvider 本体实测没有覆写）——
            // 沿继承链收集全部声明；基类入口被别的 provider 复用时，
            // rewriteHealthCardIcon 里的 device_data+MAC 命中条件保证只动本表行。
            java.util.LinkedHashSet<java.lang.reflect.Method> found = new java.util.LinkedHashSet<>();
            for (Class<?> k = provider; k != null && k != Object.class; k = k.getSuperclass()) {
                for (java.lang.reflect.Method m : k.getDeclaredMethods()) {
                    if (!"query".equals(m.getName())) continue;
                    if (!android.database.Cursor.class.isAssignableFrom(m.getReturnType())) continue;
                    if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())) continue;
                    found.add(m);
                }
            }
            for (java.lang.reflect.Method m : found) {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        rewriteHealthCardIcon(param);
                    }
                });
            }
            HookRuntime.log("DEVICE_SPACE_ICON_HOOK_INSTALLED methods=" + found.size());
        } catch (Throwable failure) {
            HookRuntime.log("DEVICE_SPACE_ICON_HOOK_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
    }

    /** 命中本表行时重建 cursor 并改写 iconUrl/mIconUrl；否则原样返回。 */
    private static void rewriteHealthCardIcon(XC_MethodHook.MethodHookParam param) {
        // 只处理卡片表查询（基类入口会被其它 provider 复用）
        if (param.args == null || param.args.length == 0
                || !(param.args[0] instanceof android.net.Uri uri)
                || !String.valueOf(uri).contains("device_info")) return;
        if (!(param.getResult() instanceof android.database.Cursor cursor)) return;
        try {
            int dataIdx = cursor.getColumnIndex("device_data");
            int idIdx = cursor.getColumnIndex("device_id");
            if (dataIdx < 0 || cursor.getCount() == 0) return;
            Bundle shown = snapshot;
            String mac = shown == null ? "" : shown.getString("mac", "");
            if (mac == null || mac.isBlank()) return;
            String md5 = md5Lowercase(mac.toLowerCase(java.util.Locale.ROOT));
            int hit = -1;
            for (int pos = 0; cursor.moveToPosition(pos); pos++) {
                String data = cursor.getString(dataIdx);
                if (data == null || !data.contains("iconUrl")) continue;
                boolean match = (idIdx >= 0 && md5.equals(cursor.getString(idIdx)))
                        || data.contains(mac);
                if (match) { hit = pos; break; }
            }
            if (hit < 0) return;
            String[] cols = cursor.getColumnNames();
            android.database.MatrixCursor out =
                    new android.database.MatrixCursor(cols, cursor.getCount());
            android.net.Uri notify = cursor.getNotificationUri();
            for (int pos = 0; cursor.moveToPosition(pos); pos++) {
                Object[] row = new Object[cols.length];
                for (int i = 0; i < cols.length; i++) {
                    if (i == dataIdx && cursor.getPosition() == hit) {
                        org.json.JSONObject o = new org.json.JSONObject(cursor.getString(i));
                        o.put("iconUrl", BRIDGE_DEVICE_IMAGE_URI);
                        o.put("mIconUrl", BRIDGE_DEVICE_IMAGE_URI);
                        row[i] = o.toString();
                        continue;
                    }
                    row[i] = switch (cursor.getType(i)) {
                        case android.database.Cursor.FIELD_TYPE_NULL -> null;
                        case android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i);
                        case android.database.Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(i);
                        default -> cursor.getString(i);
                    };
                }
                out.addRow(row);
            }
            if (notify != null && hostContext != null) {
                out.setNotificationUri(hostContext.getContentResolver(), notify);
            }
            param.setResult(out);
            HookRuntime.log("DEVICE_SPACE_HEALTH_ICON swapped row=" + hit);
        } catch (Throwable failure) {
            HookRuntime.log("DEVICE_SPACE_ICON_REWRITE_FAILED "
                    + failure.getClass().getSimpleName());
        }
    }

    /**
     * An empty cloud list calls this on the main thread and disconnects every wearable,
     * including this band. That binder call is what freezes the page.
     */
    static boolean skipEmptyCloudCleanup(boolean registered) {
        return registered;
    }

    private static void installEmptyCloudCleanup(ClassLoader loader) {
        try {
            Class<?> fragment = XposedHelpers.findClass(
                    "com.heytap.health.device.tab.NewDeviceFragment", loader);
            XposedBridge.hookAllMethods(fragment, "cleanAndDisconnect", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!skipEmptyCloudCleanup(registeredBand())) return;
                    param.setResult(null);
                    HookRuntime.log("OHEALTH_CLEAN_DISCONNECT_SKIPPED");
                }
            });
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_CLEAN_DISCONNECT_HOOK_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
    }

    /**
     * UI hooks read the process-local snapshot reference. The {@code Bundle} can still be mutated,
     * and {@link #liveSnapshot()} replaces it. {@link #registeredSnapshot()} returns a stable copy.
     */
    private static Bundle display() {
        Bundle shown = snapshot;
        return shown != null && shown.getBoolean("registered", false) ? shown : null;
    }

    public static Bundle registeredSnapshot() {
        Bundle shown = display();
        return shown == null ? null : new Bundle(shown);
    }

    /**
     * Official rows use the Bluetooth MAC as deviceUniqueId.
     * The bridge queue keeps the miband11 id.
     */
    public static String healthId(String bridgeId) {
        Bundle band = registeredSnapshot();
        if (band == null || bridgeId == null) return bridgeId;
        String mac = band.getString("mac", "");
        String id = band.getString("deviceId", "");
        if (mac.isBlank() || id.isBlank() || !id.equals(bridgeId)) return bridgeId;
        return mac;
    }

    /** Writes this band into the account's bound-device list. The list stays after the module stops. */
    public static void rememberBand(ClassLoader loader) {
        Bundle shown = registeredSnapshot();
        if (shown == null || loader == null) return;
        String mac = shown.getString("mac", "");
        if (mac.isBlank()) return;
        try {
            Class<?> accounts = Class.forName("com.heytap.health.account.AccountHelper", false, loader);
            Object manager = accounts.getMethod("getAccountManager").invoke(null);
            String ssoid = String.valueOf(manager.getClass().getMethod("getSsoid").invoke(manager));
            if (ssoid.isBlank() || "com.heytap.health".equals(ssoid) || "null".equals(ssoid)) return;
            String key = ssoid + "|" + mac;
            if (key.equals(remembered)) return;
            Object info = deviceInfo(loader, shown);
            if (info == null) return;
            Class<?> local = Class.forName(
                    "com.heytap.health.devicemanagerimpl.processor.DMLocalDeviceManager", false, loader);
            Object instance = local.getField("INSTANCE").get(null);
            local.getMethod("saveUserBoundDevice", info.getClass()).invoke(instance, info);
            remembered = key;
            HookRuntime.log("OHEALTH_DEVICE_REMEMBERED");
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_DEVICE_REMEMBER_FAILED "
                    + failure.getClass().getSimpleName());
        }
    }

    /** True when the health app's current or third-party selection is this band. */
    public static boolean matchesActiveDevice(Object manager, Object allRole) {
        Bundle band = registeredSnapshot();
        if (band == null || manager == null) return false;
        String mac = band.getString("mac", "");
        try {
            if (io.github.miam1ku.mibandoplusbridge.data.MacIds.same(mac, String.valueOf(
                    io.github.miam1ku.mibandoplusbridge.hook.xposed.XposedHelpers.callMethod(manager, "getThirdpartySelectMac")))) return true;
            return allRole != null && io.github.miam1ku.mibandoplusbridge.data.MacIds.same(mac, String.valueOf(
                    io.github.miam1ku.mibandoplusbridge.hook.xposed.XposedHelpers.callMethod(manager, "getCurrActiveMacByRole", allRole)));
        } catch (Throwable unavailable) { return false; }
    }

    private static void observeSnapshots(ClassLoader loader) {
        HandlerThread thread = HookRuntime.thread("OplusBandDeviceSnapshot");
        worker = new Handler(thread.getLooper());
        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            // 先撤掉自己可能积压的下一次计划： provider 变更（observer）与 2s 重试都会
            // 再排，这里统一收敛成「跑一次只排一个下一次」，避免 60s 心跳叠加。
            worker.removeCallbacks(refresh[0]);
            Bundle result = fromProvider();
            main.post(() -> {
                Bundle previous = snapshot;
                if (result != null) {
                    snapshot = result;
                } else if (previous != null) {
                    Bundle offline = new Bundle(previous);
                    offline.putBoolean("connected", false);
                    snapshot = offline;
                }
                for (Object controller : liveControllers()) {
                    project(controller, loader, previous, true);
                }
                worker.post(() -> dropDuplicateSpaceCard(loader));
            });
            // The first provider call often races process start. A miss used to leave the device tab empty.
            boolean giveUp = result == null && snapshotAttempts >= 14;
            boolean nowRegistered = result != null && result.getBoolean("registered");
            Bundle shownNow = snapshot;
            boolean wasRegistered = shownNow != null && shownNow.getBoolean("registered", false);
            if ((!snapshotTraced && (result != null || giveUp)) || (result != null && nowRegistered != wasRegistered)) {
                snapshotTraced = true;
                trace(result == null ? "OHEALTH_DEVICE_SNAPSHOT missing"
                        : "OHEALTH_DEVICE_SNAPSHOT registered=" + result.getBoolean("registered")
                        + " connected=" + result.getBoolean("connected"));
            }
            if (result == null && snapshotAttempts < 15) {
                if (snapshotAttempts == 0) HookRuntime.log("OHEALTH_DEVICE_SNAPSHOT_RETRY");
                snapshotAttempts++;
                worker.postDelayed(refresh[0], 2_000);
            } else snapshotAttempts = 0;
            // 周期重查：健康卡在 WatchProvider 里的行是异步发布的（进程起来、数据落库
            // 之后才出现），只靠桥接 provider 变更驱动的话，早先一次查不到就永远不 yield，
            // 设备空间和快捷面板各留一块表。每 60s 无条件重跑一次，健康卡一上线，
            // 下一轮就把桥接卡片让出去（onDeviceRemoved 由设备空间观察者执行）。
            worker.postDelayed(refresh[0], REFRESH_INTERVAL_MS);
        };
        ContentObserver observer = new ContentObserver(worker) {
            @Override public void onChange(boolean selfChange) {
                worker.removeCallbacks(refresh[0]);
                worker.post(refresh[0]);
            }
        };
        worker.post(() -> {
            try {
                HookRuntime.observe(hostContext.getContentResolver(), DeviceCardProvider.URI, true, observer);
            } catch (Throwable failure) {
                HookRuntime.log("OHEALTH_OBSERVER_UNAVAILABLE " + failure.getClass().getSimpleName());
            }
            refresh[0].run();
        });
    }

    private static Bundle fromProvider() {
        try {
            Bundle result = hostContext.getContentResolver().call(DeviceCardProvider.URI, "bandDisplay", null, null);
            // A missing or malformed IPC snapshot stays unknown. Removal waits for an explicit clear.
            if (result == null || !(result.get("registered") instanceof Boolean)) return null;
            if (result.getBoolean("registered") && (result.getString("deviceId", "").isBlank()
                    || result.getString("mac", "").isBlank() || result.getString("name", "").isBlank())) return null;
            // An empty state file is not an unregister. Unregister keeps the MAC and device id.
            if (!result.getBoolean("registered") && result.getString("deviceId", "").isBlank()
                    && result.getString("mac", "").isBlank()) return null;
            return new Bundle(result);
        } catch (Throwable failure) {
            String detail = failure.getMessage() == null ? "" : failure.getMessage();
            detail = detail.replaceAll("[0-9A-Fa-f]{8,}", "#");
            if (detail.length() > 180) detail = detail.substring(0, 180);
            HookRuntime.log("OHEALTH_PROVIDER_UNAVAILABLE " + failure.getClass().getSimpleName()
                    + " " + detail);
            return null;
        }
    }


    private static void remember(Object controller) {
        synchronized (CONTROLLERS) {
            boolean found = false;
            for (int i = CONTROLLERS.size() - 1; i >= 0; i--) {
                Object current = CONTROLLERS.get(i).get();
                if (current == null) CONTROLLERS.remove(i);
                else if (current == controller) found = true;
            }
            if (!found) CONTROLLERS.add(new WeakReference<>(controller));
        }
    }

    private static List<Object> liveControllers() {
        ArrayList<Object> live = new ArrayList<>();
        synchronized (CONTROLLERS) {
            for (int i = CONTROLLERS.size() - 1; i >= 0; i--) {
                Object controller = CONTROLLERS.get(i).get();
                if (controller == null) CONTROLLERS.remove(i);
                else live.add(controller);
            }
        }
        return live;
    }

    private static void project(Object controller, ClassLoader loader, Bundle previous, boolean refreshView) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            WeakReference<Object> reference = new WeakReference<>(controller);
            main.post(() -> {
                Object live = reference.get();
                if (live != null) project(live, loader, previous, refreshView);
            });
            return;
        }
        if (REFRESHING.get()) return;
        REFRESHING.set(true);
        try {
            Bundle shown = snapshot;
            if (shown == null) return;
            if (previous != null && !sameIdentity(previous, shown)) removeListed(controller, previous);
            if (!shown.getBoolean("registered", false)) {
                removeListed(controller, shown);
                return;
            }
            Object info = keepListed(controller, loader, shown);
            if (refreshView && info != null) {
                // refreshView leaves an empty wearable list empty. Feed the same host path that fills it.
                java.util.ArrayList<Object> seed = new java.util.ArrayList<>();
                seed.add(info);
                XposedHelpers.callMethod(controller, "refreshWearableDeviceList", seed, new java.util.ArrayList<>(), "band snapshot");
            }
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_DEVICE_ROW_UNAVAILABLE " + failure.getClass().getSimpleName());
        } finally {
            REFRESHING.set(false);
        }
    }

    private static boolean sameIdentity(Bundle first, Bundle second) {
        return first.getString("deviceId", "").equals(second.getString("deviceId", ""))
                && first.getString("mac", "").equalsIgnoreCase(second.getString("mac", ""));
    }

    private static void requestSync() {
        worker.post(() -> {
            String status = null;
            try {
                Bundle response = hostContext.getContentResolver().call(DeviceCardProvider.URI, "requestSync", null, null);
                if (response != null) status = response.getString("status");
            } catch (Throwable failure) {
                HookRuntime.log("OHEALTH_SYNC_UNAVAILABLE " + failure.getClass().getSimpleName());
            }
            if ("OPEN_CONFIG_REQUIRED".equals(status)) {
                HookRuntime.log("OHEALTH_SYNC_DEFERRED");
            }
        });
    }



    private static Object deviceInfo(ClassLoader loader, Bundle display) throws Exception {
        Class<?> constants = XposedHelpers.findClass(CONSTANTS, loader);
        // 展示模型：一加手表 W301CN（DEVICETYPE_TO_MODELS 里 type=3，圆表，
        // 视觉上最接近华为 GT3）。上游经验是「OPPO band 模型才能加载原生页面」，
        // 但那是小米手环项目的取舍；华为侧的 category/sport-mode 都由桥接自己
        // 的 BandCatalog(modelId=huawei.watch.gt3) 决定，与这里的展示模型无关。
        // 若手表模型导致设备页异常，回退 BAND_DEVICE_MODEL 即可。
        String model = "W301CN";
        int type = wearableType(constants, model);
        if (type < 0) {
            model = (String) XposedHelpers.getStaticObjectField(constants, "BAND_DEVICE_MODEL");
            type = wearableType(constants, model);
        }
        if (type < 0 || model == null || model.isBlank()) {
            HookRuntime.log("OHEALTH_DEVICE_TYPE_MISSING");
            return null;
        }
        Object info = XposedHelpers.newInstance(XposedHelpers.findClass(INFO, loader));
        String mac = display.getString("mac", "");
        XposedHelpers.callMethod(info, "setMac", mac);
        XposedHelpers.callMethod(info, "setDeviceUniqueId", mac.isBlank()
                ? display.getString("deviceId") : mac);
        XposedHelpers.callMethod(info, "setModel", model);
        XposedHelpers.callMethod(info, "setDeviceType", type);
        XposedHelpers.callMethod(info, "setManufacturer", "OPPO");
        String terminal = (String) XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("com.heytap.health.base.app.SystemUtils", loader), "getAndroidId");
        if (terminal != null && !terminal.isBlank()) XposedHelpers.callMethod(info, "setAppTerminalId", terminal);
        XposedHelpers.callMethod(info, "setBleMac", display.getString("mac"));
        updateInfo(info, display);
        return info;
    }

    private static boolean isBand(Object info) {
        return matches(info, snapshot);
    }

    private static boolean matches(Object info, Bundle shown) {
        if (info == null || shown == null) return false;
        String mac = shown.getString("mac", "");
        String deviceId = shown.getString("deviceId", "");
        return io.github.miam1ku.mibandoplusbridge.data.MacIds.same(mac, String.valueOf(XposedHelpers.callMethod(info, "getMac")))
                || (!deviceId.isBlank() && deviceId.equals(XposedHelpers.callMethod(info, "getDeviceUniqueId")));
    }

    private static void updateInfo(Object info, Bundle shown) {
        XposedHelpers.callMethod(info, "setDeviceName", shown.getString("name", ""));
        // 展示模型升级（手环 → 手表）后，把已入库的旧模型也刷成新值，
        // 否则设备页继续按旧模型渲染产品图。只作用于 matches() 命中的
        // 我们自己的设备。
        try {
            Class<?> constants = XposedHelpers.findClass(CONSTANTS, info.getClass().getClassLoader());
            String watchModel = "W301CN";
            if (wearableType(constants, watchModel) >= 0) {
                XposedHelpers.callMethod(info, "setModel", watchModel);
                XposedHelpers.callMethod(info, "setDeviceType", wearableType(constants, watchModel));
            }
        } catch (Throwable ignored) { }
        int battery = shown.getInt("battery", -1);
        XposedHelpers.callMethod(info, "setCapacityPercent", battery >= 0 && battery <= 100 ? battery : -1);
        XposedHelpers.callMethod(info, "setConnectionState", shown.getBoolean("connected") ? 102 : 103);
        XposedHelpers.callMethod(info, "setChargeStatus", shown.getBoolean("charging") ? 1 : 0);
        XposedHelpers.callMethod(info, "setFirmwareVersion", shown.getString("firmware", ""));
        XposedHelpers.callMethod(info, "setBindingTime", shown.getLong("registeredAtMs", 0));
    }

    private static Object keepListed(Object controller, ClassLoader loader, Bundle shown) throws Exception {
        Object list = XposedHelpers.getObjectField(controller, "deviceList");
        XposedHelpers.callMethod(list, "writeLock");
        try {
            for (Object device : (Iterable<?>) list) {
                if (!Boolean.TRUE.equals(XposedHelpers.callMethod(device, "isWearableDevice"))) continue;
                Object info = XposedHelpers.callMethod(device, "getData");
                if (matches(info, shown)) {
                    updateInfo(info, shown);
                    return info;
                }
            }
            Object info = deviceInfo(loader, shown);
            if (info != null) XposedHelpers.callMethod(list, "add",
                    XposedHelpers.newInstance(XposedHelpers.findClass(WEARABLE, loader), info));
            return info;
        } finally {
            XposedHelpers.callMethod(list, "writeUnLock");
        }
    }

    private static void removeListed(Object controller, Bundle identity) {
        Object list = XposedHelpers.getObjectField(controller, "deviceList");
        String mac = null;
        XposedHelpers.callMethod(list, "readLock");
        try {
            for (Object device : (Iterable<?>) list) {
                if (!Boolean.TRUE.equals(XposedHelpers.callMethod(device, "isWearableDevice"))) continue;
                Object info = XposedHelpers.callMethod(device, "getData");
                if (matches(info, identity)) {
                    mac = (String) XposedHelpers.callMethod(info, "getMac");
                    break;
                }
            }
        } finally {
            XposedHelpers.callMethod(list, "readUnLock");
        }
        // This host method removes the local row. Remote unbind stays on the official path.
        if (mac != null && !mac.isBlank()) XposedHelpers.callMethod(controller, "deleteDeviceByMac", mac);
    }

    private static final String WEARABLE_ITEM =
            "com.heytap.health.device.tab.itemview.wearable.";
    private static final Set<String> HIDE_ITEMS = Set.of(
            "DeviceWatchFaceItem", "MenuWatchFaceCenterItem", "MenuWatchFaceOfficialItem", "MenuClockItem");
    private static final Map<String, String> NATIVE_PAGES = Map.ofEntries(
            Map.entry("MenuHeartRateItem", "com.heytap.health.heartrate.ui.HeartRateHistoryActivity"),
            Map.entry("MenuSleepItem", "com.heytap.health.sleep.SleepHistoryActivity"),
            Map.entry("MenuDailyActivityItem", "com.heytap.health.daily.ui.DailyActivityDetailActivity"),
            Map.entry("MenuSportHealthItem", "com.heytap.health.daily.ui.DailyActivityDetailActivity"),
            Map.entry("MenuBloodOxygenItem", "com.heytap.health.bloodoxygen.ui.BloodOxygenHistoryActivity"),
            Map.entry("MenuPressureItem", "com.heytap.health.stress.ui.StressHistoryActivity"),
            Map.entry("MenuNotificationItem", "com.heytap.health.device.tab.notify.NotifySettingsActivity"));

    /** Discover wearable rows from BaseWearableItem. Names are matched after the scan. */
    private static void installDevicePages(ClassLoader loader) {
        Class<?> eventType = XposedHelpers.findClass(
                "com.heytap.health.device.flexadapter.refresh.EventType", loader);
        List<String> items = wearableItemNames(loader);
        if (items.isEmpty()) {
            items = new ArrayList<>();
            for (String name : HIDE_ITEMS) items.add(WEARABLE_ITEM + name);
            for (String name : NATIVE_PAGES.keySet()) items.add(WEARABLE_ITEM + name);
            HookRuntime.log("OHEALTH_WEARABLE_SCAN_EMPTY");
        }
        for (String name : HIDE_ITEMS) {
            String className = WEARABLE_ITEM + name;
            if (!items.contains(className)) items.add(className);
        }
        for (String className : items) {
            String simple = className.substring(className.lastIndexOf('.') + 1);
            if (hideWearableRow(simple)) {
                hideWatchFace(className, loader,
                        "DeviceWatchFaceItem".equals(simple) ? eventType : null,
                        "DeviceWatchFaceItem".equals(simple) ? "java.util.Map" : "kotlin.Unit");
            }
            String activity = NATIVE_PAGES.get(simple);
            if (activity != null) openNativeHistory(className, activity, loader);
        }
        installHostNotifications(loader);
        installNotifyExtras(loader);
    }

    private static boolean hideWearableRow(String simple) {
        return HIDE_ITEMS.contains(simple)
                || simple.contains("Ota")
                || simple.contains("Firmware")
                || simple.contains("NewFunction")
                || simple.endsWith("UpdateItem");
    }

    private static List<String> wearableItemNames(ClassLoader loader) {
        Class<?> base;
        try {
            base = Class.forName(WEARABLE_ITEM + "BaseWearableItem", false, loader);
        } catch (ClassNotFoundException missing) {
            return List.of();
        }
        android.content.pm.ApplicationInfo info = hostContext.getApplicationInfo();
        List<String> apks = new ArrayList<>();
        if (info.sourceDir != null) apks.add(info.sourceDir);
        if (info.splitSourceDirs != null) {
            for (String split : info.splitSourceDirs) apks.add(split);
        }
        List<String> names = new ArrayList<>();
        for (String apk : apks) {
            try {
                for (String name : DexAnchors.classNames(apk)) {
                    if (!name.startsWith(WEARABLE_ITEM) || name.indexOf('$') >= 0) continue;
                    Class<?> type;
                    try {
                        type = Class.forName(name, false, loader);
                    } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
                        continue;
                    }
                    if (type != base && base.isAssignableFrom(type)
                            && !Modifier.isAbstract(type.getModifiers())) {
                        names.add(name);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return names;
    }

    private static void installNotifyExtras(ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.heytap.health.device.tab.notify.NotifySettingsActivity", loader,
                    "showSuccessFragment", new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof android.app.Activity activity)) return;
                            Intent intent = activity.getIntent();
                            if (intent.getBundleExtra("settingsDeviceMacBundle") != null) return;
                            Bundle shown = snapshot;
                            String mac = shown == null ? "" : shown.getString("mac", "");
                            if (mac.isBlank()) return;
                            Bundle device = new Bundle();
                            device.putString("settingsDeviceMac", mac);
                            intent.putExtra("settingsDeviceMac", mac);
                            intent.putExtra("settingsDeviceMacBundle", device);
                        }
                    });
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_NOTIFY_EXTRA_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.heytap.health.devicemanager.devicetype.DeviceTypeUtil", loader,
                    "getBoundDeviceByMac", String.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            Object info = bandDevice(loader, param.args.length == 0 ? null : param.args[0]);
                            if (info != null) param.setResult(info);
                        }
                    });
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_NOTIFY_DEVICE_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
        HookRuntime.tryHook("OHEALTH_BOUND_INFO_BY_MAC_UNAVAILABLE", () -> XposedHelpers.findAndHookMethod(
                "com.heytap.health.devicemanager.devicetype.DeviceTypeUtil", loader,
                "getBoundDeviceInfoByMac", String.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Object info = bandDevice(loader, param.args.length == 0 ? null : param.args[0]);
                        if (info != null) param.setResult(info);
                    }
                }));
        try {
            Class<?> hey = XposedHelpers.findClass("com.heytap.health.devicemanager.client.DMHeytap", loader);
            java.lang.reflect.Field api = hey.getField("managerApi");
            XC_MethodHook byMac = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Object info = bandDevice(loader, param.args.length == 0 ? null : param.args[0]);
                    if (info != null) param.setResult(info);
                }
            };
            XposedBridge.hookAllMethods(api.getType(), "getBoundDeviceInfoByMac", byMac);
            XposedBridge.hookAllMethods(api.getType(), "getBoundDeviceInfos", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Bundle shown = liveSnapshot();
                    if (shown == null) return;
                    Object info = bandDevice(loader, shown.getString("mac"));
                    if (info == null) return;
                    Object raw = param.getResult();
                    ArrayList<Object> combined = new ArrayList<>();
                    if (raw instanceof List<?> current) {
                        for (Object item : current) if (!matches(item, shown)) combined.add(item);
                    }
                    combined.add(info);
                    param.setResult(combined);
                }
            });
            XposedBridge.hookAllMethods(api.getType(), "getCurrActiveMacByRole", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    if (result != null && !String.valueOf(result).isBlank()
                            && !"null".equals(String.valueOf(result))) return;
                    Bundle shown = liveSnapshot();
                    String mac = shown == null ? "" : shown.getString("mac", "");
                    if (mac.isBlank()) {
                        HookRuntime.log("OHEALTH_ACTIVE_MAC empty");
                        return;
                    }
                    param.setResult(mac);
                    HookRuntime.log("OHEALTH_ACTIVE_MAC filled");
                }
            });
            java.lang.reflect.Field business = hey.getField("businessApi");
            XposedBridge.hookAllMethods(business.getType(), "getDeviceBattery", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Bundle shown = liveSnapshot();
                    if (shown == null || param.args.length == 0) return;
                    if (!io.github.miam1ku.mibandoplusbridge.data.MacIds.same(
                            shown.getString("mac", ""), String.valueOf(param.args[0]))) return;
                    int battery = shown.getInt("battery", -1);
                    if (battery >= 0 && battery <= 100) param.setResult(battery);
                }
            });
            XposedBridge.hookAllMethods(business.getType(), "requestDeviceBattery", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Bundle shown = liveSnapshot();
                    if (shown != null && param.args.length > 0
                            && io.github.miam1ku.mibandoplusbridge.data.MacIds.same(
                                    shown.getString("mac", ""), String.valueOf(param.args[0]))) {
                        param.setResult(null);
                    }
                }
            });
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_BOUND_INFO_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.heytap.health.deviceability.NotificationBaseAbilityTool", loader,
                    "buildByMac", String.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            Object info = bandDevice(loader, param.args.length == 0 ? null : param.args[0]);
                            if (info == null) return;
                            try {
                                param.setResult(XposedHelpers.newInstance(XposedHelpers.findClass(
                                        "com.heytap.health.deviceability.NotificationBaseAbilityDevice",
                                        loader), info));
                            } catch (Throwable failure) {
                                HookRuntime.log("OHEALTH_NOTIFY_ABILITY_UNAVAILABLE "
                                        + failure.getClass().getSimpleName());
                            }
                        }
                    });
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_NOTIFY_ABILITY_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
        HookRuntime.tryHook("OHEALTH_FLUID_HOOK_UNAVAILABLE", () -> XposedHelpers.findAndHookMethod(
                "com.heytap.health.deviceability.NotificationBaseAbilityDevice", loader,
                "isSupportFluid", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (isBand(param.thisObject)) param.setResult(false);
                    }
                }));
        XC_MethodHook bandFalse = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (isBand(param.thisObject)) param.setResult(false);
            }
        };
        for (String method : new String[]{"isSecondaryDevice", "isFamilyDevice"}) {
            HookRuntime.tryHook("OHEALTH_DEVICE_ROLE_HOOK_UNAVAILABLE " + method, () ->
                    XposedHelpers.findAndHookMethod(
                            "com.heytap.health.devicemanager.deviceability.DeviceInfo", loader,
                            method, bandFalse));
        }
        try {
            Class<?> fragment = XposedHelpers.findClass(
                    "com.heytap.health.watch.notification.impl.ui.NotificationSyncFragment", loader);
            XposedBridge.hookAllMethods(fragment, "showScreen", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object vm = XposedHelpers.callMethod(param.thisObject, "getVm");
                        if (!(XposedHelpers.callMethod(vm, "successData") instanceof java.util.List<?>)) {
                            HookRuntime.log("OHEALTH_NOTIFY_SCREEN empty");
                            param.setResult(null);
                        }
                    } catch (Throwable failure) {
                        HookRuntime.log("OHEALTH_NOTIFY_SCREEN "
                                + failure.getClass().getSimpleName());
                    }
                }
            });
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_NOTIFY_SCREEN_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
    }

    private static Object bandDevice(ClassLoader loader, Object mac) {
        Bundle shown = snapshot;
        if (shown == null || !shown.getBoolean("registered", false) || mac == null) return null;
        if (!io.github.miam1ku.mibandoplusbridge.data.MacIds.same(shown.getString("mac", ""), String.valueOf(mac))) {
            return null;
        }
        try {
            return deviceInfo(loader, shown);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Native OPPO panel reads DeviceSdk/DMHeytap, not the device-center card.
     * Project this band's connection and battery, and drop watch-face/OTA rows.
     */
    private static void installNativePanel(ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.heytap.health.linkage.ui.DeviceDetailsPanelActivity", loader,
                    "showNewFunctionItem", new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (panelIsBand(param.thisObject)) param.setResult(false);
                        }
                    });
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_PANEL_FUNCTION_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.heytap.health.linkage.ui.DeviceDetailsViewModel", loader,
                    "initData", String.class, String.class, String.class, String.class,
                    boolean.class, boolean.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            applyPanelState(param.thisObject,
                                    param.args.length > 2 ? param.args[2] : null,
                                    param.args.length > 0 ? param.args[0] : null);
                        }
                    });
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_PANEL_INIT_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.heytap.health.linkage.ui.DeviceDetailsViewModel", loader,
                    "getData", boolean.class, String.class, boolean.class, boolean.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            Object mac = XposedHelpers.getObjectField(param.thisObject, "mMac");
                            Object deviceId = XposedHelpers.getObjectField(param.thisObject, "mDeviceId");
                            if (!ourBand(mac, deviceId)) return;
                            applyPanelState(param.thisObject, mac, deviceId);
                            XposedHelpers.setBooleanField(param.thisObject, "mShowNewFunctionItem", false);
                            if (param.args.length > 3) param.args[3] = Boolean.FALSE;
                        }
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            filterPanelButtons(param.thisObject);
                        }
                    });
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_PANEL_DATA_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
        HookRuntime.tryHook("OHEALTH_RECONNECT_HOOK_UNAVAILABLE", () -> XposedHelpers.findAndHookMethod(
                "com.heytap.health.linkage.ui.DeviceDetailsViewModel", loader,
                "reconnectDevice", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (!ourBand(XposedHelpers.getObjectField(param.thisObject, "mMac"),
                                XposedHelpers.getObjectField(param.thisObject, "mDeviceId"))) return;
                        param.setResult(null);
                        requestSync();
                    }
                }));
        try {
            XposedHelpers.findAndHookMethod(
                    "com.heytap.health.linkage.ui.DeviceDetailsActivity", loader,
                    "actionClick", Integer.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof android.app.Activity activity)) return;
                            if (!panelIsBand(activity)) return;
                            if (!(param.args[0] instanceof Integer action) || action != 4) return;
                            if (!openHealthApp(activity)) return;
                            param.setResult(null);
                        }
                    });
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_PANEL_APP_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.heytap.health.linkage.ui.DeviceDetailsPanelActivity", loader,
                    "notifyDeviceInfoBeans", List.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!panelIsBand(param.thisObject)) return;
                            stripWatchFace(param.thisObject, "datas", "adapter");
                            stripWatchFace(param.thisObject, "landscapeRightDatas", "landscapeRightAdapter");
                        }
                    });
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_PANEL_WATCHFACE_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
        HookRuntime.tryHook("OHEALTH_DEVICE_QUERY_HOOK_UNAVAILABLE", () -> XposedHelpers.findAndHookMethod(
                "com.oplus.mydevices.sdk.DeviceInfoManager", loader,
                "queryDeviceById", String.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args.length == 0) return;
                        Bundle shown = liveSnapshot();
                        Object info = param.getResult();
                        if (shown == null || info == null || !duplicateSpaceCard(info)) return;
                        patchSdkDevice(info, shown);
                    }
                }));
        guardDuplicateSpaceCard(loader);
    }

    private static Bundle liveSnapshot() {
        Bundle current = display();
        if (current != null) return current;
        Bundle fresh = fromProvider();
        if (fresh != null) snapshot = fresh;
        return display();
    }

    private static boolean ourMac(Object mac) {
        Bundle shown = liveSnapshot();
        return shown != null && io.github.miam1ku.mibandoplusbridge.data.MacIds.same(
                shown.getString("mac", ""), String.valueOf(mac));
    }

    private static boolean ourBand(Object mac, Object deviceId) {
        String id = deviceId == null ? "" : String.valueOf(deviceId);
        if (id.startsWith("miband11_")) return true;
        return ourMac(mac);
    }

    private static boolean panelIsBand(Object panel) {
        try {
            return ourBand(XposedHelpers.getObjectField(panel, "mMac"),
                    XposedHelpers.getObjectField(panel, "mDeviceId"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean openHealthApp(android.app.Activity activity) {
        try {
            Object mac = XposedHelpers.getObjectField(activity, "mMac");
            Intent intent = new Intent();
            intent.setClassName(HOST, "com.heytap.health.main.MainActivity");
            if (mac != null) {
                String address = String.valueOf(mac);
                if (!address.isBlank() && !"null".equals(address)) intent.putExtra("currentMac", address);
            }
            intent.putExtra("tab", "3");
            intent.putExtra("from_internal", false);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            activity.startActivity(intent);
            activity.finish();
            HookRuntime.log("OHEALTH_PANEL_OPEN_APP");
            return true;
        } catch (RuntimeException failure) {
            HookRuntime.log("OHEALTH_PANEL_OPEN_APP_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
            return false;
        }
    }

    private static void applyPanelState(Object vm, Object mac, Object deviceId) {
        if (!ourBand(mac, deviceId)) return;
        Bundle shown = liveSnapshot();
        if (shown == null) return;
        int battery = shown.getInt("battery", -1);
        try {
            XposedHelpers.setIntField(vm, "mCurrBattery", battery >= 0 && battery <= 100 ? battery : 0);
            XposedHelpers.setBooleanField(vm, "mCharge", shown.getBoolean("charging"));
            XposedHelpers.setIntField(vm, "mConnectStatus", shown.getBoolean("connected") ? 102 : 103);
            HookRuntime.log("OHEALTH_PANEL_STATE connected=" + shown.getBoolean("connected")
                    + " battery=" + battery);
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_PANEL_STATE_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
    }

    private static void filterPanelButtons(Object vm) {
        if (!ourBand(XposedHelpers.getObjectField(vm, "mMac"),
                XposedHelpers.getObjectField(vm, "mDeviceId"))) return;
        try {
            Object live = XposedHelpers.getObjectField(vm, "mListLiveData");
            Object value = XposedHelpers.callMethod(live, "getValue");
            if (!(value instanceof List<?> rows) || rows.isEmpty()) return;
            ArrayList<Object> kept = new ArrayList<>(rows.size());
            boolean changed = false;
            for (Object bean : rows) {
                int type = (Integer) XposedHelpers.callMethod(bean, "getType");
                if (type != 2) {
                    kept.add(bean);
                    continue;
                }
                int action = 0;
                try {
                    action = (Integer) XposedHelpers.callMethod(bean, "getClickAction");
                } catch (Throwable ignored) { }
                if (action == 4) kept.add(bean);
                else changed = true;
            }
            if (changed) XposedHelpers.callMethod(live, "postValue", kept);
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_PANEL_FILTER_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
    }

    @SuppressWarnings("unchecked")
    private static void stripWatchFace(Object panel, String listField, String adapterField) {
        try {
            Object rows = XposedHelpers.getObjectField(panel, listField);
            if (!(rows instanceof List<?> current) || current.isEmpty()) return;
            ArrayList<Object> kept = new ArrayList<>();
            boolean changed = false;
            for (Object item : current) {
                if (item != null && item.getClass().getSimpleName().contains("WatchFace")) {
                    changed = true;
                    continue;
                }
                kept.add(item);
            }
            if (!changed) return;
            List<Object> mutable = (List<Object>) rows;
            mutable.clear();
            mutable.addAll(kept);
            Object adapter = XposedHelpers.getObjectField(panel, adapterField);
            if (adapter != null) XposedHelpers.callMethod(adapter, "notifyDataSetChanged");
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_WATCH_FACE_STRIP_UNAVAILABLE", failure);
        }
    }

    /**
     * Health already puts this band in device space. Keep that card (it uses OPPO's own glyph)
     * and copy the live connection onto it. The bridge card stays hidden while this row exists.
     */
    private static void guardDuplicateSpaceCard(ClassLoader loader) {
        Class<?> infoType;
        try {
            infoType = XposedHelpers.findClass("com.oplus.mydevices.sdk.device.DeviceInfo", loader);
        } catch (Throwable missing) {
            HookRuntime.log("DEVICE_SPACE_GUARD_UNAVAILABLE");
            return;
        }
        XC_MethodHook patch = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length == 0 || !duplicateSpaceCard(param.args[0])) return;
                Bundle shown = liveSnapshot();
                if (shown != null) patchSdkDevice(param.args[0], shown);
            }
        };
        int hooked = 0;
        for (String typeName : new String[]{
                "com.oplus.mydevices.sdk.internal.DeviceRepositoryImpl",
                "com.oplus.mydevices.sdk.DeviceInfoManager"}) {
            Class<?> type;
            try {
                type = XposedHelpers.findClass(typeName, loader);
            } catch (Throwable missing) {
                continue;
            }
            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                String name = method.getName();
                if (!name.equals("add") && !name.equals("update") && !name.equals("insertOrUpdate")
                        && !name.equals("insertOrUpdateOnly") && !name.equals("insertOrUpdateDevice")) continue;
                if (method.getParameterCount() < 1 || !infoType.isAssignableFrom(method.getParameterTypes()[0])) continue;
                XposedBridge.hookMethod(method, patch);
                hooked++;
            }
        }
        int[] guarded = { hooked };
        HookRuntime.tryHook("OHEALTH_WATCH_STATE_HOOK_UNAVAILABLE", () -> {
            XposedHelpers.findAndHookMethod(
                    "com.heytap.health.linkage.watch.WatchController", loader,
                    "updateDeviceState", infoType, boolean.class, patch);
            guarded[0]++;
        });
        hooked = guarded[0];
        HookRuntime.log(hooked == 0 ? "DEVICE_SPACE_GUARD_UNAVAILABLE" : "DEVICE_SPACE_GUARD " + hooked);
    }

    private static volatile Boolean yielded;

    /** Hide the bridge card only while health's own device-space row is still there. */
    private static void dropDuplicateSpaceCard(ClassLoader loader) {
        Bundle shown = snapshot;
        if (shown == null || hostContext == null) return;
        boolean registered = shown.getBoolean("registered", false);
        String mac = shown.getString("mac", "");
        boolean present = registered && !mac.isBlank() && healthCardPresent(loader, mac);
        if (yielded != null && yielded == present) return;
        try {
            Bundle extras = new Bundle();
            extras.putBoolean("yield", present);
            hostContext.getContentResolver().call(DeviceCardProvider.URI, "spaceYield", null, extras);
            yielded = present;
            HookRuntime.log("DEVICE_SPACE_YIELD " + present);
        } catch (Throwable failure) {
            HookRuntime.log("DEVICE_SPACE_YIELD_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
    }

    /**
     * Health's own device-space card for this band. The bridge card lives in another process,
     * so this lookup only sees Health's row.
     *
     * <p>权威信号只取第二道验证：健康 App 自己的卡片表 {@code device_info_v2.db} 里是否真有这台表的行，
     * 且 WatchProvider 组件处于启用态（见 {@link #healthCardRowExists}）。
     *
     * <p>★ 2026-10-23 修复（方案 A）：原先第一道验证依赖健康绑定库
     * {@code DMLocalDeviceManager.getUserBoundDeviceListByMac}，但该库 DEVICE 表自
     * 2025-11-11 起一直为空（MAC 在健康数据目录下完全搜不到），导致绑定库命中恒为 false、
     * yield 恒为 false，桥接卡永远不让位。一旦健康卡因蓝牙重连/发布事件复活，设备空间就出现
     * 「健康卡 + 桥接卡」双卡（UI 按 MAC 去重失效：健康卡 device_mac 经 provider 脱敏成空串）。
     * 删除该第一道验证后，判定只剩卡片表+组件：健康卡真在 → 桥接让位（单卡健康）；
     * 健康卡被清 → 桥接顶上（单卡桥接）。两种状态都单卡，自愈逻辑不变。
     */
    private static volatile Boolean rowSeen;

    private static boolean healthCardPresent(ClassLoader loader, String mac) {
        boolean row = healthCardRowExists(mac);
        if (rowSeen == null || rowSeen != row) {
            rowSeen = row;
            HookRuntime.log("DEVICE_SPACE_HEALTH_ROW mac=" + mac + " present=" + rowSeen);
        }
        return row;
    }

    /**
     * 健康 WatchProvider 的卡片表里真有这台表的行，且组件处于启用态。
     *
     * <p>两个条件都要：
     * <ul>
     *   <li><b>表里有行</b>——桥接的卡不能冒充健康卡，两者同 MAC 会让设备空间
     *       （按 MAC 去重）把两张卡一起丢掉。</li>
     *   <li><b>组件启用</b>——表里的行是上一次发布留下的残留，组件被
     *       {@code DeviceIdleDisableWork} 禁用后行虽然还在，provider 不再对外提供，
     *       mydevices 实际查不到。此时必须让桥接卡顶上。</li>
     * </ul>
     *
     * <p>读法：hook 跑在健康 App 进程内，{@code hostContext} 就是健康 App 的 Context，
     * 直接开它自己的 {@code device_info_v2.db} 读表最稳——不依赖 provider 组件是否启用，
     * 也不会拿到被宿主脱敏（{@code device_mac} 列返回空串）的结果。
     * 读不到时回退到 provider query，比对键只用 {@code device_id}（MAC 的 MD5）
     * 与 {@code device_data} 里的 {@code mMacAddress}。
     */
    private static boolean healthCardRowExists(String mac) {
        if (hostContext == null) return false;
        if (!watchProviderEnabled()) return false;
        if (healthRowInDatabase(mac)) return true;
        return healthRowViaProvider(mac);
    }

    /** 组件是否处于启用态（被 DeviceIdleDisableWork 禁用时返回 false）。 */
    private static boolean watchProviderEnabled() {
        try {
            android.content.pm.PackageManager manager = hostContext.getPackageManager();
            android.content.ComponentName component = new android.content.ComponentName(
                    "com.heytap.health", "com.heytap.health.linkage.watch.WatchProvider");
            int state = manager.getComponentEnabledSetting(component);
            if (state == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || state == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    || state == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                return false;
            }
            return true;
        } catch (Throwable unavailable) {
            return true;
        }
    }

    /** 直接读健康 App 自己的卡片库（进程内读，provider 被禁用时也读得到）。 */
    private static boolean healthRowInDatabase(String mac) {
        try {
            java.io.File file = hostContext.getDatabasePath("device_info_v2.db");
            if (!file.isFile() || file.length() == 0) return false;
            try (android.database.sqlite.SQLiteDatabase db =
                         android.database.sqlite.SQLiteDatabase.openDatabase(
                                 file.getPath(), null,
                                 android.database.sqlite.SQLiteDatabase.OPEN_READONLY)) {
                if (!tableExists(db)) return false;
                try (android.database.Cursor cursor = db.query(
                        "device_info_table_new", new String[]{"device_id", "device_data"},
                        null, null, null, null, null)) {
                    return cursor != null && scanRows(cursor, mac);
                }
            }
        } catch (Throwable unavailable) {
            return false;
        }
    }

    private static boolean tableExists(android.database.sqlite.SQLiteDatabase db) {
        try (android.database.Cursor cursor = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='device_info_table_new'",
                null)) {
            return cursor != null && cursor.moveToFirst();
        } catch (Throwable unavailable) {
            return false;
        }
    }

    /** 回退：走 provider query（组件启用时有效）。 */
    private static boolean healthRowViaProvider(String mac) {
        try (android.database.Cursor cursor = hostContext.getContentResolver().query(
                android.net.Uri.parse(
                        "content://com.heytap.health.WatchProvider/device_info_table_new"),
                new String[]{"device_id", "device_data"}, null, null, null)) {
            return cursor != null && scanRows(cursor, mac);
        } catch (Throwable unavailable) {
            return false;
        }
    }

    /**
     * 逐行比对这台表。
     *
     * <p>比对键：{@code device_id}（健康 App 用 MAC 的 MD5 小写 hex 当卡片 id）
     * 或 {@code device_data} 里的 {@code mMacAddress}。
     * <b>不能用 {@code device_mac} 列</b>：宿主 provider 返回时该列被脱敏成空串，
     * 拿它比对永远不命中（e09da25 的第二道验证就死在这里）。
     */
    private static boolean scanRows(android.database.Cursor cursor, String mac) {
        String lower = mac.toLowerCase(java.util.Locale.ROOT);
        String upper = mac.toUpperCase(java.util.Locale.ROOT);
        String md5Lower = md5Lowercase(lower);
        String md5Upper = md5Lowercase(upper);
        while (cursor.moveToNext()) {
            String id = cursor.getString(0);
            if (id != null && (id.equalsIgnoreCase(md5Lower) || id.equalsIgnoreCase(md5Upper))) {
                return true;
            }
            String data = cursor.getString(1);
            if (data == null) continue;
            if (data.contains("\"mMacAddress\":\"" + mac + "\"")
                    || data.contains("\"mMacAddress\":\"" + lower + "\"")
                    || data.contains("\"mMacAddress\":\"" + upper + "\"")) {
                return true;
            }
        }
        return false;
    }

    private static String md5Lowercase(String text) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("MD5")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            return text;
        }
    }

    /** Health's card for this band. The bridge card lives in another process, so this lookup only sees Health's row. */
    private static boolean duplicateSpaceCard(Object info) {
        Bundle shown = liveSnapshot();
        if (shown == null || info == null || !shown.getBoolean("registered", false)) return false;
        try {
            return io.github.miam1ku.mibandoplusbridge.data.MacIds.same(
                    shown.getString("mac", ""),
                    String.valueOf(XposedHelpers.callMethod(info, "getMacAddress")));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void patchSdkDevice(Object info, Bundle shown) {
        try {
            boolean connected = shown.getBoolean("connected");
            ClassLoader loader = info.getClass().getClassLoader();
            Class<?> state = XposedHelpers.findClass("com.oplus.mydevices.sdk.device.ConnectState", loader);
            Object connect = XposedHelpers.getStaticObjectField(state, connected ? "CONNECTED" : "DISCONNECTED");
            try {
                XposedHelpers.callMethod(info, "setConnectState", connect);
            } catch (Throwable ignored) { }
            try {
                Class<?> connectionType = XposedHelpers.findClass(
                        "com.oplus.mydevices.sdk.device.Connection", loader);
                long now = System.currentTimeMillis();
                Object connection = XposedHelpers.newInstance(connectionType, connect,
                        connected ? now : 0L, connected ? 0L : now);
                XposedHelpers.callMethod(info, "setConnection", connection);
            } catch (Throwable ignored) { }
            int battery = shown.getInt("battery", -1);
            if (battery < 0 || battery > 100) return;
            Class<?> batteryInfo = XposedHelpers.findClass(
                    "com.oplus.mydevices.sdk.device.BatteryInfo", loader);
            Class<?> batteryType = XposedHelpers.findClass(
                    "com.oplus.mydevices.sdk.device.BatteryType", loader);
            Object single = XposedHelpers.getStaticObjectField(batteryType, "SINGLE");
            Object cell;
            try {
                cell = XposedHelpers.newInstance(batteryInfo, single, battery, shown.getBoolean("charging"));
            } catch (Throwable ignored) {
                cell = XposedHelpers.newInstance(batteryInfo);
                XposedHelpers.callMethod(cell, "setBatteryType", single);
                XposedHelpers.callMethod(cell, "setValue", battery);
                XposedHelpers.callMethod(cell, "setCharge", shown.getBoolean("charging"));
            }
            ArrayList<Object> batteries = new ArrayList<>();
            batteries.add(cell);
            XposedHelpers.callMethod(info, "setBatteryInfoList", batteries);
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_PANEL_SDK_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
    }

    /** Device rows otherwise open a settings screen that queries an OPPO link and stays blank. */
    private static void openNativeHistory(String menuClass, String activityClass, ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod(menuClass, loader, "itemClick", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!selectedBand(param.thisObject)) return;
                    Object context = XposedHelpers.callMethod(param.thisObject, "getContext");
                    if (!(context instanceof Context launch)) return;
                    Intent intent = new Intent().setClassName("com.heytap.health", activityClass);
                    try {
                        Object info = XposedHelpers.callMethod(param.thisObject, "getCurrSelectWearableDevice");
                        if (info != null) {
                            Object mac = XposedHelpers.callMethod(info, "getMac");
                            if (mac != null) {
                                String address = String.valueOf(mac);
                                Bundle device = new Bundle();
                                device.putString("settingsDeviceMac", address);
                                intent.putExtra("settingsDeviceMac", address);
                                intent.putExtra("settingsDeviceMacBundle", device);
                            }
                        }
                    } catch (Throwable ignored) { }
                    if (!(launch instanceof android.app.Activity)) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    }
                    launch.startActivity(intent);
                    param.setResult(null);
                }
            });
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_NATIVE_PAGE_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
    }

    private static void hideWatchFace(String className, ClassLoader loader, Class<?> eventType, String dataType) {
        XC_MethodHook hideRow = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!selectedBand(param.thisObject)) return;
                try {
                    Object view = XposedHelpers.callMethod(param.thisObject, "getItemView");
                    if (!(view instanceof android.view.View item)) return;
                    item.setVisibility(android.view.View.GONE);
                    android.view.ViewGroup.LayoutParams params = item.getLayoutParams();
                    if (params == null) return;
                    params.height = 0;
                    params.width = 0;
                    item.setLayoutParams(params);
                } catch (Throwable ignored) { }
            }
        };
        if (eventType != null) {
            try {
                XposedHelpers.findAndHookMethod(className, loader, "interceptEvent", eventType,
                        new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam param) {
                                if (selectedBand(param.thisObject)) param.setResult(Boolean.TRUE);
                            }
                        });
            } catch (Throwable failure) {
                HookRuntime.log("OHEALTH_WATCH_FACE_HOOK_UNAVAILABLE "
                        + failure.getClass().getSimpleName());
            }
        } else {
            try {
                XposedHelpers.findAndHookMethod(className, loader, "itemClick", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (selectedBand(param.thisObject)) param.setResult(null);
                    }
                });
            } catch (Throwable failure) {
                HookRuntime.log("OHEALTH_WATCH_FACE_HOOK_UNAVAILABLE "
                        + failure.getClass().getSimpleName());
            }
        }
        try {
            Class<?> data = XposedHelpers.findClass(dataType, loader);
            XposedHelpers.findAndHookMethod(className, loader, "initData", data, hideRow);
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_WATCH_FACE_ROW_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
        if ("DeviceWatchFaceItem".equals(className.substring(className.lastIndexOf('.') + 1))) {
            HookRuntime.tryHook("OHEALTH_WATCH_FACE_ROW_ALT_UNAVAILABLE initData", () ->
                    XposedHelpers.findAndHookMethod(className, loader, "initData", Object.class, hideRow));
            HookRuntime.tryHook("OHEALTH_WATCH_FACE_ROW_ALT_UNAVAILABLE initView", () ->
                    XposedHelpers.findAndHookMethod(className, loader, "initView", hideRow));
        }
    }


    private static void installHostNotifications(ClassLoader loader) {
        try {
            Class<?> bean = XposedHelpers.findClass(
                    "com.heytap.health.watch.notification.HealthNotificationBean", loader);
            String center = "com.heytap.health.watch.notification.HealthNotificationRegisterCenter";
            XposedHelpers.findAndHookMethod(center, loader, "onNotificationPosted", bean, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    forwardHostNotification(param.args[0], false);
                }
            });
            HookRuntime.log("OHEALTH_NOTIFICATION_HOOK process="
                    + android.app.Application.getProcessName());
            trace("OHEALTH_NOTIFICATION_HOOK process=" + android.app.Application.getProcessName());
            XposedHelpers.findAndHookMethod(center, loader, "onNotificationRemoved", bean, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    forwardHostNotification(param.args[0], true);
                }
            });
            // Forward before skipping. Once this call is swallowed, the post stops at the event manager.
            String manager = "com.heytap.health.watch.notification.impl.transceiver.NotificationEventManager";
            XposedHelpers.findAndHookMethod(manager, loader, "onNotificationPosted", bean, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!registeredBand()) return;
                    forwardHostNotification(param.args[0], false);
                    param.setResult(null);
                }
            });
            XposedHelpers.findAndHookMethod(manager, loader, "onNotificationRemoved", bean, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!registeredBand()) return;
                    forwardHostNotification(param.args[0], true);
                    param.setResult(null);
                }
            });
            try {
                XposedHelpers.findAndHookMethod(
                        "com.heytap.health.watch.notification.impl.transceiver.BaseInterceptWorker",
                        loader, "sendInterceptMessage", new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam param) {
                                if (registeredBand()) param.setResult(null);
                            }
                        });
            } catch (Throwable failure) {
                HookRuntime.log("OHEALTH_INTERCEPT_MESSAGE_UNAVAILABLE "
                        + failure.getClass().getSimpleName());
            }
            installPolicyPush(loader);
            XposedHelpers.findAndHookMethod("com.heytap.health.base.app.ToastUtil", loader,
                    "showShort", String.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args[0] instanceof String text && deferredConnectToast(text)) {
                                param.setResult(null);
                            }
                        }
                    });
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_NOTIFICATION_HOOK_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
        }
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> HOST_NOTIFICATIONS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ExecutorService ALLOWLIST =
            HookRuntime.worker("OplusBandAllowlist", 0);
    /**
     * Policy pushes used to run on ALLOWLIST and wait with {@code Thread.sleep} for 0.5 to 5 seconds.
     * Notification forwarding and the allowlist query share that thread, so one debounce held notifications
     * for the same time. The delay now lives on this scheduler, and ALLOWLIST only forwards and queries.
     */
    private static final java.util.concurrent.ScheduledExecutorService POLICY = policyScheduler();
    private static final Map<String, Boolean> FORWARDED_EVENTS = new java.util.LinkedHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger policyGeneration =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger policyAttempts =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final Object POLICY_LOCK = new Object();
    private static java.util.concurrent.ScheduledFuture<?> policyTask;
    /** Retries after one policy push fails. A switch change in Health clears the count through schedulePolicy. */
    static final int MAX_POLICY_ATTEMPTS = 6;

    static boolean registeredBand() {
        Bundle shown = snapshot;
        return shown != null && shown.getBoolean("registered", false);
    }

    private static boolean deferredConnectToast(String text) {
        if (!registeredBand() || hostContext == null || text == null) return false;
        try {
            int id = hostContext.getResources().getIdentifier(
                    "settings_enable_after_connected", "string", HOST);
            return id != 0 && text.equals(hostContext.getString(id));
        } catch (RuntimeException ignored) {
            return text.contains("连接设备后生效") || text.contains("after your device is reconnected");
        }
    }

    /**
     * Translates a Health notification into a local command and sends it through the bridge provider.
     * After the register center's {@code onNotificationPosted}, Health has already admitted it.
     * The event manager's {@code onNotificationPosted} is also hooked before Health's own filter, and that call is then swallowed.
     */
    private static void forwardHostNotification(Object bean, boolean removed) {
        String process = android.app.Application.getProcessName();
        Bundle shown = liveSnapshot();
        if (bean == null || hostContext == null || shown == null || !shown.getBoolean("registered", false)) {
            notifyDrop("snapshot bean=" + (bean != null) + " context=" + (hostContext != null)
                    + " registered=" + (shown != null && shown.getBoolean("registered", false)), "", process);
            return;
        }
        try {
            String pkg = text(bean, "getPackageName");
            String key = text(bean, "getKey");
            if (pkg.isBlank() || key.isBlank()) {
                notifyDrop("identity pkg=" + !pkg.isBlank() + " key=" + !key.isBlank(), pkg, process);
                return;
            }
            if (deliverPhoneAlarm(bean, pkg, key, removed)) return;
            Object posted = XposedHelpers.callMethod(bean, "getPostTimeMillis");
            long when = posted instanceof Number time && time.longValue() > 0
                    ? time.longValue() : System.currentTimeMillis();
            String title = text(bean, "getTitle");
            String body = text(bean, "getContent");
            String token = (removed ? "r|" : "p|") + key + "|" + when + "|" + title + "|" + body;
            if (!OHealthNotifyFilter.claim(FORWARDED_EVENTS, token, 64)) {
                notifyDrop("duplicate", pkg, process);
                return;
            }
            ALLOWLIST.execute(() -> deliverForward(bean, removed, process, pkg, key, when, title, body));
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_NOTIFICATION_FORWARD_FAILED "
                    + failure.getClass().getSimpleName());
            trace("OHEALTH_NOTIFICATION_FORWARD_FAILED " + failure.getClass().getSimpleName());
        }
    }

    private static void deliverForward(Object bean, boolean removed, String process, String pkg, String key,
            long when, String title, String body) {
        try {
            boolean call = isIncomingCall(bean);
            if (!removed && !call) {
                Object importance = XposedHelpers.callMethod(bean, "getImportance");
                if (importance instanceof Number level && level.intValue() <= 2) {
                    notifyDrop("importance level=" + level.intValue(), pkg, process);
                    return;
                }
                Boolean blocked = healthIntercept(bean);
                if (blocked == null) {
                    String fallback = readAllowBlock(bean);
                    if (fallback != null) {
                        notifyDrop(fallback, pkg, process);
                        return;
                    }
                    if (screenOnBlocks(bean)) {
                        notifyDrop("screen", pkg, process);
                        return;
                    }
                } else if (blocked) {
                    notifyDrop("intercept", pkg, process);
                    return;
                }
            }
            Integer id = OHealthNotifyFilter.id(HOST_NOTIFICATIONS, key, removed && !call);
            if (id == null) {
                notifyDrop("not-forwarded", pkg, process);
                return;
            }
            if (removed && call) HOST_NOTIFICATIONS.remove(key);
            Bundle extras = new Bundle();
            extras.putBoolean("removed", removed);
            extras.putBoolean("call", call);
            extras.putInt("callState", call ? callState(bean) : 0);
            extras.putBoolean("connected", call && callConnected(bean));
            extras.putString("pkg", pkg);
            extras.putString("key", key);
            extras.putInt("id", id);
            String app = text(bean, "getAppName");
            if (app.isBlank() || app.equals(pkg)) {
                String resolved = io.github.miam1ku.mibandoplusbridge.notify.AppLabels.label(hostContext, pkg);
                if (!resolved.isBlank()) app = resolved;
            }
            extras.putString("app", app);
            extras.putString("title", title);
            extras.putString("body", body);
            extras.putLong("when", when);
            Bundle result = hostContext.getContentResolver().call(
                    io.github.miam1ku.mibandoplusbridge.integration.HostNotifyProvider.URI, "forward", null, extras);
            String status = result == null ? "NULL" : result.getString("status", "");
            HookRuntime.log("OHEALTH_NOTIFICATION_FORWARD removed=" + removed
                    + " call=" + call + " pkg=" + pkg + " status=" + status);
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_NOTIFICATION_FORWARD_FAILED "
                    + failure.getClass().getSimpleName());
            trace("OHEALTH_NOTIFICATION_FORWARD_FAILED " + failure.getClass().getSimpleName());
        }
    }

    /** Null when Health's own intercept cannot be called. True means Health would drop it. */
    private static Boolean healthIntercept(Object bean) {
        try {
            Class<?> manager = bean.getClass().getClassLoader().loadClass(
                    "com.heytap.health.watch.notification.impl.transceiver.NotificationEventManager");
            Object intercept = XposedHelpers.getStaticObjectField(manager, "mIntercept");
            Object blocked = XposedHelpers.callMethod(intercept, "intercept", bean, true);
            return Boolean.TRUE.equals(blocked);
        } catch (Throwable failure) {
            trace("OHEALTH_INTERCEPT_UNAVAILABLE " + failure.getClass().getSimpleName());
            return null;
        }
    }

    private static final java.util.Set<String> phoneAlarmKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Only the klaxon foreground notification is a ring. Next-alarm ticks stay off the band. */
    private static boolean deliverPhoneAlarm(Object bean, String pkg, String key, boolean removed) {
        if (!io.github.miam1ku.mibandoplusbridge.notify.PhoneAlarmNotice.CLOCK.equals(pkg)) return false;
        boolean live = io.github.miam1ku.mibandoplusbridge.notify.PhoneAlarmNotice.ringing(clockNotification(bean));
        if (localListenerConnected()) return true;
        if (!removed) {
            trace("ALARM_CLOCK_POST " + clockShape(bean));
            if (!live) return true;
            if (!phoneAlarmKeys.add(key)) return true;
        } else if (!phoneAlarmKeys.remove(key)) {
            return true;
        }
        if (hostContext == null) return true;
        android.os.Bundle extras = new android.os.Bundle();
        extras.putBoolean("removed", removed);
        extras.putBoolean("phoneAlarm", true);
        extras.putInt("alarmOp", removed ? 1 : 0);
        extras.putString("pkg", pkg);
        extras.putString("key", key);
        extras.putString("title", removed ? "" : text(bean, "getTitle"));
        try {
            hostContext.getContentResolver().call(
                    io.github.miam1ku.mibandoplusbridge.integration.HostNotifyProvider.URI,
                    "forward", null, extras);
            return true;
        } catch (RuntimeException failure) {
            HookRuntime.log("CLOCK_ALARM_FORWARD_FAILED "
                    + failure.getClass().getSimpleName());
            return true;
        }
    }

    private static android.service.notification.StatusBarNotification clockNotification(Object bean) {
        try {
            Object origin = io.github.miam1ku.mibandoplusbridge.hook.xposed.XposedHelpers.callMethod(bean, "getOrigin");
            if (origin instanceof android.service.notification.StatusBarNotification posted) return posted;
        } catch (Throwable ignored) { }
        return null;
    }

    private static String clockShape(Object bean) {
        try {
            Object origin = io.github.miam1ku.mibandoplusbridge.hook.xposed.XposedHelpers.callMethod(bean, "getOrigin");
            if (!(origin instanceof android.service.notification.StatusBarNotification posted)) return "origin=absent";
            android.app.Notification notification = posted.getNotification();
            String channel = notification == null || notification.getChannelId() == null ? "" : notification.getChannelId();
            return "id=" + posted.getId() + " flags=" + (notification == null ? 0 : notification.flags)
                    + " channel=" + channel;
        } catch (Throwable failure) {
            return "origin=" + failure.getClass().getSimpleName();
        }
    }

    private static boolean isIncomingCall(Object bean) {
        try {
            Object origin = XposedHelpers.callMethod(bean, "getOrigin");
            if (origin instanceof StatusBarNotification posted) {
                android.app.Notification notification = posted.getNotification();
                if (notification != null
                        && android.app.Notification.CATEGORY_CALL.equals(notification.category)) {
                    return true;
                }
            }
        } catch (Throwable ignored) { }
        try {
            Object holder = XposedHelpers.getStaticObjectField(
                    bean.getClass().getClassLoader().loadClass(
                            "com.heytap.health.watch.notification.impl.module.NotificationHolder"),
                    "INSTANCE");
            return Boolean.TRUE.equals(XposedHelpers.callMethod(holder, "isDial", text(bean, "getPackageName")));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int callState(Object bean) {
        try {
            Object origin = XposedHelpers.callMethod(bean, "getOrigin");
            if (origin instanceof StatusBarNotification posted) {
                return io.github.miam1ku.mibandoplusbridge.notify.CallPresentation.wire(posted.getNotification());
            }
        } catch (Throwable ignored) { }
        return io.github.miam1ku.mibandoplusbridge.notify.CallPresentation.wire(
                io.github.miam1ku.mibandoplusbridge.notify.CallPresentation.kind(false, false, 0,
                        text(bean, "getContent"), text(bean, "getTitle")));
    }


    private static boolean callConnected(Object bean) {
        try {
            Object origin = XposedHelpers.callMethod(bean, "getOrigin");
            if (origin instanceof StatusBarNotification posted) {
                return io.github.miam1ku.mibandoplusbridge.notify.CallPresentation.connected(posted.getNotification());
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /** Health switch {@code screen_on_push} false holds the push while the screen is on. */
    private static boolean screenOnBlocks(Object bean) {
        try {
            ClassLoader loader = bean.getClass().getClassLoader();
            Object holder = XposedHelpers.getStaticObjectField(loader.loadClass(
                    "com.heytap.health.watch.notification.impl.whitelist.NotificationRoomHolder"), "INSTANCE");
            boolean on = io.github.miam1ku.mibandoplusbridge.notify.NotifySwitch.on(
                    XposedHelpers.callMethod(holder, "getPackageSwitchStatus", "screen_on_push"));
            if (on) return false;
            Object utils = XposedHelpers.getStaticObjectField(loader.loadClass(
                    "com.heytap.health.watch.notification.impl.utils.NotificationScreenUtils"), "INSTANCE");
            Object offOrLocked = XposedHelpers.callMethod(utils, "isScreenOffOrLocked", hostContext);
            return !Boolean.TRUE.equals(offOrLocked);
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_NOTIFICATION_SCREEN_UNAVAILABLE "
                    + failure.getClass().getSimpleName());
            return false;
        }
    }
    /**
     * Null means the Health allowlist admits this package. A query timeout, an interrupt, and most
     * exceptions also return null, so a failed lookup admits the package too.
     * The Room query runs on {@code ALLOWLIST}, and the main thread waits here for the result.
     */
    private static String readAllowBlock(Object bean) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return allowBlock(bean);
        java.util.concurrent.atomic.AtomicReference<String> result = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        ALLOWLIST.execute(() -> {
            try {
                result.set(allowBlock(bean));
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(1, java.util.concurrent.TimeUnit.SECONDS)) {
                noteAllowlist("timeout");
                return null;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            noteAllowlist("thread");
            return null;
        }
        return result.get();
    }

    private static String allowBlock(Object bean) {
        try {
            Object holder = XposedHelpers.getStaticObjectField(bean.getClass().getClassLoader()
                    .loadClass("com.heytap.health.watch.notification.impl.whitelist.NotificationRoomHolder"),
                    "INSTANCE");
            Object mainSwitch = XposedHelpers.callMethod(holder, "getPackageSwitchStatus", "main_switch");
            if (!io.github.miam1ku.mibandoplusbridge.notify.NotifySwitch.on(mainSwitch)) {
                return "main_switch value=" + String.valueOf(mainSwitch);
            }
            String pkg = text(bean, "getPackageName");
            Object appSwitch = XposedHelpers.callMethod(holder, "getPackageSwitchStatus", pkg);
            boolean packageOn = io.github.miam1ku.mibandoplusbridge.notify.NotifySwitch.on(appSwitch);
            if (!packageOn) return "package value=" + String.valueOf(appSwitch);
            return null;
        } catch (Throwable unavailable) {
            String kind = OHealthNotifyFilter.allowlistFailure(unavailable);
            if (OHealthNotifyFilter.blocks(kind)) return kind;
            noteAllowlist(kind);
            return null;
        }
    }

    private static void installPolicyPush(ClassLoader loader) {
        String process = android.app.Application.getProcessName();
        if (!"com.heytap.health:transport".equals(process)) return;
        try {
            Class<?> holder = XposedHelpers.findClass(
                    "com.heytap.health.watch.notification.impl.whitelist.NotificationRoomHolder", loader);
            XC_MethodHook later = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) { schedulePolicy(500); }
            };
            for (String name : new String[] {"update", "updateWithSync", "batchUpdate", "batchUpdateWithSync",
                    "insert", "delete", "updateDbData"}) {
                XposedBridge.hookAllMethods(holder, name, later);
            }
            schedulePolicy(3_000);
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_POLICY_PUSH_UNAVAILABLE " + failure.getClass().getSimpleName());
            trace("OHEALTH_POLICY_PUSH_UNAVAILABLE " + failure.getClass().getSimpleName());
        }
    }

    private static void schedulePolicy(long delayMs) {
        policyAttempts.set(0);
        enqueuePolicy(policyGeneration.incrementAndGet(), delayMs);
    }

    /** First retry waits 5 seconds, then doubles, and stops growing at 60 seconds. */
    static long policyRetryDelayMs(int attempt) {
        int shift = Math.max(0, Math.min(attempt - 1, 4));
        return Math.min(60_000L, 5_000L << shift);
    }

    private static void retryPolicy(String reason) {
        int attempt = policyAttempts.incrementAndGet();
        if (attempt > MAX_POLICY_ATTEMPTS) {
            String line = "OHEALTH_POLICY_PUSH_STOPPED attempts=" + MAX_POLICY_ATTEMPTS + " reason=" + reason;
            HookRuntime.log(line);
            trace(line);
            return;
        }
        enqueuePolicy(policyGeneration.incrementAndGet(), policyRetryDelayMs(attempt));
    }

    private static void enqueuePolicy(int generation, long delayMs) {
        try {
            synchronized (POLICY_LOCK) {
                if (policyTask != null) policyTask.cancel(false);
                policyTask = POLICY.schedule(() -> {
                    if (generation != policyGeneration.get()) return;
                    pushPolicy();
                }, Math.max(0, delayMs), java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            // Hot reload shuts this generation's scheduler down. The new generation installs the push again. 
        }
    }

    private static java.util.concurrent.ScheduledExecutorService policyScheduler() {
        java.util.concurrent.ScheduledThreadPoolExecutor executor =
                new java.util.concurrent.ScheduledThreadPoolExecutor(1, runnable -> {
                    Thread thread = new Thread(runnable, "OplusBandPolicy");
                    thread.setDaemon(true);
                    return thread;
                });
        executor.setRemoveOnCancelPolicy(true);
        HookRuntime.track(executor::shutdownNow);
        return executor;
    }

    private static void pushPolicy() {
        int generation = policyGeneration.get();
        if (hostContext == null) {
            if (generation == policyGeneration.get()) retryPolicy("context");
            return;
        }
        try {
            ClassLoader loader = hostContext.getClassLoader();
            Object holder = XposedHelpers.getStaticObjectField(loader.loadClass(
                    "com.heytap.health.watch.notification.impl.whitelist.NotificationRoomHolder"), "INSTANCE");
            boolean main = io.github.miam1ku.mibandoplusbridge.notify.NotifySwitch.on(
                    XposedHelpers.callMethod(holder, "getPackageSwitchStatus", "main_switch"));
            boolean screen = io.github.miam1ku.mibandoplusbridge.notify.NotifySwitch.on(
                    XposedHelpers.callMethod(holder, "getPackageSwitchStatus", "screen_on_push"));
            ArrayList<String> open = new ArrayList<>();
            ArrayList<String> closed = new ArrayList<>();
            Object beans = XposedHelpers.callMethod(holder, "queryAll");
            if (beans instanceof List<?> list) {
                for (Object bean : list) {
                    Object name = XposedHelpers.callMethod(bean, "getPackageName");
                    if (!(name instanceof String pkg) || pkg.isBlank()) continue;
                    Object enabled = XposedHelpers.callMethod(bean, "isOpen");
                    (Boolean.TRUE.equals(enabled) ? open : closed).add(pkg);
                }
            }
            Object notificationHolder = XposedHelpers.getStaticObjectField(loader.loadClass(
                    "com.heytap.health.watch.notification.impl.module.NotificationHolder"), "INSTANCE");
            String[] defaults = stringArray(whitelist(notificationHolder));
            String[] mms = stringArray(XposedHelpers.callMethod(notificationHolder, "mms"));
            Bundle extras = new Bundle();
            extras.putBoolean("main", main);
            extras.putBoolean("screenOnPush", screen);
            extras.putStringArray("open", open.toArray(String[]::new));
            extras.putStringArray("closed", closed.toArray(String[]::new));
            if (defaults != null) extras.putStringArray("defaults", defaults);
            if (mms != null) extras.putStringArray("mms", mms);
            hostContext.getContentResolver().call(
                    io.github.miam1ku.mibandoplusbridge.integration.HostNotifyProvider.URI,
                    "policySnapshot", null, extras);
            String pushed = "OHEALTH_POLICY_PUSH main=" + main + " screen=" + screen
                    + " open=" + open.size() + " closed=" + closed.size();
            HookRuntime.log(pushed);
            trace(pushed);
        } catch (Throwable failure) {
            String detail = String.valueOf(failure);
            if (detail.length() > 180) detail = detail.substring(0, 180);
            HookRuntime.log("OHEALTH_POLICY_PUSH_FAILED " + detail);
            trace("OHEALTH_POLICY_PUSH_FAILED " + failure.getClass().getSimpleName());
            if (generation == policyGeneration.get()) retryPolicy(failure.getClass().getSimpleName());
        }
    }

    private static Object whitelist(Object notificationHolder) {
        try {
            Object config = XposedHelpers.callMethod(notificationHolder, "getMConfig");
            return config == null ? null : XposedHelpers.callMethod(config, "getWhiteList");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String[] stringArray(Object value) {
        if (!(value instanceof List<?> list)) return null;
        ArrayList<String> items = new ArrayList<>();
        for (Object item : list) if (item instanceof String text && !text.isBlank()) items.add(text);
        return items.toArray(String[]::new);
    }

    private static boolean localListenerConnected() {
        if (hostContext == null) return false;
        try {
            android.os.Bundle result = hostContext.getContentResolver().call(
                    io.github.miam1ku.mibandoplusbridge.integration.HostNotifyProvider.URI, "listener", null, null);
            return result != null && result.getBoolean("connected", false);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static String lastAllowlist = "";

    private static void noteAllowlist(String kind) {
        String line = "OHEALTH_ALLOWLIST_UNAVAILABLE " + kind;
        if (line.equals(lastAllowlist)) return;
        lastAllowlist = line;
        HookRuntime.log(line);
        trace(line);
    }

    private static String lastNotifyDrop = "";

    private static void notifyDrop(String reason, String pkg, String process) {
        String line = reason + "|" + pkg + "|" + process;
        if (line.equals(lastNotifyDrop)) return;
        lastNotifyDrop = line;
        HookRuntime.log("OHEALTH_NOTIFICATION_DROP reason=" + reason
                + " pkg=" + (pkg == null || pkg.isBlank() ? "none" : pkg)
                + " process=" + process);
        trace("OHEALTH_NOTIFICATION_DROP reason=" + reason
                + " pkg=" + (pkg == null || pkg.isBlank() ? "none" : pkg)
                + " process=" + process);
    }

    private static void trace(String line) {
        traceLine(hostContext, line);
    }

    static void traceLine(Context context, String line) {
        if (context == null || line == null || line.isBlank()) return;
        try {
            android.os.Bundle extras = new android.os.Bundle();
            extras.putString("line", line.length() > 240 ? line.substring(0, 240) : line);
            context.getContentResolver().call(
                    io.github.miam1ku.mibandoplusbridge.integration.HostNotifyProvider.URI,
                    "trace", null, extras);
        } catch (RuntimeException ignored) { }
    }

    private static boolean hostAllows(Object bean) {
        return allowBlock(bean) == null;
    }


    private static String text(Object bean, String method) {
        Object value = XposedHelpers.callMethod(bean, method);
        return value instanceof String text ? text : "";
    }


    private static boolean selectedBand(Object item) {
        Bundle shown = snapshot;
        if (item == null || shown == null || !shown.getBoolean("registered", false)) return false;
        try {
            return matches(XposedHelpers.callMethod(item, "getCurrSelectWearableDevice"), shown);
        } catch (Throwable unavailable) {
            return false;
        }
    }



    /** 型号→类型表的 dump 只做一次（诊断用：为华为手表挑一个正确的展示模型）。 */
    private static boolean typeMapDumped;

    private static int wearableType(Class<?> constants, String model) {
        Object companion = XposedHelpers.getStaticObjectField(constants, "Companion");
        Object mapped = XposedHelpers.callMethod(companion, "getDEVICETYPE_TO_MODELS");
        if (!(mapped instanceof Map<?, ?> types)) return -1;
        if (!typeMapDumped) {
            typeMapDumped = true;
            for (Map.Entry<?, ?> entry : types.entrySet()) {
                StringBuilder models = new StringBuilder();
                appendModels(models, entry.getValue());
                // 每个类型一行：type=2 等是手表还是手环，models 是该类型下的全部
                // 模型串 —— 换展示模型（修设备页图片）就从这里挑。
                HookRuntime.log("OHEALTH_DEVICE_TYPE_MAP type=" + entry.getKey()
                        + " models=" + models);
            }
        }
        for (Map.Entry<?, ?> entry : types.entrySet()) {
            if (containsModel(entry.getValue(), model) && entry.getKey() instanceof Number type) return type.intValue();
        }
        return -1;
    }
    private static boolean containsModel(Object value, String model) {
        if (value instanceof String text) return model.equals(text);
        if (value instanceof Collection<?> values) {
            for (Object child : values) if (containsModel(child, model)) return true;
        }
        return false;
    }

    private static void appendModels(StringBuilder out, Object value) {
        if (value instanceof String text) {
            if (out.length() > 0) out.append(',');
            out.append(text);
            return;
        }
        if (value instanceof Collection<?> values) {
            for (Object child : values) appendModels(out, child);
        }
    }

}
