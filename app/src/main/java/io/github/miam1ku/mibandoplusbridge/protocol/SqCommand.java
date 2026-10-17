package io.github.miam1ku.mibandoplusbridge.protocol;

/**
 * 设备无关的会话命令。
 *
 * <p>存在的理由：{@link LiveCommandQueue} 从构造到消费的每一个签名都写死了
 * {@code nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command} ——
 * 队列内部用它做类型判别（{@code classify}）、用 {@code getType()/getSubtype()}
 * 做排序、用 protobuf 的 equals 做去重。华为链路的对应物是
 * {@code HuaweiPacket}（TLV 编码 + serviceId/commandId），两者没有共同表示。
 *
 * <p>所以这里定义一个最小接口，只暴露队列真正需要的语义：
 * <ul>
 *   <li>{@link #type()} / {@link #subtype()} —— 排序与判别；</li>
 *   <li>{@link #payload()} —— 上行字节（小米走 protobuf 序列化，华为走
 *       {@code HuaweiPacket.serialize()}）；</li>
 *   <li>{@link #commandId()} —— 设备类型标记，用于日志与断言。</li>
 * </ul>
 *
 * <p>刻意<b>不</b>放进接口的东西：protobuf 消息体、华为的 TLV 标签、
 * 任何一方特有的能力查询。队列不该知道协议细节。
 *
 * @see LiveSession
 */
public interface SqCommand {

    /**
     * 命令大类。用于队列内的同类合并（coalesce）与优先级判定。
     *
     * <p>小米的 type 直接取 {@code XiaomiProto.Command.getType()}；
     * 华为侧目前只有单一 BR 通道，握手/数据同步/电池都归为
     * {@link #GENERIC}，由上层用 {@link #commandId()} 细分。
     */
    int type();

    /**
     * 命令子类。缺省返回 {@link #SUBTYPE_NONE}。
     *
     * <p>对小米语义与 {@code getSubtype()} 一致；对华为是
     * {@code HuaweiPacket.commandId}。
     */
    default int subtype() {
        return SUBTYPE_NONE;
    }

    /** 上行字节。空表示"只等响应、不发数据"。 */
    byte[] payload();

    /** 设备类型标记，仅用于日志与断言，不参与队列逻辑。 */
    String commandId();

    /** 未定义子类。 */
    int SUBTYPE_NONE = 0;

    /** 无设备专有语义（华为 BR 通道专用）。 */
    int GENERIC = 0;
}
