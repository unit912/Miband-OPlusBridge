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
 * <p>只写有内容的分钟：步数为 0 的一分钟写成 0 行或写成空，对当日总和、日聚合
 * 都没有区别，但能省下上千条记录。心率同理，只写 40..220 的合理值。
 */
public final class HuaweiStepsImporter {

    /** 一分钟。steps_interval / heart_rate 都要求区间的长度正好是这个值。 */
    private static final long MINUTE_MS = 60_000L;

    private HuaweiStepsImporter() {}

    /** 队列写入结果。 */
    public record Result(int steps, int heart, int purged) {}

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
            }
            return new Result(0, 0, purged);
        }
        ZoneId resolved = zone == null ? ZoneId.systemDefault() : zone;
        HealthRecordStore store = new HealthRecordStore(context);
        int purged = 0;
        if (windowStartMs > 0 && windowEndMs > windowStartMs) {
            purged += store.purgeWindow(deviceId, "steps_interval", windowStartMs, windowEndMs);
            purged += store.purgeWindow(deviceId, "heart_rate", windowStartMs, windowEndMs);
        }
        int steps = 0;
        int heart = 0;
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
                    return new Result(steps, heart, purged);
                }
            }
            if (sample.heartrate >= 40 && sample.heartrate <= 220) {
                if (write(context, store, deviceId, "heart_rate", minute,
                        sample.heartrate, resolved, null, null)) {
                    heart++;
                } else {
                    return new Result(steps, heart, purged);
                }
            }
        }
        SessionLog.line(context, "HUAWEI_STEPS_IMPORT minutes=" + steps
                + " steps=" + total + " heart=" + heart + " purged=" + purged);
        return new Result(steps, heart, purged);
    }

    private static boolean write(android.content.Context context, HealthRecordStore store,
            String deviceId, String kind, long startMs, int value, ZoneId zone,
            Integer calories, Integer distance) {
        try {
            store.enqueueMeasurement(measurement(deviceId, kind, startMs, value, zone,
                    calories, distance));
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
            long startMs, int value, ZoneId zone, Integer calories, Integer distance) {
        JSONObject json = new JSONObject();
        try {
            json.put("recordId", recordId(deviceId, kind, startMs));
            json.put("deviceId", deviceId);
            json.put("kind", kind);
            json.put("startMs", startMs);
            json.put("endMs", startMs + MINUTE_MS);
            json.put("value", value);
            json.put("stage", JSONObject.NULL);
            json.put("timezone", zone.getId());
            json.put("sourceFingerprint", fingerprint(deviceId, kind, startMs, value,
                    calories, distance));
            json.put("measurementMode", "continuous");
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
