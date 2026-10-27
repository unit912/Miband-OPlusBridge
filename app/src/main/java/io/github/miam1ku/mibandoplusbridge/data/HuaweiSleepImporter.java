// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.json.JSONObject;

import io.github.miam1ku.mibandoplusbridge.protocol.BandHistoryParser;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.FitnessData;

/**
 * 把 GT3 的睡眠分期写进 OPPO 健康队列。
 *
 * <p>链路：GT3 0x0D 子容器 → 本类聚成「一次睡眠」→
 * {@link HealthRecordStore} 的 {@code sleep_interval} / {@code sleep_stage} →
 * 宿主进程 {@code OHealthSleepPlan} 排日历 → 宿主库表 1010/1011。
 * 也就是说本类只负责生产记录，后续整套 negotiation 逻辑复用小米侧的现成实现，
 * 一行都不用改。
 *
 * <p>两个只有把 Gadgetbridge 源码翻开才拿得到的语义：
 *
 * <ul>
 *   <li><b>子容器 = (起始秒, 时长分钟, 分期)。</b>6 字节时间戳前 4 字节是大端秒级
 *       Unix 时间，后 2 字节是这一段持续的<b>分钟数</b>，不是偏移量也不是毫秒。
 *       所以每段是 [start, start + minutes) 的区间，相邻段首尾相接。</li>
 *   <li><b>分期取值与 HarmonyOS 5 的 SequenceData 同源：</b>1 浅睡、2 REM、
 *       3 深睡、4 睡中清醒、5 小睡。bridge 内部的 band stage 编号不同
 *       （2 深、3 浅、4 REM、5 醒），本类负责换算。</li>
 * </ul>
 */
public final class HuaweiSleepImporter {

    /** bridge 内部（小米手环）的睡眠分期编号，见 {@code OHealthSleepPlan}。 */
    private static final int BAND_DEEP = 2;
    private static final int BAND_LIGHT = 3;
    private static final int BAND_REM = 4;
    private static final int BAND_AWAKE = 5;

    /** 两次睡眠之间的最小间隔：超过它就属于另一次睡眠（与 OHealthSleepPlan 一致）。 */
    private static final long SESSION_GAP_MS = 20L * 60_000L;

    /**
     * 一次睡眠的<b>纯睡眠时长</b>门槛，短于此不入队。
     *
     * <p>★ 语义修正（2026-10-14 实测定位）：门槛看 {@link Session#sleepMs()}——
     * 分期里真正的睡眠分钟数，不是会话跨度。GT3 只为「判定为睡着」的时段出
     * 分期，睡着但中途清醒的时段在时间轴上是窟窿 —— 真午睡（12:06-13:23）
     * 跨度 77 分钟、纯睡眠 30 分钟、coverage 只有 0.39，旧的「跨度 + coverage
     * 0.7」双门槛把这种真午睡整场拒掉（真机实测 skipped=10、sessions=0）。
     *
     * <p>不戴表的伪迹碎片靠纯睡眠时长就能挡：真机 24h 伪迹共 82 分钟，但分散
     * 在十几个会话里，单会话纯睡眠最多 11 分钟，够不着 30 分钟线。
     */
    private static final long MIN_SLEEP_MS = 30L * 60_000L;

    /** 一次睡眠的所有分期，按时间升序。 */
    public record Session(long startMs, long endMs, List<Stage> stages) {
        /** 睡眠时长（含清醒期以外的所有分期）。 */
        public long sleepMs() {
            long ms = 0;
            for (Stage stage : stages) {
                if (stage.bandStage() != BAND_AWAKE) ms += stage.endMs() - stage.startMs();
            }
            return ms;
        }

