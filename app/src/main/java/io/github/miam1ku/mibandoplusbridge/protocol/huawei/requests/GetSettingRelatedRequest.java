// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests;

import java.util.List;

import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.DeviceConfig;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.service.HuaweiSupportProvider;

/**
 * 0x01 0x05 SettingRelated 能力探针（Gadgetbridge 同款）。
 *
 * <p>GT3 是否走 0x2C 新同步下载睡眠文件，由手表在该响应的 tag 0x02 位图里声明
 * （bit 2 = truSleepNewSync）。桥接此前从未发送过该请求，导致 0x2C 尝试属于盲打；
 * 该探针在睡眠拉取前发一次，把结果写进 session 日志，作为通道路由的实证依据。
 */
public class GetSettingRelatedRequest extends Request {
    public boolean truSleepNewSync;
    public boolean rriNewSync;
    public boolean gpsNewSync;
    public boolean hasFileSupportTag;

    public GetSettingRelatedRequest(HuaweiSupportProvider support) {
        super(support, 15000);
        this.serviceId = DeviceConfig.id;
        this.commandId = DeviceConfig.SettingRelated.id;
        this.addToResponse = true;
    }

    @Override
    protected List<byte[]> createRequest() throws RequestCreationException {
        try {
            return new DeviceConfig.SettingRelated.Request(paramsProvider).serialize();
        } catch (HuaweiPacket.CryptoException e) {
            throw new RequestCreationException(e);
        }
    }

    @Override
    protected void processResponse() throws ResponseParseException {
        if (!(receivedPacket instanceof DeviceConfig.SettingRelated.Response)) {
            throw new ResponseTypeMismatchException(receivedPacket,
                    DeviceConfig.SettingRelated.Response.class);
        }
        DeviceConfig.SettingRelated.Response r =
                (DeviceConfig.SettingRelated.Response) receivedPacket;
        this.truSleepNewSync = r.truSleepNewSync;
        this.rriNewSync = r.rriNewSync;
        this.gpsNewSync = r.gpsNewSync;
        this.hasFileSupportTag = true;
    }
}
