package io.github.miam1ku.mibandoplusbridge.protocol;

import java.util.concurrent.CompletionStage;

/**
 * 设备无关的实时会话。
 *
 * <p>桥接项目原本只有一条链路：{@link SppDiagnosticClient#runLive} 建立
 * 小米 SPP 连接后回调一个 {@link LiveCommandQueue}，{@code BandLiveService}
 * 拿到它就开始推送通知、音乐、闹钟。整条链路有 45 处
 * {@code XiaomiProto.Command} 引用，而且 {@code LiveCommandQueue.classify()}
 * 里写死了小米通知的 type/subtype 语义（type 7 是通知、subtype 0 是弹出、
 * 1 是撤回，package "phone" + id 0 是来电）。
 *
 * <p>华为 GT3 走 BR/EDR + RFCOMM，包是 TLV 而非 protobuf，通知模型是
 * {@code HuaweiPacket} 而非 {@code XiaomiProto.Notification}。把华为塞进
 * {@code LiveCommandQueue} 需要改写它的分类逻辑 —— 而那段逻辑对华为毫无意义。
 *
 * <p>所以抽象的边界画在<b>队列之上、会话之下</b>。{@code BandLiveService}
 * 面向 {@link LiveSession}，由两个实现分别承载：
 * <ul>
 *   <li>{@link XiaomiLiveSession} —— 包住现有 {@code LiveCommandQueue}，
 *       不改动其任何行为；</li>
 *   <li>{@code HuaweiLiveSession} —— 包装
 *       {@code protocol.huawei.service.HuaweiSupportProvider}，把
 *       {@code HuaweiPacket} 序列化后交给 {@code HuaweiBrTransport}。</li>
 * </ul>
 *
 * <p>取舍说明：通知优先级、合并、去重这些小米特有行为留在
 * {@code LiveCommandQueue} 内部不外泄；华为侧只暴露"发一个包、收一个包"的
 * 最小能力。华为的通知模型（id 空间、dismiss 语义）与小米不同，
 * 本就不该强行共用一套分类逻辑。
 */
public interface LiveSession {

    /**
     * 发送一个命令，不等待响应。
     *
     * @param command 命令；{@link SqCommand#payload()} 为空表示只等响应
     * @return 完成即表示字节已交给传输层，<b>不代表手表已确认</b>
     */
    CompletionStage<Void> send(SqCommand command);

    /** 会话是否仍然可用。 */
    boolean isOpen();

    /**
     * 关闭会话。可重复调用。
     *
     * @param cause 关闭原因；传 {@code null} 表示主动关闭
     */
    void close(Throwable cause);

    /**
     * 握手与能力协商的结果。
     *
     * @param batteryPercent 电量百分比，未知时传负值
     * @param batteryState   电量状态（充电中/满/未知），未知传 {@code null}
     * @param firmware       固件版本，未知传 {@code null}
     * @param hardware       硬件标识，未知传 {@code null}
     */
    record Handshake(int batteryPercent, Integer batteryState,
                     String firmware, String hardware) {
    }
}
