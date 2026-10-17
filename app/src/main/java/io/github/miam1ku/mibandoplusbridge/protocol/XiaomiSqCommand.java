package io.github.miam1ku.mibandoplusbridge.protocol;

import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;

/**
 * 包装 {@link XiaomiProto.Command} 的 {@link SqCommand}。
 *
 * <p>刻意保留原始 {@code Command} 引用（{@link #command()}）而不是在构造时
 * 就序列化成字节：
 * <ul>
 *   <li>{@code LiveCommandQueue.classify()} 需要读
 *       {@code hasNotification()} / {@code getNotification2()} 等 protobuf
 *       消息结构才能判断通知类型，序列化成字节就丢了；</li>
 *   <li>{@code LiveCommandQueue} 的 ack/响应对匹配也依赖 protobuf 的
 *       {@code equals}。</li>
 * </ul>
 * 所以序列化是<b>延迟</b>的（{@link #payload()} 首次调用时才做），
 * 这与 protobuf-javalite 的惰性语义一致，也避免为永远不会入队的命令付出
 * 序列化开销。
 */
public final class XiaomiSqCommand implements SqCommand {

    private final XiaomiProto.Command command;
    private volatile byte[] payload;

    public XiaomiSqCommand(XiaomiProto.Command command) {
        if (command == null) {
            throw new IllegalArgumentException("command 不能为空");
        }
        if (!command.isInitialized()) {
            // protobuf-javalite：required 字段缺失时 isInitialized() 为 false，
            // 序列化会抛 InvalidProtocolBufferException。这里提前失败，
            // 让调用方拿到明确的 IllegalArgumentException。
            throw new IllegalArgumentException("protobuf 命令存在未设置的 required 字段");
        }
        this.command = command;
    }

    /** 原始 protobuf 命令，供 {@code LiveCommandQueue} 使用。 */
    public XiaomiProto.Command command() {
        return command;
    }

    @Override
    public int type() {
        return command.getType();
    }

    @Override
    public int subtype() {
        return command.getSubtype();
    }

    @Override
    public byte[] payload() {
        byte[] local = payload;
        if (local == null) {
            synchronized (this) {
                local = payload;
                if (local == null) {
                    local = command.toByteArray();
                    payload = local;
                }
            }
        }
        return local;
    }

    @Override
    public String commandId() {
        return "xiaomi:" + command.getType() + "/" + command.getSubtype();
    }
}
