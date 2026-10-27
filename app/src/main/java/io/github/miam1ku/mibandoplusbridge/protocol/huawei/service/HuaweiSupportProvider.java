// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol.huawei.service;

import android.content.Context;
import io.github.miam1ku.mibandoplusbridge.data.HuaweiLogger;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.br.HuaweiBrTransport;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.br.HuaweiResponseManager;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.br.HuaweiTransactionBuilder;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.btbr.HuaweiSocketCallback;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiDeviceType;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.DataSync;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.DeviceConfig;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.FitnessData;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.Notifications;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests.GetAuthRequest;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests.HuaweiDataSyncListener;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests.HuaweiWeatherListener;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests.GetBondParamsRequest;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests.GetBondRequest;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests.GetDeviceStatusRequest;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests.GetBatteryLevelRequest;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests.GetExpandCapabilityRequest;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests.GetFitnessDataRequest;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests.GetWatchfacesListRequest;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests.GetFitnessTotalsRequest;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests.GetHiChainRequest;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests.GetLinkParamsRequest;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests.GetNotificationConstraintsRequest;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests.GetPincodeRequest;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests.GetSecurityNegotiationRequest;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests.GetSleepDataRequest;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests.SendNotificationRequest;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests.Request;

/**
 * 华为协议栈的功能枢纽：把协议层、数据同步与 BR/EDR 传输层接在一起。
 *
 * <p>对应 Gadgetbridge 的 {@code service.devices.huawei.HuaweiSupportProvider}。原类
 * 有 55 处外部依赖，其中大部分是 GB 框架（{@code GBDevice}、{@code DeviceCoordinator}、
 * 表盘/应用/OTA 管理器、相机远程、日历、媒体控制…）。bridge 不搬这些，而是把职责收敛为：
 *
 * <ul>
 *   <li>持有 {@link HuaweiBrTransport}（传输）、{@link HuaweiResponseManager}（响应重组与分发）、
 *       {@link HuaweiDataSyncManager}（数据同步）、{@link HuaweiDeviceCapabilities}（能力位）；</li>
 *   <li>给协议层提供 {@link #createBrTransactionBuilder(String)} 和
 *       {@link #getParamsProvider()} 两个出口，协议代码因此不需要知道传输实现；</li>
 *   <li>维护「在途请求」集合，链路断开时统一清理。</li>
 * </ul>
 *
 * <p>未实现的部分（表盘、应用管理、OTA、天气、GPS 等）不保留占位方法，避免协议层
 * 误以为可用。
 */
public final class HuaweiSupportProvider implements HuaweiSocketCallback {

    private static final HuaweiLogger LOG = HuaweiLogger.of(HuaweiSupportProvider.class);

    /** 与 GB 一致的读缓冲：握手包 + 认证密文可能超过 1 KB。 */
    private static final int BUFFER_SIZE = 1032;

    private final HuaweiDeviceType deviceType;
    private final HuaweiDataSyncManager dataSyncManager;
    private final HuaweiDeviceCapabilities capabilities = new HuaweiDeviceCapabilities();
    private final HuaweiResponseManager responseManager;

    /** 在途请求。链路断开时全部释放，避免整条请求链永久挂起。 */
    private final java.util.List<Request> inProgressRequests =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** 数据同步（0x37）的常驻监听器。认证通过后挂上，断链时摘掉。 */
    private final java.util.List<Request> dataSyncListeners =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** 天气（0x0F/0x04）的常驻监听器与它的业务处理者。 */
    private HuaweiWeatherListener weatherListener;
    private HuaweiWeatherListener.Handler weatherHandler;

    private final HuaweiPacket.ParamsProvider paramsProvider = new DefaultParamsProvider();
    private Context context;

    /** LinkParams 协商出的协议版本。== 2 表示支持更完整的推送能力（见 supportsPromptPushMessage）。 */
    private byte protocolVersion;

    /** 本机在华为协议里的身份。由 {@link #attachIdentity} 注入。 */
    private HuaweiIdentityStore identity;

    /** 蓝牙适配器与设备地址在 {@link #attachTransport} 时才知道，故传输层惰性创建。 */
    private HuaweiBrTransport transport;
    private HuaweiBrTransport.ConnectionListener connectionListener;

    public HuaweiSupportProvider(HuaweiDeviceType deviceType) {
        this.deviceType = deviceType;
        this.dataSyncManager = new HuaweiDataSyncManager(this);
        this.responseManager = new HuaweiResponseManager(paramsProvider);
    }

    /**
     * 绑定蓝牙通道，使 provider 具备真正连接手表的能力。
     *
     * <p>必须在任何请求发出之前调用 —— 未绑定时 {@link #getTransport()} 会返回
     * {@code null}，{@link #createBrTransactionBuilder(String)} 随之失败。这是刻意的：
     * 让「忘记绑定」立刻暴露，而不是构造出一个连不上的传输层。
     *
     * @param adapter   系统蓝牙适配器
     * @param deviceAddress 手表的 MAC 地址
     * @param listener  连接状态回调，可为 {@code null}
     */
    public void attachTransport(android.bluetooth.BluetoothAdapter adapter,
            String deviceAddress, HuaweiBrTransport.ConnectionListener listener) {
        if (adapter == null || deviceAddress == null || deviceAddress.isBlank()) {
            throw new IllegalArgumentException("adapter and deviceAddress are required");
        }
        this.connectionListener = listener;
        this.transport = new HuaweiBrTransport(
                context, adapter, deviceAddress, this,
                new ConnectionStateRelay(listener), BUFFER_SIZE);
    }

    public void setContext(Context context) {
        this.context = context;
    }

    public Context getContext() {
        return context;
    }

    public HuaweiDeviceType getDeviceType() {
        return deviceType;
    }

    public HuaweiDataSyncManager getHuaweiDataSyncManager() {
        return dataSyncManager;
    }

    public HuaweiDeviceCapabilities getDeviceState() {
        return capabilities;
    }

    public HuaweiResponseManager getResponseManager() {
        return responseManager;
    }

    /** 传输层；未调用 {@link #attachTransport} 时为 {@code null}。 */
    public HuaweiBrTransport getTransport() {
        return transport;
    }

    public boolean isTransportAttached() {
        return transport != null;
    }

