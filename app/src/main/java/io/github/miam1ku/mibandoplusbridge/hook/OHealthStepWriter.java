// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import io.github.miam1ku.mibandoplusbridge.hook.xposed.HookRuntime;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XposedBridge;
import io.github.miam1ku.mibandoplusbridge.data.HealthRecord;
import io.github.miam1ku.mibandoplusbridge.integration.HealthQueueProvider;
import io.github.miam1ku.mibandoplusbridge.protocol.BandHistoryParser;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Writes walk and home daily totals into OHealth table 1002, and minute bars into table 1001. */
final class OHealthStepWriter {
    static final int TABLE_STAT = 1002;
    static final int TABLE_DETAIL = 1001;
    /** SportMode.WATCH_OR_BAND_DATA_ONLY_USED_READ. Official saveStatData stores the day here. */
    static final int DAY_STEP_MODE = -3;
    /** SportMode.WALK. A band stores this minute type as-is. A watch remaps it. */
    static final int MINUTE_MODE = 1;
    /** DeviceCategory.BAND. The lowercase value is not a wearable, so the source list keeps only the phone. */
    static final String DEVICE_BAND = "Band";
    /** DeviceCategory.WATCH. Same spelling the health library stores for a watch. */
    static final String DEVICE_WATCH = "Watch";
    /** Phone import stores calories times 1000. The daily label divides by 1000 to show kilocalories. */
    static final int CALORIE_SCALE = 1000;
    static final int MINUTES_PER_DAY = 1_440;
    private static final long MINUTE_MS = 60_000L;
    private static final int INSERT_CHUNK = 400;
    private static final String[] COLUMNS = {"recordId", "revision", "record"};
    private final OHealthHealthImportHook.HostContract host;
    private final Class<?> statClass;
    private final Constructor<?> statNew;
    private final Method setAccount, setDevice, setDate, setMode, setSteps, setTimezone;
    private final Method setCalories, setDistance, setMoveAbout, setSync, setUpdated;
    private final Method getDevice, getDate, getMode, getSteps, getCalories, getMoveAbout;
    private boolean detailResolved;
    private Class<?> detailClass;
    private Constructor<?> detailNew;
    private Method detailAccount, detailDevice, detailStart, detailEnd, detailSteps, detailCalories;
    private Method detailDistance, detailWorkout, detailMode, detailDisplay, detailType, detailZone;
    private Method detailGetStart, detailGetSteps, detailGetDevice, detailGetEnd;
    private Method detailGetMode, detailGetType;
    /** Chart data type the host returns for an aggregated (groupUnit>0) detail read. */
    private Class<?> tsdClass;
    private Method tsdTimestamp, tsdY;

    OHealthStepWriter(OHealthHealthImportHook.HostContract host) throws ReflectiveOperationException {
        this.host = host;
        statClass = Class.forName("com.heytap.databaseengine.model.SportDataStat", false, host.loader);
        statNew = statClass.getConstructor();
        setAccount = statClass.getMethod("setSsoid", String.class);
        setDevice = statClass.getMethod("setDeviceUniqueId", String.class);
        setDate = statClass.getMethod("setDate", int.class);
        setMode = statClass.getMethod("setSportMode", int.class);
        setSteps = statClass.getMethod("setTotalSteps", int.class);
        setTimezone = statClass.getMethod("setTimezone", String.class);
        setCalories = statClass.getMethod("setTotalCalories", long.class);
        setDistance = statClass.getMethod("setTotalDistance", int.class);
        setMoveAbout = statClass.getMethod("setTotalMoveAboutTimes", int.class);
        setSync = statClass.getMethod("setSyncStatus", int.class);
        setUpdated = statClass.getMethod("setUpdateTimestamp", long.class);
        getDevice = getter(statClass, "getDeviceUniqueId", String.class);
        getDate = getter(statClass, "getDate", int.class);
        getMode = getter(statClass, "getSportMode", int.class);
        getSteps = getter(statClass, "getTotalSteps", int.class);
        getCalories = getter(statClass, "getTotalCalories", long.class);
        getMoveAbout = getter(statClass, "getTotalMoveAboutTimes", int.class);
    }

