// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.json.JSONObject;

import io.github.miam1ku.mibandoplusbridge.protocol.BandHistoryParser;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.FitnessData;

/**
 * 把 GT3 的分钟明细写进 OPPO 健康队列，让步数有历史曲线。
 *
 * <p>0x0B 本身就是分钟级数据（每个子容器 = 一分钟，带 steps/calories/distance/
 * heartrate），而小米侧进健康库的 {@code steps_interval} 也是一分钟一条，
 * 所以这里按分钟原样转写即可，宿主那边的 {@code stepDays} 聚合与表 1002 写入
 * 复用现成实现。
 *
 * <p>血氧（{@code spo2}）也搭同一趟车：GT3 不提供单独的血氧历史命令，测量结果
 * 以 featureBitmap2 bit 0x01 的形式挂在分钟子容器里（协议层已解析为
 * {@code SubContainer.spo}，单字节 0..100）。单次测量是瞬时点，按 manual 模式
 * 写入（区间长度 1ms，见 HealthRecord 的区间约束）。
 *
 * <p>只写有内容的分钟：步数为 0 的一分钟写成 0 行或写成空，对当日总和、日聚合
 * 都没有区别，但能省下上千条记录。心率同理，只写 40..220 的合理值。
 */
public final class HuaweiStepsImporter {

    /** 一分钟。steps_interval / heart_rate 都要求区间的长度正好是这个值。 */
    private static final long MINUTE_MS = 60_000L;

    private HuaweiStepsImporter() {}

    /** 队列写入结果。 */
    public record Result(int steps, int heart, int oxygen, int purged) {}

    /**
     * 转写一次拉取拿到的全部分钟样本。
     *
     * @param windowStartMs 拉取窗口起点，写入前先清掉窗口内的旧记录
     */
    public static Result enqueue(android.content.Context context, String deviceId,
            List<FitnessData.MessageData.StepResponse.SubContainer> samples, ZoneId zone,
            long windowStartMs, long windowEndMs) {
        if (samples == null || samples.isEmpty()) {
            HealthRecordStore store = new HealthRecordStore(context);
            int purged = 0;
            if (windowStartMs > 0 && windowEndMs > windowStartMs) {
                purged += store.purgeWindow(deviceId, "steps_interval", windowStartMs, windowEndMs);
                purged += store.purgeWindow(deviceId, "heart_rate", windowStartMs, windowEndMs);
                purged += store.purgeWindow(deviceId, "spo2", windowStartMs, windowEndMs);
            }
            return new Result(0, 0, 0, purged);
        }
        ZoneId resolved = zone == null ? ZoneId.systemDefault() : zone;
        HealthRecordStore store = new HealthRecordStore(context);
        int purged = 0;
        if (windowStartMs > 0 && windowEndMs > windowStartMs) {
            purged += store.purgeWindow(deviceId, "steps_interval", windowStartMs, windowEndMs);
            purged += store.purgeWindow(deviceId, "heart_rate", windowStartMs, windowEndMs);
            purged += store.purgeWindow(deviceId, "spo2", windowStartMs, windowEndMs);
        }
        int steps = 0;
        int heart = 0;
        int oxygen = 0;
        long total = 0;
        for (FitnessData.MessageData.StepResponse.SubContainer sample : samples) {
            if (sample == null || sample.timestamp <= 0) continue;
            long minute = sample.timestamp * 1000L;
            if (windowStartMs > 0 && minute < windowStartMs) continue;
            if (windowEndMs > 0 && minute + MINUTE_MS > windowEndMs) continue;
            if (sample.steps > 0) {
                if (write(context, store, deviceId, "steps_interval", minute,
                        sample.steps, resolved, sample.calories, sample.distance)) {
                    steps++;
                    total += sample.steps;
                } else {
                    return new Result(steps, heart, oxygen, purged);
                }
            }
            if (sample.heartrate >= 40 && sample.heartrate <= 220) {
                if (write(context, store, deviceId, "heart_rate", minute,
                        sample.heartrate, resolved, null, null)) {
                    heart++;
                } else {
                    return new Result(steps, heart, oxygen, purged);
                }
            }
            if (sample.spo >= 60 && sample.spo <= 100) {
                if (writeManual(context, store, deviceId, "spo2", minute, sample.spo, resolved)) {
                    oxygen++;
                } else {
                    return new Result(steps, heart, oxygen, purged);
                }
            }
        }
        SessionLog.line(context, "HUAWEI_STEPS_IMPORT minutes=" + steps
                + " steps=" + total + " heart=" + heart + " oxygen=" + oxygen
                + " purged=" + purged);
        // 归档写完了，告诉宿主现在就来读。归档走的是直接写 SQLite 的
        // enqueueMeasurement，不经过 provider 的 call()，那一路不发通知；
        // 不补这一下，健康进程里的 ContentObserver 收不到，步数就一直
        // 停在上一轮归档时的值。
        io.github.miam1ku.mibandoplusbridge.integration.HealthQueueProvider.notifyArchived(context);
        return new Result(steps, heart, oxygen, purged);
    }

