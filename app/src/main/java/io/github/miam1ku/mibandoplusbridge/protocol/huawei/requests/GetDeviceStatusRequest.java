package io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests;

import io.github.miam1ku.mibandoplusbridge.data.HuaweiLogger;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.DeviceConfig;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.service.HuaweiSupportProvider;

import java.util.List;

/**
 * 握手第二步：确认设备当前状态。
 *
 * <p>返回值决定后续走哪条认证分支：{@code -1 / 0x00 / 0x01} 表示状态正常，
 * 继续 HiChain；其他值说明手表还停在别的界面（如正在显示配对码），此时应
 * 停在原地重试而不是强行往下走认证。
 */
public class GetDeviceStatusRequest extends Request {

    private static final HuaweiLogger LOG = HuaweiLogger.of(GetDeviceStatusRequest.class);

    /** 手表返回的状态码。 */
    public byte status;

    private final boolean askStatus;

    public GetDeviceStatusRequest(HuaweiSupportProvider support, boolean askStatus) {
        super(support);
        this.serviceId = DeviceConfig.id;
        this.commandId = DeviceConfig.DeviceStatus.id;
        this.askStatus = askStatus;
    }

    @Override
    protected List<byte[]> createRequest() throws RequestCreationException {
        try {
            return new DeviceConfig.DeviceStatus.Request(paramsProvider, askStatus).serialize();
        } catch (HuaweiPacket.CryptoException e) {
            throw new RequestCreationException(e);
        }
    }

    @Override
    protected void processResponse() throws ResponseParseException {
        LOG.debug("handle Device Status");

        if (!(receivedPacket instanceof DeviceConfig.DeviceStatus.Response)) {
            throw new ResponseTypeMismatchException(receivedPacket, DeviceConfig.DeviceStatus.Response.class);
        }
        this.status = ((DeviceConfig.DeviceStatus.Response) receivedPacket).status;
        LOG.debug("Device status: {}", status);
    }

    /** 状态是否允许进入认证流程。{@code -1} 是响应字段的默认值，表示手表未回报状态。 */
    public boolean isReadyForAuth() {
        return status == -1 || status == 0x00 || status == 0x01;
    }
}