        /**
         * 分期时长占整个跨度的比例。仅作日志观测 —— 入队门槛已改为纯睡眠时长
         * （见 {@link #MIN_SLEEP_MS}）：GT3 不为「睡着但清醒」的时段出分期，
         * 真午睡的 coverage 天然只有 0.3~0.5，不能当过滤条件。
         */
        public double coverage() {
            long spanMs = endMs - startMs;
            if (spanMs <= 0) return 0;
            long stagedMs = 0;
            for (Stage stage : stages) stagedMs += stage.endMs() - stage.startMs();
            return stagedMs / (double) spanMs;
        }
    }

    /** 一段分期。{@code bandStage} 是 bridge 侧的编号。 */
    public record Stage(long startMs, long endMs, int bandStage) {}

    private HuaweiSleepImporter() {}

    /**
     * 华为分期 → bridge 分期。小睡（5）没有对应的 host 状态，Gadgetbridge 也把
     * {@code TRUSLEEP_NAP} 归一到浅睡，这里照做。返回 {@code -1} 表示未知分期。
     */
    public static int bandStage(int huaweiStage) {
        return switch (huaweiStage) {
            case 1 -> BAND_LIGHT;
            case 2 -> BAND_REM;
            case 3 -> BAND_DEEP;
            case 4 -> BAND_AWAKE;
            case 5 -> BAND_LIGHT;
            default -> -1;
        };
    }

    /**
     * 解析一次拉取拿到的全部子容器，按起始时间升序返回有效分期。
     * 起始为 0（手表把「当前正在进行的一段」也算进条数）或时长为 0 的段直接丢掉。
     */
    public static List<Stage> parse(
            List<FitnessData.MessageData.SleepResponse.SubContainer> containers) {
        List<Stage> stages = new ArrayList<>();
        if (containers == null) return stages;
        for (FitnessData.MessageData.SleepResponse.SubContainer container : containers) {
            if (container == null || container.timestamp == null
                    || container.timestamp.length < 6) continue;
            byte[] t = container.timestamp;
            long startSec = ((long) (t[0] & 0xff) << 24)
                    | ((long) (t[1] & 0xff) << 16)
                    | ((long) (t[2] & 0xff) << 8)
                    | (t[3] & 0xff);
            long minutes = ((long) (t[4] & 0xff) << 8) | (t[5] & 0xff);
            int stage = bandStage(container.type);
            if (startSec <= 0 || minutes <= 0 || stage < 0) continue;
            long startMs = startSec * 1000L;
            stages.add(new Stage(startMs, startMs + minutes * 60_000L, stage));
        }
        stages.sort(Comparator.comparingLong(Stage::startMs));
        return stages;
    }

    /**
     * 按 20 分钟间隔把分期切成「一次睡眠」。GT3 只在睡眠期间产生分期，
     * 睡眠之间没有分段，所以间隔本身就把会话边界标出来了。
     */
    public static List<Session> sessions(List<Stage> stages) {
        List<Session> sessions = new ArrayList<>();
        if (stages.isEmpty()) return sessions;
        List<Stage> current = new ArrayList<>();
        current.add(stages.get(0));
        for (int i = 1; i < stages.size(); i++) {
            Stage previous = stages.get(i - 1);
            Stage stage = stages.get(i);
            if (stage.startMs() - previous.endMs() > SESSION_GAP_MS) {
                sessions.add(new Session(current.get(0).startMs(),
                        current.get(current.size() - 1).endMs(), current));
                current = new ArrayList<>();
            }
            current.add(stage);
        }
        sessions.add(new Session(current.get(0).startMs(),
                current.get(current.size() - 1).endMs(), current));
        return sessions;
    }

    /** 队列写入结果。 */
    public record Result(int sessions, int skipped, int records, int purged) {}

    /**
     * 把解析出的睡眠写进健康队列。返回写入 summary，供会话日志记录。
     */
    public static Result enqueue(android.content.Context context, String deviceId,
            List<FitnessData.MessageData.SleepResponse.SubContainer> containers) {
        return enqueue(context, deviceId, containers, null, -1L, -1L);
    }

