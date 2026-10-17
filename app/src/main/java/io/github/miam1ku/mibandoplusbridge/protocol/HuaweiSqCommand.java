package io.github.miam1ku.mibandoplusbridge.protocol;

import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket.CryptoException;

import java.util.ArrayList;
import java.util.List;

/**
 * 包装 {@link HuaweiPacket} 的 {@link SqCommand}。
 *
 * <p>与 {@link XiaomiSqCommand} 的关键差异：华为的
 * {@link HuaweiPacket#serialize()} 返回 {@code List<byte[]>} 而非单个
 * {@code byte[]} —— 因为 RFCOMM 通道按 MTU 分片，一个逻辑包可能对应多次
 * socket 写。这里把它们原样保留，交给 {@code HuaweiLiveSession} 逐片写，
 * <b>不在这里合并</b>：合并会破坏华为自己的分片边界，而协议对分片序号有
 * 语义（{@code HuaweiPacket} 在序列化时已把序号写进每一片的头部）。
 *
 * <p>{@link #payload()} 返回<b>拼接后</b>的完整字节，仅用于日志与断言；
 * 实际发送走 {@link #frames()}。
 */
public final class HuaweiSqCommand implements SqCommand {

    private final HuaweiPacket packet;
    private volatile List<byte[]> frames;
    private volatile byte[] joined;

    public HuaweiSqCommand(HuaweiPacket packet) {
        if (packet == null) {
            throw new IllegalArgumentException("packet 不能为空");
        }
        this.packet = packet;
    }

    /** 原始华为包，供 {@code HuaweiLiveSession} 走事务队列。 */
    public HuaweiPacket packet() {
        return packet;
    }

    /** 序列化为分片列表，顺序即发送顺序。 */
    public List<byte[]> frames() {
        List<byte[]> local = frames;
        if (local == null) {
            synchronized (this) {
                local = frames;
                if (local == null) {
                    // HuaweiPacket.serialize() 抛受检异常 CryptoException
                    // （加密失败）。这里包成 IllegalStateException：
                    // SqCommand.frames() 是惰性缓存接口，不允许抛受检异常，
                    // 而"密码学失败"本身就是不可恢复的状态错误。
                    List<byte[]> serialized;
                    try {
                        serialized = packet.serialize();
                    } catch (CryptoException e) {
                        throw new IllegalStateException(
                                "华为包序列化失败: " + commandId(), e);
                    }
                    if (serialized == null || serialized.isEmpty()) {
                        // serialize() 理论上不返空，但空列表会让事务队列
                        // 认为无事可做而静默成功 —— 那比抛异常更难排查。
                        throw new IllegalStateException(
                                "华为包序列化为空: " + commandId());
                    }
                    // List 可能是不可变的，复制一份避免上游复用/修改
                    local = new ArrayList<>(serialized);
                    frames = local;
                }
            }
        }
        return local;
    }

    @Override
    public int type() {
        // 华为 BR 通道只有一条主通道，队列层不按 type 区分。
        // 真正的细分在 subtype()（= commandId）。
        return GENERIC;
    }

    @Override
    public int subtype() {
        // serviceId/commandId 在 HuaweiPacket 里是 byte（华为协议本身就是
        // 单字节 tag），这里保持无符号语义返回 0..255。
        return packet.commandId & 0xFF;
    }

    @Override
    public byte[] payload() {
        byte[] local = joined;
        if (local == null) {
            synchronized (this) {
                local = joined;
                if (local == null) {
                    List<byte[]> parts = frames();
                    int total = 0;
                    for (byte[] p : parts) {
                        total += p.length;
                    }
                    byte[] buffer = new byte[total];
                    int offset = 0;
                    for (byte[] p : parts) {
                        System.arraycopy(p, 0, buffer, offset, p.length);
                        offset += p.length;
                    }
                    joined = buffer;
                    local = buffer;
                }
            }
        }
        return local;
    }

    @Override
    public String commandId() {
        // packet.tlv 是 protected，跨包不可访问，所以这里只用公开的
        // serviceId/commandId。TLV 与否由 serialize() 内部决定，日志不需要。
        return String.format("huawei:0x%02x/0x%02x",
                packet.serviceId & 0xFF, packet.commandId & 0xFF);
    }
}
