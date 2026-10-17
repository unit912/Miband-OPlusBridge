package io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests;

import io.github.miam1ku.mibandoplusbridge.data.HuaweiLogger;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.CryptoUtils;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiConstants;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.StringUtils;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.DeviceConfig;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.service.HuaweiSupportProvider;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * HiChain 密钥协商 —— GT3 实际走的认证路径。
 *
 * <p>这是一个 4 步 PAKE（口令认证密钥交换），本类用「一个请求对象推进 4 步」的
 * 方式实现：每收到一步响应就把自己复制成下一个 {@code GetHiChainRequest}
 * 挂到链尾，状态（随机数、派生密钥、requestId）整体传递。
 *
 * <h3>两种 operationCode</h3>
 * <ul>
 *   <li>{@code 0x01} —— 认证。用户输入 PIN，PSK 由 PIN 派生。首次配对走这条。</li>
 *   <li>{@code 0x02} —— 绑定。已有 secretKey，直接用它当 PSK。认证完成后自动接上。</li>
 * </ul>
 *
 * <h3>步骤</h3>
 * <ol>
 *   <li>发 seed 与自随机数 {@code randSelf}；收对方 {@code randPeer}/{@code authIdPeer}，验 token。</li>
 *   <li>发自 token；验对方 returnCodeMac。</li>
 *   <li>用 HKDF 从 PSK 派生 sessionKey；认证模式下解出 authToken 存为 secretKey。</li>
 *   <li>发加密的确认值；绑定模式下由 sessionKey 导出最终 secretKey。</li>
 * </ol>
 *
 * <p><b>第 1 步需要用户在手表上确认配对</b>，所以 operationCode=0x01 的超时给到
 * 30 秒；后续步骤 5 秒足够。
 */
public class GetHiChainRequest extends Request {

    private static final HuaweiLogger LOG = HuaweiLogger.of(GetHiChainRequest.class);

    /** 首次认证：手表会弹出配对确认，用户可能要点一下，给足等待时间。 */
    private static final long FIRST_AUTHENTICATE_TIMEOUT = 30_000L;
    /** 认证完成后的 bind 步骤，无需人工干预。 */
    private static final long AUTHENTICATE_TIMEOUT = 5_000L;

    // 跨步骤传递的状态
    private DeviceConfig.HiChain.Request req;
    private byte operationCode;
    private byte step;
    private byte[] authIdSelf;
    private byte[] authIdPeer;
    private byte[] randSelf;
    private byte[] randPeer;
    private long requestId;
    private byte[] sessionKey;

    // 只用一次
    private byte[] seed;
    private byte[] challenge;
    private byte[] psk;

    /** 认证模式入口。 */
    public GetHiChainRequest(HuaweiSupportProvider support, boolean firstConnection) {
        super(support, firstConnection ? FIRST_AUTHENTICATE_TIMEOUT : AUTHENTICATE_TIMEOUT);
        this.serviceId = DeviceConfig.id;
        this.commandId = DeviceConfig.HiChain.id;
        // ★ operationCode 必须显式赋值，不能只处理一半。
        //
        // 0x01 = 认证（首次，PSK 来自 PIN）；0x02 = 绑定（PSK 为已有 secretKey）。
        // 曾经的 bug：只在 firstConnection 时赋 0x01，bind 路径的 operationCode
        // 保持 Java 默认值 0x00 —— 手表不认识 0x00，收到后<b>不回任何响应</b>，
        // 直接等到超时。于是得出「GT3 不支持 bind」的错误结论，把认证后的
        // 密钥交换环节整个砍掉了。
        //
        // 后果链：authToken（step3 的中间产物）被当成最终会话密钥 →
        // 手表下发的加密帧全部解不开 → 0x37 等命令在未完成密钥交接的会话里
        // 被挡回固定拒绝帧 7F 04 00 01 86 A4。
        this.operationCode = firstConnection ? (byte) 0x01 : (byte) 0x02;
        this.step = 0x01;
    }