    /**
     * 同上，指定时区与拉取窗口。
     *
     * <p>写入前先把窗口里这个 deviceId 的睡眠记录整个删掉 —— 每次拉取都是该窗口的
     * 权威结果（和小米侧 retain 的语义一致），否则上一轮写进去的东西 —— 包括后来
     * 判定为伪迹的会话 —— 会一直留在队列里。
     */
    /**
     * 合并入口：0x0D 小睡 + TruSleep 夜眠区间一起入队。
     *
     * <p>★ TruSleep 专项（2026-10-23）：GT3 整夜睡眠走文件通道而非 0x0C/0x0D，所以这里把
     * 文件解析出的夜眠起止区间（{@link HuaweiTruSleepParser.TruSleepInterval}）与 0x0D 小睡
     * 合并。落在某个夜眠区间内部的 0x0D 小睡会被丢弃（避免把「夜眠里的清醒/碎片」再算一遍）。
     *
     * <p>夜眠区间暂无分期算法（SLEEP_DATA 的 PPG/ACC 分期未实现，Gadgetbridge 同样是 FIXME），
     * 先用整段浅睡占位，保证 OPPO 渲染出时长块；分期细化是后续项。
     */
    public static Result enqueue(android.content.Context context, String deviceId,
            List<FitnessData.MessageData.SleepResponse.SubContainer> containers,
            List<HuaweiTruSleepParser.TruSleepInterval> truSleep, ZoneId zone,
            long windowStartMs, long windowEndMs) {
        List<Session> all = new ArrayList<>();
        // 0x0D 小睡
        for (Session s : sessions(parse(containers))) {
            // 落入夜眠区间的小睡碎片丢弃
            boolean inside = false;
            for (HuaweiTruSleepParser.TruSleepInterval t : truSleep) {
                if (s.startMs() >= t.startSec() * 1000L && s.endMs() <= t.endSec() * 1000L) {
                    inside = true;
                    break;
                }
            }
            if (!inside) all.add(s);
        }
        // TruSleep 夜眠区间：整段浅睡占位（分期细化待 SLEEP_DATA）
        for (HuaweiTruSleepParser.TruSleepInterval t : truSleep) {
            long from = t.startSec() * 1000L;
            long to = t.endSec() * 1000L;
            if (to - from < MIN_SLEEP_MS) continue;
            all.add(new Session(from, to,
                    java.util.List.of(new Stage(from, to, BAND_LIGHT))));
        }
        return importSessions(context, deviceId, all, zone, windowStartMs, windowEndMs);
    }

    /** purge 窗口 + 写会话列表 + 返回 summary。提取自原 enqueue，供两个入口复用。 */
    private static Result importSessions(android.content.Context context, String deviceId,
            List<Session> all, ZoneId zone, long windowStartMs, long windowEndMs) {
        ZoneId resolved = zone == null ? ZoneId.systemDefault() : zone;
        HealthRecordStore store = new HealthRecordStore(context);
        // 上次留下的痕迹先擦干净，不管这次有没有数据。
        long startMs = windowStartMs;
        long endMs = windowEndMs;
        if (startMs <= 0 && !all.isEmpty()) startMs = all.get(0).startMs() - 1;
        if (endMs <= 0 && !all.isEmpty()) endMs = all.get(all.size() - 1).endMs() + 1;
        int purged = 0;
        if (startMs > 0 && endMs > startMs) {
            purged += store.purgeWindow(deviceId, "sleep_interval", startMs, endMs);
            purged += store.purgeWindow(deviceId, "sleep_stage", startMs, endMs);
        }
        if (all.isEmpty()) return new Result(0, 0, 0, purged);

        int sessions = 0;
        int skipped = 0;
        int records = 0;
        for (Session session : all) {
            long from = session.startMs();
            long to = session.endMs();
            if (endMs > 0) to = Math.min(to, endMs);
            if (session.sleepMs() < MIN_SLEEP_MS) {
                skipped++;
                continue;
            }
            if (!write(context, store, deviceId, resolved, from, to, session.stages())) {
                // 账户未确认：后面全写不进去，不必继续。
                break;
            }
            sessions++;
            records += 1 + session.stages().size();
        }
        return new Result(sessions, skipped, records, purged);
    }

