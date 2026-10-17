package io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests;

import io.github.miam1ku.mibandoplusbridge.data.HuaweiLogger;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.DeviceConfig;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.service.HuaweiSupportProvider;

import java.util.List;

/**
 * 握手第三步：绑定参数协商。
 *
 * <p>把本机的「客户端序列号」和一个伪 MAC 地址报给手表，手表回一个加密计数器
 * 并把自己的绑定状态一并说清楚。注意这里的 MAC 是<b>随机生成后持久化</b>的，
 * 不是手表真实 MAC —— 这是华为协议的身份凭据之一，换机会导致握手失败。
 *
 * <p>若手表回报 {@code status == 1} 且处于 HiChain 之外的认证模式，说明
 * 它已经绑定了别的手机，本链在此终止（见 {@link #processResponse}）。
 */
public class GetBondParamsRequest extends Request {

    private static final HuaweiLogger LOG = HuaweiLogger.of(GetBondParamsRequest.class);

    public GetBondParamsRequest(HuaweiSupportProvider support) {
        super(support);
        this.serviceId = DeviceConfig.id;
        this.commandId = DeviceConfig.BondParams.id;
    }

    @Override
    protected List<byte[]> createRequest() throws RequestCreationException {
        try {
            return new DeviceConfig.BondParams.Request(
                    paramsProvider,
                    supportProvider.getClientSerial(),
                    supportProvider.getMacAddress()).serialize();
        } catch (HuaweiPacket.CryptoException e) {
            throw new RequestCreationException(e);
        }
    }

    @Override
    protected void processResponse() throws ResponseParseException {
        LOG.debug("handle BondParams");

        if (!(receivedPacket instanceof DeviceConfig.BondParams.Response)) {
            throw new ResponseTypeMismatchException(receivedPacket, DeviceConfig.BondParams.Response.class);
        }
        DeviceConfig.BondParams.Response response = (DeviceConfig.BondParams.Response) receivedPacket;

        paramsProvider.setEncryptionCounter(response.encryptionCounter);

        // HiChain 模式由自己的绑定状态判断；其他模式下若手表说"已绑定"，
        // 意味着它绑的是另一台手机，继续往下走只会得到莫名其妙的 HMAC 不匹配。
        if (paramsProvider.getAuthMode() != 0x02 && response.status == 1) {
            LOG.warn("BondParams reports device already bound (status=1); stopping chain");
            stopChain(this);
        }
    }
}