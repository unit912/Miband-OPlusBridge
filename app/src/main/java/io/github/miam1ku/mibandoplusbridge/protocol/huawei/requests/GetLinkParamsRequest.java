package io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests;

import io.github.miam1ku.mibandoplusbridge.data.HuaweiLogger;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.DeviceConfig;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.service.HuaweiSupportProvider;

import java.util.List;

/**
 * 握手第一步：询问链路参数。
 *
 * <p>这是整条认证链的入口。响应里带回一组后续一切都依赖的参数：
 * 协议版本、MTU、分片大小、认证版本/算法/模式，以及 18 字节的
 * {@code serverNonce} —— Auth 请求的 HMAC 就是拿它和客户端 nonce 拼起来算的。
 *
 * <p>本请求是握手序列的根，由 {@code doPerform()} 自行排队（GB 里对应
 * {@code isSelfQueue = false}，那种写法把排队权交给调用方以便先配置 builder；
 * 本项目的排队时机已由 {@link Request} 自己掌握）。
 */
public class GetLinkParamsRequest extends Request {

    private static final HuaweiLogger LOG = HuaweiLogger.of(GetLinkParamsRequest.class);

    /** 手表返回的服务端随机数，长度固定 18 字节。 */
    public byte[] serverNonce;

    /** 绑定状态：0x00 未绑定，0x01 已绑定。决定后面走"首次认证"还是"快速重连"。 */
    public byte bondState;

    public GetLinkParamsRequest(HuaweiSupportProvider support) {
        super(support);
        this.serviceId = DeviceConfig.id;
        this.commandId = DeviceConfig.LinkParams.id;
        this.serverNonce = new byte[18];
    }

    @Override
    protected List<byte[]> createRequest() throws RequestCreationException {
        try {
            return new DeviceConfig.LinkParams.Request(
                    paramsProvider, supportProvider.getDeviceType()).serialize();
        } catch (HuaweiPacket.CryptoException e) {
            throw new RequestCreationException(e);
        }
    }

    @Override
    protected void processResponse() throws ResponseParseException {
        LOG.debug("handle LinkParams");

        if (!(receivedPacket instanceof DeviceConfig.LinkParams.Response)) {
            throw new ResponseTypeMismatchException(receivedPacket, DeviceConfig.LinkParams.Response.class);
        }
        DeviceConfig.LinkParams.Response response = (DeviceConfig.LinkParams.Response) receivedPacket;

        supportProvider.setProtocolVersion(response.protocolVersion);
        paramsProvider.setDeviceSupportType(response.deviceSupportType);
        paramsProvider.setSliceSize(response.sliceSize);
        paramsProvider.setMtu(response.mtu);
        paramsProvider.setAuthVersion(response.authVersion);
        paramsProvider.setAuthAlgo(response.authAlgo);
        paramsProvider.setEncryptMethod(response.encryptMethod);

        this.serverNonce = response.serverNonce;
        this.bondState = response.bondState;

        LOG.debug("LinkParams: authVersion={}, authAlgo={}, deviceSupportType={}, bondState={}, mtu={}",
                response.authVersion, response.authAlgo, response.deviceSupportType,
                response.bondState, response.mtu);
    }

    /** 首次配对（手表端还没绑定过本机）时走慢速 PIN 认证流程。 */
    public boolean isFirstBinding() {
        return bondState != 0x01;
    }
}