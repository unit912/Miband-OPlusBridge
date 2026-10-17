// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests;

import io.github.miam1ku.mibandoplusbridge.data.HuaweiLogger;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.DeviceConfig;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.service.HuaweiSupportProvider;

import java.util.List;

/**
 * 握手第三步：安全与认证模式协商（命令 0x33）。
 *
 * <p>这一步在 GB 原版里位于 DeviceStatus 与 HiChain 之间，是<b>不能跳过</b>的：
 * 它同时承担两件事。
 *
 * <ol>
 *   <li><b>请求侧</b>把 {@code authMode} 与本机 authId 报给手表。{@code authMode}
 *       必须在这里按 {@code deviceSupportType} 设好 —— 它决定 PIN 用哪种加密方式
 *       解密（{@code DeviceConfig.PinCode.Response.parseTlv} 把 authMode 传给
 *       {@code HuaweiCrypto}），也决定 {@code GetBondParamsRequest} 是否要在
 *       {@code status==1} 时中止。漏掉这一步，{@code authMode} 会停在 0，
 *       PIN 解密结果就是错的，HiChain 第 1 步的 PSK 随之全错。</li>
 *   <li><b>响应侧</b>带回 {@code authType}，用于分辨 HiChain（{@code 0x0186A0}
 *       或 HiChain3）与 HiChain Lite（{@code 0x01}/{@code 0x02}）。</li>
 * </ol>
 *
 * <p>{@code phoneModel} 只在 {@code authMode == 0x04}（HiChain3）时写进 tag 0x07，
 * 走 {@code android.os.Build.MODEL}。
 *
 * <p>GT3 走的是荣耀以外的经典路径，因此<b>不</b>实现 PAKE 分支
 * （GB 里那套走 {@code GetHiChainPakeRequest}，仅 Honor Watch 5 一类）。
 */
public class GetSecurityNegotiationRequest extends Request {

    private static final HuaweiLogger LOG =
            HuaweiLogger.of(GetSecurityNegotiationRequest.class);

    /**
     * 响应带回的认证类型。{@code 0x0186A0} 与 HiChain3（{@code (authType ^ 1|2) == 4}）
     * 走 {@link GetHiChainRequest}；{@code 0x01}/{@code 0x02} 走 HiChain Lite。
     */
    public int authType;

    /**
     * tag 0x02 在荣耀设备上是 pairType，在经典华为设备上是协商后的 authType。
     * 字段保留但对 GT3 无意义 —— 记录下来只为排障时能看到手表实际回了什么。
     */
    public int honorPairType = -1;

    /** 响应 tag 0x03。仅 PAKE 路径使用，此处只记录。 */
    public byte[] responseNonce;

    public GetSecurityNegotiationRequest(HuaweiSupportProvider support) {
        super(support);
        this.serviceId = DeviceConfig.id;
        this.commandId = DeviceConfig.SecurityNegotiation.id;
    }

    @Override
    protected List<byte[]> createRequest() throws RequestCreationException {
        try {
            return new DeviceConfig.SecurityNegotiation.Request(
                    paramsProvider,
                    paramsProvider.getAuthMode(),
                    supportProvider.getAndroidId(),
                    android.os.Build.MODEL).serialize();
        } catch (HuaweiPacket.CryptoException e) {
            throw new RequestCreationException(e);
        }
    }

    @Override
    protected void processResponse() throws ResponseParseException {
        if (!(receivedPacket instanceof DeviceConfig.SecurityNegotiation.Response)) {
            throw new ResponseTypeMismatchException(receivedPacket,
                    DeviceConfig.SecurityNegotiation.Response.class);
        }
        DeviceConfig.SecurityNegotiation.Response response =
                (DeviceConfig.SecurityNegotiation.Response) receivedPacket;

        this.authType = response.authType;
        this.honorPairType = response.honorPairType;
        this.responseNonce = response.responseNonce;

        LOG.debug("SecurityNegotiation: authMode={} authType=0x{} pairType={} nonce={}",
                paramsProvider.getAuthMode(), Integer.toHexString(authType & 0xFFFFFFFF),
                honorPairType,
                responseNonce == null ? "none" : String.valueOf(responseNonce.length));
    }

    /** 是否为 HiChain3（{@code authType} 为 4 家族）。 */
    public boolean isHiChain3() {
        return (authType ^ 0x01) == 0x04 || (authType ^ 0x02) == 0x04;
    }

    /** 是否走完整 HiChain（{@code 0x0186A0} 或 HiChain3）。 */
    public boolean isFullHiChain() {
        return authType == 0x0186A0 || isHiChain3();
    }

    /** 是否走 HiChain Lite —— 认证靠 Auth(0x13)/BondParams/Bond 而非 PAKE。 */
    public boolean isHiChainLite() {
        return authType == 0x01 || authType == 0x02;
    }
}