    /** 步骤推进：复制上一请求的全部协商状态。 */
    private GetHiChainRequest(GetHiChainRequest prev) {
        // ★ 必须继承超时时长，不能用 super(prev.supportProvider)。
        //
        // 那个单参构造走的是 Request(support)，timeoutMillis=0 → 不建计时器。
        // 后果是 step2/3/4 完全没有超时保护：手表一旦不回包，这几个请求会
        // 永远挂在 handlers 里等，握手既不成功也不失败，表现为连接建立后
        // 长时间静默，然后被外层看门狗强拆。
        //
        // step1 之后手表不再需要人工确认，用较短的 AUTHENTICATE_TIMEOUT 即可。
        super(prev.supportProvider, AUTHENTICATE_TIMEOUT);
        this.serviceId = DeviceConfig.id;
        this.commandId = DeviceConfig.HiChain.id;
        this.req = prev.req;
        this.requestId = prev.requestId;
        this.operationCode = prev.operationCode;
        this.step = prev.step;
        this.authIdSelf = prev.authIdSelf;
        this.authIdPeer = prev.authIdPeer;
        this.randSelf = prev.randSelf;
        this.randPeer = prev.randPeer;
        this.psk = prev.psk;
        this.sessionKey = prev.sessionKey;
    }

    @Override
    protected List<byte[]> createRequest() throws RequestCreationException {
        if (requestId == 0x00L) {
            requestId = System.currentTimeMillis();
        }
        LOG.debug("HiChain request operationCode={} step={}", operationCode, step);

        if (req == null) {
            req = new DeviceConfig.HiChain.Request(
                    operationCode, requestId, supportProvider.getAndroidId(), HuaweiConstants.GROUP_ID);
        }

        try {
            HuaweiPacket packet = null;
            // messageId 在进入分支前捕获。绑定模式下 step 会在下面自增到 0x04，
            // 但发出去的消息 ID 必须停留在 0x03 —— 这是 GB 原版的实际行为，
            // 自作主张改成 0x04 会让手表拒收。
            int messageId = step;

            if (step == 0x01) {
                seed = new byte[32];
                randSelf = new byte[16];
                new Random().nextBytes(seed);
                new Random().nextBytes(randSelf);
                packet = req.new StepOne(paramsProvider, messageId, randSelf, seed);

            } else if (step == 0x02) {
                byte[] message = ByteBuffer
                        .allocate(randPeer.length + randSelf.length + authIdSelf.length + authIdPeer.length)
                        .put(randSelf).put(randPeer).put(authIdPeer).put(authIdSelf)
                        .array();
                byte[] selfToken = CryptoUtils.calcHmacSha256(psk, message);
                packet = req.new StepTwo(paramsProvider, messageId, selfToken);

            } else if (step == 0x03) {
                byte[] salt = ByteBuffer.allocate(randSelf.length + randPeer.length)
                        .put(randSelf).put(randPeer).array();
                byte[] info = "hichain_iso_session_key".getBytes(StandardCharsets.UTF_8);
                sessionKey = CryptoUtils.hkdfSha256(psk, salt, info, 32);
                // 会话密钥不进日志（短期凭据，但排查期泄漏同样危险）。

                if (operationCode == 0x01) {
                    byte[] nonce = new byte[12];
                    challenge = new byte[16];
                    new Random().nextBytes(nonce);
                    new Random().nextBytes(challenge);
                    byte[] aad = "hichain_iso_exchange".getBytes(StandardCharsets.UTF_8);
                    byte[] encData = CryptoUtils.encryptAES_GCM_NoPad(challenge, sessionKey, nonce, aad);
                    packet = req.new StepThree(paramsProvider, messageId, nonce, encData);
                } else {
                    // 绑定模式没有 step3 这一轮：派生出 sessionKey 后直接落到 step4，
                    // 但消息 ID 仍是上面捕获的 0x03。
                    step = 0x04;
                }
            }

            if (step == 0x04) {
                if (sessionKey == null) {
                    throw new RequestCreationException("HiChain reached step 4 without sessionKey");
                }
                byte[] nonce = new byte[12];
                new Random().nextBytes(nonce);
                byte[] input = new byte[]{0x00, 0x00, 0x00, 0x00};
                byte[] aad = "hichain_iso_result".getBytes(StandardCharsets.UTF_8);
                byte[] encResult = CryptoUtils.encryptAES_GCM_NoPad(input, sessionKey, nonce, aad);
                packet = req.new StepFour(paramsProvider, messageId, nonce, encResult);
            }

            if (packet == null) {
                throw new RequestCreationException("HiChain produced no packet at step " + step);
            }

            return packet.serialize();
        } catch (RequestCreationException e) {
            throw e;
        } catch (Exception e) {
            throw new RequestCreationException("HiChain exception", e);
        }
    }

