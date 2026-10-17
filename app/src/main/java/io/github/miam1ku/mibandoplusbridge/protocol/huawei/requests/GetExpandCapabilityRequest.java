package io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests;

import io.github.miam1ku.mibandoplusbridge.data.HuaweiLogger;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.GB;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.DeviceConfig;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.service.HuaweiSupportProvider;

import java.util.List;

/**
 * 握手收尾：读取设备扩展能力位。
 *
 * <p>返回值是一张位图，描述手表支持哪些可选功能 —— 数据同步要不要分片、
 * 是否支持双通道 socket（GT3 支持）、是否支持主动上报心率等。全流程的
 * {@code supportsExpandCapability(n)} 判断都读它，所以必须在认证后立刻拉一次。
 */
public class GetExpandCapabilityRequest extends Request {

    private static final HuaweiLogger LOG = HuaweiLogger.of(GetExpandCapabilityRequest.class);

    public GetExpandCapabilityRequest(HuaweiSupportProvider support) {
        super(support);
        this.serviceId = DeviceConfig.id;
        this.commandId = DeviceConfig.ExpandCapability.id;
    }

    @Override
    protected List<byte[]> createRequest() throws RequestCreationException {
        try {
            return new DeviceConfig.ExpandCapability.Request(paramsProvider).serialize();
        } catch (HuaweiPacket.CryptoException e) {
            throw new RequestCreationException(e);
        }
    }

    @Override
    protected void processResponse() throws ResponseParseException {
        LOG.debug("handle ExpandCapability");

        if (!(receivedPacket instanceof DeviceConfig.ExpandCapability.Response)) {
            throw new ResponseTypeMismatchException(receivedPacket,
                    DeviceConfig.ExpandCapability.Response.class);
        }
        byte[] bitmap = ((DeviceConfig.ExpandCapability.Response) receivedPacket).expandCapabilities;
        if (bitmap == null) {
            // 走到这里说明 parseTlv() 里 tlv.contains(0x01) 为 false。把整个 TLV
            // 打出来：0x37 是握手最后一步，而它的请求本身是「空 tag 问能力位」，
            // 不同固件回的 tag 布局未必一致，不看原始 tag 就只能猜。
            LOG.error("ExpandCapability returned no bitmap; raw payload = {}",
                    receivedPacket.getPayloadForDiagnostics() == null
                            ? "(null payload)"
                            : GB.hexdump(receivedPacket.getPayloadForDiagnostics(), 0, -1));
            throw new ResponseParseException("ExpandCapability returned no bitmap");
        }
        supportProvider.getDeviceState().setExpandCapabilities(bitmap);
        LOG.debug("ExpandCapability bitmap length = {}", bitmap.length);
    }
}