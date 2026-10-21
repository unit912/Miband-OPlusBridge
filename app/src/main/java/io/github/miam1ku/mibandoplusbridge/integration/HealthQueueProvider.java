// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.integration;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.Process;
import android.os.UserManager;
import io.github.miam1ku.mibandoplusbridge.HostIdentity;
import io.github.miam1ku.mibandoplusbridge.protocol.BandHistoryParser.Measurement;
import io.github.miam1ku.mibandoplusbridge.data.HealthRecordStore;
import org.json.JSONObject;

/** Pending writes in credential-encrypted storage. Callers are checked by package name and read only the confirmed account. */
public final class HealthQueueProvider extends ContentProvider {
    public static final String AUTHORITY = "io.github.miam1ku.mibandoplusbridge.health";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/pending");
    public static final Uri RECORDS_URI = Uri.parse("content://" + AUTHORITY + "/records");
    private static final String HOST = "com.heytap.health";
    private static final String[] COLUMNS = {"recordId", "revision", "record"};
    private HealthRecordStore store;
    private String proposedFingerprint;
    private long proposedAtMs;

    /**
     * 归档写完后通知宿主立即导入。
     *
     * <p>健康进程里的导入 hook 靠 {@code ContentObserver} 盯着本 provider 的
     * 两个 URI。归档走的 {@code HealthRecordStore.enqueueMeasurement} 是直接写
     * SQLite 的，不经过 provider 的 {@code call()}，所以那一路不会发通知 ——
     * 结果是分钟数据进了库却没人来读，宿主健康里的步数一直停在上一轮。
     *
     * <p>这里补一次通知。批量归档只发一次，不按条发。
     */
    public static void notifyArchived(android.content.Context context) {
        if (context == null) return;
        try {
            context.getContentResolver().notifyChange(URI, null);
            context.getContentResolver().notifyChange(RECORDS_URI, null);
        } catch (RuntimeException unavailable) {
            // 通知失败不影响数据本身：下一次宿主启动时仍会导入。
        }
    }

    @Override public boolean onCreate() {
        store = new HealthRecordStore(getContext());
        return true;
    }

    private void requireUnlocked() {
        if (!getContext().getSystemService(UserManager.class).isUserUnlocked()) {
            throw new SecurityException("HEALTH_USER_LOCKED");
        }
    }

