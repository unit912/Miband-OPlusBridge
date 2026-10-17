// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import android.content.Context;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 华为协议设备（GT3）的注册表：主应用侧「当前绑定的是一块华为表」的唯一事实来源。
 *
 * <p><b>为什么不能复用小米那套</b>：小米链路的设备信息来自 Mi Fitness 的
 * {@code BindingStore}（{@code address/model/userId/region/token} 五件套 +
 * WearAuthV2 认证分支），由 Xposed hook 从运动健康里抓出来；华为 GT3 不经过
 * Mi Fitness，握手自检又跑在独立 applicationId 的 probe APK 里，
 * SharedPreferences 互不可见。所以这里单独记一份最小集：
 * 手表 MAC + 型号 + 配对时间。</p>
 *
 * <p><b>与 {@code HuaweiIdentityStore} 的分工</b>：identity store 按 MAC 存
 * 协议身份（伪 MAC / androidID / secretKey），是「怎么认证」；本类是
 * 「有没有一块华为表、它是谁」，是路由决策的输入。两者键相同（手表 MAC），
 * 但职责不同，故意不合并 —— identity store 可以被自检快照/还原，
 * 而注册表一旦写下就不该被一次性自检动到。</p>
 */
public final class HuaweiDeviceRegistry {

    private static final String NAME = "huawei-device";
    private static final String KEY_ADDRESS = "address";
    private static final String KEY_MODEL = "model";
    private static final String KEY_PROVISIONED_AT = "provisionedAt";
    private static final String KEY_LAST_BATTERY = "lastBattery";
    private static final String KEY_LAST_CHARGING = "lastCharging";
    private static final String KEY_LAST_SEEN_AT = "lastSeenAt";
    private static final String KEY_CONNECTED = "connected";
    private static final String KEY_LAST_STEPS = "lastSteps";
    private static final String KEY_STEPS_AT = "stepsAt";
    private static final String KEY_LAST_HR = "lastHeartRate";
    private static final String KEY_HR_AT = "heartRateAt";
    private static final String KEY_SLEEP_START = "lastSleepStartMs";
    private static final String KEY_SLEEP_END = "lastSleepEndMs";
    private static final String KEY_SLEEP_AT = "lastSleepAt";

    private HuaweiDeviceRegistry() {}

    /** 已注册华为设备的 MAC；未注册时返回空串。 */
    public static String address(Context context) {
        return LocalPrefs.open(context, NAME).getString(KEY_ADDRESS, "");
    }

    /** 设备型号（如 {@code HUAWEI WATCH GT3}）；未知时为空串。 */
    public static String model(Context context) {
        return LocalPrefs.open(context, NAME).getString(KEY_MODEL, "");
    }

    /** 配对完成的时间戳（epoch millis）；未注册时为 0。 */
    public static long provisionedAt(Context context) {
        return LocalPrefs.open(context, NAME).getLong(KEY_PROVISIONED_AT, 0L);
    }

    /** 是否已注册华为设备 —— {@code BandLiveService} 据此选择协议分支。 */
    public static boolean provisioned(Context context) {
        return !address(context).isBlank();
    }

    /**
     * 登记一块华为设备。地址为空视为清除（防止把空注册写进去把路由带偏）。
     *
     * @param address 手表真实 MAC，如 {@code AA:BB:CC:DD:EE:FF}
     * @param model   型号描述，可为空串
     * @return 写入是否成功
     */
    public static boolean save(Context context, String address, String model) {
        if (address == null || address.isBlank()) return false;
        return LocalPrefs.open(context, NAME).edit()
                .putString(KEY_ADDRESS, address.trim().toUpperCase(java.util.Locale.ROOT))
                .putString(KEY_MODEL, model == null ? "" : model)
                .putLong(KEY_PROVISIONED_AT, System.currentTimeMillis())
                // 换表/重新配对时清掉上一块表的会话残留，避免 connected=true 的幽灵状态。
                .remove(KEY_LAST_BATTERY)
                .remove(KEY_LAST_CHARGING)
                .remove(KEY_LAST_SEEN_AT)
                .remove(KEY_CONNECTED)
                .commit();
    }

    /** 清除注册（解绑/换表时调用）。 */
    public static boolean clear(Context context) {
        return LocalPrefs.open(context, NAME).edit()
                .remove(KEY_ADDRESS)
                .remove(KEY_MODEL)
                .remove(KEY_PROVISIONED_AT)
                .remove(KEY_LAST_BATTERY)
                .remove(KEY_LAST_CHARGING)
                .remove(KEY_LAST_SEEN_AT)
                .remove(KEY_CONNECTED)
                .commit();
    }

