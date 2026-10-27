// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.integration;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.UserManager;
import io.github.miam1ku.mibandoplusbridge.HostIdentity;
import io.github.miam1ku.mibandoplusbridge.data.BandCatalog;
import io.github.miam1ku.mibandoplusbridge.service.BandLiveService;
import io.github.miam1ku.mibandoplusbridge.service.HostKeepAlive;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Device-center projection and authenticated bridge commands. */
public final class DeviceCardProvider extends ContentProvider {
    public static final String AUTHORITY = "io.github.miam1ku.mibandoplusbridge.devices";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/device_info_table_new");
    private static final String HOST = "com.heytap.mydevices";
    private static final String HEALTH = "com.heytap.health";
    private static final String[] COLUMNS = {"device_id", "device_mac", "device_data", "authority"};

    @Override public boolean onCreate() { return true; }

    /** Card hooks install in the device-space process. The host is matched by package name, so a cert rotation on a system update still injects. */
    public static boolean matchesInstalledHost(Context context) {
        return HostIdentity.installed(context, HOST);
    }

    private void requireReader() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid() || uid == 0 || uid == 2000) return; // self, root, developer shell
        if (!uidHas(HOST) && !uidHas(HEALTH)) throw new SecurityException("DEVICE_CARD_CALLER_NOT_AUTHORIZED");
    }

    private void requireSyncCaller() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return;
        if (!uidHas(HEALTH)) throw new SecurityException("DEVICE_SYNC_CALLER_NOT_AUTHORIZED");
    }

    private boolean uidHas(String packageName) {
        return io.github.miam1ku.mibandoplusbridge.HostIdentity.uidHas(getContext(), Binder.getCallingUid(), packageName);
    }

    private io.github.miam1ku.mibandoplusbridge.data.LocalPrefs state() {
        UserManager users = getContext().getSystemService(UserManager.class);
        if (users == null || !users.isUserUnlocked()) throw new IllegalStateException("USER_LOCKED");
        return io.github.miam1ku.mibandoplusbridge.data.LocalPrefs.open(getContext(), "band-state");
    }


    private static void requireUri(Uri uri) {
        if (!URI.equals(uri)) throw new IllegalArgumentException("UNSUPPORTED_DEVICE_CARD_URI");
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        requireReader();
        requireUri(uri);
        if (sortOrder != null) throw new IllegalArgumentException("UNSUPPORTED_SORT_ORDER");
        String key = null;
        if (selection == null) {
            if (selectionArgs != null && selectionArgs.length != 0) throw new IllegalArgumentException("UNEXPECTED_ARGUMENTS");
        } else {
            if (selectionArgs == null || selectionArgs.length != 1 || selectionArgs[0] == null)
                throw new IllegalArgumentException("INVALID_SELECTION_ARGUMENTS");
            key = switch (selection) {
                case "device_id=?", "device_id = ?" -> "device_id";
                case "device_mac=?", "device_mac = ?" -> "device_mac";
                case "authority=?", "authority = ?" -> "authority";
                default -> throw new IllegalArgumentException("UNSUPPORTED_SELECTION");
            };
        }
        String[] requested = projection == null ? COLUMNS : projection.clone();
        for (String column : requested) {
            if (!isColumn(column)) throw new IllegalArgumentException("UNSUPPORTED_COLUMN");
        }
        MatrixCursor result = new MatrixCursor(requested, 1);
        result.setNotificationUri(getContext().getContentResolver(), URI);
        var state = state();
        if (!state.getBoolean("registered", false)) return result;
        // When Health already placed this band in device space, the bridge row stays out.
        if (spaceYielded()) return result;
        String deviceId = state.getString("deviceId", "");
        if (deviceId == null || deviceId.isBlank()) return result;
        String mac = state.getString("mac", "");
        if (key != null) {
            String actual = switch (key) {
                case "device_id" -> deviceId;
                case "device_mac" -> mac;
                default -> AUTHORITY;
            };
            if (!actual.equals(selectionArgs[0])) return result;
        }
        try {
            JSONObject data = deviceData(state, deviceId, mac);
            MatrixCursor.RowBuilder row = result.newRow();
            for (String column : requested) {
                row.add(switch (column) {
                    case "device_id" -> deviceId;
                    case "device_mac" -> mac;
                    case "device_data" -> data.toString();
                    default -> AUTHORITY;
                });
            }
            return result;
        } catch (JSONException failure) {
            result.close();
            throw new IllegalStateException("DEVICE_CARD_SERIALIZATION_FAILED", failure);
        }
    }

    private static boolean isColumn(String column) {
        for (String allowed : COLUMNS) if (allowed.equals(column)) return true;
        return false;
    }

    private static JSONObject deviceData(io.github.miam1ku.mibandoplusbridge.data.LocalPrefs state,
            String deviceId, String mac) throws JSONException {
        boolean connected = state.getBoolean("registered", false) && state.getBoolean("connected", false);
        String connectState = connected ? "CONNECTED" : "DISCONNECTED";
        int battery = state.getInt("battery", -1);
        JSONArray batteries = new JSONArray();
        JSONArray levels = new JSONArray();
        if (battery >= 0 && battery <= 100) {
            batteries.put(new JSONObject().put("batteryType", "SINGLE")
                    .put("value", battery).put("charge", state.getBoolean("charging", false)));
            levels.put(battery);
        }
        JSONObject connection = new JSONObject().put("connectState", connectState)
                .put("coordinationState", "DEFAULT")
                .put("lastConnectTime", connected ? state.getLong("lastUpdateMs", 0) : 0)
                .put("lastDisconnectTime", connected ? 0 : state.getLong("lastUpdateMs", 0));
        JSONObject data = new JSONObject()
                .put("mDeviceId", deviceId)
                .put("mDeviceName", BandCatalog.displayName(
                        state.getString("modelId", ""), state.getString("name", "")))
                .put("mMacAddress", mac)
                .put("modelId", NativePanel.BAND_MODEL)
                // An empty picture URL makes device space use icon_default_device, which looks like a phone.
                // A band uses the system wristband picture; a watch shape uses the watch picture.
                .put("mDeviceType", BandCatalog.spaceType(state.getString("modelId", "")))
                .put("iconUrl", spaceIcon(state))
                .put("mIconUrl", spaceIcon(state))
                .put("mBatteryInfoList", batteries)
                .put("mBatteryList", levels)
                .put("connection", connection)
                .put("mConnectState", connectState)
                .put("mAuthority", AUTHORITY)
                .put("isActive", connected)
                .put("cardStyle", 1)
                .put("timestamp", state.getLong("lastUpdateMs", 0))
                .put("versionCode", 1L)
                .put("feature", 0)
                .put("linkageVersion", 0)
                .put("autoSwitch", false)
                .put("revision", state.getLong("revision", 0));
        return data;
    }

    private boolean spaceYielded() {
        try {
            return io.github.miam1ku.mibandoplusbridge.data.LocalPrefs.open(getContext(), "space-card")
                    .getBoolean("yield", false);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String spaceIcon(io.github.miam1ku.mibandoplusbridge.data.LocalPrefs state) {
        // 2026-10-26：旧实现发 android.resource://com.heytap.mydevices/drawable/
        // default_wristband —— 但 mydevices 里根本不存在 default_watch /
        // default_wristband 这两个资源（实际只有 icon_default_device / icon_watch），
        // 加载失败后列表卡退化成蓝色占位块（icon_default_device）、详情页退化成
        // 白方表（icon_watch）。上游小米卡正是把自己 DeviceImageStore 的 content URI
        // 填进 iconUrl（注释原话："An empty picture URL makes device space use
        // icon_default_device"）。
        //
        // 2026-10-27：列表卡此前指向 /watchface_preview 已生效，但详情页大图
        // （BlueToothDetailActivity.mDeviceIconIV）经
        // transformIconUrl2FileProviderUri 加载时只认 /device_image 路径
        // （dex 里有 "/device_image/" 字符串，无 miband-body 命名约束）。
        // 这里与上游 bodyIcon 一致，把 iconUrl/mIconUrl 都指向
        // /device_image/miband-body-0.png —— provider 的 openFile 对
        // /device_image 与 /device_image/<name>.png 都 serve 同一张桥接
        // 合成 PNG，列表卡与详情页同源。健康 App 设备页顶部图仍走
        // /watchface_preview（OHealthWatchfaceHook.decodePreview）。
        return "content://" + AUTHORITY + "/device_image/miband-body-0.png";
    }

    @Override public String getType(Uri uri) {
        requireReader();
        if (imagePath(uri)) return "image/png";
        requireUri(uri);
        return "vnd.android.cursor.dir/vnd." + AUTHORITY + ".device_info";
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        requireReader();
        throw new SecurityException("DEVICE_CARD_READ_ONLY");
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        requireReader();
        throw new SecurityException("DEVICE_CARD_READ_ONLY");
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        requireReader();
        throw new SecurityException("DEVICE_CARD_READ_ONLY");
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if ("requestSync".equals(method)) {
            requireSyncCaller();
            long identity = Binder.clearCallingIdentity();
            try {
                Bundle result = new Bundle();
                // Service admission checks registration/ownership without root or Bluetooth work here.
                result.putString("status", BandLiveService.requestSync(getContext()));
                return result;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
        if ("spaceYield".equals(method)) {
            requireSyncCaller();
            boolean yield = extras != null && extras.getBoolean("yield");
            long identity = Binder.clearCallingIdentity();
            try {
                var space = io.github.miam1ku.mibandoplusbridge.data.LocalPrefs.open(getContext(), "space-card");
                if (space.getBoolean("yield", false) == yield) return Bundle.EMPTY;
                space.edit().putBoolean("yield", yield).commit();
                getContext().getContentResolver().notifyChange(URI, null);
                return Bundle.EMPTY;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
        requireReader();
        if (!"bandDisplay".equals(method)) {
            // The host drives the card through a small protocol of its own
            // (AppDeviceService): card_show, card_hide, connect, disconnect,
            // delete_device. Tapping the card in device space calls card_show,
            // and an unknown method used to throw SecurityException, which
            // crossed the binder and popped "the app has stopped" over the
            // host. Answering with an empty bundle keeps the host working even
            // when it asks for something this bridge does not implement.
            return hostCardCall(method, extras);
        }
        HostKeepAlive.ensureBridge(getContext());
        var state = state();
        Bundle display = new Bundle();
        boolean registered = state.getBoolean("registered", false);
        display.putBoolean("registered", registered);
        display.putString("name", BandCatalog.displayName(
                state.getString("modelId", ""), state.getString("name", "")));
        display.putString("deviceId", state.getString("deviceId", ""));
        display.putString("mac", state.getString("mac", ""));
        display.putString("modelId", state.getString("modelId", ""));
        display.putString("firmware", state.getString("verifiedFirmware", ""));
        display.putString("hardware", state.getString("verifiedHardware", ""));
        display.putBoolean("connected", registered && state.getBoolean("connected", false));
        display.putInt("battery", state.getInt("battery", -1));
        display.putBoolean("charging", state.getBoolean("charging", false));
        display.putInt("historyFiles", new io.github.miam1ku.mibandoplusbridge.data.RawFitnessFileStore(getContext()).storedFiles());
        display.putLong("stepsToday", state.getLong("stepsToday", -1));
        display.putInt("heartRate", state.getInt("heartRate", -1));
        display.putLong("registeredAtMs", state.getLong("registeredAtMs", 0));
        display.putLong("lastSyncAtMs", state.getLong("lastSyncAtMs", 0));
        display.putLong("heartRateAtMs", state.getLong("heartRateAtMs", 0));
        display.putLong("stepsAtMs", state.getLong("stepsAtMs", 0));
        display.putLong("lastUpdateMs", state.getLong("lastUpdateMs", 0));
        display.putLong("revision", state.getLong("revision", 0));
        // 表盘列表（Watchface 0x27）：宿主设备页表盘区与桥接表盘管理的数据源。
        display.putString("watchfaces", state.getString("watchfaces", ""));
        display.putLong("watchfacesAtMs", state.getLong("watchfacesAtMs", 0));
        return display;
    }

    /**
     * 宿主驱动设备卡的那套小协议（{@code AppDeviceService}）。
     *
     * <p>宿主在设备空间点卡片时会调 {@code card_show}，卡片隐藏时调
     * {@code card_hide}，连接态用 {@code connect}/{@code disconnect}，
     * 删除用 {@code delete_device}。小米 App 作为 DeviceApp 时实现了整套，
     * 桥接自建 DeviceApp 就得同样回应，否则宿主那边直接抛异常。
     *
     * <p>这里全部返回空 Bundle：本桥接的连接状态由它自己的实时快照提供
     * （{@code bandDisplay}），宿主问来的 connect/disconnect 只是 UI 层的
     * 开关动作，不需要真的连蓝牙。{@code delete_device} 走桥接自己的
     * 「从健康移除」流程，先只记日志。
     *
     * <p>不认识的方法同样返回空 Bundle，而不是抛异常 —— 宿主以后加新方法时，
     * 这里不会把「点了卡就崩」重新引回来。
     */
    private Bundle hostCardCall(String method, Bundle extras) {
        android.util.Log.i("OplusBandBridge", "DEVICE_CARD_HOST_CALL method=" + method);
        Bundle result = new Bundle();
        switch (method == null ? "" : method) {
            case "card_show", "card_hide", "connect", "disconnect" -> {
                // The host only needs an answer it can treat as handled.
            }
            case "delete_device" -> {
                // Deleting the band is driven by the bridge's own flow; the
                // host's call is only a notification that the user asked.
            }
            default -> {
                // Unknown method: stay silent rather than fail the host.
            }
        }
        return result;
    }

    /**
     * 表盘预览 PNG：{@code content://<authority>/watchface_preview} 与
     * {@code content://<authority>/device_image}（或带文件名
     * {@code /device_image/<name>.png}）。
     *
     * <p>宿主健康 App 的 {@code OHealthWatchfaceHook} 换设备页顶部静态图时
     * 从 {@code /watchface_preview} 读图；mydevices 设备空间的列表卡与详情页
     * （{@code BlueToothDetailActivity.mDeviceIconIV}）按 iconUrl 字段经
     * {@code transformIconUrl2FileProviderUri} 加载大图，要求 URI 走
     * {@code /device_image} 路径——上游小米卡正是把
     * {@code content://<authority>/device_image/miband-body-<v>.png} 填进
     * iconUrl。两条路径都返回同一张桥接合成的 GT3 表盘 PNG，保证列表卡与
     * 详情页同源。文件缺失（桥接刚装、还没 publish 过）时同步现画一张，
     * 保证首次打开设备页就有图。
     */
    @Override
    public android.os.ParcelFileDescriptor openFile(Uri uri, String mode) {
        requireReader();
        if (!imagePath(uri)) {
            throw new IllegalArgumentException("UNSUPPORTED_FILE_URI " + uri);
        }
        if (!"r".equals(mode)) throw new IllegalArgumentException("READ_ONLY");
        java.io.File png = io.github.miam1ku.mibandoplusbridge.data
                .WatchfacePreviewRenderer.file(getContext());
        if (!png.isFile() || png.length() == 0) {
            io.github.miam1ku.mibandoplusbridge.data.WatchfacePreviewRenderer
                    .render(getContext());
        }
        try {
            return android.os.ParcelFileDescriptor.open(png,
                    android.os.ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (java.io.FileNotFoundException e) {
            throw new IllegalStateException("WATCHFACE_PREVIEW_MISSING", e);
        }
    }

    /**
     * 判断 URI 是否是本 provider 提供的图片路径。
     *
     * <p>支持三种形式：
     * <ul>
     *   <li>{@code /watchface_preview} —— 健康设备页顶部静态图；</li>
     *   <li>{@code /device_image} —— mydevices 列表卡与详情页大图（裸路径）；</li>
     *   <li>{@code /device_image/<name>.png} —— 上游 {@code bodyIcon} 形态，
     *       文件名形如 {@code miband-body-<v>.png}，但本 provider 不校验
     *       文件名，任何 {@code .png} 后缀都接受。</li>
     * </ul>
     */
    private static boolean imagePath(Uri uri) {
        if (uri == null || !AUTHORITY.equals(uri.getAuthority())) return false;
        java.util.List<String> segments = uri.getPathSegments();
        if (segments == null || segments.isEmpty()) return false;
        String first = segments.get(0);
        if ("watchface_preview".equals(first)) return true;
        if (!"device_image".equals(first)) return false;
        if (segments.size() == 1) return true;
        // 上游 bodyIcon 用 device_image/miband-body-<v>.png 形式；mydevices
        // 详情页对文件名无强约束（dex 里只有 /device_image/ 路径片段），
        // 这里只校验 .png 后缀，足够宽松兼容上游命名与本机直接访问。
        return segments.size() == 2 && segments.get(1).endsWith(".png");
    }
}