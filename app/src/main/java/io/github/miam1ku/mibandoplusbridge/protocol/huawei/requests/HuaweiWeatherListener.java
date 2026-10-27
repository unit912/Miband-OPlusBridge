// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests;

import io.github.miam1ku.mibandoplusbridge.data.HuaweiLogger;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.Weather;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.service.HuaweiSupportProvider;
import java.io.IOException;

/**
 * 天气（0x0F）常驻监听器：接管手表主动发来的 {@code WeatherDeviceRequest}(0x04)。
 *
 * <p>对应 Gadgetbridge 的 {@code HuaweiWeatherManager.handleAsyncMessage}。
 *
 * <p><b>为什么必须有它</b>：华为手表是天气同步的<b>主动方</b>。打开表上的天气
 * App 时，手表发 0x0F/0x04，TLV {@code 0x7f == 0x000186AA} 表示"我要天气"。
 * 手机必须回应这条请求（回 OK，或紧接着推一整条天气链），手表才会退出
 * 「正在刷新」态。桥接之前没有任何常驻 handler 认领它，日志里表现为
 * {@code service 0xf command 0x4: unsolicited, no handler} —— 手表干等到超时，
 * 于是显示「天气暂不可用」。
 *
 * <p>与 {@link HuaweiDataSyncListener} 同理：这是手表<b>主动推送</b>的帧，没有
 * 「请求—响应」配对关系，所以 {@link #autoRemoveFromResponseHandler()} 返回
 * {@code false} 永不摘除，且 {@code addToResponse = false}、不带超时线程。
 */
public class HuaweiWeatherListener extends Request {

    private static final HuaweiLogger LOG = HuaweiLogger.of(HuaweiWeatherListener.class);

    /** 手表在 0x7f 里写这个值表示「请给我天气」。 */
    private static final int REQUEST_WEATHER = 0x000186AA;
    /** 其余情况回给手表的确认值。 */
    private static final int RESPONSE_OK = 0x000186A0;

    /** 天气数据的提供方；{@code null} 时一律只回 OK。 */
    public interface Handler {
        /** 返回 {@code true} 表示已接管并正在推送天气，本监听器不再回 OK。 */
        boolean onWeatherRequested();
    }

    private final Handler handler;

    public HuaweiWeatherListener(HuaweiSupportProvider support, Handler handler) {
        // timeoutMillis = 0 → Request 构造不会创建超时线程。
        super(support);
        this.serviceId = Weather.id;
        this.commandId = Weather.WeatherDeviceRequest.id;
        this.addToResponse = false;
        this.handler = handler;
    }

    /** 常驻监听：处理完一帧继续等下一帧。 */
    @Override
    public boolean autoRemoveFromResponseHandler() {
        return false;
    }

    @Override
    protected void processResponse() throws ResponseParseException {
        HuaweiPacket packet = receivedPacket;
        int code = packet == null || packet.getTlv() == null
                ? -1 : packet.getTlv().getInteger(0x7f, -1);
        LOG.info("weather device request code=0x{}", Integer.toHexString(code));

        boolean taken = false;
        if (code == REQUEST_WEATHER && handler != null) {
            try {
                taken = handler.onWeatherRequested();
            } catch (RuntimeException e) {
                LOG.error("weather handler failed", e);
            }
        }
        if (taken) return;

        // 没要天气，或没有可用天气数据：按官方做法回一条 OK，让手表退出刷新态。
        try {
            new DeviceAck(supportProvider).doPerform();
        } catch (IOException e) {
            LOG.error("could not ack weather device request", e);
        }
    }

    /** 回给手表的 0x0F/0x04 确认包（fire-and-forget，无响应）。 */
    public static final class DeviceAck extends Request {
        private DeviceAck(HuaweiSupportProvider support) {
            super(support);
            this.serviceId = Weather.id;
            this.commandId = Weather.WeatherDeviceRequest.id;
            this.addToResponse = false;
        }

        @Override
        protected java.util.List<byte[]> createRequest() throws RequestCreationException {
            try {
                return new Weather.WeatherDeviceRequest(paramsProvider, RESPONSE_OK).serialize();
            } catch (HuaweiPacket.CryptoException e) {
                throw new RequestCreationException(e);
            }
        }
    }
}
