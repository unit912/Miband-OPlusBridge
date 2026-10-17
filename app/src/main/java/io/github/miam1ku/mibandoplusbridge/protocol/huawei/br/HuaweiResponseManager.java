// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol.huawei.br;

import io.github.miam1ku.mibandoplusbridge.data.HuaweiLogger;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.btbr.HuaweiSocketCallback;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests.Request;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把从 socket 读到的字节流重组为 {@link HuaweiPacket}，并分发给在等响应的请求。
 *
 * <p>对应 Gadgetbridge 的 {@code service.devices.huawei.ResponseManager}。
 *
 * <p>两个必须保留的实现细节：
 *
 * <ol>
 *   <li><b>重组状态按通道隔离。</b>主 socket 与每个 aux socket 各有独立读线程，
 *       共用一个半包缓冲区会让一条流的碎片污染另一条正在重组的包（且被静默丢弃）。</li>
 *   <li><b>一次读取可能包含多个粘包。</b>解析后按 {@link HuaweiPacket#getLeft()} 循环
 *       处理剩余部分，直到消费完。</li>
 * </ol>
 *
 * <p>解析失败时丢弃该通道的半包并返回，让下一帧有机会被正确解析 —— 否则一次错位就会
 * 让通道永久卡住。
 */
public final class HuaweiResponseManager {

    private static final HuaweiLogger LOG = HuaweiLogger.of(HuaweiResponseManager.class);

    private final List<Request> handlers = Collections.synchronizedList(new ArrayList<>());
    private final Map<Integer, HuaweiPacket> partials = new HashMap<>();
    private final HuaweiPacket.ParamsProvider paramsProvider;

    public HuaweiResponseManager(HuaweiPacket.ParamsProvider paramsProvider) {
        this.paramsProvider = paramsProvider;
    }

    public void addHandler(Request handler) {
        synchronized (handlers) {
            handlers.add(handler);
        }
    }

    public void removeHandler(Request handler) {
        synchronized (handlers) {
            handlers.remove(handler);
        }
    }

    public void removeHandlers(Class<?> type) {
        synchronized (handlers) {
            handlers.removeIf(request -> request.getClass() == type);
        }
    }

    /** 主 socket 收到数据。 */
    public void handleData(byte[] data) {
        handleData(data, HuaweiSocketCallback.MAIN_CHANNEL);
    }

    public synchronized void handleData(byte[] data, int channel) {
        int left = 0;
        do {
            if (left > 0) {
                data = Arrays.copyOfRange(data, data.length - left, data.length);
            }
            HuaweiPacket packet = partials.get(channel);
            try {
                packet = packet == null
                        ? new HuaweiPacket(paramsProvider).parse(data)
                        : packet.parse(data);
                partials.put(channel, packet);
                left = packet.getLeft();
            } catch (HuaweiPacket.ParseException e) {
                LOG.error("packet parse failed on channel {}", channel, e);
                partials.remove(channel);
                return;
            }

            if (!packet.complete) {
                continue;
            }

            Request handler = null;
            synchronized (handlers) {
                for (Request request : handlers) {
                    if (request.handleResponse(packet)) {
                        handler = request;
                        break;
                    }
                }
            }

            if (handler == null) {
                LOG.debug("service 0x{} command 0x{}: unsolicited, no handler",
                        Integer.toHexString(packet.serviceId & 0xFF),
                        Integer.toHexString(packet.commandId & 0xFF));
            } else {
                LOG.debug("service 0x{} command 0x{}: handled by {}",
                        Integer.toHexString(packet.serviceId & 0xFF),
                        Integer.toHexString(packet.commandId & 0xFF),
                        handler.getClass().getSimpleName());
                if (handler.autoRemoveFromResponseHandler()) {
                    synchronized (handlers) {
                        handlers.remove(handler);
                    }
                }
                handler.handleResponse();
            }
            partials.remove(channel);
        } while (left > 0);
    }

    /** 链路断开时清空所有等待者，避免它们一直等到超时。 */
    public void reset() {
        synchronized (handlers) {
            for (Request request : new ArrayList<>(handlers)) {
                request.onTransportClosed();
            }
            handlers.clear();
        }
        partials.clear();
    }
}