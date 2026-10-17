package io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests;

import io.github.miam1ku.mibandoplusbridge.data.HuaweiLogger;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.DeviceConfig;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.service.HuaweiSupportProvider;

import java.util.List;

/**
 * 握手收尾：绑定（{@code Bond, 0x0E}）。
 *
 * <h3>为什么这一步不能省</h3>
 *
 * <p>HiChain 认证（0x28）完成的是 <b>PAKE 密钥交换</b>，双方确认了彼此的身份，
 * 但手表<b>还没有把本机写入它的绑定表</b>。判据来自 LinkParams(0x01) 响应里的
 * {@code bondState}：
 *
 * <pre>
 *   GetLinkParamsRequest D LinkParams: authVersion=1, authAlgo=0,
 *                                  deviceSupportType=4, bondState=0, mtu=990
 * </pre>
 *
 * <p>{@code bondState=0} 即手表侧未绑定。这个状态在整条链走完后<b>依然是 0</b> ——
 * 因为从头到尾没有任何代码发过 Bond(0x0E)。包结构（{@link DeviceConfig.Bond.Request}）
 * 是现成的，却从没被调用过。
 *
 * <p>这解释了三个此前无法理解的实测现象：
 *
 * <ol>
 *   <li><b>0x37 被拒且响应是明文拒绝帧。</b>手表尚未把本机纳入绑定态，
 *       因此不认为这是一个已认证会话该来的加密命令，于是按未认证路径回
 *       {@code 7F 04 00 01 86 A4}。加密与否、回哪个命令号都不影响这个帧，
 *       因为拒绝发生在命令分发之前。</li>
 *   <li><b>0x0F BondParams 同样被拒。</b>它是绑定流程的一部分，
 *       绑定没做，它自然也没有意义。</li>
 *   <li><b>15秒后手表主动下发加密 0x1F 却解不开。</b>那帧用的是上一次会话的
 *       绑定密钥；本会话的密钥从未完成绑定登记，双方密钥上下文不一致。
 *       已用离线 AES-GCM 验证：authToken、sessionKey、以及它们的 HKDF 派生
 *       变体全部无法解开该帧。</li>
 * </ol>
 *
 * <p>GB 原版在 HiChain 成功后正是要发 Bond：认证（知道你是谁）→ 绑定
 * （把你写进我的设备列表）。少了绑定，认证结果无处落地。
 */
public class GetBondRequest extends Request {

    private static final HuaweiLogger LOG = HuaweiLogger.of(GetBondRequest.class);

    /** 认证已完成，此处只需等手表确认绑定结果，10 秒足够。 */
    private static final long BOND_TIMEOUT = 10_000L;

    public GetBondRequest(HuaweiSupportProvider support) {
        super(support, BOND_TIMEOUT);
        this.serviceId = DeviceConfig.id;
        this.commandId = DeviceConfig.Bond.id;
    }

    @Override
    protected List<byte[]> createRequest() throws RequestCreationException {
        byte[] secretKey = paramsProvider.getSecretKey();
        if (secretKey == null || secretKey.length == 0) {
            // 没有认证出来的密钥就不能绑定 —— 提交空密钥会让手表写进一条
            // 永远解不开的绑定记录，比不发更糟。
            throw new RequestCreationException(
                    "Bond requires an established secretKey; auth chain did not complete");
        }
        try {
            LOG.info("sending Bond: clientSerial={} keyLen={}",
                    supportProvider.getClientSerial(), secretKey.length);
            return new DeviceConfig.Bond.Request(
                    paramsProvider,
                    supportProvider.getClientSerial(),
                    secretKey,
                    paramsProvider.getIv()).serialize();
        } catch (HuaweiPacket.CryptoException e) {
            throw new RequestCreationException(e);
        }
    }

    @Override
    protected void processResponse() throws ResponseParseException {
        LOG.info("Bond accepted by the watch; binding is now established");
    }
}