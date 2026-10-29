// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import io.github.miam1ku.mibandoplusbridge.data.BandStateRepository;
import io.github.miam1ku.mibandoplusbridge.data.LocalPrefs;
import io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand;
import io.github.miam1ku.mibandoplusbridge.notify.FindPhone;
import io.github.miam1ku.mibandoplusbridge.notify.PhoneDnd;
import io.github.miam1ku.mibandoplusbridge.protocol.BandDndCommand;
import io.github.miam1ku.mibandoplusbridge.protocol.BandMusicCommand;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.Alarms;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.DeviceConfig;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.MusicControl;
import io.github.miam1ku.mibandoplusbridge.service.BandLiveService;
import io.github.miam1ku.mibandoplusbridge.ui.BridgeScreen;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground protocol tests. The log keeps protocol fields; MAC, token, and health values stay in memory. */
public final class LabActivity extends AppCompatActivity {
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private BridgeScreen screen;
    private TextView info;
    private TextView session;
    private TextView result;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        screen = BridgeScreen.attach(this, "协议测试", true);
        LinearLayout device = screen.card();
        screen.overline(device, "设备信息");
        info = screen.bodyText(device, "正在读取…");
        screen.setLastChildMargin(device, 0);
        screen.outlined("刷新设备信息", this::refresh);

        LinearLayout live = screen.card();
        screen.overline(live, "会话");
        session = screen.bodyText(live, "正在检查连接会话…");
        screen.setLastChildMargin(live, 0);
        screen.outlined("立即同步", () -> {
            try {
                BandLiveService.start(this);
                setResultText("已请求同步。等手环连上后再发通知或来电。");
            } catch (RuntimeException failure) {
                setResultText("无法启动会话：" + failure.getClass().getSimpleName());
            }
            refresh();
        });

        LinearLayout notify = screen.card();
        screen.overline(notify, "消息通知");
        screen.caption(notify, "经当前 SPP 会话发一条测试通知到这只手环。不经过系统通知监听。");
        screen.setLastChildMargin(notify, 0);
        screen.filled("发送测试通知", () -> send("测试通知", BandNotificationCommand.post(
                getPackageName(), "桥接测试", "lab-notify", 1,
                "测试通知", "这是一条发往手环的测试消息。", Instant.now(), ZoneId.systemDefault()), false));
        screen.outlined("撤回测试通知", () -> send("撤回通知",
                BandNotificationCommand.dismiss(getPackageName(), "lab-notify", 1), false));

        LinearLayout call = screen.card();
        screen.overline(call, "来电");
        screen.caption(call, "手环应显示来电界面。点结束来电可关掉。");
        screen.setLastChildMargin(call, 0);
        screen.filled("发送测试来电", () -> send("测试来电",
                BandNotificationCommand.incomingCall("测试来电", Instant.now(), ZoneId.systemDefault()), false));
        screen.outlined("结束测试来电", () -> send("来电结束", BandNotificationCommand.endCall(), false));

        LinearLayout find = screen.card();
        screen.overline(find, "查找手机");
        screen.caption(find, "调用 OPPO 健康自己的查找手机响铃，不向手环发命令。");
        screen.setLastChildMargin(find, 0);
        screen.filled("测试查找手机", () -> FindPhone.start(this));
        screen.outlined("停止查找", () -> FindPhone.stop(this));

        LinearLayout music = screen.card();
        screen.overline(music, "音乐");
        screen.caption(music, "把手环上的播放信息刷成一条测试曲目。真正播放时会自动同步，手环按键会控制手机。");
        screen.setLastChildMargin(music, 0);
        screen.filled("发送测试音乐", this::sendTestMusic);

        LinearLayout dnd = screen.card();
        screen.overline(dnd, "免打扰");
        screen.caption(dnd, "把手机当前的免打扰开关同步到手表。两包都发，不看型号。");
        screen.setLastChildMargin(dnd, 0);
        screen.filled("同步免打扰", this::syncDnd);

        LinearLayout alarm = screen.card();
        screen.overline(alarm, "闹钟");
        screen.caption(alarm, "只测响铃、关闭和稍后响，不写入手环自己的闹钟列表。");
        screen.setLastChildMargin(alarm, 0);
        screen.filled("测试闹钟响铃", this::sendTestAlarmRing);
        screen.outlined("测试关闭闹钟", this::sendTestAlarmClose);
        screen.outlined("测试稍后响", this::sendTestAlarmSnooze);