    @Override
    protected void processResponse() throws ResponseParseException {
        if (!(receivedPacket instanceof DeviceConfig.HiChain.Response)) {
            throw new ResponseTypeMismatchException(receivedPacket, DeviceConfig.HiChain.Response.class);
        }
        DeviceConfig.HiChain.Response response = (DeviceConfig.HiChain.Response) receivedPacket;

        if (response.errorCode != 0) {
            throw new ResponseParseException("HiChain got errorCode " + response.errorCode);
        }
        step = response.step;
        LOG.debug("HiChain response operationCode={} step={}", operationCode, step);

        try {
            if (step == 0x04) {
                finish();
                return;
            }

            switch (step) {
                case 0x01:
                    handleStepOne(response);
                    break;
                case 0x02:
                    handleStepTwo(response);
                    break;
                case 0x03:
                    handleStepThree(response);
                    break;
                default:
                    throw new ResponseParseException("unexpected HiChain step " + step);
            }

            step += 0x01;
            GetHiChainRequest next = new GetHiChainRequest(this);
            next.setFinalizeReq(this.finalizeReq);
            nextRequest(next);
        } catch (ResponseParseException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseParseException(e);
        }
    }

    /** 第 1 步：校验手表给出的 token，据此派生 PSK。 */
    private void handleStepOne(DeviceConfig.HiChain.Response response) throws Exception {
        authIdSelf = supportProvider.getAndroidId();
        authIdPeer = response.step1Data.peerAuthId;
        randPeer = response.step1Data.isoSalt;
        byte[] peerToken = response.step1Data.token;

        byte[] key;
        if (operationCode == 0x01) {
            // 认证：PSK 来自用户 PIN
            byte[] pin = paramsProvider.getPinCode();
            if (pin == null || pin.length == 0) {
                // 没取到 PIN 就走到这里，说明 0x2C 被漏掉了或响应解密失败。
                // 继续下去算出的 PSK 必然是错的，报出来比让手表静默拒绝好。
                throw new ResponseParseException(
                        "HiChain auth requested but no PIN is available; "
                                + "GetPincodeRequest (0x2C) must run before HiChain");
            }
            String pinCodeHex = StringUtils.bytesToHex(pin);
            key = CryptoUtils.digest(pinCodeHex.getBytes(StandardCharsets.UTF_8));
        } else {
            // 绑定：PSK 就是上一次认证拿到的 secretKey
            key = supportProvider.getSecretKey();
            if (key == null || key.length == 0) {
                throw new ResponseParseException(
                        "HiChain bind requested but no secretKey is stored");
            }
        }
        psk = CryptoUtils.calcHmacSha256(key, seed);

        byte[] message = ByteBuffer
                .allocate(randPeer.length + randSelf.length + authIdSelf.length + authIdPeer.length)
                .put(randPeer).put(randSelf).put(authIdSelf).put(authIdPeer)
                .array();
        byte[] tokenCheck = CryptoUtils.calcHmacSha256(psk, message);
        if (!Arrays.equals(peerToken, tokenCheck)) {
            throw new ResponseParseException("HiChain tokenCheck mismatch: computed "
                    + StringUtils.bytesToHex(tokenCheck) + " != received "
                    + StringUtils.bytesToHex(peerToken));
        }
        LOG.debug("HiChain step1 token check passes");
    }

    /** 第 2 步：验证 returnCodeMac。 */
    private void handleStepTwo(DeviceConfig.HiChain.Response response) throws Exception {
        byte[] check = CryptoUtils.calcHmacSha256(psk, new byte[]{0x00, 0x00, 0x00, 0x00});
        if (!Arrays.equals(check, response.step2Data.returnCodeMac)) {
            throw new ResponseParseException("HiChain returnCodeMac mismatch: computed "
                    + StringUtils.bytesToHex(check) + " != received "
                    + StringUtils.bytesToHex(response.step2Data.returnCodeMac));
        }
        LOG.debug("HiChain step2 returnCodeMac check passes");
    }

