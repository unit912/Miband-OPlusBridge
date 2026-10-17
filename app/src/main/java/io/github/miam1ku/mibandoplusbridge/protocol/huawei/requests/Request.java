// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests;

import io.github.miam1ku.mibandoplusbridge.data.HuaweiLogger;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.br.HuaweiResponseManager;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.br.HuaweiTransactionBuilder;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.service.HuaweiSupportProvider;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * 一个华为协议请求：把包写出、等响应、再交给下一个请求。
 *
 * <p>对应 Gadgetbridge 的 {@code service.devices.huawei.requests.Request}。
 *
 * <p>与原实现的差异，都是因为 bridge 没有 GB 的框架：
 *
 * <ul>
 *   <li>没有 {@code BLE} 分支。GT3 走 BR/EDR，{@code createLeTransactionBuilder}
 *       与「荣耀新协议」的 characteristic 选择一并去掉。</li>
 *   <li>没有 {@code P2P}/{@code HuaweiDualChannelHelper} 的包级路由。
 *       GT3 的数据同步只用主通道，{@code getDualChannelDestPackage()} 保留为空实现，
 *       子类可覆盖但默认不再参与路由。</li>
 *   <li>没有 {@code OperationStatus} / {@code GBDevice} / toast。进度改由
 *       {@link RequestCallback} 回调上报，异常记录进会话日志。</li>
 *   <li>响应超时计时器与 {@code Handler} 保留 —— 这是协议正确性的一部分：
 *       手表不回包时必须让链路继续，不能永久挂起。</li>
 * </ul>
 */
public abstract class Request {

    private static final HuaweiLogger LOG = HuaweiLogger.of(Request.class);

    public static class RequestCreationException extends Exception {
        public RequestCreationException(String message) {
            super(message);
        }

        public RequestCreationException(HuaweiPacket.CryptoException e) {
            super(e);
        }

        public RequestCreationException(String message, Exception e) {
            super(message, e);
        }
    }

    public static class ResponseParseException extends Exception {
        public ResponseParseException(String message) {
            super(message);
        }

        public ResponseParseException(Exception e) {
            super(e);
        }

        public ResponseParseException(String message, Exception e) {
            super(message, e);
        }
    }

    /**
     * 响应包类型与预期不符。
     *
     * <p>通常意味着响应分发把包送错了请求 —— 例如 serviceId/commandId 相同但
     * 语义不同，或者上一个请求的响应被误认领。
     */
    public static class ResponseTypeMismatchException extends ResponseParseException {
        public ResponseTypeMismatchException(HuaweiPacket actual, Class<?> expected) {
            super("Response type mismatch, packet is of type "
                    + (actual == null ? "null" : actual.getClass().getName())
                    + " but expected " + expected.getName());
        }

        public ResponseTypeMismatchException(HuaweiPacket actual, Class<?> expected, Class<?> alsoExpected) {
            super("Response type mismatch, packet is of type "
                    + (actual == null ? "null" : actual.getClass().getName())
                    + " but expected " + expected.getName() + " or " + alsoExpected.getName());
        }
    }

    /** 请求完成后的回调。 */
    public static class RequestCallback {
        private final HuaweiSupportProvider support;

        public RequestCallback() {
            this.support = null;
        }

        public RequestCallback(HuaweiSupportProvider support) {
            this.support = support;
        }

        protected HuaweiSupportProvider getSupport() {
            return support;
        }

        public void call() { }

        public void call(Request request) {
            call();
        }

        public void handleException(ResponseParseException e) {
            LOG.error("callback request exception", e);
        }

        public void handleException(Request request, ResponseParseException e) {
            handleException(e);
        }

        public void timeout(Request request) {
            request.handleNext();
        }
    }

    protected byte serviceId;
    protected byte commandId;
    protected HuaweiPacket receivedPacket;
    protected final HuaweiSupportProvider supportProvider;
    protected final HuaweiPacket.ParamsProvider paramsProvider;

    /** 子类链接到下一个请求，形成链式执行。 */
    protected Request nextRequest;
    protected RequestCallback finalizeReq;
    protected boolean stopChain;
    protected boolean addToResponse = true;

    /**
     * 本请求是否因超时而终止。
     *
     * <p>{@link #onTimeout()} 置位。它的作用是让 {@link #handleNext()} 区分
     * 「链正常走完」与「等不到响应被迫推进」—— 后者绝不能回调
     * {@code finalizeReq.call()}，否则上层会以为认证成功了。
     */
    private volatile boolean timedOut;

    private final long timeoutMillis;
    private final Thread timeoutWatcher;

    public Request(HuaweiSupportProvider support) {
        this(support, 0);
    }

    public Request(HuaweiSupportProvider support, long timeoutMillis) {
        this.supportProvider = support;
        this.paramsProvider = support.getParamsProvider();
        this.timeoutMillis = timeoutMillis;
        this.timeoutWatcher = timeoutMillis > 0
                ? new Thread(this::onTimeout, "HuaweiRequestTimeout")
                : null;
        if (timeoutWatcher != null) {
            timeoutWatcher.setDaemon(true);
        }
    }

