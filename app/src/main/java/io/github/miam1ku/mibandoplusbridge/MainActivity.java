// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge;

import android.Manifest;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.UserManager;
import android.provider.Settings;
import io.github.miam1ku.mibandoplusbridge.data.BindingStore;
import io.github.miam1ku.mibandoplusbridge.data.HealthRecordStore;
import io.github.miam1ku.mibandoplusbridge.data.BandCatalog;
import io.github.miam1ku.mibandoplusbridge.data.TransportObservation;
import io.github.miam1ku.mibandoplusbridge.integration.DeviceCardProvider;
import org.json.JSONObject;
import androidx.appcompat.app.AppCompatActivity;
import android.content.ComponentName;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.color.MaterialColors;
import io.github.miam1ku.mibandoplusbridge.ui.BridgeScreen;
import io.github.miam1ku.mibandoplusbridge.ui.SetupProgress;
import io.github.miam1ku.mibandoplusbridge.integration.CredentialProvider;
import io.github.miam1ku.mibandoplusbridge.integration.HealthQueueProvider;
import io.github.miam1ku.mibandoplusbridge.data.BandStateRepository;
import io.github.miam1ku.mibandoplusbridge.data.HuaweiDeviceRegistry;
import io.github.miam1ku.mibandoplusbridge.service.OwnershipController;
import io.github.miam1ku.mibandoplusbridge.service.BandLiveService;
import io.github.miam1ku.mibandoplusbridge.service.HuaweiPairActivity;
import io.github.miam1ku.mibandoplusbridge.notify.BandNotificationListener;
import io.github.miam1ku.mibandoplusbridge.notify.SleepMusic;
import io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand;
import io.github.miam1ku.mibandoplusbridge.protocol.SppDiagnosticClient;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Persistent device registration is independent of connection ownership. */
public final class MainActivity extends AppCompatActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private BridgeScreen screen;
    private LinearLayout advanced;
    private LinearLayout setupCard;
    private LinearLayout healthAccessCard;
    private LinearLayout accountCard;
    private boolean pendingHuaweiPair;
    private TextView status;
    private TextView ownershipStatus;
    private boolean hostSupported;
    private static final int BLUETOOTH_PERMISSION = 1;
    private static final int CALL_PERMISSIONS = 2;
    private boolean callPermissionsAsked;
    private TextView deviceName;
    private TextView connectionLine;
    private TextView metricsLine;
    private TextView healthLine;
    private TextView permissionStatus;
    private TextView checklistRoot;
    private TextView checklistImport;
    private TextView checklistProfile;
    private TextView checklistAdd;
    private TextView primaryHint;
    private Button homePrimary;
    private Button openKsu;
    private Button remove;
    private Button restore;
    private boolean changingOwnership;
    private boolean versionPromptShown;
    private boolean launchHostAfterOpen;
    private boolean profileAfterRestore;
    private boolean rootGranted;
    private boolean probingRoot;
    private static final String SETUP_PREREQ = "开始前：在系统蓝牙里配对好华为手表；LSPosed 启用本模块并勾选 OHealth、我的设备后强停一次；在 KernelSU 中允许本应用。";
    enum Change { ADD, RECONNECT, REMOVE, RESTORE }

    /** Open the profile window when this restore task succeeded and the user came in from the capture entry. */
    static boolean openProfileAfterRestore(Change change, boolean restored) {
        return restored && change == Change.RESTORE;
    }
    private final ContentObserver deviceObserver = new ContentObserver(main) {
        @Override public void onChange(boolean selfChange) { refreshDevice(); }
    };
    private final ContentObserver observer = new ContentObserver(main) {
        @Override public void onChange(boolean selfChange) {
            refresh();
            refreshDevice();
        }
    };
    private final Runnable expireRefresh = this::refresh;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        screen = BridgeScreen.attach(this, "H₂O", false);
        LinearLayout statusCard = screen.card();
        deviceName = screen.title(statusCard, "正在读取设备状态…");
        connectionLine = screen.bodyText(statusCard, "");
        metricsLine = screen.caption(statusCard, "");
        ownershipStatus = screen.caption(statusCard, "");
        permissionStatus = screen.caption(statusCard, "正在读取权限状态…");
        healthLine = screen.caption(statusCard, "");
        healthLine.setVisibility(View.GONE);
        screen.setLastChildMargin(statusCard, 0);
        healthAccessCard = screen.card();
        screen.bodyText(healthAccessCard, "系统没有真正给 OPPO 健康通知使用权。请关掉再打开一次。");
        screen.outlined(healthAccessCard, "打开健康的通知使用权", this::openHealthListenerSettings);
        screen.setLastChildMargin(healthAccessCard, 0);
        healthAccessCard.setVisibility(View.GONE);
        accountCard = screen.card();
        TextView accountHint = screen.bodyText(accountCard,
                "OPPO 健康还没完成账号确认，健康数据暂时写不进去。请打开 OPPO 健康、登录 OPPO/欢太账号，并让它在前台正常运行一次。");
        accountHint.setTextColor(0xFFB3261E);
        screen.outlined(accountCard, "打开 OPPO 健康", this::openOHealth);
        screen.setLastChildMargin(accountCard, 0);
        accountCard.setVisibility(View.GONE);
        setupCard = screen.card();
        screen.overline(setupCard, "首次设置");
        checklistRoot = screen.caption(setupCard, "");
        checklistImport = screen.caption(setupCard, "");
        checklistProfile = screen.caption(setupCard, "");
        checklistAdd = screen.caption(setupCard, "");
        primaryHint = screen.caption(setupCard, "");
        screen.caption(setupCard, SETUP_PREREQ);
        screen.setLastChildMargin(setupCard, 0);
        homePrimary = screen.filled("检查 Root", this::runHomePrimary);
        openKsu = screen.outlined("打开 KernelSU", this::openKernelSu);
        remove = screen.outlined("从健康移除", () -> new MaterialAlertDialogBuilder(this)
                .setTitle("移除华为手表？")
                .setMessage("将停止桥接连接并移除这块华为手表。已保存的健康历史和加密绑定信息会保留；以后可重新配对同一只手表。")
                .setNegativeButton("取消", null)
                .setPositiveButton("移除", (dialog, which) -> changeOwnership(Change.REMOVE))
                .show());
        restore = screen.textButton("重试恢复官方应用", () -> changeOwnership(Change.RESTORE));
        restore.setVisibility(View.GONE);
        screen.heading("常用");
        LinearLayout shortcuts = screen.card();
        screen.outlined(shortcuts, "配对华为手表", this::startHuaweiPairingPicker);
        screen.navRow(shortcuts, "设备详情", () -> startActivity(new Intent(this,
                io.github.miam1ku.mibandoplusbridge.integration.BandDetailsActivity.class)));
        screen.navRow(shortcuts, "协议测试", () -> startActivity(new Intent(this, LabActivity.class)));
        screen.navRow(shortcuts, "天气同步", () -> startActivity(new Intent(this, WeatherActivity.class)));
        screen.navRow(shortcuts, "发送调试日志到 QQ", this::shareDebugLog);
        screen.caption(shortcuts, HuaweiDeviceRegistry.provisioned(this)
                ? "手表磁贴由设备空间自动维护，下拉快捷面板点一下即可拉起连接，不会断开。"
                : "控制中心编辑里添加「手环」。下拉或点一下会拉起连接，不会断开。");
        screen.setLastChildMargin(shortcuts, 0);
        LinearLayout sleepCard = screen.card();
        MaterialSwitch sleepPause = new MaterialSwitch(this);
        sleepPause.setText("入睡后暂停音乐");
        sleepPause.setTextSize(16);
        sleepPause.setTextColor(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnSurface, 0));
        sleepPause.setMinHeight(BridgeScreen.dp(this, 48));
        LinearLayout.LayoutParams sleepParams = new LinearLayout.LayoutParams(-1, -2);
        sleepParams.bottomMargin = BridgeScreen.dp(this, 8);
        sleepCard.addView(sleepPause, sleepParams);
        screen.caption(sleepCard, "手表报入睡后暂停正在播放的音乐，醒来不自动继续。刚打开时如果已经在睡，不会马上暂停。这块手表若不回报实时入睡，就等睡眠记录同步后再暂停，大约几分钟。");
        screen.setLastChildMargin(sleepCard, 0);
        final boolean[] writingSleepPause = {false};
        sleepPause.setChecked(SleepMusic.enabled(this));
        sleepPause.setOnCheckedChangeListener((button, checked) -> {
            if (writingSleepPause[0]) return;
            if (SleepMusic.setEnabled(this, checked)) {
                BandLiveService.sleepPauseChanged(this);
                return;
            }
            writingSleepPause[0] = true;
            button.setChecked(!checked);
            writingSleepPause[0] = false;
            ownershipStatus.setText("入睡暂停开关未能保存。");
        });
        screen.textButton("系统权限设置", () -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()))));
        screen.textButton("使用说明", this::showSetupGuide);
        advanced = new LinearLayout(this);
        advanced.setOrientation(LinearLayout.VERTICAL);
        advanced.setVisibility(View.GONE);
        screen.textButton("高级设置", () -> {
            boolean openNow = advanced.getVisibility() != View.VISIBLE;
            advanced.setVisibility(openNow ? View.VISIBLE : View.GONE);
        });
        LinearLayout.LayoutParams advancedParams = new LinearLayout.LayoutParams(-1, -2);
        screen.body.addView(advanced, advancedParams);
        screen.heading(advanced, "诊断工具");
        screen.caption(advanced, "以下为调试工具，仅在有需要时展开。配对与设备选择在首页「常用 → 配对华为手表」。");

        if (BuildConfig.DEBUG) {
            screen.outlined(advanced, "采集连接参数（120 秒）", () -> action("openDiagnostics", null));
            screen.outlined(advanced, "采集协议消息（120 秒）", () -> new MaterialAlertDialogBuilder(this)
                    .setTitle("采集所选手环的协议消息")
                    .setMessage("可能包含通知和健康数据。原始消息只保存在本机私有目录，不输出日志、不上传。仅用于本次协议分析。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("开始采集", (dialog, which) -> action("openCapture", null))
                    .show());
            screen.outlined(advanced, "独立连接诊断", () -> startActivity(new Intent(this, DiagnosticActivity.class)));
            screen.outlined(advanced, "协议测试", () -> startActivity(new Intent(this, LabActivity.class)));
        }
        status = screen.bodyText(advanced, "华为配对与诊断工具。");
        screen.caption(advanced, "配对与设备选择在首页「常用 → 配对华为手表」。以下为调试工具，仅在展开后可用。");
        getContentResolver().registerContentObserver(CredentialProvider.URI, false, observer);
        getContentResolver().registerContentObserver(DeviceCardProvider.URI, false, deviceObserver);
        getContentResolver().registerContentObserver(HealthQueueProvider.URI, false, deviceObserver);
        refreshDevice();
        requestRoot();
    }

    private void shareDebugLog() {
        worker.execute(() -> {
            try {
                java.io.File file = io.github.miam1ku.mibandoplusbridge.data.SessionLog.export(this);
                Uri uri = androidx.core.content.FileProvider.getUriForFile(this,
                        "io.github.miam1ku.mibandoplusbridge.debuglog", file);
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.putExtra(Intent.EXTRA_SUBJECT, "H₂O调试日志");
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                // 部分 ROM / Android 16 的分享目标只认 ClipData，且 chooser 包装后 grant
                // 旗标不一定往下传。双保险：带 ClipData，并显式给所有候选接收方授予读权限，
                // 避免「弹出了分享框但对方读不到文件」。
                send.setClipData(android.content.ClipData.newUri(getContentResolver(),
                        "H₂O调试日志", uri));
                java.util.List<android.content.pm.ResolveInfo> targets =
                        getPackageManager().queryIntentActivities(send,
                                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
                for (android.content.pm.ResolveInfo target : targets) {
                    grantUriPermission(target.activityInfo.packageName, uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
                Intent qq = new Intent(send);
                qq.setPackage("com.tencent.mobileqq");
                Intent chooser = Intent.createChooser(send, "发送调试日志");
                chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                main.post(() -> {
                    if (isDestroyed()) return;
                    if (qq.resolveActivity(getPackageManager()) != null) startActivity(qq);
                    else startActivity(chooser);
                });
            } catch (Exception failure) {
                main.post(() -> {
                    if (!isDestroyed()) android.widget.Toast.makeText(this,
                            "调试日志未能生成", android.widget.Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        refreshHealthAccessCard();
        refreshHealthAccountCard();
        promptHostVersions();
        if (!rootGranted) requestRoot();
        else continueInit();
    }

    private void refreshHealthAccessCard() {
        if (healthAccessCard == null) return;
        io.github.miam1ku.mibandoplusbridge.data.LocalPrefs prefs =
                io.github.miam1ku.mibandoplusbridge.data.LocalPrefs.open(this, BandNotificationListener.SETTINGS);
        boolean show = prefs.getBoolean("healthSecure", false)
                && !prefs.getBoolean("healthApproved", true)
                && prefs.getBoolean("healthGrantFailed", false);
        healthAccessCard.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /**
     * 健康账号确认状态提示。
     *
     * <p>写健康数据前要求 binding 表里有一条已确认账号 —— 这条记录只能由宿主 OPPO 健康
     * 回调 HealthQueueProvider.adoptAccount 写入。宿主从来没被打开初始化过、或者宿主自身
     * 崩溃时记录就会缺失。以前这种情况会让后台归档线程抛异常并杀掉整个桥接进程，用户只能
     * 看到「手表要求重新配对」这类表象；这里改成显式告诉用户该做什么。
     */
    private void refreshHealthAccountCard() {
        if (accountCard == null) return;
        worker.execute(() -> {
            String state;
            try {
                state = new io.github.miam1ku.mibandoplusbridge.data.HealthRecordStore(this)
                        .accountState();
            } catch (RuntimeException | Error unavailable) {
                android.util.Log.w("OplusBandBridge", "ACCOUNT_STATE_UNAVAILABLE", unavailable);
                return;
            }
            main.post(() -> {
                if (isDestroyed()) return;
                boolean ready = "ACCOUNT_CONFIRMED".equals(state);
                accountCard.setVisibility(ready ? View.GONE : View.VISIBLE);
            });
        });
    }

    private void openOHealth() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("com.heytap.health");
        if (launch != null) {
            try {
                startActivity(launch);
                return;
            } catch (RuntimeException ignored) {
                // 宿主被冻结/停用时打不开，落到下面的提示
            }
        }
        if (ownershipStatus != null) ownershipStatus.setText("打不开 OPPO 健康，可能已被冻结或停用。");
    }

    private void openHealthListenerSettings() {
        Intent detail = new Intent("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS");
        detail.putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME",
                "com.heytap.health/com.heytap.health.watch.commonnotification.HeytapNotificationListenerService");
        try {
            startActivity(detail);
        } catch (RuntimeException failure) {
            try {
                startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
            } catch (RuntimeException ignored) {
                ownershipStatus.setText("打不开通知使用权设置。");
            }
        }
    }

    private void continueInit() {
        refreshOwnership();
        refreshDevice();
        // 华为链路不依赖小米运动健康：直接视为可用，避免误报「不支持」。
        hostSupported = true;
        refresh();
        ensureCallPermissions();
    }

    private void promptHostVersions() {
        if (isDestroyed() || versionPromptShown) return;
        java.util.ArrayList<HostVersionNotice.Installed> installed = new java.util.ArrayList<>();
        for (HostVersionNotice.Expectation expect : HostVersionNotice.EXPECTATIONS) {
            try {
                var info = getPackageManager().getPackageInfo(expect.packageName(), 0);
                installed.add(new HostVersionNotice.Installed(expect.packageName(), info.versionName,
                        info.getLongVersionCode(), true));
            } catch (PackageManager.NameNotFoundException missing) {
                installed.add(new HostVersionNotice.Installed(expect.packageName(), "", 0, false));
            }
        }
        String finger = HostVersionNotice.fingerprint(installed);
        if (finger.isEmpty()) return;
        SharedPreferences prefs = getSharedPreferences("host-version-notice", MODE_PRIVATE);
        if (finger.equals(prefs.getString("dismissed", ""))) return;
        versionPromptShown = true;
        String message = HostVersionNotice.message(installed);
        new MaterialAlertDialogBuilder(this)
                .setTitle("建议对齐已验证版本")
                .setMessage(message)
                .setCancelable(true)
                .setPositiveButton("我知道了", (dialog, which) -> prefs.edit().putString("dismissed", finger).apply())
                .setOnCancelListener(dialog -> prefs.edit().putString("dismissed", finger).apply())
                .show();
    }


    private void requestRoot() {
        if (worker.isShutdown() || probingRoot || changingOwnership) return;
        probingRoot = true;
        ownershipStatus.setText("正在检查 KernelSU 授权…");
        worker.execute(() -> {
            boolean granted = new OwnershipController(this).probeRoot();
            main.post(() -> {
                if (isDestroyed()) return;
                probingRoot = false;
                rootGranted = granted;
                ownershipStatus.setText(granted
                        ? "KernelSU 已授权。"
                        : "未获得 root。打开 KernelSU，为本应用打开超级用户权限，返回后点「检查 Root」。");
                updateControls();
                refreshDevice();
                if (granted) continueInit();
            });
        });
    }

    private void openKernelSu() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("me.weishu.kernelsu");
        if (launch == null) {
            ownershipStatus.setText("未找到 KernelSU。请安装管理器后为本应用打开超级用户权限。");
            return;
        }
        startActivity(launch);
    }

    private void refreshOwnership() {
        if (worker.isShutdown() || changingOwnership || !rootGranted) return;
        worker.execute(() -> {
            if (!isUnlocked()) return;
            OwnershipController owner = new OwnershipController(this);
            BandStateRepository repository = new BandStateRepository(this);
            String message;
            try {
                boolean registered = repository.isRegistered();
                boolean ready = owner.nativeReady();
                if (!registered) {
                    BandLiveService.stop(this);
                    if (!SppDiagnosticClient.stopAllAndWait()) {
                        throw new OwnershipController.Failure("DIAGNOSTIC_SOCKET_STILL_ACTIVE");
                    }
                    repository.disconnected();
                }
                if (registered && ready && hasBluetoothPermission()) {
                    BandLiveService.start(this);
                }
                message = !registered
                        ? (needsOfficialRestore() ? "尚未登记，官方应用仍处于暂停状态。可添加设备或恢复官方管理。" : "尚未添加设备。")
                        : HuaweiDeviceRegistry.provisioned(this)
                                // 华为链路没有小米所有权可接管：设备由桥接直接管理，
                                // 落回小米文案会让人以为还要去接管小米运动健康。
                                ? "已由桥接直接管理（华为链路），无需华为运动健康参与。"
                                : "NATIVE".equals(owner.mode())
                                        ? "已由桥接管理。连接状态以设备实际响应为准。"
                                        : "当前由其他应用管理。可点击连接重新配对。";
            } catch (Exception failure) {
                message = repository.isRegistered()
                        ? "设备登记已保留。连接恢复未完成，请重试或恢复官方管理。"
                        : "设备未登记，官方恢复未完成，请重试恢复官方管理。";
            }
            String visible = message;
            main.post(() -> {
                if (isDestroyed()) return;
                ownershipStatus.setText(visible);
                updateControls();
            });
        });
    }

    private void changeOwnership(Change change) {
        if (worker.isShutdown() || changingOwnership) return;
        if (!rootGranted) {
            requestRoot();
            return;
        }
        // 华为设备没有小米所有权可恢复：移除 = 清注册表 + 停服务 + 注销设备空间卡片，
        // 走下面那套小米 machinery 只会报 DEVICE_IDENTITY_CHANGED。
        if (change == Change.REMOVE && HuaweiDeviceRegistry.provisioned(this)) {
            if (!isUnlocked()) {
                ownershipStatus.setText("请先解锁手机，再管理设备。");
                return;
            }
            changingOwnership = true;
            updateControls();
            ownershipStatus.setText("正在移除，请稍候…");
            worker.execute(() -> {
                String message;
                try {
                    HuaweiDeviceRegistry.clear(this);
                    BandLiveService.stop(this);
                    // 华为表移除后设备空间不再自动上磁贴，桥接自己的磁贴恢复供用户手动添加。
                    io.github.miam1ku.mibandoplusbridge.service.BandTileService
                            .setEnabled(this, true);
                    // registered=false 让 DeviceCardProvider 停止供卡，设备空间观察者随即撤卡。
                    io.github.miam1ku.mibandoplusbridge.data.LocalPrefs.open(this, "band-state")
                            .edit().putBoolean("registered", false).commit();
                    message = "已移除华为设备，可随时重新配对。";
                } catch (Exception failure) {
                    message = "移除未完成：" + failureHint(failure);
                }
                String visible = message;
                main.post(() -> {
                    if (isDestroyed()) return;
                    changingOwnership = false;
                    ownershipStatus.setText(visible);
                    updateControls();
                    refreshDevice();
                });
            });
            return;
        }
        if (!isUnlocked()) {
            ownershipStatus.setText("请先解锁手机，再管理设备。");
            return;
        }
        changingOwnership = true;
        updateControls();
        ownershipStatus.setText("正在处理，请稍候…");
        worker.execute(() -> {
            OwnershipController owner = new OwnershipController(this);
            BandStateRepository repository = new BandStateRepository(this);
            String message;
            boolean registrationSaved = false;
            boolean removalSaved = false;
            boolean officialRestored = false;
            try {
                if (change == Change.ADD || change == Change.RECONNECT) {
                    if (!isUnlocked()) throw new IllegalStateException("USER_LOCKED");
                    if (!owner.probeRoot()) throw new OwnershipController.Failure("ROOT_REQUIRED");
                    if (!hasBluetoothPermission()) throw new IllegalStateException("NEARBY_PERMISSION_REQUIRED");
                    if (change == Change.ADD) {
                        repository.registerDevice();
                        registrationSaved = true;
                    }
                    if (!repository.isRegistered()) throw new IllegalStateException("DEVICE_NOT_REGISTERED");
                    if (!owner.nativeReady()) owner.takeOver();
                    if (!repository.isRegistered() || !owner.nativeReady()) {
                        throw new IllegalStateException("NATIVE_OWNERSHIP_REQUIRED");
                    }
                    BandLiveService.start(this);
                    BandLiveService.requestSync(this);
                    message = "设备已登记，正在连接。";
                } else {
                    if (change == Change.REMOVE) {
                        repository.unregisterDevice();
                        removalSaved = true;
                        main.post(this::refreshDevice);
                    }
                    BandLiveService.stop(this);
                    if (!SppDiagnosticClient.stopAllAndWait()) {
                        throw new OwnershipController.Failure("DIAGNOSTIC_SOCKET_STILL_ACTIVE");
                    }
                    repository.disconnected();
                    owner.restoreOfficial();
                    officialRestored = change == Change.RESTORE;
                    message = change == Change.REMOVE
                            ? "已移除并恢复官方管理。绑定和健康历史已保留。"
                            : "已恢复官方管理。绑定和健康历史已保留。";
                }
            } catch (Exception failure) {
                if (removalSaved || (change == Change.RESTORE && !repository.isRegistered())) {
                    message = "已移除，官方恢复待重试。" + failureHint(failure);
                } else if (change == Change.REMOVE) {
                    message = "移除未能保存，设备仍保持登记。请重试。";
                } else if (registrationSaved || (change == Change.RECONNECT && repository.isRegistered())) {
                    message = "设备登记已保留，连接未完成。" + failureHint(failure);
                } else {
                    message = failureHint(failure);
                }
            }
            String visible = message;
            boolean followProfile = openProfileAfterRestore(change, officialRestored);
            main.post(() -> {
                if (isDestroyed()) return;
                changingOwnership = false;
                ownershipStatus.setText(visible);
                updateControls();
                refreshDevice();
                if (profileAfterRestore && followProfile) {
                    profileAfterRestore = false;
                    startProfileWindow();
                }
            });
        });
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == CALL_PERMISSIONS) {
            BandLiveService.refreshNotificationSettings(this);
            return;
        }
        if (requestCode != BLUETOOTH_PERMISSION) return;
        if (pendingHuaweiPair) {
            pendingHuaweiPair = false;
            if (hasBluetoothPermission()) showHuaweiDevicePicker();
            else ownershipStatus.setText("配对华为手表需要附近设备权限。请在权限设置中授权后重试。");
            return;
        }
        refreshDevice();
        if (!hasBluetoothPermission())
            ownershipStatus.setText("添加或连接需要附近设备权限。请在权限设置中授权。已有登记会保留。");
    }

    private void ensureCallPermissions() {
        if (callPermissionsAsked || !isUnlocked() || !new BandStateRepository(this).isRegistered()) return;
        boolean phone = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
        boolean hangup = checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED;
        if (phone && hangup) return;
        callPermissionsAsked = true;
        if (!phone && !hangup) {
            requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS},
                    CALL_PERMISSIONS);
        } else if (!phone) {
            requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE}, CALL_PERMISSIONS);
        } else {
            requestPermissions(new String[]{Manifest.permission.ANSWER_PHONE_CALLS}, CALL_PERMISSIONS);
        }
    }

    private boolean isUnlocked() {
        return getSystemService(UserManager.class).isUserUnlocked()
                && !getSystemService(KeyguardManager.class).isDeviceLocked();
    }

    private boolean hasBluetoothPermission() {
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean needsOfficialRestore() {
        var ownership = io.github.miam1ku.mibandoplusbridge.data.LocalPrefs.open(this, "ownership");
        return ownership.getBoolean("ownsDisable", false) || ownership.getBoolean("transitionPending", false);
    }

    private void updateControls() {
        boolean registered = new BandStateRepository(this).isRegistered();
        SetupProgress progress = readProgress();
        if (setupCard != null) {
            setupCard.setVisibility(progress.showChecklist() ? View.VISIBLE : View.GONE);
            if (progress.showChecklist()) bindChecklist(progress);
        }
        if (homePrimary != null) {
            homePrimary.setText(progress.primaryLabel());
            homePrimary.setEnabled(!changingOwnership && !probingRoot);
        }
        if (openKsu != null) {
            openKsu.setVisibility(rootGranted ? View.GONE : View.VISIBLE);
            openKsu.setEnabled(!probingRoot);
        }
        remove.setVisibility(registered && rootGranted ? View.VISIBLE : View.GONE);
        remove.setEnabled(!changingOwnership);
        restore.setVisibility(rootGranted && needsOfficialRestore() ? View.VISIBLE : View.GONE);
        restore.setText(registered ? "恢复官方应用" : "重试恢复官方应用");
        restore.setEnabled(!changingOwnership);
    }

    private SetupProgress readProgress() {
        boolean registered = new BandStateRepository(this).isRegistered();
        // 华为表（GT3）不经过小米运动健康：绑定事实在 HuaweiDeviceRegistry，
        // 健康侧注册由 HuaweiDeviceRegistry.publish() 随会话自动写进 band-state。
        // 小米三步（导入绑定 / 采集连接参数 / 添加到健康）对它一步都不适用，
        // 不分支就会永远停在「导入绑定」——2026-10-17 实机正是如此。
        if (HuaweiDeviceRegistry.provisioned(this)) {
            return new SetupProgress(rootGranted, true, true, registered, true, true, true);
        }
        boolean hasBinding = false;
        String model = "";
        try {
            JSONObject binding = new BindingStore(this).read();
            hasBinding = binding != null;
            if (binding != null) model = binding.optString("model", "");
        } catch (Exception ignored) { }
        boolean hasProfile = hasBinding && TransportObservation.supportsLive(
                TransportObservation.read(this), model);
        boolean nativeOwned = false;
        try {
            nativeOwned = new OwnershipController(this).nativeReady();
        } catch (RuntimeException ignored) { }
        boolean accountConfirmed = false;
        try {
            accountConfirmed = new HealthRecordStore(this).confirmedAccountHash() != null;
        } catch (Exception ignored) { }
        return new SetupProgress(rootGranted, hasBinding, hasProfile, registered, nativeOwned, accountConfirmed, false);
    }
    private void bindChecklist(SetupProgress progress) {
        bindChecklistRow(checklistRoot, progress, SetupProgress.Step.ROOT, "KernelSU 授权");
        if (progress.huawei) {
            bindChecklistRow(checklistImport, progress, SetupProgress.Step.IMPORT, "配对华为手表");
            bindChecklistRow(checklistProfile, progress, SetupProgress.Step.PROFILE, "配对华为手表");
            bindChecklistRow(checklistAdd, progress, SetupProgress.Step.ADD, "配对华为手表");
        } else {
            bindChecklistRow(checklistImport, progress, SetupProgress.Step.IMPORT, "导入绑定");
            bindChecklistRow(checklistProfile, progress, SetupProgress.Step.PROFILE, "采集连接参数");
            bindChecklistRow(checklistAdd, progress, SetupProgress.Step.ADD, "添加到健康");
        }
        String hint = progress.primaryHint();
        primaryHint.setText(hint);
        primaryHint.setVisibility(hint.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void bindChecklistRow(TextView row, SetupProgress progress, SetupProgress.Step step, String title) {
        int variant = MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant, 0);
        int primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, 0);
        int current = progress.current().ordinal();
        int index = step.ordinal();
        if (index < current) {
            row.setText("已完成 · " + title);
            row.setTextColor(variant);
        } else if (index == current) {
            row.setText("正在进行 · " + title);
            row.setTextColor(primary);
        } else {
            row.setText("未完成 · " + title);
            row.setTextColor(variant);
        }
    }

    private void runHomePrimary() {
        if (changingOwnership || probingRoot) return;
        SetupProgress progress = readProgress();
        // Root 是两条链路共同的前置，先处理。
        if (progress.current() == SetupProgress.Step.ROOT) {
            requestRoot();
            return;
        }
        // 华为设备没有小米所有权可接管：已配对走重新配对，未配对走设备选择器。
        if (HuaweiDeviceRegistry.provisioned(this)) startHuaweiPairing();
        else startHuaweiPairingPicker();
    }

    /**
     * 重新配对：拉起华为配对入口（同应用 Activity，MAC 取自注册表）。
     * 首次认证时手表会弹确认；成功后注册表落盘，BandLiveService 随后的连接全部走 bind。
     */
    private void startHuaweiPairing() {
        String mac = HuaweiDeviceRegistry.address(this);
        if (mac.isBlank()) {
            ownershipStatus.setText("尚未登记华为设备，请先配对。");
            return;
        }
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(this, HuaweiPairActivity.class));
        intent.putExtra("mac", mac);
        intent.putExtra("model", HuaweiDeviceRegistry.model(this));
        intent.putExtra("autostart", true);
        startActivity(intent);
    }

    /**
     * 配对华为手表：列出系统已配对设备，点一下直接配对。
     * 取代原来只能 adb 拉起 + 手查 MAC 的入口；MAC 与型号都从系统蓝牙直接取。
     */
    private void startHuaweiPairingPicker() {
        if (changingOwnership || probingRoot) return;
        if (!rootGranted) {
            requestRoot();
            return;
        }
        if (!isUnlocked()) {
            ownershipStatus.setText("请先解锁手机，再配对华为手表。");
            return;
        }
        if (!hasBluetoothPermission()) {
            pendingHuaweiPair = true;
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, BLUETOOTH_PERMISSION);
            return;
        }
        showHuaweiDevicePicker();
    }

    private void showHuaweiDevicePicker() {
        BluetoothAdapter adapter = getSystemService(BluetoothManager.class).getAdapter();
        if (adapter == null) {
            ownershipStatus.setText("此设备没有蓝牙适配器。");
            return;
        }
        java.util.Set<BluetoothDevice> devices;
        try {
            devices = adapter.getBondedDevices();
        } catch (SecurityException denied) {
            ownershipStatus.setText("需要附近设备权限才能列出已配对设备。");
            return;
        }
        if (devices == null || devices.isEmpty()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("请先在系统蓝牙中配对华为手表")
                    .setMessage("打开系统蓝牙设置，把华为手表配对到这台手机后，再来这里选择它。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        java.util.List<BluetoothDevice> listed = new ArrayList<>(devices);
        listed.sort(Comparator.comparing((BluetoothDevice device) -> !looksLikeHuaweiWatch(device.getName()))
                .thenComparing(device -> safeName(device.getName()), String.CASE_INSENSITIVE_ORDER));
        String[] names = new String[listed.size()];
        String[] macs = new String[listed.size()];
        String[] labels = new String[listed.size()];
        for (int index = 0; index < listed.size(); index++) {
            BluetoothDevice device = listed.get(index);
            macs[index] = device.getAddress() == null ? "" : device.getAddress().toUpperCase(Locale.ROOT);
            String label = safeName(device.getName());
            labels[index] = stripVariant(label);
            if (!macs[index].isBlank()) label += "  (" + macs[index] + ")";
            names[index] = label;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("选择要配对的华为手表")
                .setItems(names, (dialog, which) -> pairHuaweiDevice(macs[which], labels[which]))
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 配对时把系统蓝牙里读到的设备名作为型号带进去（原作者的做法：用手表自己的
     * 蓝牙广播名当型号，而不是写死 GT3）。Fit5 / GT4 / GT5 等都会显示成真实型号。
     * 型号只用于展示与设备空间卡片归类，填不对也只会显示原样名字，不会变砖。
     */
    private void pairHuaweiDevice(String mac, String name) {
        if (mac == null || mac.isBlank()) return;
        if (!BluetoothAdapter.checkBluetoothAddress(mac)) {
            ownershipStatus.setText("蓝牙地址无效：" + mac);
            return;
        }
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(this, HuaweiPairActivity.class));
        intent.putExtra("mac", mac);
        intent.putExtra("model", name == null ? "" : name);
        intent.putExtra("autostart", true);
        startActivity(intent);
        ownershipStatus.setText("已拉起配对，请在手表上确认配对请求；成功后由桥接自动连接。");
    }

    private static boolean looksLikeHuaweiWatch(String name) {
        if (name == null) return false;
        String normalized = name.toUpperCase(Locale.ROOT);
        return normalized.contains("HUAWEI") || normalized.contains("WATCH")
                || normalized.contains("MATE") || normalized.contains("BUDS")
                || normalized.contains("FIT") || normalized.contains("HONOR")
                || normalized.contains("MAGIC") || normalized.contains("ULTIMATE")
                || normalized.contains("GT ") || normalized.contains("GT2")
                || normalized.contains("GT3") || normalized.contains("GT4")
                || normalized.contains("GT5");
    }

    /** 去掉型号尾部的变体后缀（如 GT 3-13D 里的 -13D），避免把颜色/版本段带进型号。 */
    private static String stripVariant(String name) {
        if (name == null) return "";
        return name.replaceAll("[\\s\\-_]+[A-Z0-9]{2,5}$", "").trim();
    }

    private static String safeName(String name) {
        if (name == null) return "未命名设备";
        String clean = name.replaceAll("[\\p{Cntrl}\\p{Cf}]", "").trim();
        return clean.isEmpty() ? "未命名设备" : clean;
    }

    private void refreshDevice() {
        if (isDestroyed() || worker.isShutdown()) return;
        updateControls();
        boolean bluetooth = hasBluetoothPermission();
        boolean notify = getSystemService(NotificationManager.class).areNotificationsEnabled();
        publishPermissionStatus(bluetooth, notify);
        if (!isUnlocked()) {
            deviceName.setText("请先解锁手机以读取绑定信息。");
            connectionLine.setVisibility(View.GONE);
            metricsLine.setVisibility(View.GONE);
            healthLine.setVisibility(View.GONE);
            return;
        }
        connectionLine.setVisibility(View.VISIBLE);
        var state = io.github.miam1ku.mibandoplusbridge.data.LocalPrefs.open(this, "band-state");
        if (new BandStateRepository(this).isRegistered()) {
            deviceName.setText(safeIdentity(
                    BandCatalog.displayName(state.getString("modelId", ""), state.getString("name", "")),
                    "已登记手环"));
            connectionLine.setText(state.getBoolean("connected", false) ? "已连接" : offlineHint());
            setOptionalCaption(metricsLine, metricsText(state));
            setOptionalCaption(healthLine, healthExtras());
            return;
        }
        deviceName.setText("未添加");
        connectionLine.setText("未添加");
        healthLine.setVisibility(View.GONE);
        try {
            JSONObject binding = new BindingStore(this).read();
            setOptionalCaption(metricsLine, binding == null ? "" : "已导入，待添加");
        } catch (Exception unavailable) {
            setOptionalCaption(metricsLine, "暂时无法读取绑定信息。请解锁手机后重试；已有数据未删除。");
        }
    }

    private static String metricsText(io.github.miam1ku.mibandoplusbridge.data.LocalPrefs state) {
        int battery = state.getInt("battery", -1);
        long lastSync = state.getLong("lastSyncAtMs", 0);
        String batteryText = battery >= 0 && battery <= 100 ? "电量 " + battery + "%" : "";
        String syncText = lastSync > 0
                ? java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(lastSync))
                : "";
        if (batteryText.isEmpty()) return syncText;
        if (syncText.isEmpty()) return batteryText;
        return batteryText + " · " + syncText;
    }

    private String offlineHint() {
        String status = "";
        java.io.File file = new java.io.File(getNoBackupFilesDir(), "live-status.txt");
        if (file.isFile() && file.length() > 0 && file.length() < 256) {
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                byte[] bytes = new byte[(int) file.length()];
                int n = in.read(bytes);
                if (n > 0) status = new String(bytes, 0, n, java.nio.charset.StandardCharsets.UTF_8).trim();
            } catch (Exception ignored) { }
        }
        return switch (status) {
            case "OBSERVED_PROFILE_REQUIRED" -> "离线 · 连接参数丢失";
            case "NATIVE_OWNERSHIP_REQUIRED" -> "离线 · 需要重新接管";
            case "UNPROVISIONED", "BINDING_INCOMPLETE", "BINDING_ADDRESS", "BINDING_REGION",
                    "TOKEN_ENCODING_UNSUPPORTED" -> "离线 · 绑定不完整";
            case "CREDENTIAL_REFRESH_REQUIRED" -> "离线 · 需要重新导入绑定";
            case "BLUETOOTH_DISABLED" -> "离线 · 蓝牙已关闭";
            default -> "离线";
        };
    }

    private String healthExtras() {
        String extra = "";
        if ("UNAVAILABLE".equals(getSharedPreferences("live-service", MODE_PRIVATE)
                .getString("healthHostStatus", ""))) {
            extra = "健康处理不可用，请打开 OHealth 后重试。";
        }
        String collection = getSharedPreferences("live-service", MODE_PRIVATE).getString("healthCollectionStatus", "");
        String collectionText = switch (collection) {
            case "ACCOUNT_CONFIRMATION_REQUIRED" -> "健康记录写入当前 OHealth 账号，并由 OHealth 同步到云端。";
            case "HEALTH_OUTBOX_FULL" -> "等待健康处理，采集已暂停，记录未丢弃。";
            case "HISTORY_FORMAT_UNSUPPORTED" -> "部分健康文件暂不支持，原始记录已保留。";
            case "HISTORY_FILE_REJECTED" -> "健康文件未通过校验，将在下次同步重试。";
            case "HISTORY_STORAGE_FAILED", "HEALTH_STORAGE_UNAVAILABLE" -> "存储不可用，健康同步已暂停。";
            case "HEALTH_REPLAY_PAUSED", "HISTORY_CONFIRMATION_PENDING" -> "健康同步待重试，已保存的记录仍保留。";
            default -> "";
        };
        if (collectionText.isEmpty()) return extra;
        return extra.isEmpty() ? collectionText : extra + "\n" + collectionText;
    }

    private static void setOptionalCaption(TextView view, String value) {
        boolean show = value != null && !value.isBlank();
        view.setVisibility(show ? View.VISIBLE : View.GONE);
        view.setText(show ? value : "");
    }

    private void publishPermissionStatus(boolean bluetooth, boolean notify) {
        if (bluetooth && notify) {
            permissionStatus.setVisibility(View.GONE);
            permissionStatus.setOnClickListener(null);
            return;
        }
        permissionStatus.setVisibility(View.VISIBLE);
        permissionStatus.setOnClickListener(null);
        permissionStatus.setText("附近设备权限：" + (bluetooth ? "已授权" : "未授权")
                + "\n本应用通知：" + (notify ? "已开启" : "未开启"));
    }

    private static String safeIdentity(String value, String fallback) {
        String clean = value == null ? "" : value.replaceAll("[\\p{Cntrl}\\p{Cf}]", "").trim();
        return clean.isEmpty() || clean.length() > 80 ? fallback : clean;
    }

    private static String failureHint(Exception failure) {
        String code = failure instanceof OwnershipController.Failure
                ? ((OwnershipController.Failure) failure).code : failure.getMessage();
        if (code == null) return "操作未完成，请重试；必要时先恢复官方管理。";
        return switch (code) {
            case "USER_LOCKED" -> "请先解锁手机。";
            case "UNPROVISIONED", "BINDING_INCOMPLETE" -> "绑定信息不完整或不可用，请在高级设置中重新导入。";
            case "BINDING_ADDRESS" -> "绑定里的蓝牙地址不是大写 MAC。请重新选择这只手环再导入。";
            case "BINDING_REGION" -> "绑定里没有地区。请在原厂 App 里打开这只手环，等它连上后再导入一次。";
            case "TOKEN_ENCODING_UNSUPPORTED" -> "绑定里的密钥不是 16 字节。请让手表在原厂配对流程中连上一次后再试。";
            case "OBSERVED_PROFILE_REQUIRED" -> "连接参数丢失。请在原厂 App 里重连一次手表，或重新配对。";
            case "FIRMWARE_OR_MODEL_UNSUPPORTED" -> "手环未返回可用的型号或固件。";
            case "OOB_BRANCH_UNSUPPORTED" -> "这只设备使用了不受支持的配对认证。";
            case "PROTOCOL_VERSION_UNSUPPORTED", "SESSION_CONFIGURATION_UNSUPPORTED" -> "官方连接不是可接管的 SPP 会话。蓝牙或 GATT-only 设备无法添加。";
            case "DEVICE_IDENTITY_CHANGED" -> "导入设备与已保存的设备身份不一致，不能替换。请重新导入原手环。";
            case "BAND_STATE_STORAGE_FAILED", "OWNERSHIP_STORAGE_FAILED" -> "设备状态未能保存，请检查可用存储空间后重试。";
            case "ROOT_REQUIRED", "ROOT_ACTION_FAILED", "ROOT_ACTION_TIMEOUT" -> "请确认已授予桥接应用 KernelSU root 权限后重试。";
            case "HOST_VERSION_UNSUPPORTED" -> "原厂 App 版本不受支持，请更换受支持的版本后重试。";
            case "NEARBY_PERMISSION_REQUIRED" -> "请授予附近设备权限后重试。";
            case "DEVICE_NOT_REGISTERED" -> "请先添加设备。";
            case "DIAGNOSTIC_SOCKET_STILL_ACTIVE" -> "连接尚未完全停止，请稍后重试恢复官方管理。";
            case "OWNERSHIP_RECOVERY_REQUIRED", "OFFICIAL_ALREADY_DISABLED" -> "请先恢复官方管理并确认官方应用已启用，再重试接管。";
            case "OWNERSHIP_ROLLBACK_FAILED" -> "接管回滚没有完成，原厂 App 可能仍是停用。请再试一次恢复官方管理。";
            default -> "操作未完成，请重试；必要时先恢复官方管理。";
        };
    }

    private void action(String method, Bundle arguments) {
        if (isDestroyed() || worker.isShutdown()) return;
        worker.execute(() -> {
            try {
                Bundle response = getContentResolver().call(CredentialProvider.URI, method, null, arguments);
                main.post(() -> {
                    if (isDestroyed()) return;
                    show(response);
                    if ("openWindow".equals(method) || "openDiagnostics".equals(method) || "openCapture".equals(method)) {
                        main.removeCallbacks(expireRefresh);
                        main.postDelayed(expireRefresh, 120_100);
                    }
                });
            } catch (RuntimeException rejected) {
                main.post(() -> {
                    if (!isDestroyed()) status.setText("操作未完成。请核对设备地址，并确认手机已解锁。");
                });
            }
        });
    }

    private void refresh() { action("status", null); }

    private void show(Bundle response) {
        if (response == null) {
            status.setText("导入服务不可用。");
            return;
        }
        String code = response.getString("status", "CREDENTIAL_STORAGE_FAILED");
        String text = switch (code) {
            case "IMPORT_WINDOW_OPEN" -> "导入窗口已开启。请在原厂 App 中打开所选设备，让官方应用重新读取绑定信息。";
            case "IMPORT_WINDOW_CLOSED" -> "所有导入和协议采集窗口均已关闭。";
            case "DIAGNOSTIC_WINDOW_OPEN" -> "连接参数采集已开启。仅观察已导入手环的连接类型和协议版本，不记录消息内容或密钥。请在官方应用中重连。";
            case "PROTOCOL_CAPTURE_OPEN" -> "协议消息采集已开启，120 秒后关闭。请执行本次测试操作。";
            case "UNPROVISIONED" -> "尚未导入绑定信息。";
            case "BINDING_SAVED" -> "绑定已保存。型号：" + response.getString("model", "")
                    + "\n产品：" + response.getString("productId", "")
                    + "\n固件：" + (response.getString("firmware", "").isBlank() ? "连接后读取" : response.getString("firmware", ""));
            case "BINDING_INCOMPLETE" -> "BINDING_INCOMPLETE\n已加密保存转换结果，但尚缺实机协议证据，不能独立连接。\n型号："
                    + response.getString("model", "") + "\n产品：" + response.getString("productId", "")
                    + "\n固件：" + response.getString("firmware", "") + "\n导入时缺失：" + response.getString("missing", "");
            case "USER_LOCKED" -> "请先解锁手机。";
            default -> "本机私有存储操作失败，采集窗口已关闭。已有凭据未主动删除。";
        };
        String observation = response.getString("transportObservation", "");
        if (!observation.isBlank()) text += "\n连接参数（仅观察）：\n" + observation;
        if (response.getBoolean("diagnosticOpen", false)) text += "\n连接参数采集窗口仍开启。";
        if (response.getBoolean("captureOpen", false)) text += "\n协议消息采集窗口仍开启。";
        String captureFile = response.getString("captureFile", "");
        if (!captureFile.isBlank()) text += "\n采集记录：" + response.getInt("captureRecords")
                + "，字节：" + response.getLong("captureBytes") + "\n文件：" + captureFile;
        String captureError = response.getString("captureError", "");
        if (!captureError.isBlank()) text += "\n采集不完整：" + captureError;
        status.setText(text);
        if (ownershipStatus != null && ("IMPORT_WINDOW_OPEN".equals(code)
                || "DIAGNOSTIC_WINDOW_OPEN".equals(code))) {
            ownershipStatus.setText(text);
            if (launchHostAfterOpen) {
                launchHostAfterOpen = false;
                if (hostSupported) {
                    Intent launch = getPackageManager().getLaunchIntentForPackage(HostIdentity.MI_PACKAGE);
                    if (launch == null) {
                        ownershipStatus.setText("无法打开原厂 App。请检查应用是否已安装且启用。");
                    } else {
                        startActivity(launch);
                    }
                }
            }
        }
    }

    private void showSetupGuide() {
        boolean paired = HuaweiDeviceRegistry.provisioned(this);
        String guide = paired
                ? "你已配对华为手表，连接由桥接服务自动维持。\n\n"
                    + "如需重新配对或换表：进入「常用 → 配对华为手表」，从列表里选择系统已配对的手表，并在表上确认配对请求即可。\n\n"
                    + SETUP_PREREQ
                : "1. 打开 KernelSU，为本应用打开超级用户权限，返回后点「检查 Root」。\n"
                    + "2. 在系统蓝牙里配对好华为手表（手表需处于可配对/可被发现状态）。\n"
                    + "3. 进入「常用 → 配对华为手表」，从列表里选择你的华为手表。\n"
                    + "4. 手表会弹出配对确认，在表上点一下确认；配对成功后由桥接自动连接。\n"
                    + "5. 下拉快捷面板或控制中心添加「手环」磁贴，点一下即可拉起连接，不会断开。\n\n"
                    + SETUP_PREREQ;
        new MaterialAlertDialogBuilder(this)
                .setTitle("使用说明")
                .setMessage(guide)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void startProfileWindow() {
        launchHostAfterOpen = true;
        action("openDiagnostics", null);
    }

    private void sendDebugCall() {
        sendDebugCommand("测试来电", BandNotificationCommand.incomingCall(
                "测试来电", Instant.now(), ZoneId.systemDefault()));
    }

    private void endDebugCall() {
        sendDebugCommand("来电结束", BandNotificationCommand.endCall());
    }

    private void sendDebugCommand(String label,
            nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command command) {
        if (ownershipStatus == null) return;
        if (!BandLiveService.notificationSessionReady(this)) {
            try { BandLiveService.start(this); } catch (RuntimeException ignored) { }
            String message = "会话未就绪，无法发送" + label + "。请先点「立即同步」，等手环连上后再试。";
            ownershipStatus.setText(message);
            if (status != null) status.setText(message);
            return;
        }
        ownershipStatus.setText("正在发送" + label + "…");
        if (status != null) status.setText("正在发送" + label + "…");
        BandLiveService.forwardHostNotification(this, command).whenComplete((ignored, error) -> main.post(() -> {
            if (isDestroyed()) return;
            String detail = error == null ? "已确认送达手环。"
                    : ("发送失败：" + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
            String message = label + detail;
            android.util.Log.i("OplusBandBridge", "DEBUG_NOTIFY " + message);
            ownershipStatus.setText(message);
            if (status != null) status.setText(message);
        }));
    }


    @Override protected void onDestroy() {
        getContentResolver().unregisterContentObserver(observer);
        getContentResolver().unregisterContentObserver(deviceObserver);
        main.removeCallbacks(expireRefresh);
        worker.shutdown();
        super.onDestroy();
    }
}
