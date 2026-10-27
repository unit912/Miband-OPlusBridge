// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests;

import io.github.miam1ku.mibandoplusbridge.data.HuaweiDeviceRegistry;
import io.github.miam1ku.mibandoplusbridge.data.HuaweiLogger;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.DeviceConfig;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.service.HuaweiSupportProvider;

import java.util.List;

/**
 * 读取产品信息（DeviceConfig / ProductInfo，命令 0x07）：软件版本即固件、硬件版本、
 * 产品型号、设备名。结果写入 {@link HuaweiDeviceRegistry}，再由 {@code publish()}
 * 投影到 {@code band-state}，设备信息卡片即可显示固件 / 硬件，不再停在「未读取」。
 *
 * <p>华为链路不经过小米那套 {@code BandStateRepository.recordVerifiedDevice}
 * （它要求 NATIVE 所有权且比对身份五件套，对华为必抛），所以固件 / 硬件改由这里
 * 独立读取并落库。请求与响应都走加密通道（响应 {@code isEncrypted=false}，但请求
 * 走认证后链路），框架已按 serviceId/commandId 把响应实例化为
 * {@link DeviceConfig.ProductInfo.Response}。
 */
public class GetProductInfoRequest extends Request {

    private static final HuaweiLogger LOG = HuaweiLogger.of(GetProductInfoRequest.class);

    public GetProductInfoRequest(HuaweiSupportProvider support) {
        super(support);
        this.serviceId = DeviceConfig.id;
        this.commandId = DeviceConfig.ProductInfo.id;
    }

    @Override
    protected List<byte[]> createRequest() throws RequestCreationException {
        try {
            return new DeviceConfig.ProductInfo.Request(
                    paramsProvider, supportProvider.getDeviceType()).serialize();
        } catch (HuaweiPacket.CryptoException e) {
            throw new RequestCreationException(e);
        }
    }

    @Override
    protected void processResponse() throws ResponseParseException {
        LOG.debug("handle ProductInfo");
        if (!(receivedPacket instanceof DeviceConfig.ProductInfo.Response)) {
            throw new ResponseTypeMismatchException(
                    receivedPacket, DeviceConfig.ProductInfo.Response.class);
        }
        DeviceConfig.ProductInfo.Response response =
                (DeviceConfig.ProductInfo.Response) receivedPacket;
        String firmware = response.softwareVersion;
        String hardware = response.hardwareVersion;
        LOG.debug("ProductInfo firmware={} hardware={}", firmware, hardware);
        if (HuaweiDeviceRegistry.noteFirmwareHardware(
                supportProvider.getContext(), firmware, hardware)) {
            HuaweiDeviceRegistry.publish(supportProvider.getContext());
        }
    }
}