        LinearLayout extra = screen.card();
        screen.overline(extra, "其他");
        screen.setLastChildMargin(extra, 0);
        screen.navRow(extra, "独立鉴权与电量", () -> startActivity(new Intent(this, DiagnosticActivity.class)));
        screen.navRow(extra, "检查更新", this::checkUpdate);
        result = screen.bodyText("等待操作。");
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        LocalPrefs state = LocalPrefs.open(this, "band-state");
        boolean registered = new BandStateRepository(this).isRegistered();
        boolean connected = registered && state.getBoolean("connected", false);
        boolean ready = BandLiveService.notificationSessionReady(this);
        int battery = state.getInt("battery", -1);
        info.setText(deviceText(state, registered, connected, battery));
        session.setText(ready
                ? "华为会话已认证 ✓ 可以发通知、来电、音乐、免打扰、闹钟。"
                : (registered
                    ? "华为会话未连上。点上面的「立即同步」连上手表（约几秒）后再测。"
                    : "未登记手表。请先在首页配对华为手表。"));
    }

    private static String deviceText(LocalPrefs state, boolean registered, boolean connected, int battery) {
        if (!registered) return "未登记手环。请先在首页导入并添加到健康。";
        StringBuilder text = new StringBuilder();
        text.append("名称：").append(blank(state.getString("name", ""), "未命名")).append('\n');
        text.append("型号：").append(blank(state.getString("modelId", ""), "未知")).append('\n');
        String firmware = state.getString("verifiedFirmware", "");
        if (firmware.isBlank()) firmware = state.getString("firmware", "");
        text.append("固件：").append(blank(firmware, "未读取")).append('\n');
        text.append("硬件：").append(blank(state.getString("verifiedHardware", ""), "未读取")).append('\n');
        text.append("连接：").append(connected ? "已连接" : "未连接").append('\n');
        text.append("电量：").append(battery >= 0 && battery <= 100 ? battery + "%" : "未读取");
        if (state.getBoolean("charging", false)) text.append("（充电中）");
        text.append('\n');
        long sync = state.getLong("lastSyncAtMs", 0);
        text.append("上次同步：").append(sync > 0 ? TIME.format(Instant.ofEpochMilli(sync)) : "无").append('\n');
        String deviceId = state.getString("deviceId", "");
        text.append("设备号：").append(deviceId.length() >= 16 ? deviceId.substring(0, 16) + "…" : blank(deviceId, "无")).append('\n');
        text.append("MAC：").append(tailMac(state.getString("mac", "")));
        return text.toString();
    }

    private static String tailMac(String mac) {
        if (mac == null || mac.length() < 5) return "未保存";
        return "…" + mac.substring(mac.length() - 5).replace(":", "");
    }

    private static String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private void send(String label, nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command command,
                      boolean sessionCommand) {
        if (!ready()) return;
        setResultText("正在发送" + label + "…");
        var pending = sessionCommand
                ? BandLiveService.sendSessionCommand(command)
                : io.github.miam1ku.mibandoplusbridge.service.BandLiveService
                        .sendHuaweiTestNotification(this, command);
        pending.whenComplete((ignored, error) -> main.post(() -> finishSend(label, error)));
    }

    private void sendAll(String label, java.util.List<nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command> commands) {
        if (!ready()) return;
        setResultText("正在发送" + label + "…");
        sendAllAt(label, commands, 0);
    }

    private void sendAllAt(String label,
            java.util.List<nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command> commands, int index) {
        if (index >= commands.size()) {
            setResultText(label + "已确认送达手环。共 " + commands.size() + " 包。");
            refresh();
            return;
        }
        BandLiveService.sendSessionCommand(commands.get(index)).whenComplete((ignored, error) -> main.post(() -> {
            if (isDestroyed()) return;
            if (error != null) {
                finishSend(label + "（第 " + (index + 1) + " 包）", error);
                return;
            }
            sendAllAt(label, commands, index + 1);
        }));
    }

    private boolean ready() {
        if (BandLiveService.notificationSessionReady(this)) return true;
        try { BandLiveService.start(this); } catch (RuntimeException ignored) { }
        setResultText("会话未就绪。请先点立即同步，等手环连上后再试。");
        refresh();
        return false;
    }

    private void finishSend(String label, Throwable error) {
        if (isDestroyed()) return;
        String detail = error == null ? "已确认送达手环。"
                : ("发送失败：" + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
        Log.i("OplusBandBridge", "LAB " + label + " " + (error == null ? "ok" : error.getClass().getSimpleName()));
        String msg = label + detail;
        setResultText(msg);
        toast(msg);
        refresh();
    }

    private void checkUpdate() {
        setResultText("正在检查更新…");
        worker.execute(() -> {
            String message;
            String url = null;
            try {
                UpdateChecker.Result latest = UpdateChecker.check(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME);
                if (latest.newer()) {
                    message = "有新版本 " + latest.versionName() + "（" + latest.versionCode() + "）。当前 "
                            + BuildConfig.VERSION_NAME + "。";
                    if (!latest.notes().isBlank()) message += "\n" + latest.notes();
                    url = latest.htmlUrl();
                } else {
                    message = "已是最新：" + BuildConfig.VERSION_NAME + "（" + BuildConfig.VERSION_CODE + "）。";
                }
            } catch (Exception failure) {
                message = "检查更新失败：" + failure.getClass().getSimpleName();
            }
            String open = url;
            String text = message;
            main.post(() -> {
                if (isDestroyed()) return;
                setResultText(text);
                if (open != null) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(open)));
                    } catch (RuntimeException ignored) { }
                }
            });
        });
    }

    // ---- 华为链路命令（GT3 等华为手表）----
    // 这些命令不走小米 command 通道，而是经已认证的华为 SPP 会话 fire-and-forget 发送。

    private void toast(String message) {
        if (isDestroyed()) return;
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    /** 经华为会话发送一条已构造好的报文；会话未就绪或构造失败都给可见反馈。 */
    private void sendHuawei(String label, io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket packet) {
        if (BandLiveService.huaweiParamsProvider() == null) {
            // 会话没起来：先把桥接服务拉起来去连手表，下次点按钮就生效。
            // 这些命令（音乐/免打扰/闹钟）只在手表 RFCOMM 会话在线时才发得出去，
            // 与「查找手机」不同——查找手机走 OPPO 健康自身、不依赖华为会话。
            try { BandLiveService.start(this); } catch (RuntimeException ignored) { }
            String msg = label + "：华为会话还没连上。已尝试连接手表，稍等几秒再点一次。";
            setResultText(msg);
            toast(msg);
            refresh();
            return;
        }
        if (packet == null) {
            String msg = label + "：构造报文失败。";
            setResultText(msg);
            toast(msg);
            return;
        }
        setResultText("正在发送" + label + "…");
        BandLiveService.sendHuaweiCommand(packet).whenComplete((ignored, error) -> main.post(() -> {
            if (isDestroyed()) return;
            String detail = error == null
                    ? "已写入传输层（华为控制命令无语义回包）。"
                    : ("发送失败：" + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
            Log.i("OplusBandBridge", "LAB " + label + " " + (error == null ? "ok" : error.getClass().getSimpleName()));
            String msg = label + detail;
            setResultText(msg);
            toast(msg);
            refresh();
        }));
    }

    private void sendTestMusic() {
        io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket.ParamsProvider pp =
                BandLiveService.huaweiParamsProvider();
        if (pp == null) { sendHuawei("测试音乐", null); return; }
        io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.MusicControl.MusicInfo.Request req =
                new io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.MusicControl.MusicInfo.Request(
                        pp, "桥接测试", "实验室测试曲", (byte) 1, (byte) 10, (byte) 6);
        sendHuawei("测试音乐", req);
    }

    private void syncDnd() {
        io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket.ParamsProvider pp =
                BandLiveService.huaweiParamsProvider();
        if (pp == null) { sendHuawei("同步免打扰", null); return; }
        int filter = PhoneDnd.currentFilter(this);
        boolean enable = filter != PhoneDnd.ALL;
        byte[] start = enable ? nowBytes(0) : new byte[]{0, 0, 0};
        byte[] end = enable ? nowBytes(60) : new byte[]{0, 0, 0};
        io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.DeviceConfig.DndAddRequest req =
                new io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.DeviceConfig.DndAddRequest(
                        pp, enable, start, end, 0x7F, 0, false);
        sendHuawei("同步免打扰（" + (enable ? "开" : "关") + "）", req);
    }

    private void sendTestAlarmRing() {
        io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket.ParamsProvider pp =
                BandLiveService.huaweiParamsProvider();
        if (pp == null) { sendHuawei("测试闹钟响铃", null); return; }
        java.time.LocalTime now = java.time.LocalTime.now();
        io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.Alarms.EventAlarmsRequest req =
                new io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.Alarms.EventAlarmsRequest(pp);
        req.addEventAlarm(new io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.Alarms.EventAlarm(
                (byte) 1, true, (byte) now.getHour(), (byte) now.getMinute(), (byte) 0, "测试闹钟"));
        sendHuawei("测试闹钟响铃", req);
    }

    private void sendTestAlarmClose() {
        io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket.ParamsProvider pp =
                BandLiveService.huaweiParamsProvider();
        if (pp == null) { sendHuawei("测试关闭闹钟", null); return; }
        io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.Alarms.EventAlarmsRequest req =
                new io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.Alarms.EventAlarmsRequest(pp);
        req.addEventAlarm(new io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.Alarms.EventAlarm(
                (byte) 1, false, (byte) 0, (byte) 0, (byte) 0, "测试闹钟"));
        sendHuawei("测试关闭闹钟", req);
    }

    private void sendTestAlarmSnooze() {
        io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket.ParamsProvider pp =
                BandLiveService.huaweiParamsProvider();
        if (pp == null) { sendHuawei("测试稍后响", null); return; }
        java.time.LocalTime later = java.time.LocalTime.now().plusMinutes(5);
        io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.Alarms.EventAlarmsRequest req =
                new io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.Alarms.EventAlarmsRequest(pp);
        req.addEventAlarm(new io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.Alarms.EventAlarm(
                (byte) 1, true, (byte) later.getHour(), (byte) later.getMinute(), (byte) 0, "测试闹钟"));
        sendHuawei("测试稍后响", req);
    }

    private static byte[] nowBytes(int plusMinutes) {
        java.time.LocalTime t = java.time.LocalTime.now().plusMinutes(plusMinutes);
        return new byte[]{(byte) t.getHour(), (byte) t.getMinute(), 0};
    }

    private void setResultText(String value) {
        if (result != null) result.setText(value);
    }

    @Override protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }
}
