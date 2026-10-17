// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests;

import io.github.miam1ku.mibandoplusbridge.data.HuaweiLogger;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.Notifications;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.service.HuaweiSupportProvider;

import java.util.List;

/**
 * 查询手表的通知内容限制（Notifications 0x02 服务，命令 0x02）。
 *
 * <p>结果写入 {@link HuaweiSupportProvider} 的缓存：发送通知前按
 * {@code notificationContentLimit()} 截断标题/正文。GT3 对超长内容不报错、
 * 只是无声丢弃整条通知，所以这个限制必须在发送前应用。
 */
public class GetNotificationConstraintsRequest extends Request {

    private static final HuaweiLogger LOG = HuaweiLogger.of(GetNotificationConstraintsRequest.class);

    public GetNotificationConstraintsRequest(HuaweiSupportProvider support) {
        super(support, 10_000);
        this.serviceId = Notifications.id;
        this.commandId = Notifications.NotificationConstraints.id;
    }

    @Override
    protected List<byte[]> createRequest() throws RequestCreationException {
        try {
            return new Notifications.NotificationConstraints.Request(paramsProvider).serialize();
        } catch (HuaweiPacket.CryptoException e) {
            throw new RequestCreationException(e);
        }
    }

    @Override
    protected void processResponse() throws ResponseParseException {
        if (!(receivedPacket instanceof Notifications.NotificationConstraints.Response)) {
            throw new ResponseTypeMismatchException(receivedPacket,
                    Notifications.NotificationConstraints.Response.class);
        }
        Notifications.NotificationConstraints.Response response =
                (Notifications.NotificationConstraints.Response) receivedPacket;
        // contentFormat(2B) + contentLength(2B)：UTF-8/UTF-16 可容纳的最大字节数。
        response.constraints.position(Notifications.NotificationConstraintsType.contentLength);
        int limit = response.constraints.getShort() & 0xFFFF;
        LOG.debug("NotificationConstraints contentLength = {}", limit);
        supportProvider.noteNotificationContentLimit(limit);
    }
}
