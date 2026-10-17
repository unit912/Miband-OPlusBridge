package io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests;

import io.github.miam1ku.mibandoplusbridge.data.HuaweiLogger;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.CryptoUtils;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.GB;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiCrypto;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.StringUtils;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.DeviceConfig;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.service.HuaweiSupportProvider;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import java.util.List;

/**
 * PIN 模式的认证请求（服务 0x01 / 命令 0x13）。
 *
 * <p>用「服务端 nonce ‖ 客户端 nonce」拼成的 32 字节做 HMAC 输入，密钥来自
 * {@link HuaweiCrypto#digestChallenge} 的内部派生，最后截前 32 字节作为 challenge
 * 发给手表。手表回一个 response，本地重算一遍比对 —— 两边一致才说明双方持有
 * 同一份密钥。
 *
 * <p>这条路径只在 {@code authMode == 0x02}（PIN）或老设备上使用。GT3 走的是
 * {@link GetHiChainRequest}，不经过这里。
 */
public class GetAuthRequest extends Request {

    private static final HuaweiLogger LOG = HuaweiLogger.of(GetAuthRequest.class);

    private final byte[] clientNonce;
    private final short authVersion;
    private final byte authAlgo;
    /** 服务端 nonce ‖ 客户端 nonce，作为 HMAC 的消息体。 */
    private final byte[] doubleNonce;
    /** HMAC 密钥。由 PIN 或已有 secretKey 派生，为 {@code null} 时走 HiChainLite 简化算法。 */
    private byte[] key;
    private final HuaweiCrypto huaweiCrypto;

    public GetAuthRequest(HuaweiSupportProvider support, GetLinkParamsRequest linkParamsReq) {
        super(support);
        this.serviceId = DeviceConfig.id;
        this.commandId = DeviceConfig.Auth.id;

        this.clientNonce = HuaweiCrypto.generateNonce();
        this.doubleNonce = ByteBuffer.allocate(32)
                .put(linkParamsReq.serverNonce)
                .put(clientNonce)
                .array();

        this.authVersion = (short) (paramsProvider.getAuthVersion() & 0xFF);
        this.authAlgo = paramsProvider.getAuthAlgo();
        this.huaweiCrypto = new HuaweiCrypto(
                authVersion, authAlgo, paramsProvider.getDeviceSupportType(),
                paramsProvider.getAuthMode());
    }

    @Override
    protected List<byte[]> createRequest() throws RequestCreationException {
        try {
            if (paramsProvider.getAuthMode() == 0x02) {
                key = paramsProvider.getPinCode();
                if (authVersion == 0x02) {
                    key = paramsProvider.getSecretKey();
                }
            }

            byte[] nonce = ByteBuffer.allocate(18)
                    .putShort(authVersion)
                    .put(clientNonce)
                    .array();

            ByteBuffer digested = ByteBuffer.wrap(huaweiCrypto.digestChallenge(key, doubleNonce));
            byte[] challenge = new byte[0x20];
            digested.get(challenge, 0x00, 0x20);
            LOG.debug("challenge: {}", GB.hexdump(challenge));

            // 前 16 字节同时作为后续包加密的首密钥
            byte[] firstKey = new byte[0x10];
            digested.get(firstKey, 0x00, 0x10);
            paramsProvider.setFirstKey(firstKey);

            return new DeviceConfig.Auth.Request(paramsProvider, challenge, nonce).serialize();
        } catch (HuaweiPacket.CryptoException e) {
            throw new RequestCreationException(e);
        } catch (NoSuchAlgorithmException | InvalidKeyException | InvalidKeySpecException
                 | UnsupportedEncodingException e) {
            throw new RequestCreationException("Digest exception", e);
        }
    }

    @Override
    protected void processResponse() throws ResponseParseException {
        LOG.debug("handle Auth");

        if (!(receivedPacket instanceof DeviceConfig.Auth.Response)) {
            throw new ResponseTypeMismatchException(receivedPacket, DeviceConfig.Auth.Response.class);
        }

        try {
            ByteBuffer digested = ByteBuffer.wrap(huaweiCrypto.digestResponse(key, doubleNonce));
            byte[] expected = new byte[0x20];
            digested.get(expected, 0x00, 0x20);

            byte[] actual = ((DeviceConfig.Auth.Response) receivedPacket).challengeResponse;
            if (!Arrays.equals(expected, actual)) {
                throw new ResponseParseException("Challenge answer mismatch: "
                        + StringUtils.bytesToHex(actual) + " != " + StringUtils.bytesToHex(expected));
            }
            LOG.debug("Auth challenge/response verified");
        } catch (NoSuchAlgorithmException | InvalidKeyException | InvalidKeySpecException
                 | UnsupportedEncodingException e) {
            throw new ResponseParseException("Challenge response digest exception", e);
        }
    }

    /** 供 {@link CryptoUtils} 相关的调试使用：当前协商出的 authVersion。 */
    public short getAuthVersion() {
        return authVersion;
    }
}