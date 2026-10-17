// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.service;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import io.github.miam1ku.mibandoplusbridge.data.HuaweiDeviceRegistry;
import io.github.miam1ku.mibandoplusbridge.data.HuaweiLogger;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.br.HuaweiBrTransport;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiDeviceType;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.service.HuaweiIdentityStore;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.service.HuaweiSupportProvider;

import java.util.Locale;

/**
 * 华为 GT3 配对入口：把一块华为表登记进主应用，交给 {@link BandLiveService} 长期持有。
 *
 * <p><b>与 probe 自检的本质区别</b>：probe 是「一次性验证」，开始前快照身份、
 * 结束后还原，刻意不留任何副作用；本类是「正式配对」，
 * {@link HuaweiIdentityStore} 里的伪 MAC / androidID / secretKey 就是<b>要</b>
 * 长期驻留的 —— 手表把它们当作这台手机 identity，下次重连直接走 bind，
 * 不需要用户再点确认。</p>
 *
 * <p><b>为什么独立于 MainActivity</b>：主界面是小米五步向导（Root → 导入绑定 →
 * 采集连接参数 → 添加到健康），华为链路一步都不适用；混进去只会让两个协议
 * 的失败互相掩盖。本类只被 adb 或将来专用入口拉起：
 * <pre>
 * adb shell am start -n io.github.miam1ku.mibandoplusbridge/.service.HuaweiPairActivity \
 *     --es mac 54:52:84:9F:D1:3D --ez autostart true
 * </pre>
 * 首次认证时手表会弹配对确认，需要在表上点一下；成功后注册表落盘，
 * {@code BandLiveService} 随后的连接全部走 bind，无感。</p>
 */
public final class HuaweiPairActivity extends Activity {

    private static final String EXTRA_MAC = "mac";
    private static final String EXTRA_MODEL = "model";
    private static final String EXTRA_AUTOSTART = "autostart";
    private static final String DEFAULT_MODEL = "HUAWEI WATCH GT3";
    private static final String LOG_TAG = "GT3Pair";

    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView log;
    private Button stopButton;

    private volatile HuaweiSupportProvider provider;
    private volatile boolean running;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 华为协议栈的日志门面落盘依赖 Context；不绑则 HuaweiLogger 输出被静默丢弃。
        HuaweiLogger.bind(getApplicationContext());

        setContentView(buildUi());

