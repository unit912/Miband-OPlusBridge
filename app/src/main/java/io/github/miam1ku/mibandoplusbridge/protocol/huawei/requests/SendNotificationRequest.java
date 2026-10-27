// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests;

import io.github.miam1ku.mibandoplusbridge.data.HuaweiLogger;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.Notifications;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.service.HuaweiSupportProvider;

import java.util.List;

/**
 * 向手表推送一条通知（Notifications 0x02 服务，命令 0x01 发出 / 0x06 移除）。
 *
 * <p><b>fire-and-forget</b>：GT3 对通知包没有语义响应（真机实测写出后无回包，
 * 不回也不影响后续），所以 {@code addToResponse = false}（不进响应认领表），
 * 且不带超时计时器 —— {@code doPerform()} 把帧写进传输层队列即视为完成。
 * 与读数据的请求不同，本请求绝不等待：通知卡在等响应上比丢一条通知糟得多。
 *
 * <p>文本长度必须由调用方按
 * {@link HuaweiSupportProvider#notificationContentLimit()} 截断 —— 手表不回
 * 超长错误，只会无声丢弃整条通知。
 */
public class SendNotificationRequest extends Request {

    private static final HuaweiLogger LOG = HuaweiLogger.of(SendNotificationRequest.class);

    private final HuaweiPacket packet;

    private SendNotificationRequest(HuaweiSupportProvider support, HuaweiPacket packet) {
        super(support);
        this.packet = packet;
        this.serviceId = packet.serviceId;
        this.commandId = packet.commandId;
        // 无响应包：不进响应认领表，也不需要超时计时器（构造参数 0 即不启动）。
        this.addToResponse = false;
    }

    public static SendNotificationRequest post(HuaweiSupportProvider support,
            Notifications.NotificationActionRequest request) {
        return new SendNotificationRequest(support, request);
    }

    public static SendNotificationRequest remove(HuaweiSupportProvider support,
            Notifications.NotificationRemoveAction action) {
        return new SendNotificationRequest(support, action);
    }

    /**
     * 通用 fire-and-forget 发包：任何已完成（{@code complete=true}）的华为报文都可用，
     * 如音乐控制、免打扰、闹钟响铃等测试命令。同样不进响应认领表、不带超时。
     */
    public static SendNotificationRequest send(HuaweiSupportProvider support,
            io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket packet) {
        return new SendNotificationRequest(support, packet);
    }

    @Override
    protected List<byte[]> createRequest() throws RequestCreationException {
        try {
            return packet.serialize();
        } catch (HuaweiPacket.CryptoException e) {
            throw new RequestCreationException(e);
        }
    }
}