    // ---------------------------------------------------------------- 会话状态
    //
    // 为什么不走 BandStateRepository：那个仓库的 recordVerifiedDevice 绑定着
    // 小米身份五件套（deviceId/mac/model 与 BindingStore 逐一比对）与 NATIVE
    // 所有权模式，华为设备写进去只会得到 DEVICE_IDENTITY_CHANGED。
    // 华为侧的状态独立记，OPPO 健康/设备空间的接入后续从这里取数。

    /** 最近一次会话里读到的电量，0-100；从未读过为 -1。 */
    public static int lastBattery(Context context) {
        return LocalPrefs.open(context, NAME).getInt(KEY_LAST_BATTERY, -1);
    }

    /** 最近一次电量读取时是否在充电。 */
    public static boolean lastCharging(Context context) {
        return LocalPrefs.open(context, NAME).getBoolean(KEY_LAST_CHARGING, false);
    }

    /** 最近一次会话活动时间（epoch millis）；从未会话为 0。 */
    public static long lastSeenAt(Context context) {
        return LocalPrefs.open(context, NAME).getLong(KEY_LAST_SEEN_AT, 0L);
    }

    /** 服务当前是否持有已认证的华为会话。 */
    public static boolean connected(Context context) {
        return LocalPrefs.open(context, NAME).getBoolean(KEY_CONNECTED, false);
    }

    /**
     * 记录一次会话状态变化（握手成功 / 电量更新 / 链路断开都走这里）。
     *
     * @param battery   电量 0-100；未知传 -1（保留上次已知值）
     * @param charging  是否充电中
     * @param connected 会话是否存活
     */
    public static boolean noteSession(Context context, int battery, boolean charging,
            boolean connected) {
        LocalPrefs.Editor editor = LocalPrefs.open(context, NAME).edit()
                .putBoolean(KEY_LAST_CHARGING, charging)
                .putLong(KEY_LAST_SEEN_AT, System.currentTimeMillis())
                .putBoolean(KEY_CONNECTED, connected);
        if (battery >= 0 && battery <= 100) {
            editor.putInt(KEY_LAST_BATTERY, battery);
        }
        return editor.commit();
    }

    /** 最近一次读到的心率（bpm）；从未读到为 -1。 */
    public static int lastHeartRate(Context context) {
        return LocalPrefs.open(context, NAME).getInt(KEY_LAST_HR, -1);
    }

    /** 最近一次心率读取的时间（epoch millis）；从未读到为 0。 */
    public static long heartRateAt(Context context) {
        return LocalPrefs.open(context, NAME).getLong(KEY_HR_AT, 0L);
    }

    /** 最近一次读到的睡眠区间起点（epoch millis）；从未读到为 0。 */
    public static long lastSleepStartMs(Context context) {
        return LocalPrefs.open(context, NAME).getLong(KEY_SLEEP_START, 0L);
    }

    /** 最近一次读到的睡眠区间终点（epoch millis）；从未读到为 0。 */
    public static long lastSleepEndMs(Context context) {
        return LocalPrefs.open(context, NAME).getLong(KEY_SLEEP_END, 0L);
    }

    /**
     * 记下一次心率读数。
     *
     * <p>心率来自分钟级明细（0x07/0x0B）里 bitmap 0xC0 的样本，不是单独的命令
     * —— GT3 没有「问一次给当前心率」的接口，心率是搭在步数明细里回来的。
     * 有效区间按常识收窄到 30-220，超出视为解码噪声（真机见过 0xC0 项解出
     * 明显不合理的数值）。
     */
    public static boolean noteHeartRate(Context context, int bpm) {
        if (bpm < 30 || bpm > 220) return false;
        return LocalPrefs.open(context, NAME).edit()
                .putInt(KEY_LAST_HR, bpm)
                .putLong(KEY_HR_AT, System.currentTimeMillis())
                .commit();
    }

    /** 记下最近一段睡眠的区间（epoch millis）。两端都必须为正，否则忽略。 */
    public static boolean noteSleep(Context context, long startMs, long endMs) {
        if (startMs <= 0 || endMs <= 0 || endMs <= startMs) return false;
        return LocalPrefs.open(context, NAME).edit()
                .putLong(KEY_SLEEP_START, startMs)
                .putLong(KEY_SLEEP_END, endMs)
                .putLong(KEY_SLEEP_AT, System.currentTimeMillis())
                .commit();
    }

    /** 最近一次从手表读到的当日总步数；从未读到为 -1。 */
    public static int lastSteps(Context context) {
        return LocalPrefs.open(context, NAME).getInt(KEY_LAST_STEPS, -1);
    }