        Bundle args = getIntent() == null ? null : getIntent().getExtras();
        if (args != null && args.getBoolean(EXTRA_AUTOSTART, false)) {
            // 等一帧让窗口上屏，与 probe 同样的余量。
            main.postDelayed(this::begin, 300);
        } else {
            append("▶ 等待启动参数（--es mac <MAC> --ez autostart true）");
        }
    }

    /**
     * CONNECT 与 SCAN 必须<b>同时</b>到位：传输层建连时的
     * {@code adapter.cancelDiscovery()} 在 API 31+ 起要 SCAN，
     * 只查 CONNECT 会在运行时才抛 SecurityException。
     */
    private boolean bluetoothPermissionsGranted() {
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView intro = new TextView(this);
        intro.setText("华为 GT3 配对\n\n"
                + "首次认证时手表会弹出配对确认，需要在表上点一下。\n"
                + "配对成功后由桥接服务自动维持连接。");
        root.addView(intro, lp(-1, -2));

        stopButton = new Button(this);
        stopButton.setText("断开");
        stopButton.setEnabled(false);
        stopButton.setOnClickListener(v -> stop("用户断开"));
        root.addView(stopButton, lp(-1, -2));

        ScrollView scroller = new ScrollView(this);
        log = new TextView(this);
        log.setTextSize(11f);
        log.setTextIsSelectable(true);
        log.setMovementMethod(new ScrollingMovementMethod());
        log.setPadding(dp(8), dp(8), dp(8), dp(8));
        scroller.addView(log, lp(-1, -2));
        root.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1f));

        return root;
    }

    // ------------------------------------------------------------------ 运行控制

    private void begin() {
        if (running) {
            return;
        }
        Bundle args = getIntent() == null ? null : getIntent().getExtras();
        String requested = args == null ? null : args.getString(EXTRA_MAC);
        String model = args == null ? null : args.getString(EXTRA_MODEL);
        if (requested == null || requested.isBlank()) {
            append("✗ 缺少 --es mac <MAC> 参数");
            return;
        }
        // 握手回调是 lambda，捕获的变量必须 effectively final —— 规范化后的
        // 地址另起一个 final 局部，原变量不再改动。
        final String mac = requested.trim().toUpperCase(Locale.ROOT);
        if (!BluetoothAdapter.checkBluetoothAddress(mac)) {
            append("✗ MAC 格式不合法：" + mac);
            return;
        }
        if (!bluetoothPermissionsGranted()) {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN}, 1);
            return;
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            append("✗ 蓝牙未开启");
            return;
        }

        running = true;
        stopButton.setEnabled(true);
        append("▶ 配对目标 " + mac);

        // 与 probe 的差别只有一处：不拍身份快照、结束不还原。
        // 这里落地的身份就是长期身份，secretKey 由 bind 结束时覆盖写入。
        HuaweiSupportProvider p = new HuaweiSupportProvider(HuaweiDeviceType.BR);
        p.setContext(getApplicationContext());
        p.attachIdentity(new HuaweiIdentityStore(getApplicationContext(), mac));
        p.setHandshakeListener((success, error) -> main.post(() -> {
            if (success) {
                append("✓ 握手完成 —— 写入注册表并读取电量");
                readBatteryAndRegister(p, mac, model);
            } else {
                append("✗ 握手失败：" + error);
                stop("配对失败");
            }
        }));
        provider = p;

        try {
            p.attachTransport(adapter, mac, new PairBrListener());
            if (!p.getTransport().connect()) {
                append("✗ 无法建立 RFCOMM 连接");
                stop("连接失败");
                return;
            }
            append("▶ RFCOMM 已连接，等待 LinkParams 响应…");
        } catch (RuntimeException e) {
            append("✗ 传输层初始化异常：" + e);
            stop("传输层异常");
        }
    }

    /**
     * 握手成功后的收尾：读一发电量（认证后加密通道的第一个功能命令，
     * 顺带验证加密参数可用），把设备写进注册表，拉起桥接服务，然后主动断开 ——
     * 服务会用自己的会话重连，配对入口不必长期占着链路。
     */
    private void readBatteryAndRegister(HuaweiSupportProvider p, String mac, String model) {
        p.readBattery((level, error) -> main.post(() -> {
            if (error != null) {
                append("  ✗ 电量读取失败：" + error);
            } else {
                append("  ✓ 电量 = " + level + "%"
                        + (p.isBatteryCharging() ? "（充电中）" : ""));
            }
            registerDevice(p, mac, model, level, error == null);
        }));
    }

    private void registerDevice(HuaweiSupportProvider p, String mac, String model,
            int battery, boolean batteryOk) {
        String resolved = model == null || model.isBlank() ? DEFAULT_MODEL : model;
        if (!HuaweiDeviceRegistry.save(this, mac, resolved)) {
            append("✗ 注册表写入失败");
            stop("注册失败");
            return;
        }
        // 设备空间会给 WATCH 型设备自动上磁贴，桥接自己这块磁贴随即停用，
        // 免得快捷面板出现两块手表磁贴（小米手环是 WRISTBAND，不受影响）。
        BandTileService.setEnabled(this, false);
        append("✓ 已注册：" + resolved + " / " + mac);
        // 电量单发一次只为验证加密通道；会话状态记在华为自己的注册表里
        // （BandStateRepository.recordVerifiedDevice 绑死小米身份，华为设备
        // 写进去只会得到 DEVICE_IDENTITY_CHANGED）。
        if (batteryOk && battery >= 0) {
            HuaweiDeviceRegistry.noteSession(this, battery, p.isBatteryCharging(), false);
        }
        // 投影到 band-state，让 OPPO 健康/设备空间的钩子立刻看到这块表，
        // 不等服务第一次会话完成。
        HuaweiDeviceRegistry.publish(this);
        // 立刻把会话拉起来：服务里的华为分支会读到注册表并重连（走 bind，无感）。
        try {
            BandLiveService.start(this);
            append("▶ 已请求启动桥接服务");
        } catch (RuntimeException e) {
            append("⚠ 桥接服务启动失败（可手动启动）：" + e);
        }
        stop("配对完成");
    }

    private void stop(String reason) {
        HuaweiSupportProvider p = provider;
        provider = null;
        if (p != null && p.getTransport() != null) {
            p.getTransport().disconnect();
        }
        running = false;
        stopButton.setEnabled(false);
        append("■ " + reason);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        // 只有真授予了才继续：否则 begin() 会再次请求，死循环刷对话框。
        if (bluetoothPermissionsGranted()) {
            main.postDelayed(this::begin, 200);
        } else {
            append("✗ 蓝牙权限未授予，无法配对");
        }
    }

    @Override
    protected void onDestroy() {
        // adb 驱动下这个 Activity 常被直接杀掉，走不到 stop()，这里兜底断链。
        HuaweiSupportProvider p = provider;
        if (p != null && p.getTransport() != null) {
            p.getTransport().disconnect();
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 界面与日志

    private void append(String line) {
        android.util.Log.i(LOG_TAG, line);
        main.post(() -> {
            if (isDestroyed() || log == null) {
                return;
            }
            log.append(line + "\n");
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams lp(int width, int height) {
        int top = dp(8);
        return new LinearLayout.LayoutParams(width, height) {
            @Override public void setMargins(int l, int t, int r, int b) {
                super.setMargins(0, top, 0, 0);
            }
        };
    }

    /** 只把连接状态转发到界面；握手结果走 provider 自己的 listener。 */
    private final class PairBrListener
            implements HuaweiBrTransport.ConnectionListener {
        @Override
        public void onConnected() {
            append("  transport connected");
        }

        @Override
        public void onDisconnected(boolean deliberate) {
            main.post(() -> {
                append("  transport disconnected (deliberate=" + deliberate + ")");
                if (!deliberate && running) {
                    stop("链路断开");
                }
            }
            );
        }
    }
}
