// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.content.Context;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

import io.github.miam1ku.mibandoplusbridge.hook.xposed.HookRuntime;
import io.github.miam1ku.mibandoplusbridge.integration.HealthQueueProvider;

/**
 * 删除宿主睡眠表里本设备的一次性窗口。
 *
 * <p>存在的原因：睡眠分期握手的语义是反推出来的，早先版本把 GT3 不戴表时产生的
 * 分钟级碎片当成小睡写进了宿主库（表 1010 分段 + 表 1011 夜间统计）。队列里的
 * 记录还能 purge，宿主库里的却没有接口 —— 因为 {@code delete} 对表 1010 恒返回 0
 * 且删不掉东西（见 {@code EntryPoint.installSleepRowDelete}）。
 *
 * <p>所以这里直接拿WritableDatabase 删，两张表按 device_unique_id 限定，且不猜测
 * 表名与列名：统计表的表名靠 sqlite_master 找，列靠 PRAGMA table_info 找。
 *
 * <p>触发方式：桥接侧把意向写进 {@code no_backup/huawei-sleep-cleanup.json}，
 * 宿主进程下一次 hooks 安装时经 HealthQueueProvider 一次性取走，读到即删。
 */
public final class OHealthSleepCleanup {

    private OHealthSleepCleanup() {}

