// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests;

import java.util.List;

import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.FitnessData;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.service.HuaweiSupportProvider;

/**
 * 0x07 0x16 SetTruSleep：向手表开启/关闭科学睡眠（Gadgetbridge 在下载睡眠文件前必发）。
 *
 * <p>GT3 的 TruSleep 文件通道（0x2C sleep_state.bin）只在手表侧 TruSleep 已启用时才
 * 有数据；未启用时 2C init 恒定回报 fileSize=0（实测）。本请求与 {@link GetSettingRelatedRequest}
 * 探针一起，在睡眠拉取前链式发送，确保 2C 能取到夜眠文件。
 */
public class SetTruSleepRequest extends Request {
    private final boolean truSleepSwitch;

    public SetTruSleepRequest(HuaweiSupportProvider support, boolean truSleepSwitch) {
        super(support, 15000);
        this.serviceId = FitnessData.id;
        this.commandId = FitnessData.TruSleep.id;
        this.addToResponse = true;
        this.truSleepSwitch = truSleepSwitch;
    }

    @Override
    protected List<byte[]> createRequest() throws RequestCreationException {
        try {
            return new FitnessData.TruSleep.Request(paramsProvider, truSleepSwitch).serialize();
        } catch (HuaweiPacket.CryptoException e) {
            throw new RequestCreationException(e);
        }
    }

    @Override
    protected void processResponse() throws ResponseParseException {
        // 手表对该命令的回包无需解析（GB 同款）。
    }
}