    /** 最近一次步数读取的时间（epoch millis）；从未读到为 0。 */
    public static long stepsAt(Context context) {
        return LocalPrefs.open(context, NAME).getLong(KEY_STEPS_AT, 0L);
    }

    /**
     * 记下当日总步数。
     *
     * <p>步数来自 FitnessTotals（0x07/0x03）的「手表认定的今天」，不是我们按
     * 时间窗累加的结果 —— 后者在手表未佩戴的时间段会拿到一堆空记录。
     */
    public static boolean noteSteps(Context context, int steps) {
        if (steps < 0) return false;
        return LocalPrefs.open(context, NAME).edit()
                .putInt(KEY_LAST_STEPS, steps)
                .putLong(KEY_STEPS_AT, System.currentTimeMillis())
                .commit();
    }

    // ------------------------------------------------------- 投影到 OPPO 侧
    //
    // 钩子层（OHealthDeviceHook / MyDevicesHook / OHealthStepWriter）只有
    // 一个数据源：LocalPrefs "band-state"，经 DeviceCardProvider.bandDisplay
    // 暴露出去。band-state 过去只由小米链路的 BandStateRepository 写，
    // 而它对华为设备必抛（身份五件套比对 + NATIVE 所有权 + 固件非空）。
    // 所以华为侧自己投影一份，字段与小米侧保持一致，钩子无需改动。

    /** band-state 文件里的型号 id。{@code HUAWEI WATCH GT3} → {@code huawei.watch.gt3}。 */
    public static String modelId(Context context) {
        String model = model(context);
        if (model.isBlank()) return "";
        String id = model.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", ".");
        return id.replaceAll("^\\.+", "").replaceAll("\\.+$", "");
    }

    /** 桥接侧设备 id。与小米侧同构（{@code miband11_<sha256>}），只是前缀不同。 */
    public static String deviceId(Context context) {
        String mac = address(context);
        if (mac.isBlank()) return "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(mac.replace(":", "").getBytes(StandardCharsets.UTF_8));
            return "huaweigt3_" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            return "";
        }
    }

    /**
     * 把当前华为设备的状态写进 {@code band-state}，并通知设备卡片 URI。
     *
     * <p>步数走 {@code stepsToday}：{@code OHealthStepWriter} 用
     * {@code preferLiveTotal} 把快照里的当日总数直接补成一天的记录，
     * 不依赖小米那套原始文件归档（GT3 没有）。</p>
     */
    public static void publish(Context context) {
        try {
            String mac = address(context);
            String id = deviceId(context);
            if (mac.isBlank() || id.isBlank()) return;
            String model = modelId(context);
            LocalPrefs state = LocalPrefs.open(context, "band-state");
            long now = System.currentTimeMillis();
            LocalPrefs.Editor edit = state.edit()
                    .putBoolean("registered", true)
                    .putString("deviceId", id)
                    .putString("mac", mac)
                    .putString("modelId", model)
                    .putString("name", BandCatalog.displayName(model, model(context)))
                    .putBoolean("connected", connected(context))
                    .putLong("lastUpdateMs", now)
                    .putLong("revision", state.getLong("revision", 0) + 1);
            int battery = lastBattery(context);
            if (battery >= 0 && battery <= 100) {
                edit.putInt("battery", battery).putBoolean("charging", lastCharging(context));
            }
            int steps = lastSteps(context);
            long stepsAt = stepsAt(context);
            if (steps >= 0 && stepsAt > 0) {
                edit.putLong("stepsToday", steps).putLong("stepsAtMs", stepsAt);
            }
            // 心率：band-state 的字段与小米侧 BandStateRepository 写的一致
            // （heartRate + heartRateAtMs），设备卡片 / 首页指标卡直接读。
            int hr = lastHeartRate(context);
            long hrAt = heartRateAt(context);
            if (hr > 0 && hrAt > 0) {
                edit.putInt("heartRate", hr).putLong("heartRateAtMs", hrAt);
            }
            if (state.getLong("registeredAtMs", 0) <= 0) {
                long provisioned = provisionedAt(context);
                edit.putLong("registeredAtMs", provisioned > 0 ? provisioned : now);
            }
            edit.commit();
            context.getContentResolver().notifyChange(
                    io.github.miam1ku.mibandoplusbridge.integration.DeviceCardProvider.URI, null);
        } catch (RuntimeException ignored) {
            // 用户未解锁或存储不可用时静默跳过；下一次会话会再写一次。
        }
    }
}