    public static Result enqueue(android.content.Context context, String deviceId,
            List<FitnessData.MessageData.SleepResponse.SubContainer> containers, ZoneId zone,
            long windowStartMs, long windowEndMs) {
        return importSessions(context, deviceId, sessions(parse(containers)), zone,
                windowStartMs, windowEndMs);
    }

    /** 一次睡眠：一条 interval + 每个分期一条 stage。 */
    private static boolean write(android.content.Context context, HealthRecordStore store,
            String deviceId, ZoneId zone, long startMs, long endMs, List<Stage> stages) {
        try {
            store.enqueueMeasurement(measurement(deviceId, "sleep_interval", startMs, endMs,
                    null, zone, true, startMs, endMs, null));
            for (Stage stage : stages) {
                long from = Math.max(stage.startMs(), startMs);
                long to = Math.min(stage.endMs(), endMs);
                if (to <= from) continue;
                store.enqueueMeasurement(measurement(deviceId, "sleep_stage", from, to,
                        stage.bandStage(), zone, false, stage.startMs(), stage.endMs(),
                        stage.bandStage()));
            }
            return true;
        } catch (IllegalStateException | SecurityException unavailable) {
            // 队列要求宿主先把账户确认过；没确认时这里会抛。小米侧同样如此。
            SessionLog.line(context, "HUAWEI_SLEEP_QUEUE_UNAVAILABLE "
                    + unavailable.getMessage());
            return false;
        } catch (RuntimeException refused) {
            SessionLog.line(context, "HUAWEI_SLEEP_QUEUE_REJECTED " + refused.getMessage());
            return false;
        }
    }

    /** recordId 与指纹的取法照抄 BandHistoryParser：身份 + kind + 起始时间。 */
    private static BandHistoryParser.Measurement measurement(String deviceId, String kind,
            long startMs, long endMs, Integer bandStage, ZoneId zone, boolean complete,
            long identityStartMs, long identityEndMs, Integer identityStage) {
        JSONObject json = new JSONObject();
        try {
            json.put("recordId", recordId(deviceId, kind, identityStartMs, identityStage));
            json.put("deviceId", deviceId);
            json.put("kind", kind);
            json.put("startMs", startMs);
            json.put("endMs", endMs);
            json.put("value", JSONObject.NULL);
            json.put("stage", bandStage == null ? JSONObject.NULL : bandStage);
            json.put("timezone", zone.getId());
            json.put("sourceFingerprint", fingerprint(deviceId, kind, identityStartMs,
                    identityEndMs, bandStage, complete));
            json.put("measurementMode", "sleep");
            json.put("complete", complete);
            return BandHistoryParser.Measurement.fromJson(json);
        } catch (org.json.JSONException malformed) {
            throw new IllegalStateException("INVALID_MEASUREMENT_JSON", malformed);
        }
    }

    private static String recordId(String deviceId, String kind, long startMs, Integer stage) {
        StringBuilder seed = new StringBuilder("huawei:");
        seed.append(deviceId).append(':').append(kind).append(':').append(startMs);
        if (stage != null) seed.append(':').append(stage);
        return "huawei_" + hex(seed.toString());
    }

    private static String fingerprint(String deviceId, String kind, long startMs, long endMs,
            Integer stage, boolean complete) {
        StringBuilder seed = new StringBuilder("huawei|");
        seed.append(deviceId).append('|').append(kind).append('|')
                .append(startMs).append('|').append(endMs).append('|')
                .append(stage == null ? "-" : stage.toString()).append('|')
                .append(complete ? 1 : 0);
        return hex(seed.toString());
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
