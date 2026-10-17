// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests;

import io.github.miam1ku.mibandoplusbridge.data.HuaweiLogger;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.DeviceConfig;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.service.HuaweiSupportProvider;

import java.util.List;

/**
 * 读取电量（DeviceConfig / BatteryLevel，命令 0x08）。
 *
 * <p>这是握手成功后第一个该跑的功能命令。选它当验证点有两个原因：请求与响应
 * 都极简（请求只有一个空 tag，响应是单字节），出问题时能立刻分清是协议错了还是
 * 链路错了；而且它同时验证了<b>认证后加密通道</b>是否真的可用 —— 握手里的报文
 * 大多还是明文，0x08 才是第一次带着会话密钥收发。
 *
 * <p><b>与 GB 原版的差异</b>：GB 拿到电量后要做三件 bridge 现在不需要的事 ——
 * 发 {@code GBDeviceEventBatteryInfo} 事件、写 {@code GBDevice}、按用户偏好启动
 * 轮询。这三件都依赖 GB 的应用层，故去掉，改为写入
 * {@link io.github.miam1ku.mibandoplusbridge.protocol.huawei.service.HuaweiDeviceCapabilities}，
 * 由上层决定何时读。轮询也不在此启动：轮询周期该由 bridge 的服务层按需控制，
 * 藏在单个请求类里会让「谁在定时问手表」变得不可追。
 *
 * <p><b>多电池</b>：GT3 单电池，但部分华为机型（如手环）走 {@code multi_level} 分支。
 * 这里保留读取而不是只取 {@code level} —— 真机若报多电池，状态容器里的
 * {@code batteryIndex 0} 仍然有效，不至于整条命令失败。
 */
public class GetBatteryLevelRequest extends Request {

    private static final HuaweiLogger LOG = HuaweiLogger.of(GetBatteryLevelRequest.class);

    public GetBatteryLevelRequest(HuaweiSupportProvider support) {
        super(support);
        this.serviceId = DeviceConfig.id;
        this.commandId = DeviceConfig.BatteryLevel.id;
    }

    @Override
    protected List<byte[]> createRequest() throws RequestCreationException {
        try {
            return new DeviceConfig.BatteryLevel.Request(paramsProvider).serialize();
        } catch (HuaweiPacket.CryptoException e) {
            throw new RequestCreationException(e);
        }
    }

    @Override
    protected void processResponse() throws ResponseParseException {
        LOG.debug("handle BatteryLevel");

        if (!(receivedPacket instanceof DeviceConfig.BatteryLevel.Response)) {
            throw new ResponseTypeMismatchException(receivedPacket,
                    DeviceConfig.BatteryLevel.Response.class);
        }
        DeviceConfig.BatteryLevel.Response response =
                (DeviceConfig.BatteryLevel.Response) receivedPacket;

        if (response.multi_level != null && response.multi_level.length > 0) {
            // 多电池：取第 0 个作主电量。状态字段按 GB 语义是 BATTERY_STATUS=1 充电中。
            byte[] status = response.status;
            boolean charging = status != null && status.length > 0
                    && status[0] == BATTERY_STATUS_CHARGING;
            int level = response.multi_level[0] & 0xFF;
            LOG.debug("BatteryLevel multi[0] = {} charging={}", level, charging);
            supportProvider.getDeviceState().setBattery(level, charging);
        } else {
            // 单电池。level 是无符号字节，& 0xFF 必须 —— 直接赋值给 byte 再取会
            // 把 87 变成 -169，看着像乱码。
            int level = response.level & 0xFF;
            LOG.debug("BatteryLevel = {}%", level);
            supportProvider.getDeviceState().setBattery(level, false);
        }
    }

    /** 协议里 status 字节的取值：1 表示充电中。 */
    private static final byte BATTERY_STATUS_CHARGING = 1;
}
