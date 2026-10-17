// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.probe;

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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import io.github.miam1ku.mibandoplusbridge.data.HuaweiLogger;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiDeviceType;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.service.HuaweiDeviceCapabilities;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.service.HuaweiIdentityStore;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.service.HuaweiSupportProvider;

import java.util.Locale;

/**
 * GT3 离线握手自检。
 *
 * <p>只做一件事：连上手表，跑完认证链，把每一步的结果打印出来。<b>不</b>写通知、
 * 不做音乐控制、不碰 OPPO 健康与设备空间，也不启动 {@code BandLiveService} ——
 * 那些都要等握手稳定后再说，现在混进来只会让「是协议错了还是上层错了」变模糊。
 *
 * <p>它存在的意义是：协议栈刚移植完，静态检查与编译都过，但真机上会卡在哪一步
 * 完全是未知的。这个界面把黑盒变成白盒 —— 每一步成功/失败、协商出的
 * {@code deviceSupportType}/{@code authType}/{@code authMode}/{@code mtu}
 * 都直接可见。
 *
 * <p>用<b>独立 applicationId</b>，可与已安装的 bridge 主模块并存。身份（伪 MAC /
 * androidID / secretKey）按手表 MAC 存在自己的 SharedPreferences 里，与主模块互不干扰 ——
 * 也就是说自检失败不会污染主模块已配对的状态，反之亦然。
 */
public final class ProbeActivity extends Activity {

    private final Handler main = new Handler(Looper.getMainLooper());

    private EditText macInput;
    private EditText pinInput;
    private Button runButton;
    private Button stopButton;
    private TextView log;

    private volatile HuaweiSupportProvider provider;
    private volatile boolean running;

    /**
     * 本轮测试开始前的身份快照，测试结束时用它还原。
     *
     * <p>见 {@link #begin()} 里的说明：自检若不还原，会把同机上其它走同一
     * 华为协议的应用（典型是 Gadgetbridge）永久挡在「认证密钥协商失败」上。
     */
    private volatile String[] identitySnapshot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 日志门面依赖它落盘，不绑就所有 HuaweiLogger 输出被静默丢弃。
        HuaweiLogger.bind(getApplicationContext());

        setContentView(buildUi());
        refreshButtons();

        // 免 UI 驱动：adb shell am start -n <pkg>/.ProbeActivity \
        //     --es mac 54:52:84:9F:D1:3D --ez autostart true
        //
        // 为什么需要这个：自检要在真机上反复跑，而走软键盘输入 MAC 在部分
        // ColorOS 机型上会被输入法吞掉（adb input text 与 input keyevent 都无效，
        // 事件能到 Activity 但进不了 EditText）。把参数入口改成 Intent，
        // 诊断流程就能完全从 adb 脚本驱动，不必人在机器前戳屏幕。
        Bundle args = getIntent() == null ? null : getIntent().getExtras();
        if (args != null) {
            String mac = args.getString(EXTRA_MAC);
            if (mac != null && !mac.isEmpty()) {
                macInput.setText(mac.trim().toUpperCase(Locale.ROOT));
            }
            String pin = args.getString(EXTRA_PIN);
            if (pin != null) {
                pinInput.setText(pin.trim());
            }
            if (args.getBoolean(EXTRA_AUTOSTART, false)) {
                // 等一帧，确保视图已 attach —— 立刻 begin() 时 macInput 已可用，
                // 但 provider 的连接回调可能早于窗口上屏，留一点余量更稳。
                main.postDelayed(this::begin, 300);
            }
        }
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView intro = new TextView(this);
        intro.setText("GT3 握手自检（BR/EDR + RFCOMM）\n\n"
                + "只验证协议握手，不做任何通知或系统功能。\n"
                + "首次运行是认证模式：手表会弹出配对确认，需要在表上点一下。\n"
                + "后续运行走 bind，无需确认。\n\n"
                + "PIN 留空即可 —— 本自检通过命令 0x2C 向手表索要，"
                + "不是让你手输。");
        root.addView(intro, lp(-1, -2));