    public HuaweiPacket.ParamsProvider getParamsProvider() {
        return paramsProvider;
    }

    public byte getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(byte protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    // ---------------------------------------------------------------- 认证后功能命令

    /**
     * 读一次电量，结果写入 {@link #getDeviceState()}。
     *
     * <p>不在此启动轮询：轮询周期该由服务层按需控制，藏在单个请求类里会让
     * 「谁在定时问手表」变得不可追。这里只负责发一次。
     *
     * @param onDone 收到响应后回调（成功/失败都可能）；参数是错误描述，成功时为 {@code null}
     */
    public void readBattery(BatteryCallback onDone) {
        GetBatteryLevelRequest req = new GetBatteryLevelRequest(this);
        req.setFinalizeReq(new Request.RequestCallback() {
            @Override
            public void call() {
                if (onDone != null) {
                    onDone.onBattery(readBatteryLevel(), null);
                }
            }

            @Override
            public void handleException(Request.ResponseParseException e) {
                if (onDone != null) {
                    onDone.onBattery(readBatteryLevel(), e.getMessage());
                }
            }
        });
        try {
            req.doPerform();
        } catch (java.io.IOException e) {
            LOG.error("failed to request battery level", e);
            if (onDone != null) {
                onDone.onBattery(readBatteryLevel(), e.getMessage());
            }
        }
    }

    /**
     * 拉取一段时间窗内的步数样本（FitnessData 0x07）。
     *
     * <p>时间窗是<b>秒级</b>时间戳，与协议一致（{@code MessageCount} 的
     * start/end 都是秒）。窗口最大跨度由手表决定，实测一次问 24 小时是安全的；
     * 问太宽会被手表以错误码回绝或干脆不回。
     */
    public void readSteps(int startSec, int endSec,
            GetFitnessDataRequest.StepsListener onDone) {
        readSteps(startSec, endSec, false, onDone);
    }

    /**
     * 拉取分钟明细。{@code full} 为真时逐条取满整个时间窗 —— 一条 0x0B 记录只有
     * 48 分钟，要还原全天曲线必须把窗口内每条记录都取一遍。
     */
    public void readSteps(int startSec, int endSec, boolean full,
            GetFitnessDataRequest.StepsListener onDone) {
        GetFitnessDataRequest req = new GetFitnessDataRequest(this, startSec, endSec, onDone, full);
        try {
            req.doPerform();
        } catch (java.io.IOException e) {
            LOG.error("failed to request fitness data", e);
            if (onDone != null) {
                onDone.onError(e.getMessage());
            }
        }
    }

    /** 拉取当日汇总步数 / 卡路里 / 距离（FitnessTotals，一问一答）。 */
    public void readFitnessTotals(GetFitnessTotalsRequest.TotalsListener onDone) {
        GetFitnessTotalsRequest req = new GetFitnessTotalsRequest(this, onDone);
        try {
            req.doPerform();
        } catch (java.io.IOException e) {
            LOG.error("failed to request fitness totals", e);
            if (onDone != null) {
                onDone.onError(e.getMessage());
            }
        }
    }

    /**
     * 读一次心率（bpm）。
     *
     * <p><b>GT3 没有「问一次给当前心率」的命令。</b>心率是搭在步数分钟明细里回来
     * 的：0x0B 的每个分钟容器有自己的 feature bitmap，bitmap 0xC0 的那几项带
     * 心率。真机探针（{@code step-count 3600 → step-data 1}）拿到过
     * {@code lastHr=90}，同批还有 81/92/90 —— 与手表同期显示一致。
     *
     * <p>为什么窗口取最近一小时而不是 24 小时：0x0D/0x0B 的 {@code count} 是
     * 「窗口内第几条」（1-based，从最早开始），24 小时窗口的第 1 条是昨天晚上
     * 那段，未佩戴时整段是空记录（{@code data = 00}），一条心率都解不出来。
     * 一小时窗口的第 1 条就是刚过去那 48 分钟，里面才有真实样本。
     *
     * @param onDone 回调；读不到心率时传 -1，失败原因走 {@code onError}
     */
    public void readHeartRate(HeartRateListener onDone) {
        readHeartRate(onDone, null);
    }

    /**
     * 心率与分钟样本一起取回。
     *
     * <p>GT3 的心率搭在步数分钟明细里（0x0B 的每个分钟容器带自己的 feature
     * bitmap），所以同一次拉取里 {@code samples} 已经含有那一分钟的步数、
     * 卡路里和距离。周期心跳链每 5 分钟走这条路，之前只把心率留下、步数丢掉，
     * 结果宿主健康里的步数明细停在握手那一次拉到的窗口，越走越旧。
     *
     * <p>{@code onSamples} 非空时，同一批样本会交给它；这一步只做归档，
     * 写库的失败不会影响心率回调。
     *
     * @param onDone 回调；读不到心率时传 -1，失败原因走 {@code onError}
     * @param onSamples 同批分钟样本的消费者，可为 null
     */
    public void readHeartRate(HeartRateListener onDone,
            java.util.function.Consumer<java.util.List<FitnessData.MessageData.StepResponse.SubContainer>>
                    onSamples) {
        int endSec = (int) (System.currentTimeMillis() / 1000L);
        int startSec = endSec - 3600;
        readSteps(startSec, endSec,
                new GetFitnessDataRequest.StepsListener() {
                    @Override
                    public void onSteps(java.util.List<FitnessData.MessageData
                            .StepResponse.SubContainer> samples) {
                        if (onSamples != null) {
                            try {
                                onSamples.accept(samples);
                            } catch (RuntimeException refused) {
                                // 归档失败不该拖垮心率：这条链还要报电量和睡眠。
                                LOG.warn("FitnessData sample consumer failed: "
                                        + refused.getClass().getSimpleName());
                            }
                        }
                        int bpm = -1;
                        int atSec = 0;
                        for (FitnessData.MessageData.StepResponse.SubContainer s : samples) {
                            if (s.heartrate < 0) continue;
                            // 取时间最靠后的那个样本 —— 心率要的是「最近一次」。
                            if (s.timestamp >= atSec) {
                                atSec = s.timestamp;
                                bpm = s.heartrate;
                            }
                        }
                        if (onDone != null) {
                            onDone.onHeartRate(bpm, atSec);
                        }
                    }

                    @Override
                    public void onError(String message) {
                        if (onDone != null) {
                            onDone.onError(message);
                        }
                    }
                });
    }

    /** 心率读取回调。{@code bpm} 为 -1 表示这一批样本里没有心率。 */
    public interface HeartRateListener {
        void onHeartRate(int bpm, int measuredAtSec);

        void onError(String message);
    }

    /** 拉最近一段睡眠（窗口内的倒数第二条记录 —— 最后一条是尚未结束的一段）。 */
    public void readSleep(int windowSec, GetSleepDataRequest.SleepListener onDone) {
        int endSec = (int) (System.currentTimeMillis() / 1000L);
        GetSleepDataRequest req = new GetSleepDataRequest(this, endSec - windowSec, endSec, onDone);
        try {
            req.doPerform();
        } catch (java.io.IOException e) {
            LOG.error("failed to request sleep data", e);
            if (onDone != null) {
                onDone.onError(e.getMessage());
            }
        }
    }

    /**
     * 拉已安装表盘列表+显示名（Watchface 0x27，两阶段）。
     *
     * <p>0x27 与 FitnessData 0x07 是不同服务，但同样必须串行 —— 表盘链问名单的
     * 同时另开 0x07 链，实测会出现响应无人认领（分发按 service/command 匹配）。
     */
    public void readWatchfaces(
            GetWatchfacesListRequest.WatchfaceListener onDone) {
        GetWatchfacesListRequest req = new GetWatchfacesListRequest(this, onDone);
        try {
            req.doPerform();
        } catch (java.io.IOException e) {
            LOG.error("failed to request watchface list", e);
            if (onDone != null) {
                onDone.onError(e.getMessage());
            }
        }
    }

    /** 最近一次上报的电量，无则 {@link HuaweiDeviceCapabilities#BATTERY_UNKNOWN}。 */
    public int readBatteryLevel() {
        return capabilities.getBatteryLevel();
    }

    public boolean isBatteryCharging() {
        return capabilities.isBatteryCharging();
    }

    /** 电量读取结果回调。参数 {@code error} 为 {@code null} 表示成功。 */
    public interface BatteryCallback {
        void onBattery(int level, String error);
    }

    // ---------------------------------------------------------------- 通知推送

    /** 通知内容最大字节数，未知为 -1。来源是 Notifications.Constraints 响应。 */
    private volatile int notificationContentLimit = -1;

    public void noteNotificationContentLimit(int limit) {
        if (limit > 0 && limit <= 64 * 1024) {
            this.notificationContentLimit = limit;
        }
    }

    /**
     * 通知标题/正文允许的最大字节数（UTF-8）。
     *
     * <p>约束还没查到时保守取 255：GT3 对超长内容不报错、只无声丢弃整条通知，
     * 宁可先截短也不能把通知整条弄丢。
     */
    public int notificationContentLimit() {
        int limit = notificationContentLimit;
        return limit > 0 ? limit : 255;
    }

    /** 查询通知内容限制，结果写入 {@link #notificationContentLimit()}。 */
    public void requestNotificationConstraints(ConstraintsCallback onDone) {
        GetNotificationConstraintsRequest req = new GetNotificationConstraintsRequest(this);
        req.setFinalizeReq(new Request.RequestCallback() {
            @Override
            public void call() {
                if (onDone != null) onDone.onConstraints(notificationContentLimit, null);
            }

            @Override
            public void handleException(Request.ResponseParseException e) {
                if (onDone != null) onDone.onConstraints(notificationContentLimit, e.getMessage());
            }
        });
        try {
            req.doPerform();
        } catch (java.io.IOException e) {
            LOG.error("failed to request notification constraints", e);
            if (onDone != null) onDone.onConstraints(notificationContentLimit, e.getMessage());
        }
    }

    /** 通知内容限制查询回调。参数 {@code error} 为 {@code null} 表示成功。 */
    public interface ConstraintsCallback {
        void onConstraints(int contentLimit, String error);
    }

    /**
     * 推送一条通知到手表（fire-and-forget）。
     *
     * <p>内容按 {@link #notificationContentLimit()} 截断（UTF-8）。{@code msgId}
     * 在会话内递增即可，手表用它做去重与移除的键。{@code notificationType}
     * 见 {@link Notifications.NotificationType}，普通通知用 generic(0x7F)。
     */
    public void sendNotification(short msgId, byte notificationType,
            String sourceAppId, String title, String body, boolean vibrate) {
        java.util.ArrayList<Notifications.NotificationActionRequest.TextElement> content =
                new java.util.ArrayList<>();
        int limit = notificationContentLimit();
        if (title != null && !title.isBlank()) {
            content.add(new Notifications.NotificationActionRequest.TextElement(
                    (byte) Notifications.TextType.title, (byte) 0x01,
                    truncateUtf8(title, limit)));
        }
        if (body != null && !body.isBlank()) {
            content.add(new Notifications.NotificationActionRequest.TextElement(
                    (byte) Notifications.TextType.text, (byte) 0x01,
                    truncateUtf8(body, limit)));
        }
        Notifications.NotificationActionRequest.AdditionalParams params =
                new Notifications.NotificationActionRequest.AdditionalParams();
        Notifications.NotificationActionRequest packet = new Notifications.NotificationActionRequest(
                paramsProvider, msgId, notificationType, content, sourceAppId, params);
        try {
            SendNotificationRequest.post(this, packet).doPerform();
            LOG.debug("notification sent msgId={} type={} app={} titleLen={} bodyLen={}",
                    msgId, notificationType, sourceAppId,
                    title == null ? 0 : title.length(), body == null ? 0 : body.length());
        } catch (java.io.IOException e) {
            LOG.error("failed to send notification", e);
        }
    }

    /** 从手表上移除一条通知（按 sourceAppId + notificationKey）。 */
    public void removeNotification(String sourceAppId, String notificationKey, int notificationId) {
        Notifications.NotificationRemoveAction packet = new Notifications.NotificationRemoveAction(
                paramsProvider, (byte) 0x01, sourceAppId, notificationKey, notificationId, "", "");
        try {
            SendNotificationRequest.remove(this, packet).doPerform();
            LOG.debug("notification removed app={} key={} id={}", sourceAppId, notificationKey, notificationId);
        } catch (java.io.IOException e) {
            LOG.error("failed to remove notification", e);
        }
    }

    /** 按字节数截断字符串（UTF-8），不在多字节字符中间断开。 */
    private static String truncateUtf8(String text, int maxBytes) {
        if (text == null) return "";
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return text;
        int cut = maxBytes;
        while (cut > 0 && (bytes[cut] & 0xC0) == 0x80) cut--;
        return new String(bytes, 0, cut, java.nio.charset.StandardCharsets.UTF_8);
    }

    // ---- 认证身份（伪 MAC / androidID / secretKey） ----

    /**
     * 绑定本机身份。<b>必须在任何认证请求之前调用</b>，否则 {@link #getAndroidId()}
     * 与 {@link #getMacAddress()} 会抛异常 —— 没有身份就无法完成 HiChain 协商。
     */
    public void attachIdentity(HuaweiIdentityStore identity) {
        if (identity == null) {
            throw new IllegalArgumentException("identity must not be null");
        }
        this.identity = identity;
    }

    private HuaweiIdentityStore requireIdentity() {
        if (identity == null) {
            throw new IllegalStateException(
                    "huawei identity not attached; call attachIdentity() before authentication");
        }
        return identity;
    }

    /** 协议上报用的伪 MAC，形如 {@code FF:FF:FF:xx:xx:xx}。 */
    public byte[] getMacAddress() {
        return requireIdentity().getMacAddress();
    }

    /** 客户端序列号，取自伪 MAC 的后 6 位。 */
    public byte[] getClientSerial() {
        return requireIdentity().getClientSerial();
    }

    /** HiChain 的 peerAuthId。 */
    public byte[] getAndroidId() {
        return requireIdentity().getAndroidId();
    }

    /** 已认证过的长期密钥；从未认证时返回 {@code null}。 */
    public byte[] getSecretKey() {
        return requireIdentity().getSecretKey();
    }

    /** 认证成功后保存长期密钥，下次重连可直接 bind。 */
    public void setSecretKey(byte[] secretKey) {
        requireIdentity().setSecretKey(secretKey);
        paramsProvider.setSecretKey(secretKey);
    }

    /**
     * 快照当前身份（伪 MAC / androidID / secretKey）。
     *
     * <p>供一次性自检在测试前备份、测试后 {@link #restoreIdentity} 还原，
     * 避免自检把本机身份永久改写成另一套 —— 那会让同一部手机上其它
     * 依赖同一协议的应用（如 Gadgetbridge）从此协商失败。
     */
    public String[] snapshotIdentity() {
        return requireIdentity().snapshot();
    }

    /** 还原身份快照，并把 paramsProvider 上的密钥一并同步回去。 */
    public void restoreIdentity(String[] snapshot) {
        requireIdentity().restore(snapshot);
        paramsProvider.setSecretKey(requireIdentity().getSecretKey());
        LOG.info("identity restored from snapshot, hasSecretKey={}",
                requireIdentity().hasSecretKey());
    }

    /**
     * 构造一个 BR/EDR 事务；协议层唯一的写入出口。
     *
     * <p>未绑定传输层时抛 {@link IllegalStateException}，而不是返回一个写不出去的事务。
     */
    public HuaweiTransactionBuilder createBrTransactionBuilder(String taskName) {
        if (transport == null) {
            throw new IllegalStateException(
                    "huawei transport not attached; call attachTransport() first (task: "
                            + taskName + ")");
        }
        return new HuaweiTransactionBuilder(taskName, transport);
    }

    public boolean isBLE() {
        return deviceType != HuaweiDeviceType.BR;
    }

    // ---------------------------------------------------------------- 握手初始化链

    /** 握手只跑一次；重连时若已认证过则跳过认证直接进能力协商。 */
    private final java.util.concurrent.atomic.AtomicBoolean handshakeStarted =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 收尾幂等锁：保证 BondParams + 0x37 这一组命令每次连接只发一遍。 */
    private final java.util.concurrent.atomic.AtomicBoolean finishOnce =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 认证链是否已真正走完。
     *
     * <p>必须有这个守卫。实测日志（一次连接内）：
     * <pre>
     * 14:24:50.403  HiChain step1 发出     ← 认证开始
     * 14:24:50.424  0x0F BondParams 发出   ← 收尾抢跑，此刻还没有新secretKey
     * 14:24:50.462  0x0F 响应回来（被拒）
     * 14:24:50.483  0x37 失败：request could not be created
     * 14:24:50.551  HiChain step2 才发出    ← 认证其实还没开始
     * </pre>
     *
     * <p>收尾之所以会抢跑，是因为多条认证分支的 finalize 回调与
     * {@code nextRequest} 的链式推进存在竞态：某条链提前走到终点，
     * 就把收尾触发了一遍。此时 paramsProvider 里的 secretKey 仍是旧的
     * （甚至为空），0x37必然构造失败或被手表拒绝。
     */
    private final java.util.concurrent.atomic.AtomicBoolean authChainDone =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private volatile boolean authenticated;

    /** 是否已完成 HiChain 认证。数据同步等后续流程都以此为前置条件。 */
    public boolean isAuthenticated() {
        return authenticated;
    }

    /**
     * 启动握手序列。
     *
     * <p>完整顺序与 GB 原版一致，共六步：
     * {@code LinkParams → DeviceStatus → SecurityNegotiation(0x33) →
     * PinCode(0x2C) → HiChain → ExpandCapability}。
     *
     * <p>其中 0x33 与 0x2C 是<b>不可跳过</b>的两步：前者确定 {@code authMode}
     * （PIN 解密要用），后者填充 {@code paramsProvider.pinCode}（HiChain 派生 PSK
     * 要用）。详见 {@link GetSecurityNegotiationRequest} 与
     * {@link GetPincodeRequest}。
     *
     * <p>由 {@link #onConnectionEstablished()} 在链路建立后自动触发。之所以
     * 做成链式请求而不是一个大方法，是因为每一步都要等上一个响应回来才知道
     * 走哪个分支 —— 而响应只在传输层线程里到达。
     */
    public void startHandshake() {
        if (identity == null) {
            LOG.error("handshake aborted: identity not attached");
            notifyHandshakeResult(false, "identity not attached");
            return;
        }

        GetLinkParamsRequest linkParamsReq = new GetLinkParamsRequest(this);
        linkParamsReq.setFinalizeReq(new Request.RequestCallback() {
            @Override
            public void call() {
                onLinkParamsDone(linkParamsReq);
            }

            @Override
            public void handleException(Request.ResponseParseException e) {
                LOG.error("Link params TLV exception", e);
                notifyHandshakeResult(false, "link params: " + e.getMessage());
            }
        });

        try {
            linkParamsReq.doPerform();
        } catch (java.io.IOException e) {
            LOG.error("failed to start handshake", e);
            notifyHandshakeResult(false, "link params IO: " + e.getMessage());
        }
    }

    /**
     * DeviceStatus 通过后先做 0x33 安全协商，再决定认证分支。
     *
     * <p>顺序上必须先 0x33：{@code deviceSupportType} 决定 {@code authMode}
     * 取 4 还是 2，而 {@code authMode} 又是 0x2C 解密 PIN 的输入之一。
     */
    private void onLinkParamsDone(GetLinkParamsRequest linkParamsReq) {
        GetDeviceStatusRequest statusReq = new GetDeviceStatusRequest(this, true);
        statusReq.setFinalizeReq(new Request.RequestCallback() {
            @Override
            public void call() {
                if (statusReq.isReadyForAuth()) {
                    startSecurityNegotiation(linkParamsReq);
                } else {
                    // 手表停在别的界面（常见于正在显示配对码），此时继续认证只会
                    // 收到一堆无法解释的失败，直接把状态报给上层由用户处理。
                    LOG.warn("device not ready for auth, status={}", statusReq.status);
                    notifyHandshakeResult(false, "device not ready, status=" + statusReq.status);
                }
            }

            @Override
            public void handleException(Request.ResponseParseException e) {
                LOG.error("Device status TLV exception", e);
                notifyHandshakeResult(false, "device status: " + e.getMessage());
            }
        });

        try {
            statusReq.doPerform();
        } catch (java.io.IOException e) {
            LOG.error("device status request failed", e);
            notifyHandshakeResult(false, "device status IO: " + e.getMessage());
        }
    }

    /**
     * 第三步：0x33 安全协商。
     *
     * <p>先按 {@code deviceSupportType} 写入 {@code authMode}（GB 原版：
     * {@code type == 4 ? 4 : 2}），再发请求。响应里的 {@code authType} 决定
     * 后面是完整 HiChain 还是 HiChain Lite。
     */
    private void startSecurityNegotiation(GetLinkParamsRequest linkParamsReq) {
        byte supportType = paramsProvider.getDeviceSupportType();
        if (!isHiChainFamily(supportType)) {
            // 非 HiChain 家族（Normal mode）走 Auth(0x13) 链，不需要 0x33。
            LOG.info("deviceSupportType={} is normal mode; entering auth chain", supportType);
            startHiChainLite(linkParamsReq);
            return;
        }

        paramsProvider.setAuthMode(supportType == 0x04 ? (byte) 0x04 : (byte) 0x02);

        final GetSecurityNegotiationRequest negoReq = new GetSecurityNegotiationRequest(this);
        negoReq.setFinalizeReq(new Request.RequestCallback() {
            @Override
            public void call() {
                dispatchByAuthType(negoReq, linkParamsReq);
            }

            @Override
            public void handleException(Request.ResponseParseException e) {
                LOG.error("SecurityNegotiation failed", e);
                notifyHandshakeResult(false, "security negotiation: " + e.getMessage());
            }
        });

        try {
            negoReq.doPerform();
        } catch (java.io.IOException e) {
            LOG.error("security negotiation request failed", e);
            notifyHandshakeResult(false, "security negotiation IO: " + e.getMessage());
        }
    }

    /**
     * 按 0x33 协商出的 {@code authType} 分流。
     *
     * <p>与 GB 的 {@code finalizeSecurityNegotiationHiChain} 同构：完整 HiChain
     * 走 {@link GetHiChainRequest}，Lite 走 Auth 链。
     */
    private void dispatchByAuthType(GetSecurityNegotiationRequest negoReq,
            GetLinkParamsRequest linkParamsReq) {
        if (negoReq.isFullHiChain()) {
            // ★ 是否走 bind，必须<b>双方状态一致</b>才能决定，不能只看本地。
            //
            // bind 路径的 PSK 是上次认证拿到的 secretKey。所以只有当
            // 「手表认为已绑定」且「本地确实存有 secretKey」时才走 bind。
            //
            // 之前这里只判 !identity.hasSecretKey()，踩了个真实的坑：
            // 手机侧清了应用数据（secretKey 没了）而手表侧仍记着绑定，
            // 于是本地判定「首次认证」而实际发的是 bind 包 —— 手表按
            // 认证流程解一个 bind 包，直接无响应，双方从此卡死：
            //   SecurityNegotiation: authType=0x6 pairType=2
            //   entering full HiChain, firstConnection=false   ← 本地臆断
            //   HiChain request operationCode=0 step=1        ← 却发了 bind
            //
            // 反向的不一致（本地有密钥、手表没绑）同样要退回认证。
            boolean watchBound = !linkParamsReq.isFirstBinding();
            boolean localHasKey = identity.hasSecretKey();
            boolean firstConnection = !(watchBound && localHasKey);

            LOG.info("entering full HiChain: watchBound={} localHasSecretKey={} "
                            + "→ firstConnection={}",
                    watchBound, localHasKey, firstConnection);

            if (firstConnection) {
                startHiChainWithPincode(true);
            } else {
                // 已有 secretKey 说明认证过一次，直接 bind，省掉手表端的人工确认，
                // 也省掉 0x2C（bind 路径的 PSK 是 secretKey，不是 PIN）。
                startHiChainBind();
            }
            return;
        }
        if (negoReq.isHiChainLite()) {
            LOG.info("entering HiChain Lite, authType={}", negoReq.authType);
            startHiChainLite(linkParamsReq);
            return;
        }
        LOG.warn("unrecognized authType 0x{}", Integer.toHexString(negoReq.authType & 0xFFFFFFFF));
        notifyHandshakeResult(false, "unsupported authType 0x"
                + Integer.toHexString(negoReq.authType & 0xFFFFFFFF));
    }

    /**
     * HiChain 认证模式：先 0x2C 取 PIN，再进 PAKE。
     *
     * <p>两者的顺序不可交换 —— PIN 是 {@code paramsProvider} 上的字段，
     * HiChain 第 1 步就要用它派生 PSK。
     */
    private void startHiChainWithPincode(boolean firstConnection) {
        GetHiChainRequest hiChainReq = new GetHiChainRequest(this, firstConnection);
        hiChainReq.setFinalizeReq(new Request.RequestCallback() {
            @Override
            public void call() {
                // ★ 认证（0x01）完成后必须接一次 bind（0x02）换取最终会话密钥。
                //
                // 认证 step3 拿到的 authToken 只是中间产物（本次 bind 的 PSK）；
                // 加密通道真正的密钥在 bind 结束时由双方从 sessionKey 派生
                // （hichain_return_key）。少了这一步，手表下发的加密帧解不开、
                // 后续命令被挡在固定拒绝帧上 —— 与 bondState 的解读无关。
                LOG.info("HiChain auth finished; chaining into bind (0x02) for final key");
                startHiChainBind();
            }

            @Override
            public void handleException(Request.ResponseParseException e) {
                LOG.error("HiChain failed", e);
                notifyHandshakeResult(false, "HiChain: " + e.getMessage());
            }
        });

        GetPincodeRequest pinReq = new GetPincodeRequest(this);
        pinReq.nextRequest(hiChainReq);

        try {
            pinReq.doPerform();
        } catch (java.io.IOException e) {
            LOG.error("pincode request failed", e);
            notifyHandshakeResult(false, "pincode IO: " + e.getMessage());
        }
    }

    /**
     * HiChain Lite / Normal mode：{@code Auth(0x13) → BondParams(0x0F)}。
     *
     * <p>这条路径靠 HMAC 而非 PAKE，因此不需要 secretKey 之外的协商状态，
     * 但仍需一个已存在的 secretKey 作为 HMAC 种子 —— 没有就现场生成一个
     * 并持久化，与 GB 的 {@code createSecretKey()} 同义。
     */
    private void startHiChainLite(GetLinkParamsRequest linkParamsReq) {
        if (identity.getSecretKey() == null) {
            byte[] fresh = new byte[32];
            new java.security.SecureRandom().nextBytes(fresh);
            identity.setSecretKey(fresh);
            paramsProvider.setSecretKey(fresh);
            LOG.info("generated a fresh secretKey for the auth chain");
        }

        Request.RequestCallback afterBond = new Request.RequestCallback() {
            @Override
            public void call() {
                authChainDone.set(true);
                capabilityAndFinish();
            }

            @Override
            public void handleException(Request.ResponseParseException e) {
                LOG.error("auth chain failed", e);
                notifyHandshakeResult(false, "auth chain: " + e.getMessage());
            }
        };

        GetBondParamsRequest bondParamsReq = new GetBondParamsRequest(this);
        bondParamsReq.setFinalizeReq(afterBond);

        GetAuthRequest authReq = new GetAuthRequest(this, linkParamsReq);
        authReq.setFinalizeReq(afterBond);
        authReq.nextRequest(bondParamsReq);

        try {
            authReq.doPerform();
        } catch (java.io.IOException e) {
            LOG.error("auth request failed", e);
            notifyHandshakeResult(false, "auth IO: " + e.getMessage());
        }
    }

    /**
     * HiChain bind 路径（{@code operationCode=0x02}）：已有 secretKey，无需 PIN
     * 也无需用户确认。
     */
    private void startHiChainBind() {
        GetHiChainRequest bindReq = new GetHiChainRequest(this, false);
        bindReq.setFinalizeReq(new Request.RequestCallback() {
            @Override
            public void call() {
                authChainDone.set(true);
                capabilityAndFinish();
            }

            @Override
            public void handleException(Request.ResponseParseException e) {
                LOG.error("HiChain bind failed", e);
                notifyHandshakeResult(false, "HiChain bind: " + e.getMessage());
            }
        });
        try {
            bindReq.doPerform();
        } catch (java.io.IOException e) {
            LOG.error("HiChain bind request failed", e);
            notifyHandshakeResult(false, "HiChain bind IO: " + e.getMessage());
        }
    }

    /**
     * {@code deviceSupportType} 是否属于 HiChain 家族。
     *
     * <p>与 GB 的 {@code isHiChain()}：{@code 1/3/4} 是 HiChain，
     * {@code 2/8} 是 HiChain Lite，两者都算 HiChain 家族（都需要先做 0x33）。
     */
    private static boolean isHiChainFamily(byte supportType) {
        return supportType == 0x01 || supportType == 0x02
                || supportType == 0x03 || supportType == 0x04
                || supportType == 0x08;
    }

    /**
     * 握手收尾：拉能力位，标记已认证。
     *
     * <p>三条认证分支（完整 HiChain、HiChain Lite、Normal）都汇合到这里，
     * 保证「认证通过」与「能力位已读」始终是同一时刻成立 —— 数据同步层
     * 依赖 {@code supportsExpandCapability(n)}，读不到能力位就不该启动。
     */
    private void capabilityAndFinish() {
        // ★ 守卫一：认证链必须真的走完了。
        //
        // 不加这道闸会出现「收尾抢跑」：BondParams 和 0x37 在 HiChain 认证
        // 还没开始时就发出去，此刻 paramsProvider 里的 secretKey 还是旧的
        // （首次连接时甚至是空的），于是 0x37 构造失败或被手表拒绝。
        // 实测日志证明这条路径真实存在（0x0F 发出时HiChain step1 刚发、
        // step2 还没发）。
        if (!authChainDone.get()) {
            LOG.warn("capabilityAndFinish called before auth chain finished, ignoring");
            // 打印调用栈：这条路径意味着有别的地方在认证途中触发了收尾，
            // 光看日志只能看到「被忽略」，看不到是谁触发的。
            LOG.warn("caller stack:", new Exception("capabilityAndFinish trace"));
            return;
        }

        // 收尾只跑一次。三条认证分支都可能汇合过来，而BindParams 失败降级、
        // 握手回调重入等路径会让 expandCapabilityAndFinish() 被调用多次 ——
        // 实测见过 BondParams 与 0x37 各被发两遍。重复发命令会让手表侧状态
        // 混乱，也让日志难以判断「到底是哪一次被拒的」。
        if (!finishOnce.compareAndSet(false, true)) {
            LOG.warn("capabilityAndFinish called more than once, ignoring");
            return;
        }

        // 【撤回 0x0E Bond 步骤】真机证伪：手表对 0x0E 同样回固定拒绝帧
        // （5A000900010E 7F04000186A4），且当时的 "bond established" 是假阳性
        // —— GetBondRequest.processResponse 没校验任何字段就打了成功日志。
        //
        // 真正缺的一环是 <b>HiChain bind（operationCode=0x02，命令号仍为 0x28）</b>：
        // 构造函数只在 firstConnection 时赋 0x01，bind 路径的 operationCode 一直是
        // 非法的默认值 0x00 —— 这才是「GT3 对 bind 无响应」的真相。已在
        // startHiChainWithPincode 的回调里把认证链到 bind，bind 结束时由
        // hichain_return_key 派生最终会话密钥。
        //
        // 0x0E 的 Bond.Request 在 GB 里对应手表侧发起配对的场景（接收用），
        // 不属于手机侧握手序列。
        bondParamsAndCapability();
    }

    /** 绑定之后的收尾：协商加密计数器，再拉能力位。 */
    private void bondParamsAndCapability() {
        GetBondParamsRequest bondParamsReq = new GetBondParamsRequest(this);
        bondParamsReq.setFinalizeReq(new Request.RequestCallback() {
            @Override
            public void call() {
                LOG.info("BondParams negotiated, encryptionCounter={}",
                        paramsProvider.getEncryptionCounter());
                expandCapabilityAndFinish();
            }

            @Override
            public void handleException(Request.ResponseParseException e) {
                LOG.warn("BondParams rejected ({}); continuing on HiChain-managed counter",
                        e.getMessage());
                expandCapabilityAndFinish();
            }
        });

        try {
            bondParamsReq.doPerform();
        } catch (java.io.IOException e) {
            LOG.warn("bond params IO failed ({}); continuing", e.getMessage());
            expandCapabilityAndFinish();
        }
    }

    /**
     * 加密参数就绪后的收尾：拉能力位，标记已认证。
     *
     * <p>三条认证分支（完整 HiChain、HiChain Lite、Normal）都经
     * {@link #capabilityAndFinish} 汇合到这里，保证「认证通过」「加密参数已协商」
     * 与「能力位已读」始终是同一时刻成立 —— 数据同步层依赖
     * {@code supportsExpandCapability(n)}，读不到能力位就不该启动。
     */
    private void expandCapabilityAndFinish() {
        GetExpandCapabilityRequest capabilityReq = new GetExpandCapabilityRequest(this);
        capabilityReq.setFinalizeReq(new Request.RequestCallback() {
            @Override
            public void call() {
                authenticated = true;
                LOG.info("huawei handshake complete");
                // 能力位读到了才挂数据同步监听：0x37 的上报帧是加密的，
                // 没有能力位就不知道设备支不支持 config/event/data，挂上去只会
                // 收到一堆解不开或无法归类的帧。
                registerDataSyncListeners();
                registerWeatherListener();
                notifyHandshakeResult(true, null);
            }

            @Override
            public void handleException(Request.ResponseParseException e) {
                LOG.error("ExpandCapability failed", e);
                notifyHandshakeResult(false, "expand capability: " + e.getMessage());
            }
        });

        try {
            capabilityReq.doPerform();
        } catch (java.io.IOException e) {
            LOG.error("failed to request expand capability", e);
            notifyHandshakeResult(false, "expand capability IO: " + e.getMessage());
        }
    }

    /**
     * 挂上数据同步（0x37）的四个常驻监听器。
     *
     * <p>为什么是四个而不是一个：{@link Request#handleResponse(HuaweiPacket)} 按
     * service + command 精确匹配，一个实例只能认领一种命令。手表主动推的步数/心率/
     * 事件分别走 0x01~0x04，漏一个就等于放弃那一类上报。
     *
     * <p>幂等：重复调用不会重复挂（断链重连时 {@link #onTransportClosed()} 会清空，
     * 但上层若手动多调一次也不会造成同一个监听被挂两遍）。
     */
    public void registerDataSyncListeners() {
        synchronized (dataSyncListeners) {
            if (!dataSyncListeners.isEmpty()) {
                return;
            }
            dataSyncListeners.add(new HuaweiDataSyncListener(this, DataSync.ConfigCommand.id));
            dataSyncListeners.add(new HuaweiDataSyncListener(this, DataSync.EventCommand.id));
            dataSyncListeners.add(new HuaweiDataSyncListener(this, DataSync.DataCommand.id));
            dataSyncListeners.add(new HuaweiDataSyncListener(this, DataSync.DictDataCommand.id));
            for (Request listener : dataSyncListeners) {
                addInProgressRequest(listener);
            }
        }
        LOG.info("DataSync listeners registered: {}", dataSyncListeners.size());
    }

    /** 摘掉数据同步监听。链路断开时由 {@link #onTransportClosed()} 自动调用。 */
    public void unregisterDataSyncListeners() {
        synchronized (dataSyncListeners) {
            for (Request listener : dataSyncListeners) {
                responseManager.removeHandler(listener);
                inProgressRequests.remove(listener);
            }
            dataSyncListeners.clear();
        }
        unregisterWeatherListener();
    }

    /**
     * 挂上天气（0x0F/0x04）的常驻监听。
     *
     * <p>华为手表是天气同步的主动方：它发 {@code WeatherDeviceRequest} 要天气，
     * 手机必须回应。之前桥接没有任何常驻 handler 认领它，手表干等到超时后显示
     * 「天气暂不可用」。见 {@link HuaweiWeatherListener} 的类注释。
     *
     * <p>监听帧是加密的，因此和 {@link #registerDataSyncListeners()} 一样只能在
     * 认证通过（加密参数就绪）之后挂。
     */
    private void registerWeatherListener() {
        if (weatherListener != null || weatherHandler == null) return;
        HuaweiWeatherListener listener = new HuaweiWeatherListener(this, weatherHandler);
        weatherListener = listener;
        addInProgressRequest(listener);
        LOG.info("weather listener registered");
    }

    private void unregisterWeatherListener() {
        HuaweiWeatherListener listener = weatherListener;
        if (listener == null) return;
        weatherListener = null;
        responseManager.removeHandler(listener);
        inProgressRequests.remove(listener);
    }

    /**
     * 设置「手表要天气」时的处理者。可在认证前调用：认证完成后自动挂载监听。
     *
     * <p>回调运行在 socket 读线程上，实现方必须自行切到业务线程再做推送。
     */
    public void setWeatherRequestHandler(HuaweiWeatherListener.Handler handler) {
        this.weatherHandler = handler;
        if (authenticated) {
            registerWeatherListener();
        }
    }

    /** 握手结果回调，供上层（hook / 服务层）决定后续动作。 */
    public interface HandshakeListener {
        void onHandshakeResult(boolean success, String error);
    }

    private HandshakeListener handshakeListener;

    public void setHandshakeListener(HandshakeListener listener) {
        this.handshakeListener = listener;
    }

    private void notifyHandshakeResult(boolean success, String error) {
        HandshakeListener listener = handshakeListener;
        if (listener == null) {
            return;
        }
        try {
            listener.onHandshakeResult(success, error);
        } catch (RuntimeException e) {
            LOG.error("handshake listener threw", e);
        }
    }

    // ---------------------------------------------------------------- 传输层回调

    @Override
    public void onConnectionEstablished() {
        LOG.info("huawei transport established");
        // ★ 每条新连接都要重置收尾状态。
        //
        // 只在 onTransportClosed 里重置是不够的：那次回调未必跑得到
        // （进程被杀、异常抛出、连接直接超时等），残留的 true 会让下一条
        // 连接在认证还没开始时就通过 authChainDone 守卫。
        //
        // 实测症状极具迷惑性：日志打出「skipping BondParams, going straight
        // to capability」，看着像正常收尾，实际上此刻 HiChain 才刚发 step1、
        // step2 还没发，紧接着 0x37 就因keyLen=-1 失败。
        finishOnce.set(false);
        authChainDone.set(false);
        if (handshakeStarted.compareAndSet(false, true)) {
            startHandshake();
        }
    }

    @Override
    public void onSocketRead(byte[] data) {
        responseManager.handleData(data, HuaweiSocketCallback.MAIN_CHANNEL);
    }

    @Override
    public void onSocketRead(byte[] data, int channel) {
        responseManager.handleData(data, channel);
    }

    // ---------------------------------------------------------------- 在途请求

    public void addInProgressRequest(Request request) {
        inProgressRequests.add(request);
        responseManager.addHandler(request);
    }

    public void removeInProgressRequests(Request request) {
        inProgressRequests.remove(request);
        responseManager.removeHandler(request);
    }

    /**
     * 摘掉所有睡眠拉取请求（含卡死在响应等待里的），供服务层 watchdog 强制复位。
     *
     * <p>卡死的 0x0D 等待者必须摘：响应分发按 (service, command) 先到先认领，
     * 残留的等待者会抢走新一轮拉取链的响应，让重试也无声挂死。
     */
    public void clearSleepRequests() {
        synchronized (inProgressRequests) {
            java.util.Iterator<Request> it = inProgressRequests.iterator();
            while (it.hasNext()) {
                if (it.next() instanceof GetSleepDataRequest) {
                    it.remove();
                }
            }
        }
        responseManager.removeHandlers(GetSleepDataRequest.class);
    }

    /** 链路断开：释放所有等待者并清空重组状态。 */
    public void onTransportClosed() {
        LOG.info("huawei transport closed, releasing {} in-flight requests",
                inProgressRequests.size());
        // 必须先摘常驻监听再 reset：reset() 会清空 handlers 但不清理
        // dataSyncListeners 的记账，留着会让下次 register 误判为"已挂"从而漏挂。
        unregisterDataSyncListeners();
        responseManager.reset();
        inProgressRequests.clear();
        // 运行状态（电量等）一并清掉：它们描述的是「当前这条链路上的设备状态」，
        // 断链后继续沿用旧值，会让上层把上一次的电量当成本次的显示出来 ——
        // 而手表在这期间可能已经充过电、或根本没连上。
        capabilities.clearRuntimeState();
        authenticated = false;
        // 重连时要允许重新跑收尾，否则一次失败之后再也走不到能力位协商。
        finishOnce.set(false);
        authChainDone.set(false);
        handshakeStarted.set(false);
    }

    /** 主 socket 上打开 0x3C 协商出的双通道 socket。 */
    public void openDualChannel(int channel) {
        if (isBLE() || channel <= 0 || transport == null) {
            return;
        }
        transport.openAuxChannel(channel);
    }

    public boolean isConnected() {
        return transport != null && transport.isConnected();
    }

    /**
     * 华为协议的默认参数。
     *
     * <p>{@link HuaweiPacket.ParamsProvider} 在 GB 里是一个携带握手协商状态的普通类
     * （authVersion / secretKey / encryptMethod / encryptionCounter 等），并非接口。
     * 这里直接用它的默认值：GT3 走 RFCOMM，分片尺寸与加密参数在握手
     * （{@code DeviceConfig} → {@code 0x35} 认证）阶段由
     * {@link #getParamsProvider()} 上的 setter 写入。
     *
     * <p>MTU 用 RFCOMM 的实际上限。BR/EDR 的 L2CAP 可以承载远超 BLE MTU 的包，
     * 但 {@code HuaweiPacket} 仍按此值计算分片边界，取 GB 的 247 与其行为一致。
     */
    private final class DefaultParamsProvider extends HuaweiPacket.ParamsProvider {
        DefaultParamsProvider() {
            setMtu(247);
            // 写完一帧后给手表一个响应窗口，避免连续包被丢弃。
            setInterval((byte) 20);
        }
    }

    /** 把传输层的连接状态透传给宿主。 */
    private final class ConnectionStateRelay implements HuaweiBrTransport.ConnectionListener {
        private final HuaweiBrTransport.ConnectionListener delegate;

        ConnectionStateRelay(HuaweiBrTransport.ConnectionListener delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onConnected() {
            if (delegate != null) {
                delegate.onConnected();
            }
        }

        @Override
        public void onDisconnected(boolean deliberate) {
            onTransportClosed();
            if (delegate != null) {
                delegate.onDisconnected(deliberate);
            }
        }
    }
}