    /**
     * 事务构建器延迟到真正写出时才取。
     *
     * <p>华为握手是一串在 {@code initializeDevice} 回调里构造的请求，它们在传输层
     * 绑定之前就被 new 出来了。若在构造函数里就拿 builder，未绑定时会立刻抛异常，
     * 让「先构造请求链、再连上手表」这种正常顺序无法成立。
     */
    private HuaweiTransactionBuilder builder() {
        return supportProvider.createBrTransactionBuilder(getName());
    }

    /**
     * 计时器线程主体：<b>必须先等满 {@code timeoutMillis} 才是"超时"</b>。
     *
     * <p>★ 曾经的 bug（已实测定位）：本方法里没有等待，线程一 start 就直接往下走。
     * 于是任何带超时的请求在写出的同一毫秒就判定"超时"，走进
     * {@link RequestCallback#timeout(Request)} → {@link #handleNext()}。当
     * {@code nextRequest} 还没挂上时，{@code handleNext()} 会落到
     * {@code finalizeReq.call()} —— 也就是把「刚发出去、还没等到响应」误判成
     * 「整条链跑完了」。
     *
     * <p>实测症状极具迷惑性：HiChain 认证链的 step1 写出后 14ms 就打印
     * {@code timeout on service 0x1 command 0x28}，紧接着
     * {@code capabilityAndFinish()} 被调用，0x37 在 step3 还没落地 secretKey
     * 时就发出去（日志里 {@code keyLen=-1}），随后
     * {@code request could not be created} 让整个握手失败。更坑的是 HiChain
     * 主链其实在后台正常跑完了 step1→step4，看日志像是"只差一个收尾"。
     *
     * <p>{@code cancelTimeout()} 用 {@code interrupt()} 打断的正是这里的 sleep；
     * 响应到达时会立刻解除等待，不会白等满超时时长。
     */
    private void onTimeout() {
        try {
            Thread.sleep(timeoutMillis);
        } catch (InterruptedException e) {
            // 响应已到（或链路关闭），计时器被取消 —— 这不是超时。
            Thread.currentThread().interrupt();
            LOG.debug("timeout watcher cancelled on service 0x{} command 0x{}",
                    Integer.toHexString(serviceId & 0xFF), Integer.toHexString(commandId & 0xFF));
            return;
        }

        LOG.debug("timeout on service 0x{} command 0x{}",
                Integer.toHexString(serviceId & 0xFF), Integer.toHexString(commandId & 0xFF));
        timedOut = true;
        if (finalizeReq != null) {
            finalizeReq.timeout(this);
        } else {
            handleNext();
        }
    }

    /** 本设备是否支持该请求；返回 {@code false} 时直接跳到下一个。 */
    protected boolean requestSupported() {
        return true;
    }

    /**
     * 写出请求并开始等待响应。
     *
     * <p>按 MTU 分片。华为的握手包（如带认证的 0x35）常超过默认 MTU，必须切片，
     * 否则 RFCOMM 会写出残包。切片后插入固定间隔再启动超时计时器。
     */
    public void doPerform() throws IOException {
        if (!requestSupported()) {
            handleNext();
            return;
        }

        if (addToResponse) {
            supportProvider.addInProgressRequest(this);
        }

        final HuaweiTransactionBuilder builder;
        try {
            builder = builder();
        } catch (IllegalStateException e) {
            supportProvider.removeInProgressRequests(this);
            throw new IOException("transport not available", e);
        }

        try {
            List<byte[]> frames = createRequest();
            if (frames != null) {
                for (byte[] frame : frames) {
                    int mtu = paramsProvider.getMtu();
                    if (frame.length >= mtu) {
                        ByteBuffer buffer = ByteBuffer.wrap(frame);
                        while (buffer.hasRemaining()) {
                            byte[] slice = new byte[Math.min(mtu, buffer.remaining())];
                            buffer.get(slice);
                            builder.write(slice);
                        }
                    } else {
                        builder.write(frame);
                    }
                }
            }
            builder.sleep(paramsProvider.getInterval());
            performConnected(builder);
        } catch (RequestCreationException e) {
            throw new IOException("request could not be created", e);
        }
    }

    /** 由子类构造实际要写出的字节；返回 {@code null} 表示没有帧要发。 */
    protected List<byte[]> createRequest() throws RequestCreationException {
        return null;
    }

    /** 处理已收到的响应包。 */
    protected void processResponse() throws ResponseParseException { }

    /** 由 {@link HuaweiResponseManager} 调用：包已匹配到本请求。 */
    public void handleResponse() {
        cancelTimeout();
        // 响应既然到了，本请求就不是"超时终止"，即便计时器刚好在同一瞬间
        // 醒来并置了位，也要让流程按正常路径继续。
        timedOut = false;
        try {
            receivedPacket.parseTlv();
        } catch (HuaweiPacket.ParseException e) {
            LOG.error("parse TLV failed", e);
            if (finalizeReq != null) {
                finalizeReq.handleException(this, new ResponseParseException("parse TLV failed", e));
            }
            return;
        }
        try {
            processResponse();
        } catch (ResponseParseException e) {
            if (finalizeReq != null) {
                finalizeReq.handleException(this, e);
            }
            return;
        }
        handleNext();
    }