    private boolean requireCaller() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return true;
        if (!io.github.miam1ku.mibandoplusbridge.HostIdentity.uidHas(getContext(), uid, HOST)) {
            throw new SecurityException("HEALTH_CALLER_NOT_AUTHORIZED");
        }
        return false;
    }

    /** 一次性清理指令的内容，读不到就返回 null。文件由 adb 在 no_backup 下写好。 */
    private static String readPlan(java.io.File plan) {
        try {
            if (!plan.isFile() || plan.length() > 8192) return null;
            try (java.io.InputStream in = new java.io.FileInputStream(plan)) {
                byte[] bytes = in.readAllBytes();
                return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (java.io.IOException unreadable) {
            return null;
        }
    }

    private static void requireSelf(boolean self) {
        if (!self) throw new SecurityException("HEALTH_SELF_REQUIRED");
    }

    private static boolean recordsUri(Uri uri) {
        if (URI.equals(uri)) return false;
        if (uri == null || !RECORDS_URI.equals(uri.buildUpon().clearQuery().build())
                || !java.util.Set.of("after").containsAll(uri.getQueryParameterNames())
                || uri.getQueryParameters("after").size() > 1) {
            throw new IllegalArgumentException("UNSUPPORTED_HEALTH_URI");
        }
        return true;
    }

    private void requireCollectionReady(String deviceId) {
        if (!store.ownsDeviceArchive(deviceId)) {
            throw new SecurityException("HEALTH_ARCHIVE_OWNERSHIP_REQUIRED");
        }
    }

    @Override public synchronized Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        requireCaller();
        requireUnlocked();
        boolean history = recordsUri(uri);
        if (sortOrder != null || projection != null && !java.util.Arrays.equals(projection, COLUMNS)) {
            throw new IllegalArgumentException("UNSUPPORTED_HEALTH_PROJECTION");
        }
        Cursor cursor;
        if (history) {
            if (!"account=? AND deviceId=? AND kind=? AND startMs<? AND endMs>?".equals(selection)
                    || selectionArgs == null || selectionArgs.length != 5) {
                throw new IllegalArgumentException("HEALTH_RECORD_SELECTION_REQUIRED");
            }
            if ("steps_day".equals(selectionArgs[2]) || "steps_interval".equals(selectionArgs[2])) {
                new io.github.miam1ku.mibandoplusbridge.data.RawFitnessFileStore(getContext()).refreshStepMetrics();
            }
            if ("sleep_interval".equals(selectionArgs[2]) || "sleep_stage".equals(selectionArgs[2])
                    || "sleep_breath".equals(selectionArgs[2])) {
                new io.github.miam1ku.mibandoplusbridge.data.RawFitnessFileStore(getContext()).refreshSleepBreathMetrics();
            }
            cursor = store.records(selectionArgs[0], selectionArgs[1], selectionArgs[2],
                    Long.parseLong(selectionArgs[4]), Long.parseLong(selectionArgs[3]), uri.getQueryParameter("after"));
        } else {
            if (!"account=?".equals(selection) || selectionArgs == null || selectionArgs.length != 1) {
                throw new IllegalArgumentException("HEALTH_ACCOUNT_SELECTION_REQUIRED");
            }
            cursor = store.pending(selectionArgs[0]);
        }
        cursor.setNotificationUri(getContext().getContentResolver(), history ? RECORDS_URI : URI);
        return cursor;
    }

    @Override public synchronized Bundle call(String method, String arg, Bundle extras) {
        boolean self = requireCaller();
        requireUnlocked();
        if (method == null) throw new IllegalArgumentException("UNSUPPORTED_HEALTH_OPERATION");
        switch (method) {
            case "adoptAccount": {
                if (self || extras == null) throw new SecurityException("OHEALTH_CALLER_REQUIRED");
                String account = extras.getString("account");
                if (account == null || account.isBlank() || account.length() > 512
                        || "com.heytap.health".equals(account)) {
                    return status(store.accountState());
                }
                store.confirmAccountHash(HealthRecordStore.hashAccount(account));
                notifyRecordsChanged();
                return status("HEALTH_ACCOUNT_CONFIRMED");
            }
            case "proposeAccount": {
                if (self || extras == null) throw new SecurityException("OHEALTH_CALLER_REQUIRED");
                String account = extras.getString("account");
                proposedFingerprint = HealthRecordStore.hashAccount(account);
                proposedAtMs = SystemClock.elapsedRealtime();
                store.authorizedAccount(account); // The account Health presented becomes the write target. 
                notifyRecordsChanged();
                return status("ACCOUNT_PROPOSED");
            }
            case "confirmProposedAccount": {
                requireSelf(self);
                if (extras == null || !extras.getBoolean("userConfirmed", false)) {
                    throw new SecurityException("HEALTH_ACCOUNT_USER_CONFIRMATION_REQUIRED");
                }
                if (proposedFingerprint == null || SystemClock.elapsedRealtime() - proposedAtMs > 300_000) {
                    throw new IllegalStateException("HEALTH_ACCOUNT_PROPOSAL_EXPIRED");
                }
                if (!proposedFingerprint.equals(extras.getString("expectedFingerprint"))) {
                    throw new SecurityException("HEALTH_ACCOUNT_PROPOSAL_CHANGED");
                }
                store.confirmAccountHash(proposedFingerprint);
                proposedFingerprint = null;
                notifyRecordsChanged();
                return status("HEALTH_ACCOUNT_CONFIRMED");
            }
            case "enqueueMeasurement": {
                requireSelf(self);
                if (extras == null || extras.getString("measurement") == null) {
                    throw new IllegalArgumentException("HEALTH_MEASUREMENT_REQUIRED");
                }
                try {
                    Measurement measurement = Measurement.fromJson(
                            new JSONObject(extras.getString("measurement")));
                    requireCollectionReady(measurement.deviceId);
                    HealthRecordStore.EnqueueResult queued = store.enqueueMeasurement(measurement);
                    if (queued.added()) notifyRecordsChanged();
                    Bundle result = status(queued.added() ? "HEALTH_QUEUED" : "HEALTH_ALREADY_QUEUED");
                    result.putInt("revision", queued.revision());
                    return result;
                } catch (org.json.JSONException malformed) {
                    throw new IllegalArgumentException("INVALID_HEALTH_MEASUREMENT", malformed);
                }
            }
            case "status": {
                requireSelf(self);
                Bundle result = status(store.accountState());
                if (proposedFingerprint != null
                        && !proposedFingerprint.equals(store.confirmedAccountHash())
                        && SystemClock.elapsedRealtime() - proposedAtMs <= 300_000) {
                    result.putString("proposedFingerprintFull", proposedFingerprint);
                    result.putString("proposedFingerprint", proposedFingerprint.substring(0, 12));
                    result.putLong("proposedRemainingMs", 300_000 - (SystemClock.elapsedRealtime() - proposedAtMs));
                }
                return result;
            }
            case "ack": {
                if (self) throw new SecurityException("OHEALTH_CALLER_REQUIRED");
                if (extras == null || !extras.keySet().equals(java.util.Set.of("account", "recordId", "revision"))) {
                    throw new IllegalArgumentException("HEALTH_RECEIPT_REQUIRED");
                }
                int removed = store.acknowledge(extras.getString("account"), extras.getString("recordId"),
                        extras.getInt("revision", 0));
                if (removed != 0) notifyRecordsChanged();
                Bundle result = status("HEALTH_ACKNOWLEDGED");
                result.putInt("acknowledged", removed);
                return result;
            }
            case "releaseUnsupported": {
                if (self) throw new SecurityException("OHEALTH_CALLER_REQUIRED");
                if (extras == null || !extras.keySet().equals(java.util.Set.of("account", "recordId", "revision"))) {
                    throw new IllegalArgumentException("HEALTH_RECEIPT_REQUIRED");
                }
                int removed;
                try {
                    removed = store.releaseUnsupported(extras.getString("account"), extras.getString("recordId"),
                            extras.getInt("revision", 0));
                } catch (org.json.JSONException malformed) {
                    throw new IllegalArgumentException("INVALID_HEALTH_RECORD", malformed);
                }
                if (removed != 0) notifyRecordsChanged();
                Bundle result = status("HEALTH_HOST_UNSUPPORTED");
                result.putInt("released", removed);
                return result;
            }
            case "sleepCleanup": {
                // 维护指令：读出一次性清理窗口，但不在读取时删除 ——
                // 删得快执行慢会让意向被吃掉却没干活，宿主进程执行完再 ack。
                Bundle result = status("HEALTH_SLEEP_CLEANUP");
                java.io.File plan = new java.io.File(getContext().getNoBackupFilesDir(),
                        "huawei-sleep-cleanup.json");
                String body = readPlan(plan);
                result.putString("windows", body == null ? "" : body);
                result.putString("state", body == null ? "PLAN_MISSING" : "PLAN_PENDING");
                return result;
            }
            case "sleepCleanupAck": {
                // 宿主进程执行完探伤清点后回执，此时才销毁意向。
                java.io.File plan = new java.io.File(getContext().getNoBackupFilesDir(),
                        "huawei-sleep-cleanup.json");
                Bundle result = status("HEALTH_SLEEP_CLEANUP_ACK");
                result.putString("state", plan.isFile() && plan.delete() ? "PLAN_CLEARED" : "PLAN_ABSENT");
                return result;
            }
            case "unmeasuredStress": {
                if (self) throw new SecurityException("OHEALTH_CALLER_REQUIRED");
                String account = account(extras);
                var gaps = store.unmeasuredStress(account);
                long[] starts = new long[gaps.size()];
                long[] ends = new long[gaps.size()];
                String[] devices = new String[gaps.size()];
                for (int i = 0; i < gaps.size(); i++) {
                    starts[i] = gaps.get(i).startMs();
                    ends[i] = gaps.get(i).endMs();
                    devices[i] = gaps.get(i).deviceId();
                }
                Bundle result = status("STRESS_GAPS");
                result.putLongArray("starts", starts);
                result.putLongArray("ends", ends);
                result.putStringArray("devices", devices);
                return result;
            }
            case "forgetUnmeasuredStress": {
                if (self) throw new SecurityException("OHEALTH_CALLER_REQUIRED");
                int removed = store.forgetUnmeasuredStress(account(extras));
                if (removed != 0) notifyRecordsChanged();
                Bundle result = status("STRESS_ZEROS_FORGOTTEN");
                result.putInt("removed", removed);
                return result;
            }
            default:
                throw new IllegalArgumentException("UNSUPPORTED_HEALTH_OPERATION");
        }
    }

    private void notifyRecordsChanged() {
        getContext().getContentResolver().notifyChange(URI, null);
        getContext().getContentResolver().notifyChange(RECORDS_URI, null);
    }

    private Bundle status(String code) {
        Bundle result = new Bundle();
        result.putString("status", code);
        result.putInt("pendingCount", store.count());
        return result;
    }

    private static String account(Bundle extras) {
        if (extras == null || !extras.keySet().equals(java.util.Set.of("account"))) {
            throw new IllegalArgumentException("HEALTH_ACCOUNT_REQUIRED");
        }
        return extras.getString("account");
    }

    @Override public String getType(Uri uri) {
        requireCaller();
        return "vnd.android.cursor.dir/vnd." + AUTHORITY + (recordsUri(uri) ? ".records" : ".pending");
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        requireCaller();
        throw new SecurityException("HEALTH_CALL_ONLY");
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        requireCaller();
        throw new SecurityException("HEALTH_CALL_ONLY");
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        requireCaller();
        throw new SecurityException("HEALTH_READBACK_REQUIRED");
    }
}