    /** 第 3 步：解出 authToken 存为长期 secretKey。仅认证模式有这一步。 */
    private void handleStepThree(DeviceConfig.HiChain.Response response) throws Exception {
        if (operationCode != 0x01) {
            return;
        }
        byte[] nonce = response.step3Data.nonce;
        byte[] encAuthToken = response.step3Data.encAuthToken;
        byte[] authToken = CryptoUtils.decryptAES_GCM_NoPad(encAuthToken, sessionKey, nonce, challenge);
        supportProvider.setSecretKey(authToken);
        // 长度必须打出来：AES 要求密钥为 16/24/32 字节，authToken 若不是这三者
        // 中之一，后续所有加密命令都会在 CryptoUtils 里抛异常，而症状在手表侧
        // 只表现为「固定拒绝帧」，极难定位。
        LOG.debug("HiChain step3 authToken stored as secretKey, len={} bytes",
                authToken == null ? -1 : authToken.length);
        // 密钥明文不打日志 —— 它是长期凭据，泄漏等于交出手表控制权。
        // 排查密钥问题时用密钥长度 + 离线比对即可。
    }

    /**
     * 收尾。认证模式接着跑一次 bind 换取最终密钥；绑定模式已持有 secretKey，
     * 直接由 sessionKey 导出并结束。
     */
    private void finish() {
        if (operationCode == 0x01) {
            // ★ 认证模式下认证链本身就完成了，不要再发bind。
            //
            // 真机实测：operationCode=0x00 的 bind 请求手表<b>根本不回响应</b>，
            // 直接等到超时：
            //   HiChain request operationCode=0 step=1
            //   Request D timeout on service 0x1 command 0x28
            //   （没有 final secretKey derived）
            //
            // 于是 paramsProvider 里留着的仍是 step3 的 authToken。那东西不是
            // 32 字节的 AES 密钥，长度不匹配 → 后续加密命令（GCM）在
            // CryptoUtils 里直接抛异常，手表侧则回固定拒绝帧
            // 7F 04 00 01 86 A4。此前把 0x08电量的失败归到「没协商计数器」
            // 是错的，真正原因是这里少了一次成功的密钥落地。
            //
            // step3 已经通过 setSecretKey() 把 authToken 存成了长期密钥，
            // 认证链到此完整，bind 只在「已认证想换新密钥」时才有意义。
            LOG.debug("HiChain auth finished, secretKey already stored from step3");
            // 不要在这里调 finalizeReq.call()。
            //
            // Request 框架在 nextRequest == null 时会自动调一次
            // finalizeReq.call(this)（见 Request.java 的 finish 段）。若这里
            // 再手动调一次，同一个回调就会被触发两次 —— 于是
            // capabilityAndFinish() 抢在认证真正结束前跑一遍（实测
            // 0x0F 在 HiChain step1/step2 之间就发出去了），
            // 此刻 paramsProvider 里的 secretKey 还是旧的，0x37 必然失败。
            //
            // 认证链（operationCode=0x01）后面已经不再挂 nextRequest，
            // 框架会自行收尾，这里只需 return。
            return;
        }

        LOG.debug("HiChain bind finished");
        try {
            // ★ bind 完成后必须用 hichain_return_key 派生最终会话密钥并<b>覆盖</b>。
            //
            // 认证（0x01）step3 拿到的 authToken 只是中间产物，是本次 bind 的 PSK；
            // 真正用于加密通道的是 bind 结束时双方各自从 sessionKey 派生的
            // return key。手表侧同样执行这个派生，所以两边一致。
            //
            // 曾经反着改（"已有 secretKey 不覆盖"），依据是"覆盖会导致下次重连
            // 密钥不一致"—— 那个担心不成立：每次 bind 双方都派生，只要都落盘，
            // 下一轮 PSK 就同步换成新 key。反倒是<b>不</b>派生会把手表认定的
            // 会话密钥和本地存的 authToken 永远对不上。
            byte[] salt = ByteBuffer.allocate(randSelf.length + randPeer.length)
                    .put(randSelf).put(randPeer).array();
            byte[] info = "hichain_return_key".getBytes(StandardCharsets.UTF_8);
            byte[] key = CryptoUtils.hkdfSha256(sessionKey, salt, info, 32);
            supportProvider.setSecretKey(key);
            // 只打长度不打密钥本身 —— 长期凭据不进日志。
            LOG.info("HiChain final secretKey derived, len={} bytes", key.length);
        } catch (Exception e) {
            LOG.error("HiChain final key derivation failed", e);
            if (finalizeReq != null) {
                finalizeReq.handleException(this, new ResponseParseException("final key derivation failed", e));
            }
        }
    }
}