    /** @return 删除的行数，没有意向时为 -1。 */
    public static int run(Context context, String device) {
        if (context == null || device == null || device.isBlank()) return -1;
        Bundle result;
        try {
            result = context.getContentResolver().call(HealthQueueProvider.RECORDS_URI,
                    "sleepCleanup", null, new Bundle());
        } catch (SecurityException unauthorized) {
            HookRuntime.log("OHEALTH_SLEEP_CLEANUP_BLOCKED " + unauthorized.getMessage());
            return -1;
        } catch (RuntimeException unavailable) {
            HookRuntime.log("OHEALTH_SLEEP_CLEANUP_CALL_FAILED " + unavailable);
            return -1;
        }
        if (result == null) {
            HookRuntime.log("OHEALTH_SLEEP_CLEANUP_NO_RESULT");
            return -1;
        }
        String body = result.getString("windows");
        if (body == null || body.isBlank()) {
            HookRuntime.log("OHEALTH_SLEEP_CLEANUP_IDLE state=" + result.getString("state"));
            return -1;
        }
        List<long[]> windows = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(body);
            for (int i = 0; i < array.length(); i++) {
                JSONObject window = array.getJSONObject(i);
                long start = window.getLong("startMs");
                long end = window.getLong("endMs");
                if (end > start) windows.add(new long[] {start, end});
            }
        } catch (org.json.JSONException malformed) {
            HookRuntime.log("OHEALTH_SLEEP_CLEANUP_PLAN_INVALID " + malformed.getMessage());
            return -1;
        }
        if (windows.isEmpty()) return -1;
        Object sqlite;
        try {
            Class<?> dbClass = Class.forName(
                    "com.heytap.databaseengineservice.db.AppDatabase", false,
                    context.getClassLoader());
            Object database = dbClass.getMethod("getInstance", Context.class).invoke(null, context);
            Object helper = database.getClass().getMethod("getOpenHelper").invoke(database);
            sqlite = helper.getClass().getMethod("getWritableDatabase").invoke(helper);
        } catch (Exception | LinkageError unavailable) {
            HookRuntime.log("OHEALTH_SLEEP_CLEANUP_DB_UNAVAILABLE " + unavailable);
            return -1;
        }
        int rows = 0;
        StringBuilder tables = new StringBuilder();
        for (String table : sleepTables(sqlite)) {
            List<String> columns = tableColumns(sqlite, table);
            if (!columns.contains("device_unique_id")) continue;
            String time = firstOf(columns, "start_time", "start_timestamp", "start");
            for (long[] window : windows) {
                int removed;
                if (time != null) {
                    removed = exec(sqlite, "DELETE FROM " + table
                            + " WHERE device_unique_id = ? AND " + time + " >= ? AND " + time + " <= ?",
                            new Object[] {device, window[0], window[1]});
                } else {
                    removed = dateDelete(sqlite, table, columns, device, window);
                }
                rows += removed;
                if (removed > 0) tables.append(' ').append(table).append('=').append(removed);
            }
        }
        HookRuntime.log(String.format(Locale.ROOT,
                "OHEALTH_SLEEP_CLEANUP windows=%d rows=%d%s",
                windows.size(), rows, tables.length() == 0 ? " none" : tables.toString()));
        acknowledge(context);
        return rows;
    }

    private static int dateDelete(Object sqlite, String table, List<String> columns, String device,
            long[] window) {
        String column = firstOf(columns, "date", "day");
        if (column == null) return 0;
        int rows = 0;
        for (int date : dates(window)) {
            rows += exec(sqlite, "DELETE FROM " + table
                    + " WHERE device_unique_id = ? AND " + column + " = ?",
                    new Object[] {device, date});
        }
        return rows;
    }

    /** 统计类睡眠表：名字里带 Sleep 的表。 */
    private static List<String> sleepTables(Object sqlite) {
        List<String> tables = new ArrayList<>();
        try (android.database.Cursor rows = query(sqlite,
                "SELECT name FROM sqlite_master WHERE type='table'", null)) {
            while (rows.moveToNext()) {
                String name = rows.getString(0);
                if (name != null && name.toLowerCase(Locale.ROOT).contains("sleep")
                        && !name.startsWith("sqlite_")) {
                    tables.add(name);
                }
            }
        } catch (Throwable ignored) {
            return tables;
        }
        return tables;
    }

    private static List<String> tableColumns(Object sqlite, String table) {
        List<String> columns = new ArrayList<>();
        try (android.database.Cursor rows = query(sqlite, "PRAGMA table_info(" + table + ")", null)) {
            while (rows.moveToNext()) {
                String name = rows.getString(1);
                if (name != null) columns.add(name);
            }
        } catch (Throwable ignored) {
            return columns;
        }
        return columns;
    }

    private static List<Integer> dates(long[] window) {
        List<Integer> dates = new ArrayList<>();
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        java.time.LocalDate day = java.time.Instant.ofEpochMilli(window[0]).atZone(zone).toLocalDate();
        java.time.LocalDate last = java.time.Instant.ofEpochMilli(window[1]).atZone(zone).toLocalDate();
        while (!day.isAfter(last)) {
            dates.add(day.getYear() * 10000 + day.getMonthValue() * 100 + day.getDayOfMonth());
            day = day.plusDays(1);
        }
        return dates;
    }

    private static String firstOf(List<String> columns, String... candidates) {
        for (String candidate : candidates) {
            if (columns.contains(candidate)) return candidate;
        }
        return null;
    }

    /** 执行完才回执，避免意向被吃掉却没干活。 */
    private static void acknowledge(Context context) {
        try {
            context.getContentResolver().call(HealthQueueProvider.RECORDS_URI,
                    "sleepCleanupAck", null, new Bundle());
        } catch (RuntimeException ignored) {
            // 回执失败无所谓：下一个宿主进程还会再来一遍，删除本身是幂等的。
        }
    }

    /**
     * 先数再删 —— execSQL 没有返回值，想统计只能自己 COUNT。
     * @return 实际删掉的行数（按删除前的 COUNT 计）。
     */
    private static int exec(Object sqlite, String sql, Object[] args) {
        String count = "SELECT COUNT(*) FROM (" + sql.replaceFirst("^DELETE FROM", "SELECT * FROM") + ")";
        int before = 0;
        try (android.database.Cursor rows = query(sqlite, count, strings(args))) {
            if (rows.moveToFirst()) before = rows.getInt(0);
        } catch (Exception unreadable) {
            return 0;
        }
        if (before == 0) return 0;
        try {
            sqlite.getClass().getMethod("execSQL", String.class, Object[].class)
                    .invoke(sqlite, sql, args);
        } catch (Exception failure) {
            return 0;
        }
        return before;
    }

    private static String[] strings(Object[] args) {
        String[] out = new String[args.length];
        for (int i = 0; i < args.length; i++) out[i] = String.valueOf(args[i]);
        return out;
    }

    private static android.database.Cursor query(Object sqlite, String sql, String[] args)
            throws Exception {
        return (android.database.Cursor) sqlite.getClass()
                .getMethod("rawQuery", String.class, String[].class)
                .invoke(sqlite, sql, args);
    }
}
