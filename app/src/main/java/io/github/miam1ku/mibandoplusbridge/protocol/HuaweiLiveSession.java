package io.github.miam1ku.mibandoplusbridge.protocol;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;

import io.github.miam1ku.mibandoplusbridge.data.HuaweiLogger;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.br.HuaweiBrTransport;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.br.HuaweiTransactionBuilder;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.service.HuaweiSupportProvider;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 用华为 BR/EDR 链路实现的 {@link LiveSession}。
 *
 * <p>与 {@link XiaomiLiveSession} 的语义差异（调用方必须知道）：
 * <ul>
 *   <li><b>没有 ack 概念</b>。小米队列的 {@code send} 在收到 ack 时才完成；
 *       华为协议没有对应的确认包，只有"请求 → 响应"的请求链
 *       （{@code HuaweiSupportProvider} 的 {@code Request} 链）。所以这里的
 *       完成语义是"<b>分片已交给传输层</b>"。</li>
 *   <li><b>发送前要求已认证</b>。华为握手链（LinkParams → HiChain →
 *       ExpandCapability）完成后 {@code authVersion}/{@code secretKey} 才有值，
 *       否则 {@code serialize()} 会对未加密包报错。</li>
 * </ul>
 *
 * <p>本类<b>不</b>负责握手 —— 那是 {@code HuaweiSupportProvider.startHandshake()}
 * 的职责，由 {@code BandLiveService} 在连接建立后触发。这里只负责"已认证后发包"。
 */
public final class HuaweiLiveSession implements LiveSession {

    private static final HuaweiLogger LOG =
            HuaweiLogger.of(HuaweiLiveSession.class);

    private final HuaweiSupportProvider provider;
    private final AtomicBoolean closed = new AtomicBoolean();

    public HuaweiLiveSession(HuaweiSupportProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("provider 不能为空");
        }
        this.provider = provider;
    }

    /**
     * 建立华为链路：绑定传输层、连接、启动握手。
     *
     * @param context       用于取系统服务
     * @param deviceAddress 手表 MAC 地址
     * @param onConnected   连接成功回调；可为 {@code null}
     * @return 传输层已连接（握手尚未完成）
     */
    public static boolean connect(Context context, String deviceAddress,
            HuaweiBrTransport.ConnectionListener onConnected) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            LOG.error("no bluetooth adapter");
            return false;
        }
        HuaweiSupportProvider provider =
                new HuaweiSupportProvider(
                        io.github.miam1ku.mibandoplusbridge.protocol.huawei.core
                                .HuaweiDeviceType.BR);
        provider.setContext(context);
        // 身份必须在握手之前注入：startHandshake() 第一行就检查 identity，
        // 缺了它整条链会以 "identity not attached" 直接 abort。
        // HuaweiIdentityStore 按手表 MAC 分文件持久化伪 MAC / androidID /
        // secretKey —— 三者跨会话稳定，手表才认这台手机为「已配对的老朋友」。
        provider.attachIdentity(
                new io.github.miam1ku.mibandoplusbridge.protocol.huawei.service
                        .HuaweiIdentityStore(context, deviceAddress));
        // 第三个参数不能省：ConnectionStateRelay 依赖它把断开事件透传给宿主，
        // 且 onDisconnected 里会触发 provider.onTransportClosed() 释放在途请求。
        provider.attachTransport(adapter, deviceAddress, onConnected);
        if (!provider.getTransport().connect()) {
            LOG.error("transport connect failed: {}", deviceAddress);
            return false;
        }
        // 握手在传输层的连接回调里自动触发（provider.onConnectionEstablished）
        return true;
    }

    @Override
    public CompletionStage<Void> send(SqCommand command) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("SESSION_CLOSED"));
        }
        if (!(command instanceof HuaweiSqCommand huawei)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "HuaweiLiveSession 只接受 HuaweiSqCommand，实际收到 "
                            + command.getClass().getName()));
        }
        if (!provider.isTransportAttached()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("TRANSPORT_NOT_ATTACHED"));
        }
        if (!provider.isAuthenticated()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("NOT_AUTHENTICATED"));
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        try {
            HuaweiTransactionBuilder builder =
                    provider.createBrTransactionBuilder(command.commandId());
            for (byte[] frame : huawei.frames()) {
                builder.write(frame);
            }
            // 响应必须回到 provider —— 它实现了 HuaweiSocketCallback 并持有
            // 在途请求表与重组状态。这里不能新建空回调：那既不实现
            // onConnectionEstablished()，也会把上行数据从 provider 手里夺走。
            builder.setCallback(provider);
            builder.queue();
            future.complete(null);
        } catch (Exception e) {
            LOG.error("huawei send failed: {}", command.commandId(), e);
            future.completeExceptionally(e);
        }
        return future;
    }

    @Override
    public boolean isOpen() {
        return !closed.get() && provider.isConnected();
    }

    @Override
    public void close(Throwable cause) {
        if (closed.compareAndSet(false, true)) {
            LOG.info("huawei live session closing: {}",
                    cause == null ? "deliberate" : cause.toString());
            provider.getTransport().disconnect();
        }
    }
}