    /**
     * 把 GT3 的当日汇总（0x07/0x03 FitnessTotals）写成 {@code steps_day} 日汇总记录。
     *
     * <p>GT3 的分钟明细只回活动时段，真正的日总步数/活动消耗来自这个总计。
     * 写成 {@code steps_day} 后，OHealthStepWriter 才能把权威日总卡路里写进
     * 表 1002 与运动缓存，避免首页消耗环/日汇总与手表不同步。</p>
     *
     * @return 是否成功入队（相同 fingerprint 会返回 false，不必视为失败）
     */
    public static boolean enqueueDayTotal(android.content.Context context, String deviceId,
            int steps, int calories, int distance, ZoneId zone) {
        if (steps < 0 && calories < 0 && distance < 0) return true;
        if (deviceId == null || deviceId.isBlank()) return false;
        ZoneId resolved = zone == null ? ZoneId.systemDefault() : zone;
        HealthRecordStore store = new HealthRecordStore(context);
        try {
            long startMs = java.time.LocalDate.now(resolved)
                    .atStartOfDay(resolved)
                    .toInstant()
                    .toEpochMilli();
            long endMs = startMs + 86_400_000L;
            store.enqueueMeasurement(measurement(deviceId, "steps_day", startMs, steps, resolved,
                    calories >= 0 ? calories : null,
                    distance >= 0 ? distance : null, endMs, "continuous"));
            return true;
        } catch (IllegalStateException | SecurityException unavailable) {
            SessionLog.line(context, "HUAWEI_DAYTOTAL_QUEUE_UNAVAILABLE "
                    + unavailable.getMessage());
            return false;
        } catch (RuntimeException refused) {
            SessionLog.line(context, "HUAWEI_DAYTOTAL_QUEUE_REJECTED " + refused.getMessage());
            return false;
        }
    }

    /** manual 模式的瞬时点写入：区间长度 1ms（HealthRecord 的区间约束）。 */
    private static boolean writeManual(android.content.Context context, HealthRecordStore store,
            String deviceId, String kind, long startMs, int value, ZoneId zone) {
        try {
            store.enqueueMeasurement(measurement(deviceId, kind, startMs, value, zone,
                    null, null, startMs + 1L, "manual"));
            return true;
        } catch (IllegalStateException | SecurityException unavailable) {
            // 账户没确认时写不进去，小米侧同样如此。
            SessionLog.line(context, "HUAWEI_STEPS_QUEUE_UNAVAILABLE "
                    + unavailable.getMessage());
            return false;
        } catch (RuntimeException refused) {
            SessionLog.line(context, "HUAWEI_STEPS_QUEUE_REJECTED " + refused.getMessage());
            return false;
        }
    }

    private static boolean write(android.content.Context context, HealthRecordStore store,
            String deviceId, String kind, long startMs, int value, ZoneId zone,
            Integer calories, Integer distance) {
        try {
            store.enqueueMeasurement(measurement(deviceId, kind, startMs, value, zone,
                    calories, distance, startMs + MINUTE_MS, "continuous"));
            return true;
        } catch (IllegalStateException | SecurityException unavailable) {
            // 账户没确认时写不进去，小米侧同样如此。
            SessionLog.line(context, "HUAWEI_STEPS_QUEUE_UNAVAILABLE "
                    + unavailable.getMessage());
            return false;
        } catch (RuntimeException refused) {
            SessionLog.line(context, "HUAWEI_STEPS_QUEUE_REJECTED " + refused.getMessage());
            return false;
        }
    }

    private static BandHistoryParser.Measurement measurement(String deviceId, String kind,
            long startMs, int value, ZoneId zone, Integer calories, Integer distance,
            long endMs, String mode) {
        JSONObject json = new JSONObject();
        try {
            json.put("recordId", recordId(deviceId, kind, startMs));
            json.put("deviceId", deviceId);
            json.put("kind", kind);
            json.put("startMs", startMs);
            json.put("endMs", endMs);
            json.put("value", value);
            json.put("stage", JSONObject.NULL);
            json.put("timezone", zone.getId());
            json.put("sourceFingerprint", fingerprint(deviceId, kind, startMs, value,
                    calories, distance));
            json.put("measurementMode", mode);
            json.put("complete", false);
            if (calories != null && calories >= 0) json.put("calories", calories);
            if (distance != null && distance >= 0) json.put("distance", distance);
            return BandHistoryParser.Measurement.fromJson(json);
        } catch (org.json.JSONException malformed) {
            throw new IllegalStateException("INVALID_MEASUREMENT_JSON", malformed);
        }
    }

    private static String recordId(String deviceId, String kind, long startMs) {
        return "huawei_" + hex("huawei:" + deviceId + ":" + kind + ":" + startMs);
    }

    private static String fingerprint(String deviceId, String kind, long startMs, int value,
            Integer calories, Integer distance) {
        return hex("huawei|" + deviceId + "|" + kind + "|" + startMs + "|" + value
                + "|" + (calories == null ? "-" : calories.toString())
                + "|" + (distance == null ? "-" : distance.toString()));
    }

    private static String hex(String seed) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(seed.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(String.format(Locale.ROOT, "%02x", b));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException("SHA256_UNAVAILABLE", absent);
        }
    }
}