    /** 链路断开时由传输层调用：停止计时器并推进链子，避免整条链永久等待。 */
    public void onTransportClosed() {
        cancelTimeout();
        if (finalizeReq != null) {
            finalizeReq.handleException(this, new ResponseParseException("transport closed"));
        }
    }

    /** 启动超时计时器并把事务排入写队列。 */
    private void performConnected(HuaweiTransactionBuilder builder) {
        if (timeoutWatcher != null) {
            timeoutWatcher.start();
        }
        builder.queue();
    }

    private void cancelTimeout() {
        // 计时器是每个请求一个的一次性线程，线程自然结束即视为取消；
        // 中断可让 sleep 提前返回，从而不等满超时时长。
        Thread watcher = timeoutWatcher;
        if (watcher != null && watcher.isAlive()) {
            watcher.interrupt();
        }
    }

    /** 执行链上的下一个请求；若没有则结束并回调。 */
    public void handleNext() {
        // ★ 超时不是"链走完了"。
        //
        // 走到这里说明等不到本请求的响应。链上的后续步骤都建立在本请求的响应
        // 之上，继续执行没有意义；而 finalizeReq.call() 的语义是"整条链成功
        // 结束"，一旦在这里触发，上层就会去读能力位 / 发认证后命令 —— 在密钥
        // 还没落地时表现为 keyLen=-1、加密失败。
        //
        // 所以超时一律走 handleException，让上层以失败收场。
        if (timedOut) {
            String hex = String.format("service 0x%s command 0x%s",
                    Integer.toHexString(serviceId & 0xFF), Integer.toHexString(commandId & 0xFF));
            LOG.warn("request timed out, aborting chain instead of finalizing: " + hex);
            if (finalizeReq != null) {
                finalizeReq.handleException(this, new ResponseParseException(
                        "timeout on service 0x" + Integer.toHexString(serviceId & 0xFF)
                                + " command 0x" + Integer.toHexString(commandId & 0xFF)));
            }
            return;
        }
        if (nextRequest != null && !stopChain) {
            Request next = nextRequest;
            // ★ 必须在递归调用 doPerform() 之前清空。
            //
            // 原实现把 nextRequest = null 放在最后，于是执行完 next.doPerform()
            // 之后，nextRequest 仍非 null，紧接着的 `if (nextRequest == null)`
            // 判断为 false —— 看起来没事；但 doPerform() 是<b>异步</b>的，它内部
            // 会回调 handleNext()，那条链走完时会再进一次本方法。两次调用读到
            // 同一个非空的 nextRequest，就把它执行了两遍，并各自触发一次
            // finalizeReq.call()。
            //
            // 实测症状：认证链还在跑 step2，capabilityAndFinish() 就被触发，
            // 0x37 在没有新 secretKey 的情况下发出，手表回固定拒绝帧
            // 7F 04 00 01 86 A4。
            nextRequest = null;
            try {
                next.doPerform();
            } catch (IOException e) {
                LOG.error("next request failed", e);
                if (finalizeReq != null) {
                    finalizeReq.handleException(this, new ResponseParseException("next request failed", e));
                }
            }
            return;
        }
        if (finalizeReq != null) {
            finalizeReq.call(this);
        }
    }

    public Request nextRequest(Request request) {
        if (request != null) {
            nextRequest = request;
        }
        return this;
    }

    public void stopChain(Request request) {
        request.stopChain = true;
        Request next = request.nextRequest;
        if (next != null) {
            next.stopChain = true;
            supportProvider.removeInProgressRequests(next);
        }
    }

    public void stopChain() {
        stopChain = true;
    }

    /** 供响应管理器匹配：service/command 均相等才由本请求处理。 */
    public boolean handleResponse(HuaweiPacket response) {
        if (response.serviceId == serviceId && response.commandId == commandId) {
            receivedPacket = response;
            return true;
        }
        return false;
    }

    /** 是否在处理完这一个响应后自动摘除监听。 */
    public boolean autoRemoveFromResponseHandler() {
        return true;
    }

    public void setFinalizeReq(RequestCallback callback) {
        this.finalizeReq = callback;
    }

    /** 覆盖超时时长（毫秒）。必须在构造后、{@link #doPerform()} 前调用。 */
    public void setupTimeoutUntilNext(long millis) {
        // 超时线程在构造时按 timeoutMillis 创建；此处仅为兼容原 API 保留，
        // bridge 侧统一通过构造参数设置。
        throw new UnsupportedOperationException(
                "timeout must be supplied to the constructor: " + millis);
    }

    /** 匿名类会被用作任务名，这里剥掉匿名包装，取真实类名。 */
    public String getName() {
        Class<?> type = getClass();
        while (type.isAnonymousClass()) {
            type = type.getSuperclass();
        }
        return type.getSimpleName();
    }

    /** 华为双通道按目标包名路由 P2P 服务的钩子；GT3 走主通道，默认不使用。 */
    protected String getDualChannelDestPackage() {
        return null;
    }
}