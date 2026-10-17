// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests;

import io.github.miam1ku.mibandoplusbridge.data.HuaweiLogger;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.DeviceConfig;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.service.HuaweiSupportProvider;

import java.util.List;

/**
 * 握手第四步：向手表索要 PIN（命令 0x2C）。
 *
 * <p>这条请求存在的唯一原因是给 {@code paramsProvider.pinCode} 赋值。
 * HiChain 认证模式下 PSK 由 PIN 派生（{@code GetHiChainRequest.handleStepOne}）：
 * {@code key = digest(hex(PIN))}，{@code psk = HMAC(key, seed)}。没有这一步，
 * {@code getPinCode()} 返回 {@code null}，{@code StringUtils.bytesToHex(null)}
 * 直接 NPE —— 表现为握手在 HiChain 第 1 步崩掉，日志里只有
 * {@code HiChain request failed to start}。
 *
 * <p>响应体是加密的，解密依赖 {@code authVersion} / {@code authAlgo} /
 * {@code deviceSupportType} / {@code authMode} 四个参数全部就位 ——
 * 所以本请求<b>必须</b>排在 {@link GetLinkParamsRequest} 与
 * {@link GetSecurityNegotiationRequest} 之后。GB 原版正是这样排的。
 *
 * <p>重连（bind）路径不需要它：{@code operationCode=0x02} 时 PSK 直接用已存的
 * secretKey。
 */
public class GetPincodeRequest extends Request {

    private static final HuaweiLogger LOG = HuaweiLogger.of(GetPincodeRequest.class);

    public GetPincodeRequest(HuaweiSupportProvider support) {
        super(support);
        this.serviceId = DeviceConfig.id;
        this.commandId = DeviceConfig.PinCode.id;
    }

    @Override
    protected List<byte[]> createRequest() throws RequestCreationException {
        try {
            return new DeviceConfig.PinCode.Request(paramsProvider).serialize();
        } catch (HuaweiPacket.CryptoException e) {
            throw new RequestCreationException(e);
        }
    }

    @Override
    protected void processResponse() throws ResponseParseException {
        if (!(receivedPacket instanceof DeviceConfig.PinCode.Response)) {
            throw new ResponseTypeMismatchException(receivedPacket,
                    DeviceConfig.PinCode.Response.class);
        }
        byte[] pin = ((DeviceConfig.PinCode.Response) receivedPacket).pinCode;
        if (pin == null || pin.length == 0) {
            throw new ResponseParseException("PinCode response carried no PIN");
        }
        paramsProvider.setPinCode(pin);
        LOG.debug("PinCode received, length = {}", pin.length);
    }
}