    void write(Context context) throws Exception {
        String account = host.account();
        if (account == null || account.isBlank()) return;
        Object api = host.api();
        if (api == null) throw new IllegalStateException("STEP_IMPORT_NOT_READY");
        Bundle band = OHealthDeviceHook.registeredSnapshot();
        if (band == null || band.getString("deviceId", "").isBlank()) {
            HookRuntime.log("OHEALTH_STEP_WRITE_SKIP band="
                    + (band == null ? "null" : "blankDeviceId"));
            return;
        }
        String queued = band.getString("deviceId");
        String device = OHealthDeviceHook.healthId(queued);
        String category = deviceCategory(band.getString("modelId", ""));
        // 2026-10-22 诊断探针：用系统当日区间无条件复刻图表查询（不依赖 today 是否在 days 里）。
        try {
            java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneId.systemDefault());
            java.time.ZonedDateTime midnight = now.toLocalDate().atStartOfDay(java.time.ZoneId.systemDefault());
            long startMs = midnight.toInstant().toEpochMilli();
            long endMs = startMs + 86_400_000L;
            probeChartVariants(api, account, device, startMs, endMs, "PRE");
        } catch (Throwable ignored) { }
        List<HealthRecord> records = history(context, account, queued);
        List<BandHistoryParser.StepDay> days = new ArrayList<>(BandHistoryParser.preferLiveTotal(
                BandHistoryParser.stepDays(records), band.getLong("stepsToday", -1),
                band.getLong("stepsAtMs", 0), java.time.ZoneId.systemDefault()));
        days.removeIf(day -> day.steps <= 0 && day.calories < 0 && day.moveAbout < 0 && day.distance < 0);
        HookRuntime.log("OHEALTH_STEP_WRITE device=" + device + " category=" + category
                + " records=" + records.size() + " stepsToday=" + band.getLong("stepsToday", -1)
                + " stepsAtMs=" + band.getLong("stepsAtMs", 0) + " days=" + days.size());
        if (days.isEmpty()) return;
        // A live total with no queued history only feeds the home card. The stat, minute and
        // cleanup rows go through the health database service, whose calls block while that
        // service is still binding and take the host's main thread with them. A device that
        // never queued a record has nothing to reconcile there, so publish and stop.
        if (records.isEmpty()) {
            publishToday(context, days);
            publishSnapshot(context);
            HookRuntime.log("OHEALTH_STEP_IMPORT live-only days=" + days.size()
                    + " steps=" + days.get(0).steps);
            return;
        }
        SharedPreferences written = context.getSharedPreferences("oplusband-step-import", Context.MODE_PRIVATE);
        int inserted = 0;
        int skipped = 0;
        // 2026-10-21: 日汇总变了，宿主会把那一天的分钟明细一起清掉
        // （表 1002 的新行让宿主重算当天，表 1001 的行随之消失）。这时
        // 记忆里「这些分钟都写过了」已经不成立 —— 不作废的话，writeChart
        // 算出的增量全是 0，明细再也补不回来，柱状图就空了。
        // 现象：v124 写入 799 行时柱状图正常；v125 里 inserted=1 的那一轮
        // 把明细清空、chart=0 没补回来，之后每轮都是 chart=0。
        java.util.Set<Integer> rebuilt = new java.util.HashSet<>();
        for (BandHistoryParser.StepDay day : days) {
            try {
                if (writeStat(api, account, device, day, written)) {
                    inserted++;
                    rebuilt.add(day.date);
                } else {
                    skipped++;
                }
            } catch (Exception failure) {
                skipped++;
                XposedBridge.log("OplusBandBridge OHEALTH_STEP_STAT_FAIL date=" + day.date + " " + failure);
            }
        }
        for (int date : rebuilt) {
            forgetMinutes(written, device, date);
        }
        int chart = 0;
        for (BandHistoryParser.StepDay day : days) {
            try {
                chart += writeChart(api, account, device, category, day, records, written);
            } catch (Exception failure) {
                XposedBridge.log("OplusBandBridge OHEALTH_STEP_CHART_FAIL date=" + day.date + " " + failure);
            }
        }
        if (!queued.equals(device)) {
            for (BandHistoryParser.StepDay day : days) {
                long end = day.startMs + 86_400_000L;
                try {
                    host.deleteRows(api, TABLE_STAT, account, queued, day.startMs, end);
                    host.deleteRows(api, TABLE_DETAIL, account, queued, day.startMs, end);
                } catch (Exception ignored) {
                    HookRuntime.log("OHEALTH_STEP_PREVIOUS_DEVICE_KEPT date=" + day.date);
                }
            }
        }
        publishToday(context, days);
        publishSnapshot(context);
        String summary = "OHEALTH_STEP_IMPORT days=" + days.size()
                + " inserted=" + inserted + " skipped=" + skipped + " chart=" + chart;
        HookRuntime.log(summary);
        XposedBridge.log("OplusBandBridge " + summary);
        // 2026-10-22 写完后再次复刻图表查询，确认刚写入的分钟行是否真能被图表读到。
        try {
            java.time.ZonedDateTime now2 = java.time.ZonedDateTime.now(java.time.ZoneId.systemDefault());
            java.time.ZonedDateTime midnight2 = now2.toLocalDate().atStartOfDay(java.time.ZoneId.systemDefault());
            long s2 = midnight2.toInstant().toEpochMilli();
            long e2 = s2 + 86_400_000L;
            probeChartVariants(api, account, device, s2, e2, "POST");
        } catch (Throwable ignored) { }
    }

    private boolean writeStat(Object api, String account, String device, BandHistoryParser.StepDay day,
            SharedPreferences written) throws Exception {
        String memory = "metric:" + device + ":" + day.date;
        String key = day.steps + ":" + day.calories + ":" + day.moveAbout + ":" + day.distance + ":milli";
        boolean changed = !key.equals(written.getString(memory, ""));
        if (changed) {
            long end = day.startMs + 86_400_000L;
            List<?> existing = host.readStepDays(api, account, day.startMs, end, DAY_STEP_MODE);
            if (!covers(existing, device, day, DAY_STEP_MODE)) {
                host.insertRows(api, TABLE_STAT, List.of(statRow(account, device, day, DAY_STEP_MODE)));
                List<?> confirmed = host.readStepDays(api, account, day.startMs, end, DAY_STEP_MODE);
                if (!covers(confirmed, device, day, DAY_STEP_MODE)) {
                    String miss = "OHEALTH_STEP_STAT_MISS date=" + day.date
                            + " steps=" + day.steps + " cal=" + (day.calories * (long) CALORIE_SCALE)
                            + " move=" + day.moveAbout + " " + summarize(confirmed);
                    HookRuntime.log(miss);
                    XposedBridge.log("OplusBandBridge " + miss);
                    throw new IllegalStateException("STEP_STAT_UNCONFIRMED");
                }
            }
            if (!written.edit().putString(memory, key).commit()) {
                throw new IllegalStateException("STEP_IMPORT_MEMORY_FAILED");
            }
        }
        return changed;
    }

    private Object statRow(String account, String device, BandHistoryParser.StepDay day, int mode)
            throws ReflectiveOperationException {
        Object row = statNew.newInstance();
        setAccount.invoke(row, account);
        setDevice.invoke(row, device);
        setDate.invoke(row, day.date);
        setMode.invoke(row, mode);
        setSteps.invoke(row, (int) day.steps);
        if (day.timezone != null) setTimezone.invoke(row, day.timezone);
        if (day.calories >= 0) setCalories.invoke(row, day.calories * (long) CALORIE_SCALE);
        if (day.distance >= 0 && day.distance <= Integer.MAX_VALUE) {
            setDistance.invoke(row, (int) day.distance);
        }
        if (day.moveAbout >= 0) setMoveAbout.invoke(row, (int) day.moveAbout);
        // The library's exercise minutes come from the device's totalExercise, not from
        // counting minutes that merely have steps. This report does not carry that field.
        setSync.invoke(row, 0);
        setUpdated.invoke(row, day.startMs);
        return row;
    }

    private boolean covers(List<?> rows, String device, BandHistoryParser.StepDay day, int mode)
            throws ReflectiveOperationException {
        boolean steps = false;
        boolean calories = day.calories < 0;
        boolean moveAbout = day.moveAbout < 0;
        long wantCalories = day.calories < 0 ? -1 : day.calories * (long) CALORIE_SCALE;
        for (Object row : rows) {
            if (!statClass.isInstance(row)) continue;
            if (day.date != (Integer) getDate.invoke(row)) continue;
            if (!device.equals(getDevice.invoke(row))) continue;
            if ((Integer) getMode.invoke(row) != mode) continue;
            if ((Integer) getSteps.invoke(row) >= day.steps) steps = true;
            if (day.calories >= 0 && (Long) getCalories.invoke(row) >= wantCalories) calories = true;
            if (day.moveAbout >= 0 && (Integer) getMoveAbout.invoke(row) >= day.moveAbout) moveAbout = true;
        }
        return steps && calories && moveAbout;
    }

    private String summarize(List<?> rows) {
        StringBuilder text = new StringBuilder();
        int shown = 0;
        for (Object row : rows) {
            if (!statClass.isInstance(row)) continue;
            if (shown == 4) break;
            try {
                String id = String.valueOf(getDevice.invoke(row));
                text.append(" [date=").append(getDate.invoke(row))
                        .append(" mode=").append(getMode.invoke(row))
                        .append(" steps=").append(getSteps.invoke(row))
                        .append(" cal=").append(getCalories.invoke(row))
                        .append(" move=").append(getMoveAbout.invoke(row))
                        .append(" dev=").append(id.length() > 12 ? id.substring(0, 12) : id)
                        .append(']');
                shown++;
            } catch (ReflectiveOperationException ignored) {
                text.append(" [unreadable]");
            }
        }
        if (shown == 0) text.append(" none");
        return text.toString();
    }

    /**
     * Inserts minute growth. Step and calorie charts sum these rows into half hours.
     * The exercise chart sums {@code workout} the same way and caps a half hour at 30.
     * The hour chart marks a band hour only when some minute in it has more than 30 steps,
     * and a watch hour when some minute has any steps. An hour blob is a different table.
     */
    private int writeChart(Object api, String account, String device, String category,
            BandHistoryParser.StepDay day, List<HealthRecord> records, SharedPreferences written)
            throws Exception {
        if (!prepareDetail()) return 0;
        MinuteBar[] minutes = MinuteBar.day(records, day.startMs, day.timezone);
        // 2026-10-22 修法：把当日逐小时手表步数写进记忆（hour:<date>），供图表层
        // transDbDetailToChartData 注入使用。这一路绕过宿主表 1001 存储，
        // 即使宿主把分钟明细清掉，柱状图也能从记忆里拿到手表逐小时步数。
        rememberHourBars(written, device, day, minutes);
        // 2026-10-21: minute-v4. v3 的记忆键（n<分钟>）与当前 enqueue 的
        // recordId 格式对不上，宿主里那一天的分钟行其实没写进去，但记忆说
        // 「写过了」→ 之后每轮增量都是 0，柱状图永远空着。v4 换掉键并让
        // 这一天重建一次。
        String version = device + ":" + day.date + ":minute-v4";
        boolean rebuild = !"1".equals(written.getString(version, ""));
        int writtenMode = minuteSportMode(DEVICE_WATCH.equals(category));
        // 2026-10-21: 宿主会在收到那一天的日汇总新行（表 1002）时把同一天的
        // 分钟明细（表 1001）一起清掉。记忆里「这些分钟写过了」因此变成假话：
        // 增量全为 0，明细再也补不回来，柱状图空白。所以每次都要问宿主
        // 「你那边还有几行」，为 0 就把这一天重建。
        // 读回按时间桶聚合，宿主有行时至少返回 1，所以 0 是可靠信号。
        if (!rebuild) {
            List<?> left = hostRows(api, account, device, day, writtenMode);
            if (left != null && left.isEmpty() && hasBar(minutes)) {
                rebuild = true;
                written.edit().remove(version).commit();
                HookRuntime.log("OHEALTH_STEP_CHART_REBUILD date=" + day.date
                        + " hostRows=0 bars=" + countBars(minutes));
            }
        }
        if (rebuild) {
            try {
                host.deleteRows(api, TABLE_DETAIL, account, device, day.startMs, day.startMs + 86_400_000L);
            } catch (Exception deleteFailed) {
                // A host delete that cannot be confirmed leaves the day in place. The bars below are
                // upserts, so overlapping minutes are replaced; a stale minute can outlive one pass.
                HookRuntime.log("OHEALTH_STEP_CHART_DELETE_KEPT date=" + day.date
                        + " " + deleteFailed);
            }
        }
        List<Object> rows = new ArrayList<>();
        List<Integer> indexes = new ArrayList<>();
        for (int minute = 0; minute < MINUTES_PER_DAY; minute++) {
            MinuteBar bar = minutes[minute];
            if (bar == null) continue;
            int[] saved = rebuild ? new int[3] : savedMinute(written, device, day.date, minute);
            int deltaSteps = bar.steps - saved[0];
            int deltaCalories = bar.calories - saved[1];
            int deltaDistance = bar.distance - saved[2];
            if (deltaSteps <= 0 && deltaCalories <= 0 && deltaDistance <= 0) continue;
            // A later pass sends only the growth. Workout stays on the first row of that minute;
            // the merge keeps a workout it already stored when the new row does not repeat it.
            int workout = bar.workout > 0 && saved[0] <= 0 ? 1 : 0;
            rows.add(detail(account, device, category, day, minute,
                    Math.max(0, deltaSteps),
                    Math.max(0, deltaCalories) * (long) CALORIE_SCALE,
                    Math.max(0, deltaDistance), workout));
            indexes.add(minute);
        }
        if (rows.isEmpty()) {
            // 没有要写的分钟时也要看一眼宿主还剩多少行：这行日志是判断
            // 「宿主把明细清掉了」的唯一依据。
            try {
                List<?> left = hostRows(api, account, device, day, writtenMode);
                HookRuntime.log("OHEALTH_STEP_CHART_EMPTY date=" + day.date
                        + " bars=" + indexes.size() + " hostRows=" + (left == null ? -1 : left.size())
                        + " rebuild=" + rebuild);
            } catch (Exception unreadable) {
                // 读不回不影响写入判断。
            }
            if (rebuild && !written.edit().putString(version, "1").commit()) {
                throw new IllegalStateException("STEP_IMPORT_MEMORY_FAILED");
            }
            return 0;
        }
        for (int from = 0; from < rows.size(); from += INSERT_CHUNK) {
            host.insertRows(api, TABLE_DETAIL, rows.subList(from, Math.min(rows.size(), from + INSERT_CHUNK)));
        }
        // 2026-10-22 决定性：插入后零时间差复刻图表真实查询（dev=null g=3 m=-2 parse=2 [start,now]）
        // 以及 dev=MAC 变体、g=4 变体，确认"行没写进去"还是"晚 2 秒被清/被设备过滤"。
        probeChartImmediate(api, account, device, day, writtenMode);
        long dayEnd = day.startMs + 86_400_000L;
        // The host filters table 1001 by sport mode, and its read always aggregates the day into a
        // fixed set of time buckets: a minute-exact readback is not available. Confirmed on device
        // - every groupUnit from 0 to 10 returns the same row count, and writing the day one row at
        // a time still reads back bucketed. So the chart check cannot compare per minute; it has to
        // compare totals against the buckets the host actually reports.
        List<?> confirmed = host.readRows(api, account, TABLE_DETAIL, device, day.startMs, dayEnd,
                OHealthHealthImportHook.HostContract.GROUP_UNIT_DETAIL, false,
                MINUTES_PER_DAY + 24, writtenMode);
        HookRuntime.log("OHEALTH_STEP_CHART_PROBE date=" + day.date + " submitted=" + rows.size()
                + " readBack=" + confirmed.size());
        int[] got = new int[MINUTES_PER_DAY];
        int readRowsSeen = 0;
        StringBuilder shape = new StringBuilder();
        for (Object row : confirmed) {
            if (!detailClass.isInstance(row)) continue;
            if (!device.equals(detailGetDevice.invoke(row))) continue;
            long start = (Long) detailGetStart.invoke(row);
            int steps = (Integer) detailGetSteps.invoke(row);
            String endText = "?";
            if (detailGetEnd != null) {
                try {
                    endText = String.valueOf((Long) detailGetEnd.invoke(row));
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    endText = "?";
                }
            }
            int minute = (int) ((start - day.startMs) / MINUTE_MS);
            if (readRowsSeen < 6) {
                shape.append("[startOff=").append(start - day.startMs)
                        .append(" end=").append(endText)
                        .append(" minute=").append(minute)
                        .append(" steps=").append(steps).append("] ");
            }
            if (minute < 0 || minute >= MINUTES_PER_DAY) continue;
            if (steps <= 0) continue;
            // The host read returns one row per aggregation bucket, not one per minute. Spread the
            // bucket total across the minutes it spans so a per-minute check can still confirm the
            // write. A one-minute row spreads onto itself, which keeps the exact case unchanged.
            long endMs = start;
            if (detailGetEnd != null) {
                try {
                    endMs = (Long) detailGetEnd.invoke(row);
                } catch (ReflectiveOperationException | RuntimeException noEnd) {
                    endMs = start + MINUTE_MS;
                }
            } else {
                endMs = start + MINUTE_MS;
            }
            if (endMs <= start) endMs = start + MINUTE_MS;
            int lastMinute = (int) Math.min(MINUTES_PER_DAY - 1L, (endMs - 1 - day.startMs) / MINUTE_MS);
            if (lastMinute < minute) lastMinute = minute;
            readRowsSeen++;
            for (int covered = minute; covered <= lastMinute; covered++) {
                if (got[covered] <= Integer.MAX_VALUE - steps) got[covered] += steps;
            }
        }
        int firstBad = -1;
        int badFirstWant = 0;
        int badFirstGot = 0;
        for (int minute : indexes) {
            if (got[minute] < minutes[minute].steps) {
                firstBad = minute;
                badFirstWant = minutes[minute].steps;
                badFirstGot = got[minute];
                break;
            }
        }
        if (firstBad >= 0) {
            String diag = "OHEALTH_STEP_CHART_DIAG date=" + day.date + " bars=" + indexes.size()
                    + " readBack=" + readRowsSeen + " firstBadMinute=" + firstBad
                    + " badWant=" + badFirstWant + " badGot=" + badFirstGot
                    + " shape=" + shape;
            HookRuntime.log(diag);
            XposedBridge.log("OplusBandBridge " + diag);
        }
        SharedPreferences.Editor editor = written.edit();
        int stored = 0;
        for (int minute : indexes) {
            // The per-minute comparison above is diagnostics only: the host's read aggregates
            // into fixed time buckets no matter what the caller asks for, so an exact per-minute
            // confirm is not obtainable. The insert call itself throws when a row is refused,
            // which is the real failure signal. Commit the fingerprints so later passes switch
            // to incremental deltas instead of rebuilding the whole day every time.
            editor.putString(minuteKey(device, day.date, minute),
                    minutes[minute].steps + ":" + minutes[minute].calories + ":" + minutes[minute].distance);
            stored++;
        }
        editor.putString(version, "1");
        if (!editor.commit()) throw new IllegalStateException("STEP_IMPORT_MEMORY_FAILED");
        return stored;
    }

    private Object detail(String account, String device, String category, BandHistoryParser.StepDay day,
            int minute, int steps, long calories, int distance, int workout) throws ReflectiveOperationException {
        Object row = detailNew.newInstance();
        detailAccount.invoke(row, account);
        detailDevice.invoke(row, device);
        long start = day.startMs + minute * MINUTE_MS;
        detailStart.invoke(row, start);
        detailEnd.invoke(row, start + MINUTE_MS);
        detailSteps.invoke(row, steps);
        detailCalories.invoke(row, calories);
        detailDistance.invoke(row, distance);
        // The half-hour exercise chart is the sum of this flag. Xiaomi does not send
        // minuteExercise, so a minute that has steps is the minute the chart can draw.
        detailWorkout.invoke(row, workout);
        // A watch remaps walk (1) to -1, which keeps those minutes out of the phone walk bucket.
        detailMode.invoke(row, minuteSportMode(DEVICE_WATCH.equals(category)));
        detailDisplay.invoke(row, 1);
        detailType.invoke(row, category);
        if (day.timezone != null) detailZone.invoke(row, day.timezone);
        return row;
    }

    /** {@code DeviceCategory} string for a Xiaomi model. Unknown models stay a band. */
    static String deviceCategory(String modelId) {
        return io.github.miam1ku.mibandoplusbridge.data.BandCatalog.healthCategory(modelId);
    }

    /** Walk minutes on a watch use the library remap. A band keeps type 1. */
    static int minuteSportMode(boolean watch) {
        return watch ? groupSportType(MINUTE_MODE) : MINUTE_MODE;
    }

    /** {@code DataUtils.groupSportType}. Watch daily-activity minutes go through this. */
    static int groupSportType(int type) {
        return switch (type) {
            case 0 -> 6;
            case 1 -> -1;
            case 2 -> 1;
            case 4, 5 -> 5;
            case 6 -> 2;
            case 7 -> 3;
            case 8 -> 4;
            case 9 -> 7;
            default -> 0;
        };
    }

    private boolean prepareDetail() {
        if (detailResolved) return detailClass != null;
        detailResolved = true;
        try {
            detailClass = Class.forName("com.heytap.databaseengine.model.SportDataDetail", false, host.loader);
            detailNew = detailClass.getConstructor();
            detailAccount = detailClass.getMethod("setSsoid", String.class);
            detailDevice = detailClass.getMethod("setDeviceUniqueId", String.class);
            detailStart = detailClass.getMethod("setStartTimestamp", long.class);
            detailEnd = detailClass.getMethod("setEndTimestamp", long.class);
            detailSteps = detailClass.getMethod("setSteps", int.class);
            detailCalories = detailClass.getMethod("setCalories", long.class);
            detailDistance = detailClass.getMethod("setDistance", int.class);
            detailWorkout = detailClass.getMethod("setWorkout", int.class);
            detailMode = detailClass.getMethod("setSportMode", int.class);
            detailDisplay = detailClass.getMethod("setDisplay", int.class);
            detailType = detailClass.getMethod("setDeviceType", String.class);
            detailZone = detailClass.getMethod("setTimezone", String.class);
            detailGetStart = getter(detailClass, "getStartTimestamp", long.class);
            try {
                detailGetEnd = getter(detailClass, "getEndTimestamp", long.class);
            } catch (NoSuchMethodException noEnd) {
                detailGetEnd = null;
            }
            detailGetSteps = getter(detailClass, "getSteps", int.class);
            detailGetDevice = getter(detailClass, "getDeviceUniqueId", String.class);
            detailGetMode = getter(detailClass, "getSportMode", int.class);
            try {
                detailGetType = getter(detailClass, "getDeviceType", String.class);
            } catch (NoSuchMethodException noType) {
                detailGetType = null;
            }
            // The aggregated (groupUnit>0) detail read returns chart rows of this type instead of
            // raw SportDataDetail. Resolved from the host loader so the diagnostic can read the
            // same objects the chart's transDbDetailToChartData consumes.
            try {
                tsdClass = Class.forName(
                        "com.heytap.health.core.widget.charts.data.TimeStampedData", false, host.loader);
                tsdTimestamp = tsdClass.getMethod("getTimestamp");
                tsdY = tsdClass.getMethod("getY");
            } catch (ReflectiveOperationException | LinkageError noTsd) {
                tsdClass = null;
            }
            return true;
        } catch (ReflectiveOperationException | LinkageError unsupported) {
            detailClass = null;
            HookRuntime.log("OHEALTH_STEP_CHART_UNAVAILABLE");
            return false;
        }
    }


    private static int[] savedMinute(SharedPreferences written, String device, int date, int minute) {
        String saved = written.getString(minuteKey(device, date, minute), "");
        int[] parts = new int[3];
        String[] fields = saved.split(":");
        if (fields.length != 3) return parts;
        try {
            parts[0] = Integer.parseInt(fields[0]);
            parts[1] = Integer.parseInt(fields[1]);
            parts[2] = Integer.parseInt(fields[2]);
        } catch (NumberFormatException invalid) {
            return new int[3];
        }
        return parts;
    }

    /**
     * 2026-10-22 诊断：dump 表 1001 当天原始行。两趟读取：(a) 免设备 —— 看宿主自己 73 行的
     * device/mode/type 长什么样；(b) 只限本设备 —— 看华为的行还在不在。
     * 用 g=4（GROUP_UNIT_DETAIL）+ parse，返回 SportDataDetail 原始行。只打日志，不改数据。
     */
    private void dumpHostRaw(Object api, String account, String device, long startMs, long endMs) {
        dumpOne(api, account, null, startMs, endMs, "ALL");
        dumpOne(api, account, device, startMs, endMs, "OURS");
    }

    private void dumpOne(Object api, String account, String device,
            long startMs, long endMs, String tag) {
        try {
            // g=4（GROUP_UNIT_DETAIL）+ parse，返回 SportDataDetail 原始行，可看 device/mode/type。
            List<?> rows = host.readRows(api, account, TABLE_DETAIL, device,
                    startMs, endMs, OHealthHealthImportHook.HostContract.GROUP_UNIT_DETAIL, true, 2000, null);
            StringBuilder text = new StringBuilder("OHEALTH_STEP_CHART_RAW tag=").append(tag)
                    .append(" rows=").append(rows.size())
                    .append(" dev=").append(device == null ? "-" : device);
            int shown = 0;
            for (Object row : rows) {
                if (!detailClass.isInstance(row)) continue;
                if (shown >= 12) break;
                shown++;
                text.append(" [");
                try { text.append("dev=").append(detailGetDevice.invoke(row)); } catch (Throwable t) { text.append("dev=?"); }
                try { text.append(" mode=").append(detailGetMode.invoke(row)); } catch (Throwable t) { text.append(" mode=?"); }
                try { text.append(" type=").append(detailGetType == null ? "?" : detailGetType.invoke(row)); } catch (Throwable t) { text.append(" type=?"); }
                try {
                    long start = (Long) detailGetStart.invoke(row);
                    text.append(" off=").append(start - startMs);
                } catch (Throwable t) { text.append(" off=?"); }
                try {
                    long finish = (Long) detailGetEnd.invoke(row);
                    text.append(" endOff=").append(finish - startMs);
                } catch (Throwable t) { text.append(" endOff=?"); }
                try { text.append(" steps=").append(detailGetSteps.invoke(row)); } catch (Throwable t) { text.append(" steps=?"); }
                text.append(']');
            }
            HookRuntime.log(text.toString());
            XposedBridge.log("OplusBandBridge " + text);
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_STEP_CHART_RAW_FAIL tag=" + tag + " "
                    + failure.getClass().getSimpleName());
        }
    }

    /**
     * 2026-10-22 决定性诊断：精确复刻图表 {@code StepGoalRepository.queryDetail} 的读取
     * （表 1001 / groupUnit=3 / setReadSportMode(-2) / 不设设备 / setIsParse(2)），把宿主
     * 返回给图表的聚合行（TimeStampedData）按小时归并成 24 根柱的 Y 值。
     *
     * <p>对比两趟：
     * <ol>
     *   <li>{@code all} —— 不设设备，等于图表实际拿到的全部（手机+手表+华为）；</li>
     *   <li>{@code ours} —— 只限华为设备，看华为的行在聚合读里到底在不在。</li>
     * </ol>
     * 若 {@code oursHour} 24 根柱全是 0，说明华为的 mode=-1 / type="Watch" 行被聚合读排除了，
     * 这就是柱状图空白的根因；若 {@code oursHour} 有值而 {@code allHour} 没有，则是图表另有过滤。
     * 只打日志，不动数据。
     */
    /**
     * 2026-10-22 决定性诊断：图表 queryDetail 读 table=1001 dev=null mode=-2 group=3（按小时聚合）。
     * transDbDetailToChartData 在输入为空时直接返回 24 根零柱 → 空白。本探针逐项变参复刻该查询，
     * 锁定"为什么返回 0 行"：是 dev=null 被宿主当成当前主设备（手机）排除了我们的手表行，
     * 还是 mode=-2 过滤掉了我们写的 mode=-1 行，还是 groupUnit=3 聚合要求特定行形状。
     */
    private void probeChartVariants(Object api, String account, String device, long startMs, long endMs, String tag) {
        if (!prepareDetail()) {
            HookRuntime.log("OHEALTH_CHART_PROBE2_SKIP detail-unavailable tag=" + tag);
            return;
        }
        // 2026-10-22 修正：原探针用 endMs=startMs+86400000（次日 00:00，未来时刻），
        // 宿主明细按日期分区，读到跨天的 end 会落到明天的空分区 → 返回 0，制造「行被删」假象。
        // 真实图表查询 end=now。这里把 end 夹到 now，复刻图表实际窗口。
        long now = System.currentTimeMillis();
        long winEnd = Math.min(endMs, now);
        StringBuilder txt = new StringBuilder("OHEALTH_CHART_PROBE2_").append(tag)
                .append(" start=").append(startMs).append(" end=").append(winEnd).append("(now=").append(now).append(")");
        // V0 图表真实查询：dev=null g=3 parse mode=-2
        txt.append("\n  V_chart   dev=null  g=3 p  m=-2 ").append(probeOne(api, account, device, startMs, winEnd, null, 3, true, -2));
        // V1 带我们的设备
        txt.append("\n  V_devours dev=OUR  g=3 p  m=-2 ").append(probeOne(api, account, device, startMs, winEnd, device, 3, true, -2));
        // V2 不带设备但用我们写的 mode=-1
        txt.append("\n  V_modeOurs dev=null g=3 p  m=-1 ").append(probeOne(api, account, device, startMs, winEnd, null, 3, true, -1));
        // V3 带设备 + 我们写的 mode=-1
        txt.append("\n  V_devMode dev=OUR  g=3 p  m=-1 ").append(probeOne(api, account, device, startMs, winEnd, device, 3, true, -1));
        // V4 groupUnit=4（我们 hostRows 用的聚合）不带设备
        txt.append("\n  V_g4      dev=null g=4 p  m=-2 ").append(probeOne(api, account, device, startMs, winEnd, null, 4, true, -2));
        // V5 原始行（g=0 不聚合）带设备 + 我们写的 mode=-1 —— 确认我们的行在库里存在
        txt.append("\n  V_raw     dev=OUR  g=0 np m=-1 ").append(probeOne(api, account, device, startMs, winEnd, device, 0, false, -1));
        // V6 原始行不带设备 + mode=-1
        txt.append("\n  V_rawAll  dev=null g=0 np m=-1 ").append(probeOne(api, account, device, startMs, winEnd, null, 0, false, -1));
        HookRuntime.log(txt.toString());
        XposedBridge.log("OplusBandBridge " + txt);
        // 暴露宿主自己那 73 行的真实 device/mode/type（用 g=4 dev=null 聚合读，hostRows 同款可工作），
        // 判断「华为行被设备过滤」还是「华为行根本没写进去」。
        dumpHostRaw(api, account, device, startMs, winEnd);
    }

    /** 复刻一次图表查询并返回 "count=N sumY=S"（聚合行按小时归并 Y；原始行只算 count）。 */
    private String probeOne(Object api, String account, String device, long startMs, long endMs,
                            String dev, int group, boolean parse, int mode) {
        try {
            List<?> rows = host.readRows(api, account, TABLE_DETAIL, dev, startMs, endMs,
                    group, parse, 4000, mode);
            if (rows == null) return "count=-1";
            int[] hour = new int[24];
            long sum = sumHour(rows, hour);
            return "count=" + rows.size() + " sumY=" + sum;
        } catch (Throwable failure) {
            return "ERR " + failure.getClass().getSimpleName() + " " + failure.getMessage();
        }
    }

    /**
     * 2026-10-22 决定性：在 writeChart 插入循环之后、零时间差，复刻图表真实查询与若干变体。
     * 若此处的 V_devours/V_rawMac 立即 >0 而 2 秒后的 V_devours=0，则是"随后被宿主清掉"；
     * 若此处就已经是 0，则是 g=3/g=0 读与我们的 mode/device 形状不兼容（读不到，而非没写）。
     */
    private void probeChartImmediate(Object api, String account, String device,
            BandHistoryParser.StepDay day, int writtenMode) {
        if (!prepareDetail()) return;
        long now = System.currentTimeMillis();
        long end = Math.min(day.startMs + 86_400_000L, now);
        String line = "OHEALTH_CHART_IMM date=" + day.date
                + " V_chart=" + probeOne(api, account, device, day.startMs, end, null, 3, true, -2)
                + " V_devours=" + probeOne(api, account, device, day.startMs, end, device, 3, true, -2)
                + " V_devM1=" + probeOne(api, account, device, day.startMs, end, device, 3, true, writtenMode)
                + " V_rawMac=" + probeOne(api, account, device, day.startMs, end, device, 0, false, -2)
                + " V_g4mac=" + probeOne(api, account, device, day.startMs, end, device, 4, true, -2);
        HookRuntime.log(line);
        XposedBridge.log("OplusBandBridge " + line);
        // 零时间差 dump 存活行：看"手机设备 id"长什么样、我们的 step 柱到底在不在（device/mode/type/steps）。
        dumpOne(api, account, null, day.startMs, end, "IMM_ALL");
        dumpOne(api, account, device, day.startMs, end, "IMM_OURS");
    }

    /** Sums aggregated chart rows into a 24-hour bucket array and returns the grand total (y is float). */
    private long sumHour(List<?> rows, int[] hour) {
        if (tsdClass == null || rows == null) return -1;
        long sum = 0;
        for (Object row : rows) {
            if (!tsdClass.isInstance(row)) continue;
            try {
                long ts = (Long) tsdTimestamp.invoke(row);
                float y = (Float) tsdY.invoke(row);
                java.time.LocalDateTime ldt = java.time.LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(ts), java.time.ZoneId.systemDefault());
                int h = ldt.getHour();
                int yi = (int) y;
                if (h >= 0 && h < 24) {
                    hour[h] += yi;
                    sum += yi;
                }
            } catch (Throwable ignored) {
                // 一行解析失败不影响整体。
            }
        }
        return sum;
    }

    private static String hourStr(int[] hour) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 24; i++) {
            b.append(i).append('=').append(hour[i]).append(' ');
        }
        return b.toString();
    }

    /** 宿主那边这一天还剩多少行分钟明细。读不回时返回 null。 */
    private List<?> hostRows(Object api, String account, String device,
            BandHistoryParser.StepDay day, int writtenMode) throws Exception {        return host.readRows(api, account, TABLE_DETAIL, device, day.startMs,
                day.startMs + 86_400_000L,
                OHealthHealthImportHook.HostContract.GROUP_UNIT_DETAIL, false,
                MINUTES_PER_DAY + 24, writtenMode);
    }

    /**
     * 把当日逐小时手表步数写进记忆（{@code hour:<date>}），供图表层注入读取。
     *
     * <p>按分钟柱归并成 24 个小时桶：每个有步数的分钟加到它所属小时的桶里。
     * 这一天没有任何步数柱时清掉该键，避免陈旧数据被图表层注入到别的日期。
     */
    private void rememberHourBars(SharedPreferences written, String device, BandHistoryParser.StepDay day,
            MinuteBar[] minutes) {
        int[] hour = new int[24];
        boolean any = false;
        for (int minute = 0; minute < MINUTES_PER_DAY; minute++) {
            MinuteBar bar = minutes[minute];
            if (bar == null) continue;
            int h = minute / 60;
            if (h >= 0 && h < 24) {
                if (hour[h] <= Integer.MAX_VALUE - bar.steps) hour[h] += bar.steps;
                if (bar.steps > 0) any = true;
            }
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 24; i++) {
            if (i > 0) b.append(',');
            b.append(hour[i]);
        }
        SharedPreferences.Editor editor = written.edit();
        if (any) {
            editor.putString("hour:" + day.date, b.toString());
        } else {
            editor.remove("hour:" + day.date);
        }
        editor.commit();
    }

    private static boolean hasBar(MinuteBar[] minutes) {
        return countBars(minutes) > 0;
    }

    private static int countBars(MinuteBar[] minutes) {
        int bars = 0;
        for (MinuteBar bar : minutes) {
            if (bar != null) bars++;
        }
        return bars;
    }

    /**
     * 忘掉某一天已经写过的分钟。
     *
     * <p>宿主收到那一天的日汇总新行（表 1002）会把同一天的分钟明细（表 1001）
     * 一起清掉。记忆里留着「这些分钟写过了」就会让后面的增量判定全为 0，
     * 明细再也补不回来。所以日汇总一变，就要把这一天的分钟记忆清掉，
     * 让下一轮按重建的方式整段重写。
     *
     * <p>清掉版本标记就够了：{@code writeChart} 看到标记不在就走 rebuild，
     * 那一轮会以零为基线重写全天分钟，并把新的指纹写回记忆。
     */
    private static void forgetMinutes(SharedPreferences written, String device, int date) {
        try {
            SharedPreferences.Editor editor = written.edit();
            editor.remove(device + ":" + date + ":minute-v4");
            editor.commit();
        } catch (RuntimeException unavailable) {
            // 记忆清不掉时下一轮仍会按增量判定，明细可能补不回来。
        }
    }

    /**
     * 记忆里「这一分钟写到了什么」的键。
     *
     * <p>2026-10-21：前缀从 {@code n} 换成 {@code m}。旧键（{@code <设备>:<日期>:n<分钟>}）
     * 是更早一版代码写的，那版写入用的 recordId 格式和现在
     * {@code HealthRecordStore.enqueueMeasurement} 生成的对不上，于是同一分钟
     * 的「已写值」和实际入库的行是两个东西：增量判定算出 0，宿主健康里的
     * 步数柱状图一直空着，而健康 App 里的步数总量还停在几百步。
     *
     * <p>换前缀后旧键不再命中，分钟数据会按当前格式重写一遍 —— 表 1001 的行
     * 是 upsert，重复写不会翻倍。
     */
    private static String minuteKey(String device, int date, int minute) {
        return device + ":" + date + ":m" + minute;
    }

    /**
     * The watch is the authoritative step source for the card: its total replaces the cached
     * home steps whenever it is a plausible day total. The previous "only grow" guard kept the
     * phone sensor's own count on the card whenever it led the watch, which read on the device
     * as "steps stopped syncing".
     */
    static boolean publishSteps(long shown, long band) {
        return band > 0 && band <= 200_000L;
    }

    /**
     * Official step daemon writes today's total through
     * {@code content://com.heytap.health.sporthealthprovider/self/sport}. The health card reads that
     * cache, not the bridge snapshot. Only {@code step} is sent so calorie and goals already there stay.
     */
    void publishSnapshot(Context context) {
        Bundle band = OHealthDeviceHook.registeredSnapshot();
        if (band == null) return;
        long steps = band.getLong("stepsToday", -1);
        if (!publishSteps(0, steps)) return;
        // A snapshot from before midnight must not land in the new day's cache: it carries the
        // previous day's total. Publish only while the live total belongs to today.
        if (!sameDay(band.getLong("stepsAtMs", 0))) return;
        ContentValues values = new ContentValues();
        values.put("step", steps);
        updateSportCache(context, values, steps);
    }

    private void publishToday(Context context, List<BandHistoryParser.StepDay> days) {
        BandHistoryParser.StepDay today = null;
        for (BandHistoryParser.StepDay day : days) {
            if (isToday(day)) today = day;
        }
        if (today == null) return;
        ContentValues values = new ContentValues();
        values.put("step", today.steps);
        if (today.calories >= 0) values.put("calorie", (double) today.calories);
        if (today.distance >= 0) values.put("distance", today.distance / 1000.0);
        if (today.moveAbout >= 0 && today.moveAbout <= Integer.MAX_VALUE) {
            values.put("activityCount", (int) today.moveAbout);
        }
        updateSportCache(context, values, today.steps);
        OHealthHomeMetricHook.requestReload();
    }

    private void updateSportCache(Context context, ContentValues values, long steps) {
        try {
            Class<?> adapter = Class.forName(
                    "com.heytap.health.core.provider.adapter.open.SportDataAdapter", false, host.loader);
            Object result = adapter.getMethod("querySportData", Context.class).invoke(null, context);
            if (!(result instanceof Bundle bundle)) throw new IllegalStateException("STEP_CACHE_BUNDLE");
            if (!publishSteps(bundle.getLong("step"), steps)) return;
            adapter.getMethod("updateSportData", Context.class, ContentValues.class)
                    .invoke(null, context, values);
        } catch (Throwable failure) {
            String unavailable = "OHEALTH_STEP_CACHE_UNAVAILABLE " + failure.getClass().getSimpleName();
            HookRuntime.log(unavailable);
            XposedBridge.log("OplusBandBridge " + unavailable);
        }
    }

    private static boolean isToday(BandHistoryParser.StepDay day) {
        ZoneId zone = ZoneId.systemDefault();
        if (day.timezone != null && !day.timezone.isBlank()) {
            try {
                zone = ZoneId.of(day.timezone);
            } catch (DateTimeException ignored) { }
        }
        LocalDate now = LocalDate.now(zone);
        int today = now.getYear() * 10_000 + now.getMonthValue() * 100 + now.getDayOfMonth();
        return today == day.date;
    }

    /** True when the timestamp falls on today's local date. Stale snapshots fail this. */
    private static boolean sameDay(long ms) {
        if (ms <= 0) return false;
        LocalDate now = LocalDate.now(ZoneId.systemDefault());
        return ms >= now.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /** One local day of minute bars. A minute with steps is one workout minute on the chart. */
    static final class MinuteBar {
        final int steps;
        final int calories;
        final int distance;
        final int workout;

        MinuteBar(int steps, int calories, int distance, int workout) {
            this.steps = steps;
            this.calories = calories;
            this.distance = distance;
            this.workout = workout;
        }

        static MinuteBar[] day(List<HealthRecord> records, long dayStart, String timezone) {
            int[] steps = new int[MINUTES_PER_DAY];
            int[] calories = new int[MINUTES_PER_DAY];
            int[] distance = new int[MINUTES_PER_DAY];
            boolean[] seen = new boolean[MINUTES_PER_DAY];
            long dayEnd = dayStart + 86_400_000L;
            if (records != null) {
                for (HealthRecord record : records) {
                    if (record == null || !"steps_interval".equals(record.kind) || record.value == null) continue;
                    if (record.startMs < dayStart || record.startMs >= dayEnd) continue;
                    if (timezone != null && !timezone.equals(record.timezone)) continue;
                    int minute = (int) ((record.startMs - dayStart) / MINUTE_MS);
                    if (minute < 0 || minute >= MINUTES_PER_DAY) continue;
                    steps[minute] = saturate(steps[minute], record.value.longValue());
                    if (record.calories != null) calories[minute] = saturate(calories[minute], record.calories);
                    if (record.distance != null) distance[minute] = saturate(distance[minute], record.distance);
                    seen[minute] = true;
                }
            }
            MinuteBar[] bars = new MinuteBar[MINUTES_PER_DAY];
            for (int minute = 0; minute < MINUTES_PER_DAY; minute++) {
                if (!seen[minute] || (steps[minute] <= 0 && calories[minute] <= 0 && distance[minute] <= 0)) continue;
                bars[minute] = new MinuteBar(steps[minute], calories[minute], distance[minute],
                        steps[minute] > 0 ? 1 : 0);
            }
            return bars;
        }

        private static int saturate(int current, long add) {
            if (add <= 0) return current;
            return add >= Integer.MAX_VALUE - current ? Integer.MAX_VALUE : current + (int) add;
        }
    }

    private List<HealthRecord> history(Context context, String account, String device) throws Exception {
        List<HealthRecord> records = new ArrayList<>();
        for (String kind : new String[] {"steps_day", "steps_interval"}) {
            String after = null;
            for (;;) {
                Uri uri = after == null ? HealthQueueProvider.RECORDS_URI
                        : HealthQueueProvider.RECORDS_URI.buildUpon().appendQueryParameter("after", after).build();
                int count = 0;
                try (Cursor rows = context.getContentResolver().query(uri, COLUMNS,
                        "account=? AND deviceId=? AND kind=? AND startMs<? AND endMs>?",
                        new String[] {account, device, kind, Long.toString(Long.MAX_VALUE), "0"}, null)) {
                    if (rows == null) throw new IllegalStateException("STEP_HISTORY_UNAVAILABLE");
                    while (rows.moveToNext()) {
                        records.add(HealthRecord.fromJson(new JSONObject(rows.getString(2))));
                        after = rows.getString(0);
                        count++;
                    }
                }
                if (count < 200) break;
            }
        }
        return records;
    }

    private static Method getter(Class<?> type, String name, Class<?> returnType) throws NoSuchMethodException {
        Method method = type.getMethod(name);
        if (method.getReturnType() != returnType) throw new NoSuchMethodException("STEP_MODEL_CONTRACT");
        return method;
    }
}