        macInput = new EditText(this);
        macInput.setHint("手表 MAC，例如 C1:2F:xx:xx:xx:xx");
        macInput.setText(getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_LAST_MAC, ""));
        root.addView(macInput, lp(-1, -2));

        pinInput = new EditText(this);
        pinInput.setHint("PIN（可留空，由 0x2C 获取）");
        root.addView(pinInput, lp(-1, -2));

        runButton = new Button(this);
        runButton.setText("开始握手");
        runButton.setOnClickListener(v -> begin());
        root.addView(runButton, lp(-1, -2));

        stopButton = new Button(this);
        stopButton.setText("断开");
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
        String mac = macInput.getText().toString().trim().toUpperCase(Locale.ROOT);
        if (!BluetoothAdapter.checkBluetoothAddress(mac)) {
            append("✗ MAC 格式不合法：" + mac);
            return;
        }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
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

        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LAST_MAC, mac).apply();

        log.setText("");
        running = true;
        refreshButtons();
        append("▶ 目标 " + mac);
        append("▶ 本机 " + safeLocalName(adapter));

        HuaweiSupportProvider p = new HuaweiSupportProvider(HuaweiDeviceType.BR);
        p.setContext(getApplicationContext());
        // 身份按手表 MAC 持久化：伪 MAC / androidID / secretKey 三者必须跨会话稳定，
        // 否则手表会把我们当成新手机，每次都要重新确认。
        p.attachIdentity(new HuaweiIdentityStore(getApplicationContext(), mac));

        // ★ 测试前快照身份，测完还原。
        //
        // HiChain 认证会把手表的绑定槽改写成我们上报的伪 MAC + androidID。
        // 手表同一时间只认一台手机，一旦被自检占住，机上其它走同一协议的应用
        // （典型是 Gadgetbridge）再握手就会报「认证密钥协商失败」——
        // 它拿着自己的密钥，去解手表发给「自检身份」的密文，必然解不开。
        //
        // 快照只能还原<b>手机侧</b>；手表侧被改写的绑定槽要靠「不真正完成
        // 全新认证」或事后重新配对才能复原。所以每轮测试后都会调restore，
        // 把 secretKey 和伪 MAC 换回原样。
        identitySnapshot = p.snapshotIdentity();
        append("▶ 已快照本机身份（mac="
                + (identitySnapshot[0] == null ? "无" : identitySnapshot[0])
                + " hasKey=" + (identitySnapshot[2] != null && !identitySnapshot[2].isEmpty())
                + "）");

        p.setHandshakeListener(new HuaweiSupportProvider.HandshakeListener() {
            @Override
            public void onHandshakeResult(boolean success, String error) {
                main.post(() -> {
                    refreshButtons();
                    if (success) {
                        append("✓ 握手完成 —— 协议链路可用");
                        dumpNegotiated(p);
                        // running 保持 true：电量请求还在路上，
                        // 「断开」按钮在整个验证过程里都该可点。
                        readBattery(p);
                    } else {
                        running = false;
                        refreshButtons();
                        append("✗ 握手失败：" + error);
                        restoreIdentity(p, "握手失败");
                    }
                });
            }
        });
        provider = p;

        try {
            p.attachTransport(adapter, mac, new HuaweiBrListener());
            if (!p.getTransport().connect()) {
                running = false;
                refreshButtons();
                append("✗ 无法建立 RFCOMM 连接");
                restoreIdentity(p, "连接失败");
                return;
            }
            append("▶ RFCOMM 已连接，等待 LinkParams 响应…");
            append("  首次认证时手表会弹配对确认，请在表上确认");
        } catch (RuntimeException e) {
            running = false;
            refreshButtons();
            append("✗ 传输层初始化异常：" + e);
            restoreIdentity(p, "传输层异常");
        }
    }

    /** 把协商结果摊开 —— 这些值决定了后面所有包能不能正确加解密。 */
    private void dumpNegotiated(HuaweiSupportProvider p) {
        HuaweiPacket.ParamsProvider pp = p.getParamsProvider();
        append("  protocolVersion   = " + p.getProtocolVersion());
        append("  deviceSupportType = 0x"
                + Integer.toHexString(pp.getDeviceSupportType() & 0xFF));
        append("  authVersion       = 0x"
                + Integer.toHexString(pp.getAuthVersion() & 0xFF));
        append("  authAlgo          = 0x"
                + Integer.toHexString(pp.getAuthAlgo() & 0xFF));
        append("  authMode          = 0x"
                + Integer.toHexString(pp.getAuthMode() & 0xFF));
        append("  encryptMethod     = " + pp.getEncryptMethod());
        append("  mtu / sliceSize   = " + pp.getMtu() + " / " + pp.getSliceSize());
        append("  encryptionCounter = " + pp.getEncryptionCounter());
        byte[] pin = pp.getPinCode();
        append("  pinCode           = " + (pin == null ? "未获取" : pin.length + " 字节"));

        HuaweiDeviceCapabilities caps = p.getDeviceState();
        byte[] bitmap = caps.getExpandCapabilities();
        append("  capabilityBits    = " + (bitmap == null
                ? "未获取" : bitmap.length + " 字节"));
        append("    dataSync.config=" + caps.supportsDeviceCommandConfig()
                + " event=" + caps.supportsDeviceCommandEvent()
                + " data=" + caps.supportsDeviceCommandData()
                + " dict=" + caps.supportsDeviceCommandDictData());
        // provider.getMacAddress() 返回的是伪 MAC 的 UTF-8 字节，
        // 展示时转回字符串形式。三个 FF 开头是华为协议对"手机侧自报身份"的约定。
        append("  identityMac       = " + new String(p.getMacAddress(),
                java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * 握手成功后打一发 0x08 电量。
     *
     * <p>这是本次验证的真正目的：握手成功只说明认证过了，而 0x08 是第一个走
     * <b>认证后加密通道</b>的功能命令 —— 它要能在会话密钥下正确加解密并被手表接受。
     * 若这一步失败，故障就锁定在「加密方法 / 计数器 / 密钥派生」，而不是「链路没通」。
     */
    private void readBattery(final HuaweiSupportProvider p) {
        append("▶ 读取电量（DeviceConfig 0x08，认证后加密通道）…");
        p.readBattery((level, error) -> main.post(() -> {
            if (error != null) {
                append("  ✗ 电量读取失败：" + error);
            } else {
                append("  ✓ 电量 = " + level + "%"
                        + (p.isBatteryCharging() ? "（充电中）" : ""));
            }
            running = false;
            refreshButtons();
            append("■ 验证结束（链路仍保持连接，可继续观察）");
            // 无论电量读成功还是失败，都必须还原身份，否则这轮自检会污染同机其它应用。
            restoreIdentity(p, "验证结束");
        }));
    }

    /**
     * 还原本轮测试前的身份快照。
     *
     * <p>失败也照样还原 —— 正是「握手中途失败」最容易留下半套身份，那种情况
     * 最容易让人误判成「手表坏了」。
     */
    private void restoreIdentity(HuaweiSupportProvider p, String when) {
        String[] snap = identitySnapshot;
        identitySnapshot = null;
        if (snap == null) {
            return;
        }
        try {
            p.restoreIdentity(snap);
            append("↩ 已还原本机身份（" + when + "）—— 本轮自检不会遗留副作用");
        } catch (RuntimeException e) {
            append("⚠ 身份还原失败：" + e);
        }
    }

    private void stop(String reason) {
        HuaweiSupportProvider p = provider;
        if (p != null && p.getTransport() != null) {
            p.getTransport().disconnect();
        }
        if (p != null) {
            restoreIdentity(p, reason);
        }
        running = false;
        refreshButtons();
        append("■ " + reason);
    }

    private void refreshButtons() {
        boolean granted = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
        runButton.setEnabled(granted && !running);
        stopButton.setEnabled(running);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        refreshButtons();
    }

    @Override
    protected void onDestroy() {
        HuaweiSupportProvider p = provider;
        if (p != null && p.getTransport() != null) {
            p.getTransport().disconnect();
        }
        // 退出时兜底还原：adb 驱动下Activity 常常是被 force-stop 直接杀掉的，
        // 走不到 stop()/握手回调那几条正常收尾路径。
        if (p != null) {
            restoreIdentity(p, "退出");
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 工具

    private void append(String line) {
        // 同时写 logcat：界面文本没法从 adb 读，logcat 可以。诊断时用
        // adb logcat -s GT3Probe 就能拿到完整时序，不必截图或 uiautomator 解析。
        android.util.Log.i(LOG_TAG, line);
        main.post(() -> {
            if (isDestroyed()) {
                return;
            }
            log.append(line + "\n");
        });
    }

    private String safeLocalName(BluetoothAdapter adapter) {
        try {
            return adapter.getName() + " / " + adapter.getAddress();
        } catch (SecurityException e) {
            return "(权限不足)";
        }
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

    private static final String PREFS = "gt3-probe";
    private static final String KEY_LAST_MAC = "last_mac";

    private static final String LOG_TAG = "GT3Probe";

    private static final String EXTRA_MAC = "mac";
    private static final String EXTRA_PIN = "pin";
    private static final String EXTRA_AUTOSTART = "autostart";

    /** 只把连接状态转发到界面；握手结果走 provider 自己的 listener。 */
    private final class HuaweiBrListener
            implements io.github.miam1ku.mibandoplusbridge.protocol.huawei.br
            .HuaweiBrTransport.ConnectionListener {
        @Override
        public void onConnected() {
            append("  transport connected");
        }

        @Override
        public void onDisconnected(boolean deliberate) {
            main.post(() -> {
                running = false;
                refreshButtons();
                append("  transport disconnected (deliberate=" + deliberate + ")");
            });
        }
    }
}
