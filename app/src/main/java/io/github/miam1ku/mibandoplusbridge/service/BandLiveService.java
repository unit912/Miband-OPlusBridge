// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import io.github.miam1ku.mibandoplusbridge.data.BandStateRepository;
import io.github.miam1ku.mibandoplusbridge.data.HuaweiDeviceRegistry;
import io.github.miam1ku.mibandoplusbridge.data.HuaweiStepsImporter;
import io.github.miam1ku.mibandoplusbridge.integration.WeatherSnapshotProvider;
import io.github.miam1ku.mibandoplusbridge.protocol.BandWeatherEncoder;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.Weather;
import io.github.miam1ku.mibandoplusbridge.protocol.SppDiagnosticClient;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.br.HuaweiBrTransport;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiDeviceType;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.FitnessData;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.Notifications;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests.GetFitnessDataRequest;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests.GetFitnessTotalsRequest;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests.GetProductInfoRequest;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests.GetSleepDataRequest;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.service.HuaweiIdentityStore;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.service.HuaweiSupportProvider;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;

/** Keeps one authenticated SPP session for the whole NATIVE ownership period and reconnects after the link drops. */
public final class BandLiveService extends Service {
    private static final String ACTION_SYNC = "io.github.miam1ku.mibandoplusbridge.SYNC";
    private static volatile BandLiveService instance;
    private static volatile boolean diagnosticPaused;
    private volatile java.util.concurrent.CountDownLatch stopped = new java.util.concurrent.CountDownLatch(0);
    private final java.util.concurrent.CountDownLatch destroyed = new java.util.concurrent.CountDownLatch(1);
    private final java.util.concurrent.ScheduledExecutorService coordinator =
            Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "OplusBandCoordinator"));
    private volatile io.github.miam1ku.mibandoplusbridge.protocol.LiveCommandQueue commands;
    private LiveHistorySync historySync;
    private volatile boolean syncRequested;
    private volatile boolean realtimeStatsStopPending;
    private long nextBatteryAt;
    private long nextHealthAt;
    private long nextWeatherAt;
    /** While idle, ask only for the battery and today's file list. Unchanged files and weather stay off the radio so the ACL can still sniff. */
    private static final long IDLE_POLL_MINUTES = 30;
    private static final long REALTIME_STATS_TIMEOUT_SECONDS = 30;
    /** 8/45 opens the band's live-stats stream. The band measures heart rate while that stream runs, so only the first snapshot is kept. */
    private static final int REALTIME_STATS_START = 45;
    /** 8/46 stops the live stream after the first snapshot. */
    private static final int REALTIME_STATS_STOP = 46;
    private WeatherSync weatherSync;
    private HealthReplay healthReplay;
    private volatile long sessionEpoch;
    // ---- 睡眠拉取状态机（串行化 + watchdog 自愈）----
    // 2026-10-14 实测：睡眠链 (0x0C/0x0D) 与 0x0B 大链并发在飞时，手表会
    // 间歇性丢 0x0D 响应，且请求超时器不触发 —— 链路无声卡死，队列里永远
    // 拿不到 sleep 记录。三件套防复发：
    //   busy/gen —— 互斥发起 + 代际号让陈旧回调（清理后残留的 onSleep/
    //               onError/timeout）不污染新一轮拉取；
    //   sinceMs  —— 周期 watchdog 判断卡死的依据（>90s 无终态即强制复位）；
    //   okAtMs   —— 成功后 15 分钟内不重拉，避免反复冲击手表。
    private final AtomicBoolean sleepPullBusy = new AtomicBoolean();
    private volatile long sleepPullGen;
    private volatile long sleepPullSinceMs;
    private volatile long sleepPulledOkAtMs;
    private static final long SLEEP_STUCK_MS = 90_000L;
    private static final long SLEEP_REPULL_MS = 15 * 60_000L;
    /** 周期路径（非握手串行链）调用 maybePullSleep 时的空推进：链早就走完了。 */
    private static final Runnable NO_ADVANCE = () -> { };
    private static final java.util.concurrent.atomic.AtomicLong SESSION_IDS = new java.util.concurrent.atomic.AtomicLong();
    private io.github.miam1ku.mibandoplusbridge.notify.PhoneCallMonitor calls;
    private static final String HEALTH_PACKAGE = "com.heytap.health";
    private static final android.content.ComponentName HEALTH_SERVICE = new android.content.ComponentName(
            HEALTH_PACKAGE, "com.heytap.health.rpc.host.HealthRpcMsgService");
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private volatile boolean healthBound;
    private final android.content.ServiceConnection healthConnection = new android.content.ServiceConnection() {
        @Override public void onServiceConnected(android.content.ComponentName name, IBinder binder) {
            healthHostStatus("READY");
        }
        @Override public void onServiceDisconnected(android.content.ComponentName name) {
            healthHostStatus("UNAVAILABLE");
        }
        @Override public void onBindingDied(android.content.ComponentName name) {
            unbindHealthHost();
            healthHostStatus("UNAVAILABLE");
            reviveHealth();
        }
        @Override public void onNullBinding(android.content.ComponentName name) {
            unbindHealthHost();
            healthHostStatus("UNAVAILABLE");
        }
    };
    private static final String CHANNEL = "band-live";
    private static final io.github.miam1ku.mibandoplusbridge.notify.NotifyDedupe NOTIFICATIONS =
            new io.github.miam1ku.mibandoplusbridge.notify.NotifyDedupe();
    private static final io.github.miam1ku.mibandoplusbridge.notify.NotifyReplay HELD_NOTIFICATIONS =
            new io.github.miam1ku.mibandoplusbridge.notify.NotifyReplay();
    private final java.util.ArrayDeque<String> iconPackages = new java.util.ArrayDeque<>();
    private final AtomicBoolean iconUploadBusy = new AtomicBoolean();
    private volatile String iconPackage;
    private static final int NOTICE = 7;
    private static final long INITIAL_BACKOFF_MS = 3_000;
    private static final long MAX_BACKOFF_MS = 30_000;
    /** Config errors stay in this process. Exiting is what makes health launch the app. */
    private static final long QUIET_BACKOFF_MS = 120_000;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "OplusBandLive");
        thread.setDaemon(false);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean();
    private final Object stopLock = new Object();
    private volatile boolean stopRequested;
    private volatile long lastDndSentNanos;
    private int lastDndFilter = Integer.MIN_VALUE;
    private long dndSyncAtNanos;
    private final Object dndSyncLock = new Object();
    private final Runnable dndRulesAgain = this::sendDndRulesAgain;
    private volatile boolean dndPushPhone;
    private volatile boolean dndBandKnown;
    private volatile boolean dndBandOn;
    private final java.util.concurrent.atomic.AtomicBoolean dndAdopted = new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command lastMusic;
    private final io.github.miam1ku.mibandoplusbridge.notify.SleepMusic sleepMusic =
            new io.github.miam1ku.mibandoplusbridge.notify.SleepMusic();
    private volatile boolean sleepPauseOn;
    private volatile long sleepArmedAtMs = Long.MAX_VALUE;
    private long nextSleepFileAt;
    private volatile boolean retryNow;
    private String noticeTitle = "";
    private volatile SppDiagnosticClient client;
    /**
     * 华为分支当前持有的 provider；null 表示当前跑的是小米分支或未连接。
     * 断开、停机、蓝牙关闭三条路径都通过它强制断链，与小米侧的 {@link #client} 对位。
     */
    private volatile HuaweiSupportProvider huaweiProvider;
    /**
     * 华为通知的 msgId 计数器（Notifications 0x02/0x01 的 tag 0x01，short）。
     * 进程内单调递增不复位：手表按它去重/移除，重启会话后重用近期 id 有
     * 被旧通知表吞掉的风险，单调最稳。上限 0x7FFF 后回绕。
     */
    private final java.util.concurrent.atomic.AtomicInteger huaweiNotifySeq =
            new java.util.concurrent.atomic.AtomicInteger();
    private boolean receiverRegistered;
    private boolean dndReceiverRegistered;
    private final BroadcastReceiver bluetoothEvents = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) {
                noteBond(context, intent);
                return;
            }
            if (!BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) return;
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(context, "bt adapter state=" + state
                    + "/" + adapterState(state));
            if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                SppDiagnosticClient active = client;
                if (active != null) active.close();
                // 华为分支同样要断：socket 读线程会阻塞到适配器断电，不主动断
                // 链路状态会假装活着，重连全被这条死链路挡住。
                HuaweiSupportProvider huawei = huaweiProvider;
                if (huawei != null && huawei.getTransport() != null) {
                    huawei.getTransport().disconnect();
                }
                // The system sends this broadcast. Clear the card immediately; the socket
                // thread may still be blocked in a read when the adapter is powered off.
                try { new BandStateRepository(context).markSessionClosed(); }
                catch (RuntimeException ignored) { }
            } else if (state == BluetoothAdapter.STATE_ON) {
                synchronized (stopLock) {
                    retryNow = true;
                    stopLock.notifyAll();
                }
            }
        }
    };
    private final BroadcastReceiver dndEvents = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED.equals(intent.getAction())) syncDnd();
        }
    };
    private final android.database.ContentObserver zenMode = new android.database.ContentObserver(main) {
        @Override public void onChange(boolean selfChange) { syncDnd(); }
    };

    public static void start(Context context) {
        admit(context);
        launch(context, false);
    }

    /** Package update runs this off the main thread and can wait for the root start. */
    public static void startBlocking(Context context) {
        admit(context);
        launch(context, true);
    }

    /** Health card reads and notification IPC. Does nothing while this process already hosts the service. */
    public static void ensureProcess(Context context) {
        BandLiveService live = instance;
        if (live != null && !live.stopRequested) return;
        try {
            start(context);
        } catch (RuntimeException ignored) { }
    }
    /** True while this process hosts the service and a stop has not been requested. */
    public static boolean isRunning() {
        BandLiveService live = instance;
        return live != null && !live.stopRequested;
    }

    public static boolean mayWake(Context context) {
        if (context == null || diagnosticPaused) return false;
        if (!context.getSystemService(android.os.UserManager.class).isUserUnlocked()) return false;
        if (HuaweiDeviceRegistry.provisioned(context)) return true;
        if (!new BandStateRepository(context).isRegistered()) return false;
        return new OwnershipController(context).nativeReady();
    }

    private static void admit(Context context) {
        if (diagnosticPaused) throw new IllegalStateException("DIAGNOSTIC_ACTIVE");
        if (!context.getSystemService(android.os.UserManager.class).isUserUnlocked()) {
            throw new IllegalStateException("USER_LOCKED");
        }
        // 华为 GT3 不经过 Mi Fitness 导入，也没有 NATIVE 所有权概念 ——
        // 注册表里有地址就等于具备启动条件，小米的两个门禁对它不适用。
        if (HuaweiDeviceRegistry.provisioned(context)) return;
        if (!new BandStateRepository(context).isRegistered()) {
            throw new IllegalStateException("DEVICE_NOT_REGISTERED");
        }
        if (!new OwnershipController(context).nativeReady()) {
            throw new IllegalStateException("NATIVE_OWNERSHIP_REQUIRED");
        }
    }

    private static void launch(Context context, boolean waitForRoot) {
        try {
            context.startForegroundService(new Intent(context, BandLiveService.class));
        } catch (RuntimeException backgroundRejected) {
            if (waitForRoot) HostKeepAlive.startBridge(context);
            else HostKeepAlive.startBridgeAsync(context);
        }
    }

    public static void stop(Context context) {
        BandLiveService live = instance;
        if (live != null) live.requestStop();
        context.stopService(new Intent(context, BandLiveService.class));
    }

    public static void pauseForDiagnostic(Context context) throws InterruptedException {
        diagnosticPaused = true;
        BandLiveService live = instance;
        stop(context);
        if (live != null && (!live.destroyed.await(10, TimeUnit.SECONDS)
                || !live.stopped.await(10, TimeUnit.SECONDS))) {
            throw new IllegalStateException("LIVE_SESSION_STILL_ACTIVE");
        }
        if (!SppDiagnosticClient.stopAllAndWait()) throw new IllegalStateException("DIAGNOSTIC_SOCKET_STILL_ACTIVE");
    }

    public static void resumeAfterDiagnostic(Context context) {
        diagnosticPaused = false;
        if (HuaweiDeviceRegistry.provisioned(context)
                || (new BandStateRepository(context).isRegistered()
                && new OwnershipController(context).nativeReady())) start(context);
    }

    /** Accepts a request without waiting for root checks, Bluetooth, or a Binder transaction. */
    public static String requestSync(Context context) {
        if (diagnosticPaused) return "OPEN_CONFIG_REQUIRED";
        if (!context.getSystemService(android.os.UserManager.class).isUserUnlocked()) return "OPEN_CONFIG_REQUIRED";
        if (!new BandStateRepository(context).isRegistered()) return "DEVICE_NOT_REGISTERED";
        var ownership = io.github.miam1ku.mibandoplusbridge.data.LocalPrefs.open(context, "ownership");
        if (!"NATIVE".equals(ownership.getString("mode", "OFFICIAL"))
                || !ownership.getBoolean("ownsDisable", false)
                || ownership.getBoolean("officialRestored", false)) return "NATIVE_OWNERSHIP_REQUIRED";
        BandLiveService live = instance;
        if (live != null && !live.stopRequested) {
            live.syncRequested = true;
            synchronized (live.stopLock) {
                live.retryNow = true;
                live.stopLock.notifyAll();
            }
            try {
                live.coordinator.execute(live::tick);
                return "ACCEPTED";
            } catch (java.util.concurrent.RejectedExecutionException stopping) {
                return "OPEN_CONFIG_REQUIRED";
            }
        }
        try {
            start(context);
            return "ACCEPTED";
        } catch (RuntimeException denied) {
            return "OPEN_CONFIG_REQUIRED";
        }
    }

    /** Switch-on starts a new baseline. A sleep already underway keeps playing. */
    public static void sleepPauseChanged(Context context) {
        BandLiveService live = instance;
        if (live == null || live.stopRequested) return;
        live.sleepPauseOn = io.github.miam1ku.mibandoplusbridge.notify.SleepMusic.enabled(context);
        live.sleepMusic.reset();
        live.sleepArmedAtMs = io.github.miam1ku.mibandoplusbridge.notify.SleepMusic.armedAt(context);
        if (live.sleepPauseOn) {
            live.nextSleepFileAt = System.nanoTime();
            live.requestSleepState();
        }
    }

    public static java.util.concurrent.CompletionStage<Void> requestWeather(Context context,
            io.github.miam1ku.mibandoplusbridge.protocol.BandWeatherEncoder.Sample sample, boolean inspect) {
        BandLiveService live = instance;
        if (live == null || live.stopRequested) {
            return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("LIVE_SESSION_UNAVAILABLE"));
        }
        if (live.commands != null) {
            // 小米分支
            if (!new BandStateRepository(context).isRegistered()) {
                return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("LIVE_SESSION_REQUIRED"));
            }
            return inspect ? live.weatherSync.inspectCities(sample) : live.weatherSync.send(sample);
        }
        // 华为分支：不经小米命令通道，直接构华为天气报文。inspect 对华为无意义（按地点名推送，无城市码清单）。
        HuaweiSupportProvider huawei = live.huaweiProvider;
        if (huawei == null || !huawei.isAuthenticated()) {
            return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("LIVE_SESSION_UNAVAILABLE"));
        }
        return live.sendHuaweiWeather(sample);
    }

    public static void refreshWeather(Context context) {
        BandLiveService live = instance;
        if (live == null || live.stopRequested || !new BandStateRepository(context).isRegistered()) return;
        if (live.commands != null) {
            live.weatherSync.refreshAndSend();
            return;
        }
        // 华为分支：读最新天气快照后推送到手表。
        live.refreshHuaweiWeather();
    }

    public static boolean notificationSessionReady(Context context) {
        BandLiveService live = instance;
        if (live == null || live.stopRequested) return false;
        if (live.commands != null) {
            if (!new BandStateRepository(context).isRegistered()) return false;
            var owner = io.github.miam1ku.mibandoplusbridge.data.LocalPrefs.open(context, "ownership");
            return "NATIVE".equals(owner.getString("mode", "OFFICIAL"))
                    && owner.getBoolean("ownsDisable", false) && !owner.getBoolean("officialRestored", false);
        }
        // 华为分支：commands 恒为 null，会话就绪的判据是已认证的 provider。
        // NATIVE 所有权概念只对小米分支存在，这里不做检查。
        HuaweiSupportProvider huawei = live.huaweiProvider;
        return huawei != null && huawei.isAuthenticated();
    }
    private static String sessionReason(Context context) {
        BandLiveService live = instance;
        if (live == null || live.stopRequested || live.commands == null) return "service";
        boolean registered = new BandStateRepository(context).isRegistered();
        var owner = io.github.miam1ku.mibandoplusbridge.data.LocalPrefs.open(context, "ownership");
        return "session registered=" + registered
                + " mode=" + owner.getString("mode", "OFFICIAL")
                + " ownsDisable=" + owner.getBoolean("ownsDisable", false)
                + " restored=" + owner.getBoolean("officialRestored", false);
    }

    public static boolean callsOwned() {
        BandLiveService live = instance;
        return live != null && live.calls != null && live.calls.ownsCalls();
    }

    public static void noteCallAnswered() {
        BandLiveService live = instance;
        if (live != null && live.calls != null) live.calls.noteAnswered();
    }

    private void logFeatures() {
        var settings = getSharedPreferences("notification-settings", MODE_PRIVATE);
        boolean access = io.github.miam1ku.mibandoplusbridge.notify.BandNotificationListener.accessGranted(this);
        boolean up = io.github.miam1ku.mibandoplusbridge.notify.BandNotificationListener.listenerConnected();
        String enabled = android.provider.Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        boolean health = enabled != null && enabled.contains(
                "com.heytap.health/com.heytap.health.watch.commonnotification.HeytapNotificationListenerService");
        io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "features local="
                + (up ? "up" : access ? "granted" : "absent")
                + " healthListener=" + health
                + " healthApproved=" + io.github.miam1ku.mibandoplusbridge.data.LocalPrefs.open(this,
                        "notification-settings").getBoolean("healthApproved", false)
                + " healthLive=" + io.github.miam1ku.mibandoplusbridge.notify.HealthListenerState.connected()
                + " notify=" + settings.getBoolean("enabled", true)
                + " packages=" + settings.getStringSet("packages", java.util.Set.of()).size()
                + " calls=" + settings.getBoolean("callsEnabled", false)
                + " callOwner=" + (calls != null && calls.ownsCalls())
                + " music=" + io.github.miam1ku.mibandoplusbridge.notify.PhoneMusic.attached());
        io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "hosts "
                + hostVersion("com.coloros.alarmclock")
                + " " + hostVersion("com.heytap.health")
                + " " + hostVersion("com.heytap.mydevices")
                + " " + hostVersion(getPackageName()));
    }

    private String hostVersion(String pkg) {
        String name = pkg == null ? "" : pkg.substring(pkg.lastIndexOf('.') + 1);
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(pkg, 0);
            return name + "=" + info.versionName + "/" + info.getLongVersionCode();
        } catch (android.content.pm.PackageManager.NameNotFoundException missing) {
            return name + "=absent";
        }
    }


    public static long notificationSessionId() {
        BandLiveService live = instance;
        return live == null || live.stopRequested || live.commands == null ? 0 : live.sessionEpoch;
    }

    public static int notificationPayloadLimit() {
        BandLiveService live = instance;
        SppDiagnosticClient active = live == null ? null : live.client;
        return active == null || live.commands == null ? 0 : active.notificationPayloadLimit();
    }

    public static java.util.concurrent.CompletionStage<Void> sendSessionCommand(
            nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command command) {
        BandLiveService live = instance;
        var queue = live == null ? null : live.commands;
        if (queue == null || command == null || !command.isInitialized()) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("NOTIFICATION_SESSION_UNAVAILABLE"));
        }
        if (command.getType() == 18 && command.getSubtype() == 1) lastMusic = command;
        return queue.send(command);
    }


    public static java.util.concurrent.CompletionStage<Void> sendNotification(Context context,
            nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command command) {
        if (!notificationCommand(command)) {
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(context, "NOTIFY_DROP reason=command");
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("NOTIFICATION_SESSION_UNAVAILABLE"));
        }
        BandLiveService live = instance;
        var queue = live == null ? null : live.commands;
        var notification = command.getNotification();
        boolean call = command.getSubtype() == 0 && notification.hasNotification2()
                && io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand.isCall(
                        notification.getNotification2().getNotification3());
        boolean clearCall = command.getSubtype() == 1 && notification.hasNotificationDismiss()
                && notification.getNotificationDismiss().getNotificationIdCount() == 1
                && "phone".equals(notification.getNotificationDismiss().getNotificationId(0).getPackage())
                && notification.getNotificationDismiss().getNotificationId(0).getId() == 0;
        var settings = context.getSharedPreferences("notification-settings", MODE_PRIVATE);
        if (call && (!settings.getBoolean("callsEnabled", false)
                || context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED)) {
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(context, "NOTIFY_DROP reason=call-permission");
            return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("CALL_PERMISSION_REQUIRED"));
        }
        if (!call && !clearCall) {
            var manager = context.getSystemService(NotificationManager.class);
            if (!settings.getBoolean("enabled", true) || !manager.isNotificationListenerAccessGranted(
                    new android.content.ComponentName(context, io.github.miam1ku.mibandoplusbridge.notify.BandNotificationListener.class))) {
                io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(context, "NOTIFY_DROP reason=access");
                return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("NOTIFICATION_ACCESS_REQUIRED"));
            }
        }
        if (queue == null) {
            // 华为分支：通知走 0x02/0x01 fire-and-forget，不经小米 LiveCommandQueue。
            HuaweiSupportProvider huawei = live == null ? null : live.huaweiProvider;
            if (huawei != null && huawei.isAuthenticated()) {
                if (duplicate(context, command)) {
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                }
                if (suppressForDnd(command,
                        io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.currentFilter(context))) {
                    android.util.Log.i("OplusBandBridge", "NOTIFY_SUPPRESSED_DND");
                    io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(context, "NOTIFY_SUPPRESSED_DND");
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                }
                live.sendHuaweiNotification(huawei, command);
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            if (mayWake(context)) return holdNotification(context, command);
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(context, "NOTIFY_DROP reason=service");
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("NOTIFICATION_SESSION_UNAVAILABLE"));
        }
        if (!notificationSessionReady(context)) {
            if (mayWake(context)) return holdNotification(context, command);
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(context, "NOTIFY_DROP reason="
                    + sessionReason(context));
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("NOTIFICATION_SESSION_UNAVAILABLE"));
        }
        if (duplicate(context, command)) return java.util.concurrent.CompletableFuture.completedFuture(null);
        if (suppressForDnd(command, io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.currentFilter(context))) {
            android.util.Log.i("OplusBandBridge", "NOTIFY_SUPPRESSED_DND");
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(context, "NOTIFY_SUPPRESSED_DND");
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        try {
            var fitted = io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand.fitToPayload(command,
                    notificationPayloadLimit());
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(context, "NOTIFY_OUT type=" + fitted.getType()
                    + " subtype=" + fitted.getSubtype() + " bytes=" + fitted.getSerializedSize());
            noteIconPackage(fitted);
            routeWatchCall(context, fitted);
            return queue.send(fitted);
        } catch (IllegalArgumentException tooLarge) {
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(context, "NOTIFY_DROP reason=too-large");
            return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("NOTIFICATION_IDENTITY_TOO_LARGE"));
        }
    }

    /** OHealth already decided this notification may reach the band. */
    public static java.util.concurrent.CompletionStage<Void> forwardHostNotification(Context context,
            nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command command) {
        if (!notificationCommand(command)) {
            android.util.Log.i("OplusBandBridge", "NOTIFY_DROP reason=command");
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(context, "NOTIFY_DROP reason=command");
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("NOTIFICATION_SESSION_UNAVAILABLE"));
        }
        BandLiveService live = instance;
        var queue = live == null ? null : live.commands;
        if (queue == null) {
            // 华为分支：宿主转发的通知同样走 0x02/0x01，小米队列不存在不是错误。
            HuaweiSupportProvider huawei = live == null ? null : live.huaweiProvider;
            if (huawei != null && huawei.isAuthenticated()) {
                if (duplicate(context, command)) {
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                }
                if (ordinaryPost(command) && io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd
                        .blocksNotifications(io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd
                                .currentFilter(context))) {
                    android.util.Log.i("OplusBandBridge", "NOTIFY_SUPPRESSED_DND");
                    io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(context, "NOTIFY_SUPPRESSED_DND");
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                }
                live.sendHuaweiNotification(huawei, command);
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            if (mayWake(context)) return holdNotification(context, command);
            android.util.Log.i("OplusBandBridge", "NOTIFY_DROP reason=service");
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(context, "NOTIFY_DROP reason=service");
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("NOTIFICATION_SESSION_UNAVAILABLE"));
        }
        if (!notificationSessionReady(context)) {
            if (mayWake(context)) return holdNotification(context, command);
            String reason = sessionReason(context);
            android.util.Log.i("OplusBandBridge", "NOTIFY_DROP reason=" + reason);
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(context, "NOTIFY_DROP reason=" + reason);
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("NOTIFICATION_SESSION_UNAVAILABLE"));
        }
        if (duplicate(context, command)) return java.util.concurrent.CompletableFuture.completedFuture(null);
        if (ordinaryPost(command) && io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.blocksNotifications(
                io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.currentFilter(context))) {
            android.util.Log.i("OplusBandBridge", "NOTIFY_SUPPRESSED_DND");
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(context, "NOTIFY_SUPPRESSED_DND");
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        try {
            var fitted = io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand.fitToPayload(
                    command, notificationPayloadLimit());
            android.util.Log.i("OplusBandBridge", "NOTIFY_OUT type=" + fitted.getType()
                    + " subtype=" + fitted.getSubtype() + " bytes=" + fitted.getSerializedSize()
                    + " limit=" + notificationPayloadLimit());
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(context, "NOTIFY_OUT type=" + fitted.getType()
                    + " subtype=" + fitted.getSubtype() + " bytes=" + fitted.getSerializedSize());
            noteIconPackage(fitted);
            routeWatchCall(context, fitted);
            return queue.send(fitted);
        } catch (IllegalArgumentException tooLarge) {
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(context, "NOTIFY_DROP reason=too-large");
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("NOTIFICATION_IDENTITY_TOO_LARGE"));
        }
    }

    /** Watches show the call over HFP. The notification packet still carries the name. */
    private static void routeWatchCall(Context context,
            nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command command) {
        var notification = command.getNotification();
        boolean call = command.getSubtype() == 0 && notification.hasNotification2()
                && io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand.isCall(
                        notification.getNotification2().getNotification3());
        boolean clearCall = command.getSubtype() == 1 && notification.hasNotificationDismiss()
                && notification.getNotificationDismiss().getNotificationIdCount() == 1
                && "phone".equals(notification.getNotificationDismiss().getNotificationId(0).getPackage())
                && notification.getNotificationDismiss().getNotificationId(0).getId() == 0;
        if (!call && !clearCall) return;
        String model = io.github.miam1ku.mibandoplusbridge.data.LocalPrefs.open(context, "band-state")
                .getString("modelId", "");
        if (!io.github.miam1ku.mibandoplusbridge.data.BandCatalog.watchForm(model)) return;
        int state = call ? notification.getNotification2().getNotification3().getCallState() : 0;
        io.github.miam1ku.mibandoplusbridge.notify.WatchCallHeadset.apply(context,
                io.github.miam1ku.mibandoplusbridge.notify.WatchCallRoute.link(true, state));
    }

    private static boolean notificationCommand(
            nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command command) {
        return command != null && command.getType() == 7
                && (command.getSubtype() == 0 || command.getSubtype() == 1)
                && command.hasNotification();
    }

    private static java.util.concurrent.CompletionStage<Void> holdNotification(Context context,
            nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command command) {
        io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(context, "NOTIFY_HOLD");
        return HELD_NOTIFICATIONS.add(command, android.os.SystemClock.elapsedRealtime());
    }

    private void expireHeld() {
        int expired = HELD_NOTIFICATIONS.expire(android.os.SystemClock.elapsedRealtime());
        if (expired > 0) io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this,
                "NOTIFY_DROP reason=expired count=" + expired);
    }

    private void replayHeld() {
        long now = android.os.SystemClock.elapsedRealtime();
        int expired = HELD_NOTIFICATIONS.expire(now);
        if (expired > 0) io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this,
                "NOTIFY_DROP reason=expired count=" + expired);
        for (var held : HELD_NOTIFICATIONS.poll(now)) deliverHeld(held);
    }

    private void deliverHeld(io.github.miam1ku.mibandoplusbridge.notify.NotifyReplay.Held held) {
        var command = held.command;
        var queue = commands;
        if (queue == null) {
            // 华为分支：握手中挂起的通知，认证完成后重放。
            HuaweiSupportProvider huawei = huaweiProvider;
            if (huawei != null && huawei.isAuthenticated()) {
                if (duplicate(this, command)) {
                    held.succeed();
                    return;
                }
                if (ordinaryPost(command) && io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd
                        .blocksNotifications(io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd
                                .currentFilter(this))) {
                    io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "NOTIFY_SUPPRESSED_DND");
                    held.succeed();
                    return;
                }
                sendHuaweiNotification(huawei, command);
                held.succeed();
                return;
            }
            held.fail(new IllegalStateException("NOTIFICATION_SESSION_UNAVAILABLE"));
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "NOTIFY_DROP reason=service");
            return;
        }
        if (!notificationSessionReady(this)) {
            held.fail(new IllegalStateException("NOTIFICATION_SESSION_UNAVAILABLE"));
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "NOTIFY_DROP reason=service");
            return;
        }
        if (duplicate(this, command)) {
            held.succeed();
            return;
        }
        if (ordinaryPost(command) && io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.blocksNotifications(
                io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.currentFilter(this))) {
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "NOTIFY_SUPPRESSED_DND");
            held.succeed();
            return;
        }
        try {
            var fitted = io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand.fitToPayload(
                    command, notificationPayloadLimit());
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "NOTIFY_OUT type=" + fitted.getType()
                    + " subtype=" + fitted.getSubtype() + " bytes=" + fitted.getSerializedSize());
            noteIconPackage(fitted);
            routeWatchCall(this, fitted);
            queue.send(fitted).whenComplete((ignored, error) -> {
                if (error == null) held.succeed();
                else held.fail(error);
            });
        } catch (IllegalArgumentException tooLarge) {
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "NOTIFY_DROP reason=too-large");
            held.fail(new IllegalStateException("NOTIFICATION_IDENTITY_TOO_LARGE"));
        }
    }

    /**
     * 把小米协议的通知命令转成华为 0x02/0x01 通知发到手表。
     *
     * <p>命令结构来自两条入口（桥接自有监听器、宿主 OHealth 转发），字段都装在
     * XiaomiProto.Notification3 里 —— 它只是传输壳，与手表品牌无关。映射规则：
     *
     * <ul>
     *   <li>普通通知 → 按 packageName 映射通知类型（微信/QQ/短信/邮件），
     *       未知的用 generic(0x7F)；标题 = Notification3.title（空则用应用名）。</li>
     *   <li>来电（callState=1）→ NotificationType.call；GT3 有免提通话能力，
     *       会弹带接听/挂断按钮的来电卡。</li>
     *   <li>通话结束（dismiss package=phone）→ 移除请求；手表若没收到过 call 卡
     *       则是无害空操作。</li>
     *   <li>callState=2/3（已接通/去电）不转发 —— 通话卡已在屏上，再发一张
     *       来电卡是噪音。</li>
     * </ul>
     *
     * <p>调用方需已确认 provider 已认证。文本截断在 provider 内部按
     * notificationContentLimit 完成。
     */
    private void sendHuaweiNotification(HuaweiSupportProvider provider,
            nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command command) {
        var notification = command.getNotification();
        if (command.getSubtype() == 0 && notification.hasNotification2()
                && notification.getNotification2().hasNotification3()) {
            var data = notification.getNotification2().getNotification3();
            boolean call = io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand
                    .isCall(data);
            if (call && data.getCallState()
                    != io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand.CALL_INCOMING) {
                // 已接通/去电状态：不重复弹卡。
                return;
            }
            short msgId = (short) (huaweiNotifySeq.incrementAndGet() & 0x7FFF);
            String pkg = data.getPackage();
            byte type = call
                    ? io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets
                            .Notifications.NotificationType.call
                    : huaweiNotificationType(pkg);
            String title = data.getTitle().isBlank() ? data.getAppName() : data.getTitle();
            String body = data.getBody();
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this,
                    "HUAWEI_NOTIFY_OUT app=" + pkg + " type=0x"
                            + Integer.toHexString(type & 0xFF) + " msgId=" + msgId
                            + " titleLen=" + title.length()
                            + " bodyLen=" + (body == null ? 0 : body.length()));
            provider.sendNotification(msgId, type, pkg, title, body, true);
            return;
        }
        if (command.getSubtype() == 1 && notification.hasNotificationDismiss()) {
            var dismiss = notification.getNotificationDismiss();
            for (var target : dismiss.getNotificationIdList()) {
                io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this,
                        "HUAWEI_NOTIFY_REMOVE app=" + target.getPackage()
                                + " id=" + target.getId());
                provider.removeNotification(target.getPackage(), target.getKey(),
                        target.getId());
            }
        }
    }

    /** 按包名挑手表上的通知类型（图标/样式不同），未知一律 generic。 */
    private static byte huaweiNotificationType(String pkg) {
        if (pkg == null) return Notifications.NotificationType.generic;
        switch (pkg) {
            case "com.tencent.mm":
                return Notifications.NotificationType.weChat;
            case "com.tencent.mobileqq":
            case "com.tencent.tim":
                return Notifications.NotificationType.qq;
            case "com.android.mms":
            case "com.google.android.apps.messaging":
            case "com.samsung.android.messaging":
                return Notifications.NotificationType.sms;
            default:
                return pkg.contains("mail")
                        ? Notifications.NotificationType.email
                        : Notifications.NotificationType.generic;
        }
    }

    /**
     * 华为链路的通用命令出口：把已构造好的华为报文 fire-and-forget 发到手表。
     * 用于协议测试里的音乐 / 免打扰 / 闹钟响铃等命令。返回的 CompletionStage 在帧
     * 写入传输层后即为完成（华为通知/控制命令无语义回包，等响应不如直接发）。
     * 会话未就绪则立即失败。
     */
    public static java.util.concurrent.CompletionStage<Void> sendHuaweiCommand(
            io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket packet) {
        BandLiveService live = instance;
        HuaweiSupportProvider huawei = live == null ? null : live.huaweiProvider;
        if (huawei == null || !huawei.isAuthenticated() || packet == null) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("NOTIFICATION_SESSION_UNAVAILABLE"));
        }
        try {
            io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests.SendNotificationRequest
                    .send(huawei, packet).doPerform();
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        } catch (java.io.IOException e) {
            return java.util.concurrent.CompletableFuture.failedFuture(e);
        }
    }

    /** 当前华为会话的 ParamsProvider；未连接或会话未认证返回 null。 */
    public static io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket.ParamsProvider
            huaweiParamsProvider() {
        BandLiveService live = instance;
        HuaweiSupportProvider huawei = live == null ? null : live.huaweiProvider;
        return huawei == null || !huawei.isAuthenticated() ? null : huawei.getParamsProvider();
    }

    /**
     * 华为分支天气推送：走完整官方链（开会话 → 温标 → 三张能力位图 → 当前天气 → 预报）。
     *
     * <p>只发 0x09/0x02/0x01 是不够的：能力位图必须从手表的响应里读回来，
     * 否则 {@link io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.Weather.Settings}
     * 全靠猜，手表不认整条链，表现就是「天气暂不可用」。
     */
    CompletionStage<Void> sendHuaweiWeather(BandWeatherEncoder.Sample sample) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        coordinator.execute(() -> {
            HuaweiSupportProvider huawei = huaweiProvider;
            if (huawei == null || !huawei.isAuthenticated()) {
                result.completeExceptionally(new IllegalStateException("LIVE_SESSION_UNAVAILABLE"));
                return;
            }
            try {
                io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests.HuaweiWeatherChain
                        .send(huawei, sample, this::recordWeatherDelivered);
                result.complete(null);
            } catch (RuntimeException e) {
                result.completeExceptionally(new IllegalStateException("WEATHER_DATA_INVALID"));
            }
        });
        return result;
    }

    /**
     * 手表主动要天气（0x0F/0x04，TLV 0x7f = 0x186AA）。
     *
     * <p>运行在 socket 读线程上，因此这里只做一次本地快照读取来判定能不能推，
     * 真正的协议链交给协调线程。返回 {@code false} 时监听器会替我们回一条 OK。
     */
    private boolean onWatchWeatherRequest() {
        HuaweiSupportProvider huawei = huaweiProvider;
        if (huawei == null || !huawei.isAuthenticated()) return false;
        BandWeatherEncoder.Sample sample = latestWeatherSample();
        if (sample == null) {
            io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests.HuaweiWeatherChain
                    .sendUnavailable(huawei);
            return true;
        }
        coordinator.execute(() -> {
            HuaweiSupportProvider live = huaweiProvider;
            if (live == null || !live.isAuthenticated()) return;
            io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests.HuaweiWeatherChain
                    .send(live, sample, this::recordWeatherDelivered);
        });
        return true;
    }

    /** 读一份仍然新鲜的天气快照；过期或缺失返回 {@code null}。 */
    private BandWeatherEncoder.Sample latestWeatherSample() {
        try {
            Bundle response = getContentResolver()
                    .call(WeatherSnapshotProvider.URI, "getSnapshot", null, null);
            return response == null ? null : WeatherSync.freshSample(response);
        } catch (Exception e) {
            return null;
        }
    }

    /** 华为分支没有城市码清单，发送成功后直接记录确认，状态页据此提示已在手环核对。 */
    private void recordWeatherDelivered() {
        try {
            var edit = getSharedPreferences("weather-sync", 0).edit()
                    .putString("status", "WEATHER_TRANSPORT_CONFIRMED")
                    .putLong("confirmedAtMs", System.currentTimeMillis());
            edit.apply();
            getContentResolver().notifyChange(
                    io.github.miam1ku.mibandoplusbridge.integration.WeatherSnapshotProvider.UPDATED, null);
        } catch (RuntimeException ignored) { }
    }

    /** 华为分支：读最新已获取天气快照后走完整链推送（idle 轮询触发）。 */
    void refreshHuaweiWeather() {
        coordinator.execute(() -> {
            HuaweiSupportProvider huawei = huaweiProvider;
            if (huawei == null || !huawei.isAuthenticated()) return;
            BandWeatherEncoder.Sample sample = latestWeatherSample();
            if (sample == null) return;
            io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests.HuaweiWeatherChain
                    .send(huawei, sample, this::recordWeatherDelivered);
        });
    }

    static boolean suppressForDnd(
            nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command command, int filter) {
        return ordinaryPost(command) && io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd
                .blocksNotifications(filter);
    }

    private static boolean ordinaryPost(nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command command) {
        if (command.getSubtype() != 0 || !command.hasNotification()
                || !command.getNotification().hasNotification2()) return false;
        return !io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand.isCall(
                command.getNotification().getNotification2().getNotification3());
    }

    private static boolean duplicate(Context context,
            nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command command) {
        if (command == null || (!ordinaryPost(command) && !ordinaryDismiss(command))) return false;
        if (NOTIFICATIONS.first(io.github.miam1ku.mibandoplusbridge.notify.NotifyDedupe.identity(command),
                android.os.SystemClock.elapsedRealtime())) return false;
        io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(context, "NOTIFY_DROP reason=duplicate");
        return true;
    }

    private static boolean ordinaryDismiss(
            nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command command) {
        if (command.getSubtype() != 1 || !command.hasNotification()
                || !command.getNotification().hasNotificationDismiss()) return false;
        var dismiss = command.getNotification().getNotificationDismiss();
        if (dismiss.getNotificationIdCount() != 1) return false;
        var id = dismiss.getNotificationId(0);
        return !"phone".equals(id.getPackage());
    }

    public static void cancelNotifications(Context context) {
        BandLiveService live = instance;
        var queue = live == null ? null : live.commands;
        if (queue != null) queue.cancelNotifications(new IllegalStateException("NOTIFICATION_CANCELLED"));
    }

    public static void cancelCall(Context context) {
        BandLiveService live = instance;
        var queue = live == null ? null : live.commands;
        if (queue != null) queue.cancelCall(new IllegalStateException("CALL_CANCELLED"));
    }

    public static void refreshNotificationSettings(Context context) {
        io.github.miam1ku.mibandoplusbridge.notify.BandNotificationListener.connectionChanged();
        BandLiveService live = instance;
        if (live != null && !live.stopRequested) live.calls.refresh();
    }

    @Override public void onCreate() {
        super.onCreate();
        io.github.miam1ku.mibandoplusbridge.data.SessionLog.start(this);
        // 华为协议栈的日志门面写入同一个会话日志，需要先拿到 Context。
        io.github.miam1ku.mibandoplusbridge.data.HuaweiLogger.bind(this);
        io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "module start");
        // 磁贴状态跟随注册表：华为表在设备空间里由系统自动上磁贴（ColorOS 只给 WATCH
        // 型设备自动上），桥接这块「手环」磁贴停用，免得快捷面板出现两块手表磁贴；
        // 移除设备后恢复供手动添加。配对/移除路径也会各同步一次，这里兜开机与重启。
        BandTileService.setEnabled(this, !HuaweiDeviceRegistry.provisioned(this));
        weatherSync = new WeatherSync(this, () -> commands, coordinator);
        healthReplay = new HealthReplay(this, this::healthCollectionStatus, () -> {
            try {
                coordinator.execute(() -> { if (!stopRequested && historySync != null) historySync.resume(); });
            } catch (java.util.concurrent.RejectedExecutionException stopping) { }
        });
        calls = new io.github.miam1ku.mibandoplusbridge.notify.PhoneCallMonitor(this, coordinator);
        io.github.miam1ku.mibandoplusbridge.notify.BandNotificationListener.ensureEnabled(this);
        HostKeepAlive.runRoot(() -> {
            try {
                new OwnershipController(this).keepAwake();
                io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "KEEP_AWAKE");
            } catch (RuntimeException ignored) { }
        });
        instance = this;
        sleepPauseOn = io.github.miam1ku.mibandoplusbridge.notify.SleepMusic.enabled(this);
        long armed = io.github.miam1ku.mibandoplusbridge.notify.SleepMusic.armedAt(this);
        if (sleepPauseOn && armed == Long.MAX_VALUE) {
            armed = System.currentTimeMillis();
            io.github.miam1ku.mibandoplusbridge.notify.SleepMusic.rememberCutoff(this, armed);
        }
        sleepArmedAtMs = sleepPauseOn ? armed : Long.MAX_VALUE;
        coordinator.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.MINUTES);
        coordinator.scheduleAtFixedRate(this::expireHeld, 5, 5, TimeUnit.SECONDS);
        try {
            IntentFilter bluetooth = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
            bluetooth.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
            registerReceiver(bluetoothEvents, bluetooth, RECEIVER_EXPORTED);
            receiverRegistered = true;
        } catch (RuntimeException ignored) { }
        try {
            registerReceiver(dndEvents, new IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED),
                    RECEIVER_EXPORTED);
            dndReceiverRegistered = true;
        } catch (RuntimeException ignored) { }
        try {
            android.content.ContentResolver resolver = getContentResolver();
            resolver.registerContentObserver(
                    android.provider.Settings.Global.getUriFor("zen_mode"), false, zenMode);
            resolver.registerContentObserver(
                    android.provider.Settings.Secure.getUriFor("focusmode_switch"), false, zenMode);
            resolver.registerContentObserver(
                    android.provider.Settings.Secure.getUriFor("focusmode_switch_new"), false, zenMode);
            resolver.registerContentObserver(
                    android.provider.Settings.Secure.getUriFor("op_breath_mode_status"), false, zenMode);
        } catch (RuntimeException ignored) { }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // 华为分支只认 HuaweiDeviceRegistry：GT3 不经过 Mi Fitness 导入，
        // BandStateRepository.isRegistered() 对它永远为 false，不能当门禁用。
        boolean huaweiProvisioned = HuaweiDeviceRegistry.provisioned(this);
        if (diagnosticPaused || !getSystemService(android.os.UserManager.class).isUserUnlocked()
                || (!new BandStateRepository(this).isRegistered() && !huaweiProvisioned)) {
            requestStop();
            stopSelf();
            return START_NOT_STICKY;
        }
        synchronized (stopLock) {
            stopRequested = false;
            stopLock.notifyAll();
        }
        if (intent != null && ACTION_SYNC.equals(intent.getAction())) syncRequested = true;
        if (running.compareAndSet(false, true)) {
            stopped = new java.util.concurrent.CountDownLatch(1);
            show("正在连接手环");
            worker.execute(this::supervise);
        }
        io.github.miam1ku.mibandoplusbridge.notify.BandNotificationListener.ensureEnabled(this);
        return START_STICKY;
    }

    /** ColorOS freezes a process whose task was swiped, even while this service is still running. */
    @Override public void onTaskRemoved(android.content.Intent rootIntent) {
        if (stopRequested || !mayWake(this)) return;
        io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "TASK_REMOVED keep");
        String title = noticeTitle == null || noticeTitle.isBlank() ? "正在连接手环" : noticeTitle;
        noticeTitle = null;
        show(title);
        io.github.miam1ku.mibandoplusbridge.notify.BandNotificationListener.ensureEnabled(this);
        synchronized (stopLock) {
            retryNow = true;
            stopLock.notifyAll();
        }
    }

    private void requestStop() {
        synchronized (stopLock) {
            stopRequested = true;
            stopLock.notifyAll();
        }
        int dropped = HELD_NOTIFICATIONS.failAll(new IllegalStateException("NOTIFICATION_SESSION_UNAVAILABLE"));
        if (dropped > 0) io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this,
                "NOTIFY_DROP reason=stopped count=" + dropped);
        SppDiagnosticClient active = client;
        if (active != null) active.close();
        HuaweiSupportProvider huawei = huaweiProvider;
        if (huawei != null && huawei.getTransport() != null) {
            huawei.getTransport().disconnect();
        }
    }

    private void supervise() {
        BandStateRepository repository = new BandStateRepository(this);
        long backoff = INITIAL_BACKOFF_MS;
        String status = "STARTING";
        try {
            try { repository.markSessionClosed(); } catch (RuntimeException ignored) { }
            while (!stopRequested) {
                // 华为 GT3 分支：注册表里有地址就走华为链路，小米路径原样不动。
                //
                // 为什么华为要绕过下面的 registered/nativeReady 门禁：那两个
                // 检查是小米五步向导的产物（Mi Fitness 导入 + NATIVE 所有权），
                // GT3 一条都不适用，它的注册事实在 HuaweiDeviceRegistry。
                String huaweiAddress = HuaweiDeviceRegistry.address(this);
                if (!huaweiAddress.isBlank()) {
                    long huaweiEpoch = SESSION_IDS.incrementAndGet();
                    sessionEpoch = huaweiEpoch;
                    AtomicBoolean huaweiAuthenticated = new AtomicBoolean();
                    status = runHuaweiSession(repository, huaweiAddress, huaweiEpoch,
                            huaweiAuthenticated);
                    if (huaweiAuthenticated.get()) backoff = INITIAL_BACKOFF_MS;
                    if (stopRequested) break;
                    if (!"STOPPED".equals(status)) {
                        show(retryable(status) ? "正在重连" : "等待重连");
                    }
                    long huaweiDelay = retryable(status) ? backoff : QUIET_BACKOFF_MS;
                    if (!waitForRetry(huaweiDelay)) break;
                    if (!huaweiAuthenticated.get() && retryable(status)) {
                        backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
                    }
                    continue;
                }
                OwnershipController owner = new OwnershipController(this);
                if (!repository.isRegistered() || !owner.nativeReady()) {
                    status = "REGISTERED_NATIVE_DEVICE_REQUIRED registered="
                            + repository.isRegistered() + " mode=" + owner.mode()
                            + " ready=" + owner.nativeReady();
                    android.util.Log.i("OplusBandBridge", status);
                    show("连接已停止");
                    break;
                }
                bindHealthHost();
                AtomicInteger stored = new AtomicInteger();
                AtomicBoolean authenticated = new AtomicBoolean();
                long epoch = SESSION_IDS.incrementAndGet();
                sessionEpoch = epoch;
                SppDiagnosticClient active = new SppDiagnosticClient(this, code -> {
                    if (stopRequested || epoch != sessionEpoch) return;
                    if ("HISTORY_FRAGMENT_RECEIVED".equals(code)) {
                        coordinator.execute(() -> {
                            if (epoch == sessionEpoch && historySync != null) historySync.progress();
                        });
                    } else if ("HISTORY_FILE_ARCHIVED".equals(code)) {
                        healthReplay.request();
                        stored.incrementAndGet();
                    } else if ("HISTORY_FORMAT_UNSUPPORTED".equals(code)) {
                        healthCollectionStatus(code);
                    } else if ("HISTORY_FILE_REJECTED".equals(code) || "HISTORY_STORAGE_FAILED".equals(code)
                            || "HISTORY_CONFIRMATION_PENDING".equals(code)) {
                        coordinator.execute(() -> {
                            if (epoch == sessionEpoch && historySync != null) historySync.rejected(code);
                        });
                        healthCollectionStatus(code);
                    }
                });
                active.setSleepFiles(measurements -> {
                    try {
                        coordinator.execute(() -> {
                            if (!stopRequested) noteSleepFile(measurements);
                        });
                    } catch (java.util.concurrent.RejectedExecutionException stopping) { }
                });
                client = active;
                try {
                    active.runLive(result -> {
                        authenticated.set(true);
                        try {
                            repository.recordVerifiedDevice(result.batteryPercent(), result.batteryState(), true,
                                    result.firmware(), result.hardware());
                        } catch (Exception failure) {
                            throw new IllegalStateException(failure.getMessage(), failure);
                        }
                        if (!repository.isRegistered() || stopRequested) return;
                        show("手环已连接");
                    }, queue -> coordinator.execute(() -> {
                        if (client != active || stopRequested) {
                            queue.close(new IllegalStateException("SESSION_CLOSED"));
                            return;
                        }
                        commands = queue;
                        replayHeld();
                        calls.connected();
                        logFeatures();
                        io.github.miam1ku.mibandoplusbridge.notify.BandNotificationListener.connectionChanged();
                        io.github.miam1ku.mibandoplusbridge.notify.NativeMusic.requestRefresh(this);
                        historySync = new LiveHistorySync(queue, coordinator, () -> {
                            try {
                                repository.recordSyncCompleted();
                                clearStoragePause();
                            } catch (RuntimeException unavailable) {
                                healthCollectionStatus("HEALTH_STORAGE_UNAVAILABLE");
                            }
                        }, this::healthCollectionStatus, healthReplay::hasCapacity);
                        long now = System.nanoTime();
                        nextBatteryAt = now + TimeUnit.MINUTES.toNanos(IDLE_POLL_MINUTES);
                        nextHealthAt = now + TimeUnit.MINUTES.toNanos(IDLE_POLL_MINUTES);
                        nextSleepFileAt = now + TimeUnit.MINUTES.toNanos(10);
                        nextWeatherAt = now + TimeUnit.MINUTES.toNanos(30);
                        weatherSync.refreshAndSend();
                        syncRequested = false;
                        healthReplay.request();
                        queue.send(realtimeStats(REALTIME_STATS_START));
                        realtimeStatsStopPending = true;
                        coordinator.schedule(() -> {
                            if (epoch == sessionEpoch) stopRealtimeStats();
                        }, REALTIME_STATS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        historySync.request(true);
                        syncDnd();
                        requestSleepState();
                    }), fileId -> coordinator.execute(() -> {
                        if (client == active && !stopRequested && historySync != null) historySync.saved(fileId);
                    }), command -> {
                        if (command.getType() == 2) {
                            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(BandLiveService.this,
                                    describeSystem(command));
                            noteBandRules(command);
                            if (command.getSubtype() == 109) adoptStoredBand();
                            applyBandManual(command);
                            noteSleep(command);
                        } else if (command.getType() == 7 || command.getType() == 18) {
                            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(BandLiveService.this,
                                    "rx type=" + command.getType() + " subtype=" + command.getSubtype()
                                            + " status=" + command.getStatus());
                        }
                        if (command.getType() == 7) {
                            android.util.Log.i("OplusBandBridge", "NOTIFY_IN subtype=" + command.getSubtype()
                                    + " status=" + command.getStatus());
                            if (command.getSubtype() == 16 && command.hasNotification()
                                    && command.getNotification().hasNotificationIconQuery()) {
                                offerIcon(command.getNotification().getNotificationIconQuery().getPackage());
                            }
                            if (command.getSubtype() == 15 && command.hasNotification()
                                    && command.getNotification().hasNotificationIconRequest()) {
                                uploadIcon(active, command.getNotification().getNotificationIconRequest());
                            }
                            if (calls != null) calls.onBandCommand(command);
                        }
                        if (command.getType() == 2 && command.getSubtype() == 17) {
                            main.post(() -> io.github.miam1ku.mibandoplusbridge.notify.FindPhone
                                    .onBandCommand(BandLiveService.this, command));
                        }
                        if (command.getType() == 18) {
                            if (command.getSubtype() == 0) {
                                io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(BandLiveService.this,
                                        "MUSIC_REQ");
                                main.post(BandLiveService.this::replyMusic);
                            }
                            main.post(() -> io.github.miam1ku.mibandoplusbridge.notify.NativeMusic
                                    .onBandCommand(BandLiveService.this, command));
                        }
                        if (command.getType() == 2 && command.getSubtype() == 43) {
                            main.post(BandLiveService.this::replySilent);
                        }
                        if (command.getType() == 17 && command.getSubtype() == 16) {
                            int op = command.hasSchedule() && command.getSchedule().hasPhoneAlarmOperation()
                                    ? command.getSchedule().getPhoneAlarmOperation().getOpCode() : -1;
                            int alarmId = op >= 0 && command.getSchedule().getPhoneAlarmOperation().hasPhoneAlarm()
                                    ? command.getSchedule().getPhoneAlarmOperation().getPhoneAlarm().getId() : -1;
                            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(BandLiveService.this,
                                    "ALARM_FROM_BAND op=" + op + " id=" + alarmId);
                            if (op == 1 || op == 2) {
                                io.github.miam1ku.mibandoplusbridge.integration.PhoneAlarmProvider.offer(op, alarmId);
                            }
                        }
                        weatherSync.onCommand(command);
                        if (command.getType() == 8 && command.getSubtype() == 47 && command.hasHealth()
                                && command.getHealth().hasRealTimeStats()) {
                            int steps = command.getHealth().getRealTimeStats().getSteps();
                            coordinator.execute(() -> {
                                if (epoch != sessionEpoch || steps < 0 || steps > 200_000) return;
                                try {
                                    repository.recordDailySteps(steps, System.currentTimeMillis(), true);
                                } catch (RuntimeException ignored) { }
                                if (steps > 0) stopRealtimeStats();
                            });
                        }
                        if (command.getType() == 8 && command.hasStatus() && command.getStatus() != 0) {
                            coordinator.execute(() -> {
                                if (epoch == sessionEpoch && historySync != null) historySync.onCommand(command);
                            });
                        }
                    });
                    status = "CLOSED files=" + stored.get();
                } catch (Exception failure) {
                    status = failure instanceof SppDiagnosticClient.Failure typed
                            ? typed.code : failure.getClass().getSimpleName();
                    show(retryable(status) ? "正在重连" : "等待重连");
                } finally {
                    client = null;
                    commands = null;
                    realtimeStatsStopPending = false;
                    try {
                        coordinator.execute(() -> {
                            if (epoch == sessionEpoch && commands == null && historySync != null) {
                                historySync.close();
                                historySync = null;
                            }
                        });
                    } catch (java.util.concurrent.RejectedExecutionException stopping) { }
                    weatherSync.onDisconnected();
                    calls.disconnected();
                    io.github.miam1ku.mibandoplusbridge.notify.BandNotificationListener.connectionChanged();
                    try { repository.markSessionClosed(); } catch (RuntimeException ignored) { }
                }
                if (authenticated.get()) backoff = INITIAL_BACKOFF_MS;
                if (stopRequested) break;
                long delay = retryable(status) ? backoff : QUIET_BACKOFF_MS;
                if (!waitForRetry(delay)) break;
                if (!authenticated.get() && retryable(status)) backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
            }
        } finally {
            writeStatus(stopRequested ? "STOPPED" : status);
            running.set(false);
            stopped.countDown();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    /**
     * 华为 GT3 单次会话：连接 → 握手 → 电量 → 记账 → 持有链路直到断开或停机。
     *
     * <p>与小米分支的结构差异：{@code SppDiagnosticClient.runLive} 内部自带
     * 重连、文件回传、命令队列一整套；华为侧协议栈只提供「请求链」，
     * 会话生死由本方法管理 —— 断开后交给 supervise 主循环按 backoff 重连。
     * 这也是为什么本方法只做「一次会话」而不内部循环：重连策略（backoff
     * 增长、停机响应）已经在主循环里，再来一套必然双重退避。</p>
     *
     * <p>返回值状态码经 {@link #retryable} 判定；{@code authenticated} 是出参，
     * 主循环据此决定 backoff 是否复位（握手成功过说明链路本身没问题）。</p>
     *
     * @param epoch 本次会话代号，用于丢弃上一轮的迟到回调
     */
    /**
     * 协议探针开关：{@code no_backup/huawei-probe.on} 存在即打开。
     *
     * <p>用文件而不是编译期常量，是因为探针要在真机上反复开关 —— 每次为了改
     * 一个布尔值重新编译安装一遍，排查节奏会被拖垮。adb 一行即可切换：
     * {@code run-as <pkg> sh -c 'echo on > no_backup/huawei-probe.on'}。
     */
    /**
     * 睡眠分段时间戳的前 4 字节 = 秒级 Unix 时间（大端）。
     *
     * <p>后 2 字节是什么还没确认：真机上相邻时间戳的间隔与它并不相等（08:44 之后
     * 一条是 3，下一条却在 08:48），所以只用它做区间端点，不做时长累加。
     */
    private static long firstFourBytes(byte[] timestamp) {
        if (timestamp == null || timestamp.length < 4) return 0;
        return ((long) (timestamp[0] & 0xFF) << 24)
                | ((long) (timestamp[1] & 0xFF) << 16)
                | ((long) (timestamp[2] & 0xFF) << 8)
                | (timestamp[3] & 0xFF);
    }

    /**
     * 认证后数据拉取的串行驱动。
     *
     * <p>stage 顺序：0 初始步数（通道验证）→ 1 当日汇总 → 2 心率 → 3 步数
     * 历史（24h 全量分钟明细）→ 4 通知约束（0x02）→ 5 睡眠 → 6 表盘列表
     * （0x27）。每段的终态
     * 回调（成功<b>和</b>失败）都推进到下一段 —— 单段失败不拖死后面，各段
     * 数据彼此独立。
     *
     * <p>★ 必须串行的原因（2026-10-14 实测定位，不要再改回并发）：0x07 上
     * 多链并发时手表间歇性丢响应且 15s 请求超时器不触发，睡眠链卡在
     * record 2/5 无声挂死；同命令链（0x0A/0x0B）并发还会在响应分发处
     * 先到先认领互相抢包。
     */
    /**
     * 认证完成后读一次产品信息（DeviceConfig / ProductInfo，0x07），把固件 / 硬件版本
     * 落进 HuaweiDeviceRegistry，设备信息卡片才不再显示「未读取」。这是一条独立的
     * 读请求（serviceId 0x01，与数据链 serviceId 0x07 互不抢响应），与下面的串行
     * 数据链并行发起不会撞车。
     */
    private void readHuaweiProductInfo(final HuaweiSupportProvider provider) {
        GetProductInfoRequest req = new GetProductInfoRequest(provider);
        req.setFinalizeReq(new io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests
                .Request.RequestCallback() {
            @Override public void call() { }
            @Override public void handleException(
                    io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests
                            .Request.ResponseParseException e) {
                Log.i("OplusBandBridge", "HUAWEI_PRODUCT_INFO_FAILED "
                        + (e == null ? "" : e.getMessage()));
            }
        });
        try {
            req.doPerform();
        } catch (IOException e) {
            Log.i("OplusBandBridge", "HUAWEI_PRODUCT_INFO_FAILED " + e.getMessage());
        }
    }

    private void runSerialStage(final HuaweiSupportProvider provider,
            final AtomicBoolean finalized, final long epoch, final int index) {
        if (finalized.get() || epoch != sessionEpoch) return;
        final Runnable advance = () -> runSerialStage(provider, finalized, epoch, index + 1);
        switch (index) {
            case 0: {
                // 初始步数：一是验证 FitnessData(0x07) 通道在 GT3 上可用，
                // 二是拿到手表真实步数供日志核对。
                int endSec = (int) (System.currentTimeMillis() / 1000L);
                provider.readSteps(endSec - 24 * 3600, endSec,
                        new GetFitnessDataRequest.StepsListener() {
                            @Override
                            public void onSteps(java.util.List<FitnessData.MessageData
                                    .StepResponse.SubContainer> samples) {
                                if (finalized.get() || epoch != sessionEpoch) return;
                                int total = 0;
                                int latest = -1;
                                for (FitnessData.MessageData.StepResponse.SubContainer s : samples) {
                                    if (s.steps > 0) total += s.steps;
                                    if (s.steps >= 0) latest = s.steps;
                                }
                                io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(
                                        BandLiveService.this, "HUAWEI_STEPS samples="
                                                + samples.size() + " total=" + total
                                                + " latest=" + latest);
                                advance.run();
                            }

                            @Override
                            public void onError(String message) {
                                if (finalized.get() || epoch != sessionEpoch) return;
                                io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(
                                        BandLiveService.this, "HUAWEI_STEPS_FAILED " + message);
                                advance.run();
                            }
                        });
                return;
            }
            case 1: {
                // 当日汇总：分钟级明细在无活动时段全是空记录，真正能喂给
                // 健康应用的当日总量是这个 0x03 总计。
                provider.readFitnessTotals(new GetFitnessTotalsRequest.TotalsListener() {
                    @Override
                    public void onTotals(int steps, int calories, int distance) {
                        if (finalized.get() || epoch != sessionEpoch) return;
                        io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(
                                BandLiveService.this, "HUAWEI_TOTALS steps=" + steps
                                        + " calories=" + calories + " distance=" + distance);
                        HuaweiDeviceRegistry.noteSteps(BandLiveService.this, steps);
                        HuaweiDeviceRegistry.publish(BandLiveService.this);
                        try {
                            String deviceId = HuaweiDeviceRegistry.deviceId(BandLiveService.this);
                            if (deviceId != null && !deviceId.isBlank()) {
                                HuaweiStepsImporter.enqueueDayTotal(BandLiveService.this, deviceId,
                                        steps, calories, distance, java.time.ZoneId.systemDefault());
                            }
                        } catch (Throwable ignored) { }
                        advance.run();
                    }

                    @Override
                    public void onError(String message) {
                        if (finalized.get() || epoch != sessionEpoch) return;
                        io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(
                                BandLiveService.this, "HUAWEI_TOTALS_FAILED " + message);
                        advance.run();
                    }
                });
                return;
            }
            case 2: {
                // 心率：搭在分钟明细里回来（GT3 没有单独的「当前心率」命令），
                // 取最近一小时窗口，里面才有真实样本。
                provider.readHeartRate(new HuaweiSupportProvider.HeartRateListener() {
                    @Override
                    public void onHeartRate(int bpm, int measuredAtSec) {
                        if (finalized.get() || epoch != sessionEpoch) return;
                        io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(
                                BandLiveService.this, "HUAWEI_HR bpm=" + bpm
                                        + " at=" + measuredAtSec);
                        if (HuaweiDeviceRegistry.noteHeartRate(BandLiveService.this, bpm)) {
                            HuaweiDeviceRegistry.publish(BandLiveService.this);
                        }
                        advance.run();
                    }

                    @Override
                    public void onError(String message) {
                        if (finalized.get() || epoch != sessionEpoch) return;
                        io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(
                                BandLiveService.this, "HUAWEI_HR_FAILED " + message);
                        advance.run();
                    }
                }, samples -> enqueueHuaweiMinutes(samples, 3600L));
                return;
            }
            case 3: {
                // 步数历史：拉一整天分钟明细进健康队列，OPPO 才有曲线而不是
                // 只有一个当日总数。完成后由回调推进到睡眠段。
                pullHuaweiStepHistory(provider, finalized, epoch, advance);
                return;
            }
            case 4: {
                // 通知内容长度上限（0x02 服务）：GT3 对超长通知不报错、只无声丢弃，
                // 必须先拿到这个值，sendNotification 才能正确截断。放在 0x07 数据链
                // 全部收尾之后，避免与睡眠/步数历史交错。
                provider.requestNotificationConstraints((limit, error) -> {
                    if (finalized.get() || epoch != sessionEpoch) return;
                    io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(
                            BandLiveService.this, "HUAWEI_NOTIF_CONSTRAINTS limit=" + limit
                                    + (error == null ? "" : " error=" + error));
                    advance.run();
                });
                return;
            }
            case 5: {
                // 睡眠：24h 窗口，挂在步数历史链全部到齐之后 —— 通道此刻空闲。
                maybePullSleep(provider, finalized, epoch, advance);
                return;
            }
            case 6: {
                // 表盘列表（0x27）：设备页表盘区与桥接表盘管理的数据源。
                // 低优先级，失败静默推进。
                provider.readWatchfaces(new io.github.miam1ku.mibandoplusbridge
                        .protocol.huawei.requests.GetWatchfacesListRequest
                        .WatchfaceListener() {
                    @Override
                    public void onWatchfaces(java.util.List<io.github.miam1ku
                            .mibandoplusbridge.protocol.huawei.packets.Watchface
                            .InstalledWatchfaceInfo> watchfaces,
                            java.util.HashMap<String, String> names) {
                        if (finalized.get() || epoch != sessionEpoch) return;
                        io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(
                                BandLiveService.this, "HUAWEI_WATCHFACES count="
                                        + watchfaces.size());
                        io.github.miam1ku.mibandoplusbridge.data.HuaweiDeviceRegistry
                                .noteWatchfaces(BandLiveService.this,
                                        serializeWatchfaces(watchfaces, names));
                        io.github.miam1ku.mibandoplusbridge.data.HuaweiDeviceRegistry
                                .publish(BandLiveService.this);
                        advance.run();
                    }

                    @Override
                    public void onError(String message) {
                        if (finalized.get() || epoch != sessionEpoch) return;
                        io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(
                                BandLiveService.this, "HUAWEI_WATCHFACES_FAILED " + message);
                        advance.run();
                    }
                });
                return;
            }
            default:
                // 7 = 链走完，无事可做。
        }
    }

    /** 表盘列表 → JSON 数组字符串（name/version/current/factory/display）。 */
    private static String serializeWatchfaces(java.util.List<io.github.miam1ku
            .mibandoplusbridge.protocol.huawei.packets.Watchface
            .InstalledWatchfaceInfo> watchfaces,
            java.util.HashMap<String, String> names) {
        org.json.JSONArray array = new org.json.JSONArray();
        for (io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.Watchface
                .InstalledWatchfaceInfo info : watchfaces) {
            if (info == null || info.fileName == null || info.fileName.isBlank()) continue;
            try {
                String display = names == null ? null : names.get(info.fileName);
                array.put(new org.json.JSONObject()
                        .put("name", info.fileName)
                        .put("version", info.version == null ? "" : info.version)
                        .put("current", info.isCurrent())
                        .put("factory", info.isFactory())
                        .put("editable", info.isEditable())
                        .put("display", display == null || display.isBlank()
                                ? info.fileName : display));
            } catch (org.json.JSONException ignored) {
                // 单条序列化失败跳过，不影响其余表盘。
            }
        }
        return array.toString();
    }

    /**
     * 拉一整天的分钟明细进健康队列。
     *
     * <p>回调来自 transport 读线程，SQLite 写必须挪到后台线程 —— 否则会把蓝牙
     * 读线程焊在读缓冲区上。整窗 27~30 条记录是串行的，所以放在这里等结果，
     * 不打断握手后的其它命令。
     *
     * @param onDone 链完成（成功或失败）后推进串行拉取的下一段
     */
    /**
     * 把一批分钟样本写进健康队列。
     *
     * <p>GT3 的心率搭在步数分钟明细里，所以周期心跳链拉心率时同一批样本里
     * 已经带着那一分钟的步数、卡路里和距离。之前这条链只取心率、步数丢掉，
     * 宿主健康里的步数明细就停在握手那一次拉到的窗口，之后越走越旧 ——
     * 表现就是健康 App 里的步数柱状图一直是空的、步数总量不动。
     *
     * <p>这里把样本交给 {@link io.github.miam1ku.mibandoplusbridge.data.HuaweiStepsImporter}，
     * 它自己负责窗口裁剪、清理旧行和去重，重复喂同一批样本是安全的。
     *
     * @param windowSec 这批样本覆盖的窗口长度，用来定位窗口起点
     */
    private void enqueueHuaweiMinutes(
            java.util.List<io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets
                    .FitnessData.MessageData.StepResponse.SubContainer> samples,
            long windowSec) {
        if (samples == null || samples.isEmpty()) return;
        long now = System.currentTimeMillis();
        long from = now - windowSec * 1000L;
        java.util.List<io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets
                .FitnessData.MessageData.StepResponse.SubContainer> copy =
                new java.util.ArrayList<>(samples);
        new Thread(() -> {
            io.github.miam1ku.mibandoplusbridge.data.HuaweiStepsImporter.enqueue(
                    BandLiveService.this,
                    HuaweiDeviceRegistry.deviceId(BandLiveService.this), copy,
                    java.time.ZoneId.systemDefault(), from, now);
        }, "HuaweiMinuteArchive").start();
    }

    private void pullHuaweiStepHistory(
            HuaweiSupportProvider provider,
            java.util.concurrent.atomic.AtomicBoolean finalized, long epoch,
            Runnable onDone) {        long now = System.currentTimeMillis();
        long from = now - 24L * 3_600_000L;
        provider.readSteps((int) (from / 1000L), (int) (now / 1000L), true,
                new io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests
                        .GetFitnessDataRequest.StepsListener() {
                    @Override
                    public void onSteps(java.util.List<io.github.miam1ku.mibandoplusbridge
                            .protocol.huawei.packets.FitnessData.MessageData
                            .StepResponse.SubContainer> samples) {
                        if (finalized.get() || epoch != sessionEpoch) return;
                        java.util.List<io.github.miam1ku.mibandoplusbridge.protocol.huawei
                                .packets.FitnessData.MessageData.StepResponse.SubContainer> copy =
                                new java.util.ArrayList<>(samples);
                        new Thread(() -> {
                            io.github.miam1ku.mibandoplusbridge.data.HuaweiStepsImporter.enqueue(
                                    BandLiveService.this,
                                    HuaweiDeviceRegistry.deviceId(BandLiveService.this), copy,
                                    java.time.ZoneId.systemDefault(), from, now);
                        }, "HuaweiStepHistory").start();
                        // 导入在后台线程跑，协议通道此刻已空闲，直接推进下一段。
                        onDone.run();
                    }

                    @Override
                    public void onError(String message) {
                        if (finalized.get() || epoch != sessionEpoch) return;
                        io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(
                                BandLiveService.this, "HUAWEI_STEPS_FAILED " + message);
                        onDone.run();
                    }
                });
    }

    /**
     * 周期性电量刷新（0x01/0x08）。结果写进 {@link HuaweiDeviceRegistry#noteSession}
     * 并投影到 band-state，设备页卡片显示的就是这份值。
     *
     * <p>失败静默：电量是低优先级数据，读不到就保留上次已知值（握手时已读
     * 过一次），不值得为此打断 5 分钟周期里的其它拉取。
     */
    private void refreshHuaweiBattery(HuaweiSupportProvider provider,
            java.util.concurrent.atomic.AtomicBoolean finalized, long epoch) {
        provider.readBattery((level, error) -> {
            if (finalized.get() || epoch != sessionEpoch) return;
            if (error != null || level < 0) return;
            HuaweiDeviceRegistry.noteSession(BandLiveService.this, level,
                    provider.isBatteryCharging(), true);
            HuaweiDeviceRegistry.publish(BandLiveService.this);
        });
    }

    /**
     * 发起一次睡眠拉取（24h 窗口），带在飞互斥 + 代际号防陈旧回调 +
     * 成功后 15 分钟内不重拉。
     *
     * <p>睡眠链 (0x0C/0x0D) 对并发极敏感（见类头部注释），只在通道空闲时
     * 调用：握手串行链的末段，或 5 分钟周期里心率回调返回之后。卡死由
     * {@link #clearStuckSleepPull(HuaweiSupportProvider)} 周期复位。
     */
    private void maybePullSleep(final HuaweiSupportProvider provider,
            final AtomicBoolean finalized, final long epoch, final Runnable advance) {
        if (finalized.get() || epoch != sessionEpoch) return;
        long now = System.currentTimeMillis();
        if (sleepPulledOkAtMs > 0 && now - sleepPulledOkAtMs < SLEEP_REPULL_MS) {
            // 本会话已拉过睡眠且在抑制窗内：跳过拉取，但链还要继续走
            // （表盘等后续阶段不能因睡眠被抑制而饿死）。
            advance.run();
            return;
        }
        if (!sleepPullBusy.compareAndSet(false, true)) {
            // 上一轮睡眠链还在飞：不重复拉，但同样要把链放过去 ——
            // 并发同通道链会无声挂死，宁可让后续阶段在通道忙时排队。
            advance.run();
            return;
        }
        final long gen = ++sleepPullGen;
        sleepPullSinceMs = now;
        io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this,
                "HUAWEI_SLEEP_PULL gen=" + gen);
        // 24h 窗口足够覆盖「昨夜→今晨」整夜睡眠（夜眠结束时间仍在窗口内）。
        provider.readSleep(24 * 3600, new GetSleepDataRequest.SleepListener() {
            @Override
            public void onSleep(int total,
                    java.util.List<FitnessData.MessageData.SleepResponse.SubContainer> segments) {
                // 0x0D 小睡已到；整夜睡眠主体走 0x2C 文件通道，busy 标志在 0x2C
                // 收尾后由 finishSleep() 释放 —— 否则 0x2C 帧会与 0x07 拉取在
                // 同一条 RFCOMM 上交错，触发手表静默丢响应（2026-10-14 实测教训）。
                if (sleepPullGen == gen) {
                    sleepPulledOkAtMs = System.currentTimeMillis();
                }
                if (finalized.get() || epoch != sessionEpoch) return;
                handleSleepOnce(provider, finalized, epoch, total, segments, advance);
            }

            @Override
            public void onError(String message) {
                if (sleepPullGen == gen) sleepPullBusy.set(false);
                if (finalized.get() || epoch != sessionEpoch) return;
                io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(BandLiveService.this,
                        "HUAWEI_SLEEP_FAILED gen=" + gen + " " + message);
                advance.run();
            }
        });
    }

    /**
     * 周期 watchdog：睡眠链卡死（既无 onSleep 也无 onError —— 2026-10-14
     * 实测超时器不触发的那种无声挂死）超过 {@link #SLEEP_STUCK_MS} 时，
     * 摘掉卡死的等待者并复位状态，让下一轮重试。
     *
     * <p>必须同时摘响应管理器里的残留请求：分发按 (service, command)
     * 先到先认领，卡死的 0x0D 等待者会抢走重试链的响应。
     */
    private void clearStuckSleepPull(HuaweiSupportProvider provider) {
        if (!sleepPullBusy.get()) return;
        long since = sleepPullSinceMs;
        if (since <= 0 || System.currentTimeMillis() - since < SLEEP_STUCK_MS) return;
        io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this,
                "HUAWEI_SLEEP_STUCK gen=" + sleepPullGen + " sinceMs="
                        + (System.currentTimeMillis() - since)
                        + " 清除残留等待者，稍后重试");
        provider.clearSleepRequests();
        sleepPullGen++;
        sleepPullBusy.set(false);
    }

    /**
     * 处理一次睡眠拉取：唤醒易用日志 + 写入 OPPO 健康队列。
     *
     * <p>回调来自 transport 的读线程，而这里要开 SQLite 写，必须挪到后台线程，
     * 否则会把蓝牙读线程焊在读缓冲区上。
     */
    private void handleSleepOnce(
            io.github.miam1ku.mibandoplusbridge.protocol.huawei.service.HuaweiSupportProvider provider,
            java.util.concurrent.atomic.AtomicBoolean finalized, long epoch,
            int total,
            java.util.List<io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets
                    .FitnessData.MessageData.SleepResponse.SubContainer> segments,
            Runnable advance) {
        BandLiveService service = BandLiveService.this;
        java.util.List<io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets
                .FitnessData.MessageData.SleepResponse.SubContainer> copy =
                new java.util.ArrayList<>(segments);
        new Thread(() -> {
            java.util.List<io.github.miam1ku.mibandoplusbridge.data.HuaweiSleepImporter.Stage>
                    stages = io.github.miam1ku.mibandoplusbridge.data.HuaweiSleepImporter
                            .parse(copy);
            java.util.List<io.github.miam1ku.mibandoplusbridge.data.HuaweiSleepImporter.Session>
                    sessions = io.github.miam1ku.mibandoplusbridge.data.HuaweiSleepImporter
                            .sessions(stages);
            long lastStart = 0;
            long lastEnd = 0;
            for (io.github.miam1ku.mibandoplusbridge.data.HuaweiSleepImporter.Session session
                    : sessions) {
                if (session.endMs() > lastEnd) {
                    lastStart = session.startMs();
                    lastEnd = session.endMs();
                }
                io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(service,
                        "HUAWEI_SLEEP_SESSION " + sleepClock(session.startMs()) + " - "
                                + sleepClock(session.endMs()) + " stages="
                                + session.stages().size() + " sleepMin="
                                + session.sleepMs() / 60_000L + " coverage="
                                + String.format(java.util.Locale.ROOT, "%.2f",
                                        session.coverage()));
            }
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(service,
                    "HUAWEI_SLEEP records=" + total + " segments=" + stages.size()
                            + " sessions=" + sessions.size());
            // 0x0D 小睡的「最近一次睡眠」暂存，0x2C 夜眠覆盖后取更晚的结束。
            final long[] last = {lastStart, lastEnd};
            final long now = System.currentTimeMillis();
            final long windowStartMs = now - 24L * 3_600_000L;
            final long windowEndMs = now;
            // TruSleep 文件下载窗口：GT3 的 sleep_state 文件按「上次同步锚点」组织，
            // 24h 滚动窗口会让 init 报空（实测 fileSize=0）。对齐 GB：首同步从
            // 2000-01-01 起拉全量，之后用 lastSync。这里先用 30 天宽窗做实证，
            // 若 2C 回报非空则证明是窗口锚点问题。
            final long truStartMs = now - 30L * 24 * 3_600_000L;
            final String deviceId = HuaweiDeviceRegistry.deviceId(service);
            // ★ TruSleep 专项（2026-10-23）：整夜睡眠走 0x2C 文件通道，必须先把
            // 0x2C 文件拉回、解析出夜眠区间，再与 0x0D 小睡合并入队。0x2C 全程
            // 复用 sleepPullBusy，收尾由 finishSleep() 释放并推进链。
            final Runnable startTruSleep = () -> {
            io.github.miam1ku.mibandoplusbridge.protocol.huawei.service
                    .HuaweiSleepFileDownload download =
                    new io.github.miam1ku.mibandoplusbridge.protocol.huawei.service
                            .HuaweiSleepFileDownload(provider,
                            (int) (truStartMs / 1000L), (int) (windowEndMs / 1000L),
                            new io.github.miam1ku.mibandoplusbridge.protocol.huawei.service
                                    .HuaweiSleepFileDownload.Callback() {
                                @Override
                                public void onComplete(byte[] data) {
                                    java.util.List<io.github.miam1ku.mibandoplusbridge.data
                                            .HuaweiTruSleepParser.TruSleepInterval> tru =
                                            io.github.miam1ku.mibandoplusbridge.data
                                                    .HuaweiTruSleepParser.parseState(data);
                                    StringBuilder iv = new StringBuilder();
                                    for (io.github.miam1ku.mibandoplusbridge.data
                                            .HuaweiTruSleepParser.TruSleepInterval t : tru) {
                                        iv.append(sleepClock(t.startSec() * 1000L)).append("-")
                                                .append(sleepClock(t.endSec() * 1000L))
                                                .append(" ");
                                    }
                                    io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(
                                            service, "HUAWEI_TRUSLEEP intervals=" + tru.size()
                                                    + " " + iv);
                                    io.github.miam1ku.mibandoplusbridge.data.HuaweiSleepImporter
                                            .Result queued = io.github.miam1ku.mibandoplusbridge
                                            .data.HuaweiSleepImporter.enqueue(service, deviceId,
                                            copy, tru, java.time.ZoneId.systemDefault(),
                                            windowStartMs, windowEndMs);
                                    io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(
                                            service, "HUAWEI_SLEEP_IMPORT sessions="
                                                    + queued.sessions() + " skipped="
                                                    + queued.skipped() + " records="
                                                    + queued.records() + " purged="
                                                    + queued.purged());
                                    long fStart = last[0], fEnd = last[1];
                                    for (io.github.miam1ku.mibandoplusbridge.data
                                            .HuaweiTruSleepParser.TruSleepInterval t : tru) {
                                        if (t.endSec() * 1000L > fEnd) {
                                            fStart = t.startSec() * 1000L;
                                            fEnd = t.endSec() * 1000L;
                                        }
                                    }
                                    if (HuaweiDeviceRegistry.noteSleep(service, fStart, fEnd)) {
                                        HuaweiDeviceRegistry.publish(service);
                                    }
                                    finishSleep(finalized, epoch, advance);
                                }

                                @Override
                                public void onError(String message) {
                                    io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(
                                            service, "HUAWEI_TRUSLEEP_FAILED " + message);
                                    // 0x2C 失败：退化为只写 0x0D 小睡，不让夜眠缺失
                                    // 把白天小睡也拖没。
                                    io.github.miam1ku.mibandoplusbridge.data.HuaweiSleepImporter
                                            .Result queued = io.github.miam1ku.mibandoplusbridge
                                            .data.HuaweiSleepImporter.enqueue(service, deviceId,
                                            copy, java.time.ZoneId.systemDefault(),
                                            windowStartMs, windowEndMs);
                                    io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(
                                            service, "HUAWEI_SLEEP_IMPORT sessions="
                                                    + queued.sessions() + " skipped="
                                                    + queued.skipped() + " records="
                                                    + queued.records() + " purged="
                                                    + queued.purged());
                                    if (HuaweiDeviceRegistry.noteSleep(service, last[0], last[1])) {
                                        HuaweiDeviceRegistry.publish(service);
                                    }
                                    finishSleep(finalized, epoch, advance);
                                }
                            });
            download.start();
            };
            // 0x07 0x16 SetTruSleep(true)：GB 在下载睡眠文件前必发；GT3 的 TruSleep
            // 文件通道只在手表侧已启用 TruSleep 时才有数据，否则 2C init 恒定报空。
            // 启用具有持久性（手表端保留），每次睡眠拉取前重发一次无副作用。
            final Runnable enableTruSleepThenDownload = () -> {
                io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests
                        .SetTruSleepRequest setReq =
                        new io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests
                                .SetTruSleepRequest(provider, true);
                setReq.setFinalizeReq(new io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests
                        .Request.RequestCallback(provider) {
                            @Override
                            public void call(io.github.miam1ku.mibandoplusbridge.protocol.huawei
                                    .requests.Request request) {
                                io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(service,
                                        "HUAWEI_TRUSLEEP_ENABLED ok");
                                startTruSleep.run();
                            }

                            @Override
                            public void handleException(io.github.miam1ku.mibandoplusbridge
                                    .protocol.huawei.requests.Request request,
                                    io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests
                                            .Request.ResponseParseException e) {
                                io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(service,
                                        "HUAWEI_TRUSLEEP_ENABLED_FAILED " + e.getMessage());
                                startTruSleep.run();
                            }
                        });
                try {
                    setReq.doPerform();
                } catch (java.io.IOException e) {
                    io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(service,
                            "HUAWEI_TRUSLEEP_ENABLED_FAILED send: " + e.getMessage());
                    startTruSleep.run();
                }
            };
            // 0x01 0x31 SettingRelated 能力探针（GB 同款）：手表在此声明
            // truSleepNewSync 位图，决定睡眠文件走 2C 新同步还是 0x0A 旧同步。
            // 桥接此前从未发过该请求，先记录实证再发起下载；探针失败不阻塞。
            io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests
                    .GetSettingRelatedRequest probe =
                    new io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests
                            .GetSettingRelatedRequest(provider);
            probe.setFinalizeReq(new io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests
                    .Request.RequestCallback(provider) {
                        @Override
                        public void call(
                                io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests
                                        .Request request) {
                            io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests
                                    .GetSettingRelatedRequest r =
                                    (io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests
                                            .GetSettingRelatedRequest) request;
                            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(service,
                                    "HUAWEI_SETTING_RELATED truSleepNewSync=" + r.truSleepNewSync
                                            + " rriNewSync=" + r.rriNewSync
                                            + " gpsNewSync=" + r.gpsNewSync);
                            enableTruSleepThenDownload.run();
                        }

                        @Override
                        public void handleException(
                                io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests
                                        .Request request,
                                io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests
                                        .Request.ResponseParseException e) {
                            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(service,
                                    "HUAWEI_SETTING_RELATED_FAILED " + e.getMessage());
                            enableTruSleepThenDownload.run();
                        }
                    });
            try {
                probe.doPerform();
            } catch (java.io.IOException e) {
                io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(service,
                        "HUAWEI_SETTING_RELATED_FAILED send: " + e.getMessage());
                enableTruSleepThenDownload.run();
            }
        }, "HuaweiSleepImport").start();
    }

    /**
     * 睡眠链（含 0x2C 夜眠下载）收尾：释放 busy 并推进串行链 / 周期 tick。
     *
     * <p>busy 在 {@link #maybePullSleep} 里置位、在这里清零，整段 0x2C 文件下载期间
     * 其它 0x07 拉取都被挡在门外（见周期 tick 的 {@code !sleepPullBusy.get()} 守卫），
     * 避免 RFCOMM 帧交错。
     */
    private void finishSleep(java.util.concurrent.atomic.AtomicBoolean finalized, long epoch,
            Runnable advance) {
        sleepPullBusy.set(false);
        if (finalized.get() || epoch != sessionEpoch) return;
        advance.run();
    }

    /** 人读时间，用于日志排查：2026-10-13 08:44。 */
    private static String sleepClock(long epochMs) {
        if (epochMs <= 0) return "-";
        return java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
                .format(java.time.Instant.ofEpochMilli(epochMs)
                        .atZone(java.time.ZoneId.systemDefault()));
    }

    private boolean probeEnabled() {
        try {
            return new java.io.File(getNoBackupFilesDir(),
                    io.github.miam1ku.mibandoplusbridge.protocol.huawei.service
                            .HuaweiProbeSweep.FLAG_FILE).isFile();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private String runHuaweiSession(BandStateRepository repository, String address,
            long epoch, AtomicBoolean authenticated) {
        // 新会话：睡眠拉取状态机复位 —— 本会话的串行链末段会立刻拉一次，
        // 旧会话残留的回调被代际号挡在门外。
        sleepPullGen++;
        sleepPullBusy.set(false);
        sleepPullSinceMs = 0;
        sleepPulledOkAtMs = 0;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            return "BLUETOOTH_DISABLED";
        }
        CountDownLatch finished = new CountDownLatch(1);
        // finalized 是必须的：HuaweiBrTransport.disconnect() 无条件触发
        // onDisconnected(true)（哪怕 socket 已死），而 finally 里就要调它 ——
        // 不设防会把「链路自然掉线」的真实原因覆盖成「主动断开」。
        AtomicBoolean finalized = new AtomicBoolean();
        String[] outcome = {"HUAWEI_LINK_LOST"};
        HuaweiSupportProvider provider = new HuaweiSupportProvider(HuaweiDeviceType.BR);
        huaweiProvider = provider;
        provider.setContext(this);
        // 身份按手表 MAC 持久化：伪 MAC / androidID / secretKey 三者跨会话稳定，
        // 手表才认这台手机；secretKey 由 bind 结束时覆盖写入，重连免确认。
        provider.attachIdentity(new HuaweiIdentityStore(this, address));
        // 手表是天气同步的主动方：认证通过后挂 0x0F/0x04 常驻监听，
        // 表上一打开天气 App 就能立刻拿到数据，而不必等手机侧主动推。
        provider.setWeatherRequestHandler(this::onWatchWeatherRequest);
        provider.setHandshakeListener((success, error) -> {
            if (finalized.get() || epoch != sessionEpoch) {
                // 上一轮的迟到结果或会话已结束：只放行等待方，不改写结局。
                finished.countDown();
                return;
            }
            if (!success) {
                outcome[0] = "HUAWEI_HANDSHAKE_FAILED";
                io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this,
                        "HUAWEI_HANDSHAKE_FAILED " + error);
                finished.countDown();
                return;
            }
            authenticated.set(true);
            // 电量是认证后加密通道的第一个功能命令，顺带验证加密参数可用。
            provider.readBattery((level, batteryError) -> coordinator.execute(() -> {
                if (finalized.get() || epoch != sessionEpoch) return;
                boolean charging = provider.isBatteryCharging();
                HuaweiDeviceRegistry.noteSession(this, level, charging, true);
                // 投影到 band-state：钩子层（设备卡片 / OPPO 健康步数）只认这份文件。
                HuaweiDeviceRegistry.publish(BandLiveService.this);
                if (batteryError != null) {
                    io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this,
                            "HUAWEI_BATTERY_FAILED " + batteryError);
                } else {
                    io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this,
                            "HUAWEI_CONNECTED battery=" + level + " charging=" + charging);
                }
                if (!stopRequested) show("手表已连接");
                // 认证已完成的此刻，0x02 通知通道可用：把握手中挂起的通知补发出去。
                // （小米分支在 commands 就绪后做同样的事，见 replayHeld 调用点。）
                if (!stopRequested) replayHeld();
                // 诊断开关：no_backup/huawei-probe.on 存在时改跑协议探针序列。
                // 探针与下面的 readSteps 都占用 0x07/0x0A 与 0x07/0x0B，
                // 响应分发按 (serviceId, commandId) 认领第一个等待者 ——
                // 同时在飞会互相抢响应，所以这里是二选一，不是并行。
                if (probeEnabled()) {
                    io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(
                            BandLiveService.this, "HUAWEI_PROBE_SWEEP_START");
                    io.github.miam1ku.mibandoplusbridge.protocol.huawei.service
                            .HuaweiProbeSweep.run(provider, BandLiveService.this);
                } else {
                    // 认证后的数据拉取一律串行（2026-10-14 实测教训）：
                    // 0x07 上多条链并发在飞时（初始步数 + 汇总 + 心率 + 30 条
                    // 步数历史 + 睡眠），手表会间歇性丢响应且请求超时器不触发，
                    // 链路无声卡死；同命令的链还会在响应分发处互相抢包。
                    // 每段只在上段的终态回调（成功或失败都续走）里发起。
                    // 放在电量之后，保证加密通道已被验证过一次。
                    runSerialStage(provider, finalized, epoch, 0);
                    // 固件 / 硬件：认证后立刻读一次，独立于上面的串行数据链。
                    readHuaweiProductInfo(provider);
                }
            }));
        });
        try {
            provider.attachTransport(adapter, address, new HuaweiBrTransport.ConnectionListener() {
                @Override public void onConnected() {
                    io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(
                            BandLiveService.this, "HUAWEI_TRANSPORT_CONNECTED");
                }

                @Override public void onDisconnected(boolean deliberate) {
                    if (finalized.get() || epoch != sessionEpoch) {
                        finished.countDown();
                        return;
                    }
                    outcome[0] = deliberate ? "HUAWEI_LINK_CLOSED" : "HUAWEI_LINK_LOST";
                    finished.countDown();
                }
            });
            if (!provider.getTransport().connect()) {
                huaweiProvider = null;
                return "HUAWEI_TRANSPORT_FAILED";
            }
        } catch (RuntimeException failure) {
            huaweiProvider = null;
            return "HUAWEI_TRANSPORT_ERROR " + failure.getClass().getSimpleName();
        }

        // 持有会话：握手成功后的正常运行态就停在这里，直到链路断开或停机。
        // 500ms 轮询一次 stopRequested —— CountDownLatch 没有条件等待，
        // 而停机指令来自主线程的 requestStop()，等待方必须及时醒。
        try {
            // 每 500ms 醒一次，120 次 = 60 秒刷一次当日汇总。
            // 为什么轮询而不是等手表推：GT3 在认证后是静默的（实测握手完成后
            // 数分钟一帧不上报），步数只能靠主动问。
            //
            // 2026-10-21：原来这里是 600 次（5 分钟），延迟全在那儿 ——
            // 手表 16:08 已经是 1737 步，设备空间还停在 16:05 拉到的 1599，
            // 差 138 步。一轮心跳实测 0.66 秒（totals → 心率 → 归档），
            // 60 秒的间隔让 0x07 通道占用率不到 1%，比原来的 5 分钟快 5 倍。
            // 同一通道的各段仍然是串行的：每段都在上一段的终态回调里发，
            // 并发同通道链会让手表无声挂死（2026-10-14 实测教训）。
            long ticks = 0;
            while (!finished.await(500, TimeUnit.MILLISECONDS)) {
                if (stopRequested) {
                    outcome[0] = "STOPPED";
                    break;
                }
                if (++ticks % 120 == 0 && provider.isAuthenticated()) {
                    // 睡眠链卡死自愈：无声挂死（无终态回调）时强制复位，本轮
                    // 末尾的心率回调之后会自动重试睡眠拉取。
                    clearStuckSleepPull(provider);
                    // 0x2C 夜眠下载期间 busy 为 true：此刻跳过 0x07 的汇总/心率拉取，
                    // 避免两类帧在 RFCOMM 上交错让手表静默丢响应。至多错过一个
                    // 60 秒周期（0x2C 自带 10s 看门狗），心率心跳不受实质影响。
                    if (!sleepPullBusy.get()) {
                        provider.readFitnessTotals(new GetFitnessTotalsRequest.TotalsListener() {
                            @Override
                            public void onTotals(int steps, int calories, int distance) {
                                if (finalized.get() || epoch != sessionEpoch) return;
                                io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(
                                        BandLiveService.this, "HUAWEI_TOTALS steps=" + steps
                                                + " calories=" + calories
                                                + " distance=" + distance);
                                HuaweiDeviceRegistry.noteSteps(BandLiveService.this, steps);
                                HuaweiDeviceRegistry.publish(BandLiveService.this);
                                try {
                                    String deviceId = HuaweiDeviceRegistry.deviceId(BandLiveService.this);
                                    if (deviceId != null && !deviceId.isBlank()) {
                                        HuaweiStepsImporter.enqueueDayTotal(BandLiveService.this, deviceId,
                                                steps, calories, distance, java.time.ZoneId.systemDefault());
                                    }
                                } catch (Throwable ignored) { }
                            }

                            @Override
                            public void onError(String message) {
                                if (finalized.get() || epoch != sessionEpoch) return;
                                io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(
                                        BandLiveService.this, "HUAWEI_TOTALS_FAILED " + message);
                            }
                        });
                        // 心率与步数同频刷新。它走的是同一条 0x0A/0x0B 通道，
                        // 必须串行 —— 两个同命令的请求同时在飞会互相抢响应。
                        provider.readHeartRate(new HuaweiSupportProvider.HeartRateListener() {
                            @Override
                            public void onHeartRate(int bpm, int measuredAtSec) {
                                if (finalized.get() || epoch != sessionEpoch) return;
                                io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(
                                        BandLiveService.this, "HUAWEI_HR bpm=" + bpm
                                                + " at=" + measuredAtSec);
                                if (HuaweiDeviceRegistry.noteHeartRate(BandLiveService.this, bpm)) {
                                    HuaweiDeviceRegistry.publish(BandLiveService.this);
                                }
                                // 电量随心率同频刷新：设备页从 band-state 投影读电量，
                                // 旧版只在握手读一次，长连接下电量整天不动（设备页
                                // 62% vs 手表 61% 的冻结就是这么来的）。0x01 服务，
                                // 不占 0x07 通道，可与睡眠拉取并行。
                                refreshHuaweiBattery(provider, finalized, epoch);
                                // 心率链已收尾、通道空闲：补一次睡眠拉取（带
                                // 15 分钟新鲜度门槛与在飞互斥，非每次都真发）。
                                // 周期路径不走握手阶段机，advance 是空跑。
                                maybePullSleep(provider, finalized, epoch, NO_ADVANCE);
                            }

                            @Override
                            public void onError(String message) {
                                if (finalized.get() || epoch != sessionEpoch) return;
                                io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(
                                        BandLiveService.this, "HUAWEI_HR_FAILED " + message);
                                refreshHuaweiBattery(provider, finalized, epoch);
                                maybePullSleep(provider, finalized, epoch, NO_ADVANCE);
                            }
                        }, samples -> enqueueHuaweiMinutes(samples, 3600L));
                    } else {
                        io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(
                                BandLiveService.this, "HUAWEI_TICK_DEFERRED sleep busy");
                    }
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            outcome[0] = "INTERRUPTED";
        } finally {
            finalized.set(true);
            huaweiProvider = null;
            if (provider.getTransport() != null) provider.getTransport().disconnect();
            HuaweiDeviceRegistry.noteSession(this,
                    HuaweiDeviceRegistry.lastBattery(this),
                    HuaweiDeviceRegistry.lastCharging(this), false);
            HuaweiDeviceRegistry.publish(this);
            try { repository.markSessionClosed(); } catch (RuntimeException ignored) { }
        }
        return outcome[0];
    }

    private void bindHealthHost() {
        if (healthBound || stopRequested) return;
        try {
            if (!io.github.miam1ku.mibandoplusbridge.HostIdentity.installed(this, HEALTH_PACKAGE)) {
                healthHostStatus("UNAVAILABLE");
                return;
            }
            var service = getPackageManager().getServiceInfo(HEALTH_SERVICE, 0);
            // Version drift is a one-time home-screen hint, not a reason to refuse the import bind.
            if (!service.enabled || !service.exported
                    || !service.applicationInfo.enabled || !HEALTH_PACKAGE.equals(service.processName)
                    || (service.permission != null && !service.permission.isBlank())) {
                healthHostStatus("UNAVAILABLE");
                return;
            }
            main.post(() -> {
                if (healthBound || stopRequested || !new BandStateRepository(this).isRegistered()) return;
                try {
                    healthBound = bindService(new Intent().setComponent(HEALTH_SERVICE), healthConnection, BIND_AUTO_CREATE);
                    if (!healthBound) {
                        healthHostStatus("UNAVAILABLE");
                        reviveHealth();
                    }
                } catch (RuntimeException unavailable) {
                    healthHostStatus("UNAVAILABLE");
                    reviveHealth();
                }
            });
        } catch (android.content.pm.PackageManager.NameNotFoundException unavailable) {
            healthHostStatus("UNAVAILABLE");
        }
    }

    private void unbindHealthHost() {
        if (healthBound) {
            unbindService(healthConnection);
            healthBound = false;
        }
    }

    private void reviveHealth() {
        HostKeepAlive.startHealthAsync(this, () -> main.post(() -> {
            if (!stopRequested) bindHealthHost();
        }));
    }

    private void healthHostStatus(String status) {
        getSharedPreferences("live-service", MODE_PRIVATE).edit().putString("healthHostStatus", status).apply();
        getContentResolver().notifyChange(io.github.miam1ku.mibandoplusbridge.integration.DeviceCardProvider.URI, null);
    }

    private void healthCollectionStatus(String status) {
        getSharedPreferences("live-service", MODE_PRIVATE).edit().putString("healthCollectionStatus", status).apply();
        getContentResolver().notifyChange(io.github.miam1ku.mibandoplusbridge.integration.DeviceCardProvider.URI, null);
    }
    /** A later successful round clears a storage pause that already ended. */
    private void clearStoragePause() {
        String current = getSharedPreferences("live-service", MODE_PRIVATE)
                .getString("healthCollectionStatus", "");
        if ("HEALTH_STORAGE_UNAVAILABLE".equals(current) || "HISTORY_STORAGE_FAILED".equals(current)) {
            healthCollectionStatus("");
        }
    }


    /** 45 starts the band's live-stats stream, 46 stops it, 47 is the periodic event. */
    private static nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command realtimeStats(int subtype) {
        return nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command.newBuilder()
                .setType(8).setSubtype(subtype).build();
    }

    /** Ends the live-stats stream once; a running stream keeps the band measuring heart rate. */
    private void stopRealtimeStats() {
        if (!realtimeStatsStopPending) return;
        realtimeStatsStopPending = false;
        var queue = commands;
        if (queue == null || stopRequested) return;
        queue.send(realtimeStats(REALTIME_STATS_STOP));
    }

    private void tick() {
        var queue = commands;
        if (stopRequested || queue == null || historySync == null) return;
        if (!healthBound) bindHealthHost();
        if (!new BandStateRepository(this).isRegistered()) {
            requestStop();
            return;
        }
        long now = System.nanoTime();
        if (now >= nextBatteryAt) {
            nextBatteryAt = now + TimeUnit.MINUTES.toNanos(IDLE_POLL_MINUTES);
            queue.request(nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command
                    .newBuilder().setType(2).setSubtype(1).build(), 2, 1)
                    .whenComplete((ignored, error) -> {
                        if (error != null) writeStatus("BATTERY_REFRESH_FAILED");
                    });
        }
        if (syncRequested || now >= nextHealthAt) {
            boolean historical = syncRequested;
            syncRequested = false;
            nextHealthAt = now + TimeUnit.MINUTES.toNanos(IDLE_POLL_MINUTES);
            historySync.request(historical);
            if (historical) {
                bindHealthHost();
                nextWeatherAt = now + TimeUnit.MINUTES.toNanos(30);
                weatherSync.refreshAndSend();
            }
        }
        // Bands that leave 2/78 unanswered still publish sleep as a history file.
        if (sleepPauseOn && now >= nextSleepFileAt) {
            nextSleepFileAt = now + TimeUnit.MINUTES.toNanos(10);
            historySync.request(false);
        }
        if (now >= nextWeatherAt) {
            nextWeatherAt = now + TimeUnit.MINUTES.toNanos(30);
            weatherSync.sendIfChanged();
        }
    }

    private static boolean retryable(String code) {
        if (code.indexOf('_') < 0) return true;
        return switch (code) {
            case "BLUETOOTH_DISABLED", "DIAGNOSTIC_TIMEOUT", "CANCELLED", "CONNECTION_ALREADY_ACTIVE",
                    "USER_LOCKED", "EMPTY_SOCKET_READ", "CLOSED",
                    // 华为分支：掉线/传输失败/握手失败都可重试 —— 握手失败常常只是
                    // 手表正忙或用户还没点确认，等十几秒再来一次往往就过了。
                    "HUAWEI_LINK_LOST", "HUAWEI_TRANSPORT_FAILED",
                    "HUAWEI_HANDSHAKE_FAILED", "INTERRUPTED" -> true;
            // Link loss is not a bad binding. Quiet backoff left the band down for two minutes.
            default -> code.startsWith("CLOSED") || code.startsWith("BLE_")
                    || code.startsWith("HUAWEI_TRANSPORT_ERROR");
        };
    }

    /** reason is the hidden unbond extra. 9 means some app removed the bond; 1 means auth failed. */
    private void noteBond(Context context, Intent intent) {
        int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
        int previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR);
        int reason = intent.getIntExtra("android.bluetooth.device.extra.REASON", -1);
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        String ours = "unknown";
        if (device != null) {
            try {
                ours = String.valueOf(new BandStateRepository(context).matchesAddress(device.getAddress()));
            } catch (RuntimeException locked) { }
        }
        io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(context, "bond changed state=" + state
                + "/" + bondName(state) + " prev=" + previous + "/" + bondName(previous)
                + " reason=" + reason + "/" + bondReason(reason) + " ours=" + ours);
    }

    private static String adapterState(int state) {
        return switch (state) {
            case BluetoothAdapter.STATE_OFF -> "off";
            case BluetoothAdapter.STATE_TURNING_ON -> "turning-on";
            case BluetoothAdapter.STATE_ON -> "on";
            case BluetoothAdapter.STATE_TURNING_OFF -> "turning-off";
            default -> "other";
        };
    }

    private static String bondName(int state) {
        return switch (state) {
            case BluetoothDevice.BOND_NONE -> "none";
            case BluetoothDevice.BOND_BONDING -> "bonding";
            case BluetoothDevice.BOND_BONDED -> "bonded";
            default -> "other";
        };
    }

    private static String bondReason(int reason) {
        return switch (reason) {
            case -1 -> "absent";
            case 1 -> "auth-failed";
            case 2 -> "auth-rejected";
            case 3 -> "auth-canceled";
            case 4 -> "remote-down";
            case 5 -> "discovery";
            case 6 -> "auth-timeout";
            case 7 -> "repeated";
            case 8 -> "remote-auth-canceled";
            case 9 -> "removed";
            default -> "other";
        };
    }

    private boolean waitForRetry(long delayMs) {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMs);
        synchronized (stopLock) {
            while (!stopRequested && !retryNow) {
                long leftMs = TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime());
                if (leftMs <= 0) return true;
                try {
                    stopLock.wait(leftMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            if (stopRequested) return false;
            retryNow = false;
            return true;
        }
    }

    private void writeStatus(String status) {
        try (FileWriter writer = new FileWriter(new File(getNoBackupFilesDir(), "live-status.txt"))) {
            writer.write(status);
        } catch (IOException ignored) { }
    }

    private void syncDnd() {
        var queue = commands;
        if (stopRequested || queue == null) return;
        int filter = io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.currentFilter(this);
        long now = System.nanoTime();
        synchronized (dndSyncLock) {
            long elapsed = dndSyncAtNanos == 0 ? -1 : now - dndSyncAtNanos;
            boolean phoneChanged = lastDndFilter != Integer.MIN_VALUE && lastDndFilter != filter;
            if (io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.repeatSync(lastDndFilter, elapsed, filter)) {
                return;
            }
            dndPushPhone = phoneChanged;
            lastDndFilter = filter;
            dndSyncAtNanos = now;
        }
        String detail = io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.describe(this);
        android.util.Log.i("OplusBandBridge", "DND_SYNC " + detail);
        io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "DND_SYNC " + detail);
        // The band drops a rule list that arrives before sync_with_phone is on.
        // Transport ACK is earlier than that; wait for the subtype 15 body.
        queue.request(io.github.miam1ku.mibandoplusbridge.protocol.BandDndCommand.syncWithPhone(), 2, 15)
                .whenComplete((done, error) -> {
                    if (error != null) {
                        android.util.Log.i("OplusBandBridge", "DND_SYNC_REJECTED subtype=15");
                    }
                    sendDndFollowup();
                });
    }

    private void sendDndFollowup() {
        pushDndState(false);
    }

    private void pushDndState(boolean resend) {
        var queue = commands;
        if (stopRequested || queue == null) return;
        boolean phoneOn = io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.blocksNotifications(
                io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.currentFilter(this));
        if (!io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.sendPhoneRule(
                dndPushPhone, phoneOn, dndBandKnown, dndBandOn)) {
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "DND_HOLD phone=" + phoneOn
                    + " changed=" + dndPushPhone + " bandKnown=" + dndBandKnown + " band=" + dndBandOn);
            if (!resend) sendQuietly(queue, io.github.miam1ku.mibandoplusbridge.protocol.BandDndCommand.queryRules());
            return;
        }
        lastDndSentNanos = System.nanoTime();
        int activatedAt = io.github.miam1ku.mibandoplusbridge.protocol.BandDndCommand.activatedAt(
                System.currentTimeMillis());
        sendQuietly(queue, io.github.miam1ku.mibandoplusbridge.protocol.BandDndCommand.state(phoneOn));
        sendQuietly(queue, io.github.miam1ku.mibandoplusbridge.protocol.BandDndCommand.phoneSilent(phoneOn));
        sendQuietly(queue, io.github.miam1ku.mibandoplusbridge.protocol.BandDndCommand.phoneRules(phoneOn, activatedAt));
        if (resend) {
            sendQuietly(queue, io.github.miam1ku.mibandoplusbridge.protocol.BandDndCommand.queryRules());
            return;
        }
        sendQuietly(queue, io.github.miam1ku.mibandoplusbridge.protocol.BandDndCommand.queryRules());
        main.removeCallbacks(dndRulesAgain);
        main.postDelayed(dndRulesAgain, 2000);
    }

    private void replyMusic() {
        var queue = commands;
        if (stopRequested || queue == null) return;
        var command = lastMusic;
        if (command == null) {
            android.media.AudioManager audio = getSystemService(android.media.AudioManager.class);
            int volume = 0;
            if (audio != null) {
                int max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
                volume = io.github.miam1ku.mibandoplusbridge.protocol.BandMusicCommand.percent(
                        audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC), max);
            }
            command = io.github.miam1ku.mibandoplusbridge.protocol.BandMusicCommand.nothing(volume);
        }
        var info = command.getMusic().getMusicInfo();
        io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "MUSIC_REPLY state="
                + info.getState() + " volume=" + info.getVolume());
        sendQuietly(queue, command);
    }

    private void replySilent() {
        var queue = commands;
        if (stopRequested || queue == null) return;
        boolean on = io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.blocksNotifications(
                io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.currentFilter(this));
        io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "SILENT_REQ on=" + on);
        sendQuietly(queue, io.github.miam1ku.mibandoplusbridge.protocol.BandDndCommand.phoneSilent(on));
    }


    private void applyBandManual(nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command command) {
        Boolean bandOn = io.github.miam1ku.mibandoplusbridge.protocol.BandDndCommand.pushedManualState(command);
        if (bandOn == null) return;
        if (!io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.acceptBandManual(
                bandOn, io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.currentFilter(this),
                lastDndSentNanos, System.nanoTime())) return;
        requestPhoneDnd(bandOn);
    }

    private void requestPhoneDnd(boolean on) {
        main.post(() -> {
            if (!io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.acceptBandManual(
                    on, io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.currentFilter(this),
                    lastDndSentNanos, System.nanoTime())) return;
            var attempt = io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.apply(this, on);
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "DND_FROM_BAND on=" + on
                    + " applied=" + attempt.applied() + " policy=" + attempt.policy()
                    + " via=" + attempt.via());
            if (attempt.applied()) return;
            Intent intent = new Intent(io.github.miam1ku.mibandoplusbridge.hook.OHealthDndHook.ACTION)
                    .setPackage("com.heytap.health")
                    .putExtra("on", on);
            sendOrderedBroadcast(intent, null, new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent done) {
                    boolean applied = getResultCode() == 1;
                    io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(BandLiveService.this,
                            "DND_FROM_BAND_HOST on=" + on + " applied=" + applied
                                    + " via=" + getResultData());
                    if (!applied) askRootZen(on);
                }
            }, main, 0, null, null);
        });
    }

    private void askRootZen(boolean on) {
        HostKeepAlive.runRoot(() -> {
            boolean wrote = OwnershipController.setZenMode(on);
            boolean applied = wrote && io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.blocksNotifications(
                    io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.currentFilter(this)) == on;
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "DND_FROM_BAND_ROOT on=" + on
                    + " wrote=" + wrote + " applied=" + applied);
        });
    }

    private void requestSleepState() {
        var queue = commands;
        if (stopRequested || queue == null || !sleepPauseOn) return;
        queue.request(io.github.miam1ku.mibandoplusbridge.notify.SleepMusic.query(), 2, 78).exceptionally(error -> {
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "SLEEP_MUSIC query missed");
            android.util.Log.i("OplusBandBridge", "SLEEP_MUSIC_QUERY_FAILED");
            return null;
        });
    }

    private void noteSleep(nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command command) {
        if (!sleepPauseOn) return;
        Boolean asleep = io.github.miam1ku.mibandoplusbridge.notify.SleepMusic.asleep(command);
        if (asleep == null) return;
        long now = System.currentTimeMillis();
        var report = command.getSubtype() == 79 ? sleepMusic.push(asleep, now) : sleepMusic.observe(asleep, now);
        if ((report.effect() == io.github.miam1ku.mibandoplusbridge.notify.SleepMusic.Effect.BASELINE && asleep)
                || report.effect() == io.github.miam1ku.mibandoplusbridge.notify.SleepMusic.Effect.WOKE) {
            if (io.github.miam1ku.mibandoplusbridge.notify.SleepMusic.rememberCutoff(this, now)) {
                sleepArmedAtMs = Math.max(sleepArmedAtMs, now);
            }
        }
        applySleep(report, switch (report.effect()) {
            case PAUSE -> "SLEEP_MUSIC pause";
            case BASELINE -> "SLEEP_MUSIC baseline asleep=" + asleep;
            case WOKE -> "SLEEP_MUSIC awake";
            case UNCHANGED -> null;
        });
    }

    private void noteSleepFile(java.util.List<io.github.miam1ku.mibandoplusbridge.protocol.BandHistoryParser.Measurement> measurements) {
        if (!sleepPauseOn || measurements == null) return;
        long now = System.currentTimeMillis();
        for (var measurement : measurements) {
            if (!"sleep_interval".equals(measurement.kind)) continue;
            var report = sleepMusic.currentNight(measurement.startMs, measurement.endMs, sleepArmedAtMs, now);
            if (report.effect() != io.github.miam1ku.mibandoplusbridge.notify.SleepMusic.Effect.PAUSE) continue;
            applySleep(report, "SLEEP_MUSIC pause file");
        }
    }

    private void applySleep(io.github.miam1ku.mibandoplusbridge.notify.SleepMusic.Report report, String line) {
        if (line == null) return;
        io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, line);
        android.util.Log.i("OplusBandBridge", line);
        if (report.effect() != io.github.miam1ku.mibandoplusbridge.notify.SleepMusic.Effect.PAUSE) return;
        int generation = report.generation();
        main.post(() -> {
            if (!sleepPauseOn || sleepMusic.generation() != generation) return;
            io.github.miam1ku.mibandoplusbridge.notify.NativeMusic.pause(this);
        });
    }

    private void sendDndRulesAgain() {
        pushDndState(true);
    }

    private void noteBandRules(nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command command) {
        Boolean on = io.github.miam1ku.mibandoplusbridge.protocol.BandDndCommand.manualState(command);
        if (on == null) return;
        dndBandKnown = true;
        dndBandOn = on;
    }

    private void adoptStoredBand() {
        if (dndPushPhone || !dndBandKnown) return;
        boolean phoneOn = io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.blocksNotifications(
                io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd.currentFilter(this));
        if (dndBandOn == phoneOn || !dndAdopted.compareAndSet(false, true)) return;
        requestPhoneDnd(dndBandOn);
    }

    private static void sendQuietly(
            io.github.miam1ku.mibandoplusbridge.protocol.LiveCommandQueue queue,
            nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command command) {
        int subtype = command.getSubtype();
        queue.send(command).exceptionally(error -> {
            android.util.Log.i("OplusBandBridge", "DND_SYNC_REJECTED subtype=" + subtype);
            return null;
        });
    }

    private static String describeSystem(
            nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command command) {
        byte[] raw = command.toByteArray();
        StringBuilder hex = new StringBuilder(Math.min(raw.length, 120) * 2);
        int shown = Math.min(raw.length, 120);
        for (int i = 0; i < shown; i++) {
            hex.append(Character.forDigit((raw[i] >> 4) & 0xf, 16))
                    .append(Character.forDigit(raw[i] & 0xf, 16));
        }
        StringBuilder rules = new StringBuilder();
        if (command.hasSystem() && command.getSystem().hasPhoneZenRules()) {
            var list = command.getSystem().getPhoneZenRules().getRuleList();
            rules.append(" rules=").append(list.size());
            for (var rule : list) {
                rules.append(" {").append(rule.getName()).append(" manual=").append(rule.getManual())
                        .append(" state=").append(rule.getState()).append('}');
            }
        }
        return "rx type=2 subtype=" + command.getSubtype() + " status=" + command.getStatus()
                + " bytes=" + raw.length + rules + " hex=" + hex;
    }

    private void show(String title) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "手环连接", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
        Notification notice = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(title)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build();
        synchronized (this) {
            if (title.equals(noticeTitle)) return;
            startForeground(NOTICE, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            noticeTitle = title;
        }
    }

    private static void noteIconPackage(nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command command) {
        BandLiveService live = instance;
        if (live == null || command.getType() != 7 || command.getSubtype() != 0
                || !command.hasNotification() || !command.getNotification().hasNotification2()
                || !command.getNotification().getNotification2().hasNotification3()) return;
        String pkg = command.getNotification().getNotification2().getNotification3().getPackage();
        if (pkg == null || pkg.isBlank() || "phone".equals(pkg)) return;
        synchronized (live.iconPackages) {
            live.iconPackages.remove(pkg);
            live.iconPackages.addLast(pkg);
            while (live.iconPackages.size() > 32) live.iconPackages.removeFirst();
        }
    }

    private void offerIcon(String queried) {
        String pkg = resolveIconPackage(queried);
        if (!io.github.miam1ku.mibandoplusbridge.notify.NotificationIcons.available(this, pkg)) {
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "ICON_SKIP pkg=" + pkg);
            return;
        }
        iconPackage = pkg;
        sendSessionCommand(io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand.iconQueryReply(pkg));
        io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "ICON_QUERY pkg=" + pkg);
    }

    private String resolveIconPackage(String queried) {
        if (queried == null || queried.isBlank()) return "";
        synchronized (iconPackages) {
            for (String full : iconPackages) if (full.startsWith(queried)) return full;
        }
        return queried;
    }

    private void uploadIcon(SppDiagnosticClient active,
            nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.NotificationIconRequest request) {
        if (request.getStatus() != 0) return;
        String pkg = iconPackage;
        int size = request.getSize();
        int format = request.getPixelFormat();
        if (pkg == null || pkg.isBlank() || !iconUploadBusy.compareAndSet(false, true)) {
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "ICON_BUSY");
            return;
        }
        coordinator.execute(() -> {
            try {
                int[] pixels = io.github.miam1ku.mibandoplusbridge.notify.NotificationIcons.argb(this, pkg, size);
                byte[] encoded = io.github.miam1ku.mibandoplusbridge.protocol.NotificationIconPixels
                        .encode(pixels, size, size, format);
                var queue = commands;
                if (queue == null || client != active) {
                    iconUploadBusy.set(false);
                    return;
                }
                queue.request(io.github.miam1ku.mibandoplusbridge.protocol.NotificationIconUpload.request(encoded),
                        22, 0).whenComplete((ack, error) -> deliverIcon(active, encoded, ack, error));
            } catch (RuntimeException failure) {
                iconUploadBusy.set(false);
                io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this,
                        "ICON_ENCODE " + failure.getClass().getSimpleName());
            }
        });
    }

    private void deliverIcon(SppDiagnosticClient active, byte[] encoded,
            nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command ack, Throwable error) {
        try {
            if (error != null || client != active) {
                iconUploadBusy.set(false);
                io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "ICON_UPLOAD_FAILED");
                return;
            }
            int chunk = io.github.miam1ku.mibandoplusbridge.protocol.NotificationIconUpload
                    .acceptedChunk(ack, active.notificationPayloadLimit());
            var parts = io.github.miam1ku.mibandoplusbridge.protocol.NotificationIconUpload
                    .chunks(io.github.miam1ku.mibandoplusbridge.protocol.NotificationIconUpload.envelope(encoded), chunk);
            sendIconPart(active, parts, 0);
        } catch (RuntimeException failure) {
            iconUploadBusy.set(false);
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "ICON_UPLOAD_FAILED");
        }
    }

    private void sendIconPart(SppDiagnosticClient active, java.util.List<byte[]> parts, int index) {
        if (client != active) {
            iconUploadBusy.set(false);
            return;
        }
        if (index >= parts.size()) {
            iconUploadBusy.set(false);
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "ICON_UPLOAD parts=" + parts.size());
            return;
        }
        active.sendDataChunk(parts.get(index)).whenComplete((ignored, error) -> {
            if (error != null) {
                iconUploadBusy.set(false);
                io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "ICON_UPLOAD_FAILED part=" + index);
                return;
            }
            sendIconPart(active, parts, index + 1);
        });
    }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        unbindHealthHost();
        if (receiverRegistered) {
            unregisterReceiver(bluetoothEvents);
            receiverRegistered = false;
        }
        if (dndReceiverRegistered) {
            unregisterReceiver(dndEvents);
            dndReceiverRegistered = false;
        }
        try { getContentResolver().unregisterContentObserver(zenMode); } catch (RuntimeException ignored) { }
        requestStop();
        calls.close();
        weatherSync.close();
        healthReplay.close();
        coordinator.execute(() -> {
            if (historySync != null) {
                historySync.close();
                historySync = null;
            }
        });
        coordinator.shutdown();
        worker.shutdownNow();
        super.onDestroy();
        destroyed.countDown();
    }
}
