package io.github.miam1ku.mibandoplusbridge.protocol;

import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 用 {@link LiveCommandQueue} 实现的 {@link LiveSession}。
 *
 * <p>这是一个<b>零行为改动</b>的适配器：它只做类型转换，
 * {@code LiveCommandQueue} 的排序、合并、去重、超时、隔离逻辑全部不变。
 * 这样小米链路的行为与改造前逐字节一致 —— 这点很重要，因为 bridge 的通知
 * 去重（{@code retiredUntil} 表）和来电特殊处理（package "phone" + id 0）
 * 都是踩过坑调出来的，不能因为抽象而回归。
 *
 * <p>{@link #send} 的完成语义：{@code LiveCommandQueue.send} 返回的
 * {@code CompletionStage} 在<b>收到 ack</b> 时完成，不是"写入 socket 时"完成。
 * 本类原样透传，不重新定义。
 */
public final class XiaomiLiveSession implements LiveSession {

    private final LiveCommandQueue queue;
    private final AtomicBoolean closed = new AtomicBoolean();

    public XiaomiLiveSession(LiveCommandQueue queue) {
        if (queue == null) {
            throw new IllegalArgumentException("queue 不能为空");
        }
        this.queue = queue;
    }

    /** 供需要读原始队列的服务层使用（例如历史同步、实时统计）。 */
    public LiveCommandQueue queue() {
        return queue;
    }

    @Override
    public CompletionStage<Void> send(SqCommand command) {
        if (closed.get()) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("SESSION_CLOSED"));
        }
        if (!(command instanceof XiaomiSqCommand xiaomi)) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalArgumentException(
                            "XiaomiLiveSession 只接受 XiaomiSqCommand，实际收到 "
                                    + command.getClass().getName()));
        }
        return queue.send(xiaomi.command());
    }

    @Override
    public boolean isOpen() {
        return !closed.get();
    }

    @Override
    public void close(Throwable cause) {
        if (closed.compareAndSet(false, true)) {
            queue.close(cause);
        }
    }
}
