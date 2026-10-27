// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests;

import io.github.miam1ku.mibandoplusbridge.data.HuaweiLogger;
import io.github.miam1ku.mibandoplusbridge.protocol.BandWeatherEncoder;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.Weather;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.service.HuaweiSupportProvider;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 华为天气推送链：把一份 OHealth 天气快照按 Gadgetbridge 的官方顺序推给手表。
 *
 * <p>对应 Gadgetbridge 的 {@code HuaweiWeatherManager.sendWeather}。
 *
 * <p><b>顺序不可改</b>。手表是状态机：必须先 {@code WeatherStart}(0x09) 开会话，
 * 再依次问能力位 {@code WeatherUnit}(0x05)、{@code WeatherSupport}(0x02)、
 * {@code WeatherExtendedSupport}(0x06)、{@code WeatherSunMoonSupport}(0x0A)，
 * <b>把每一步手表回的能力位图读回来填进 {@link Weather.Settings}</b>，然后才发
 * {@code CurrentWeather}(0x01) 与 {@code WeatherForecastData}(0x08)。
 *
 * <p>桥接之前的 {@code sendHuaweiWeather} 只发了 0x09/0x02/0x01 三段且全部
 * fire-and-forget：能力位图从未被读回（{@code Settings} 是硬编码的），会话也
 * 没有真正建立。报文确实到了传输层，但手表不认，于是显示「天气暂不可用」。
 */
public final class HuaweiWeatherChain {

    private static final HuaweiLogger LOG = HuaweiLogger.of(HuaweiWeatherChain.class);

    /** 每一步的响应超时。手表回能力位很快，超时即放弃本次推送，避免整条链挂死。 */
    private static final long STEP_TIMEOUT_MS = 2500L;

    private HuaweiWeatherChain() {}

    /**
     * 推送一份天气快照。
     *
     * @param onDone 整条链正常走完时回调（可为 {@code null}）；任何一步失败都不回调。
     */
    public static void send(HuaweiSupportProvider support, BandWeatherEncoder.Sample sample,
                            Runnable onDone) {
        if (support == null || sample == null) return;
        Weather.Settings settings = new Weather.Settings();
        settings.uvIndexSupported = sample.uvIndex() != null;

        Request first = new StartRequest(support, settings);
        Request last = first;
        last = link(last, new UnitRequest(support, sample));
        last = link(last, new SupportRequest(support, settings));
        last = link(last, new ExtendedSupportRequest(support, settings));
        last = link(last, new SunMoonSupportRequest(support, settings));
        last = link(last, new CurrentRequest(support, settings, sample));
        last = link(last, new ForecastRequest(support, settings, sample));

        Request.RequestCallback finalizer = new Request.RequestCallback() {
            @Override
            public void call() {
                LOG.info("weather chain delivered");
                if (onDone != null) {
                    try {
                        onDone.run();
                    } catch (RuntimeException ignored) { }
                }
            }

            @Override
            public void handleException(Request.ResponseParseException e) {
                LOG.error("weather chain aborted: {}", e.getMessage());
            }
        };
        last.setFinalizeReq(finalizer);

        try {
            first.doPerform();
        } catch (IOException e) {
            LOG.error("failed to start weather chain", e);
        }
    }

    /**
     * 手表要天气但没有可用快照：按官方做法回一条「天气已禁用」。
     *
     * <p>直接不回应会让手表一直停在「正在刷新」；回错误码它会显示不可用。
     */
    public static void sendUnavailable(HuaweiSupportProvider support) {
        if (support == null) return;
        try {
            SendNotificationRequest.send(support, new Weather.WeatherErrorSimple.Request(
                    support.getParamsProvider(), Weather.ErrorCode.WEATHER_DISABLED)).doPerform();
        } catch (IOException e) {
            LOG.error("failed to send weather error", e);
        }
    }

    private static Request link(Request previous, Request next) {
        previous.nextRequest(next);
        return next;
    }

    /** 链上每一步的公共外壳：构造报文字节、等响应。 */
    private abstract static class StepRequest extends Request {
        StepRequest(HuaweiSupportProvider support, byte serviceId, byte commandId) {
            super(support, STEP_TIMEOUT_MS);
            this.serviceId = serviceId;
            this.commandId = commandId;
        }

        @Override
        protected List<byte[]> createRequest() throws RequestCreationException {
            HuaweiPacket packet = packet();
            if (packet == null) return null;
            try {
                return packet.serialize();
            } catch (HuaweiPacket.CryptoException e) {
                throw new RequestCreationException(e);
            }
        }

        /** 本步要写出的报文；{@code null} 表示跳过（不产生帧，链继续）。 */
        protected abstract HuaweiPacket packet();
    }

    /** 0x0F/0x09 开天气会话，并读回 successCode（0x186A0 / 0x186A3 为成功）。 */
    private static final class StartRequest extends StepRequest {
        StartRequest(HuaweiSupportProvider support, Weather.Settings settings) {
            super(support, Weather.id, Weather.WeatherStart.id);
        }

        @Override
        protected HuaweiPacket packet() {
            return new Weather.WeatherStart.Request(paramsProvider);
        }

        @Override
        protected void processResponse() throws ResponseParseException {
            if (receivedPacket instanceof Weather.WeatherStart.Response) {
                Weather.WeatherStart.Response resp = (Weather.WeatherStart.Response) receivedPacket;
                LOG.info("weather start successCode=0x{} success={}",
                        Integer.toHexString(resp.successCode), resp.success);
            }
        }
    }

    /** 0x0F/0x05 温标。 */
    private static final class UnitRequest extends StepRequest {
        private final BandWeatherEncoder.Sample sample;

        UnitRequest(HuaweiSupportProvider support, BandWeatherEncoder.Sample sample) {
            super(support, Weather.id, Weather.WeatherUnitRequest.id);
            this.sample = sample;
        }

        @Override
        protected HuaweiPacket packet() {
            return new Weather.WeatherUnitRequest(paramsProvider,
                    unit(sample));
        }
    }

    /** 0x0F/0x02 基础能力位图 → {@link Weather.Settings}。 */
    private static final class SupportRequest extends StepRequest {
        private final Weather.Settings settings;

        SupportRequest(HuaweiSupportProvider support, Weather.Settings settings) {
            super(support, Weather.id, Weather.WeatherSupport.id);
            this.settings = settings;
        }

        @Override
        protected HuaweiPacket packet() {
            return new Weather.WeatherSupport.Request(paramsProvider);
        }

        @Override
        protected void processResponse() throws ResponseParseException {
            if (!(receivedPacket instanceof Weather.WeatherSupport.Response)) return;
            Weather.WeatherSupport.Response r = (Weather.WeatherSupport.Response) receivedPacket;
            settings.weatherSupported = r.weatherSupported;
            settings.windSupported = r.windSupported;
            settings.pm25Supported = r.pm25Supported;
            settings.temperatureSupported = r.temperatureSupported;
            settings.locationNameSupported = r.locationNameSupported;
            settings.currentTemperatureSupported = r.currentTemperatureSupported;
            settings.unitSupported = r.unitSupported;
            settings.airQualityIndexSupported = r.airQualityIndexSupported;
            LOG.info("weather support bitmap=0x{}", Integer.toHexString(r.supportedBitmap & 0xFF));
        }
    }

    /** 0x0F/0x06 扩展能力位图（时间/来源/天气图标）。 */
    private static final class ExtendedSupportRequest extends StepRequest {
        private final Weather.Settings settings;

        ExtendedSupportRequest(HuaweiSupportProvider support, Weather.Settings settings) {
            super(support, Weather.id, Weather.WeatherExtendedSupport.id);
            this.settings = settings;
        }

        @Override
        protected HuaweiPacket packet() {
            return new Weather.WeatherExtendedSupport.Request(paramsProvider);
        }

        @Override
        protected void processResponse() throws ResponseParseException {
            if (!(receivedPacket instanceof Weather.WeatherExtendedSupport.Response)) return;
            Weather.WeatherExtendedSupport.Response r =
                    (Weather.WeatherExtendedSupport.Response) receivedPacket;
            settings.timeSupported = r.timeSupported;
            settings.sourceSupported = r.sourceSupported;
            settings.weatherIconSupported = r.weatherIconSupported;
            LOG.info("weather extended support bitmap=0x{}",
                    Integer.toHexString(r.supportedBitmap & 0xFFFF));
        }
    }

    /** 0x0F/0x0A 日月能力位图。 */
    private static final class SunMoonSupportRequest extends StepRequest {
        private final Weather.Settings settings;

        SunMoonSupportRequest(HuaweiSupportProvider support, Weather.Settings settings) {
            super(support, Weather.id, Weather.WeatherSunMoonSupport.id);
            this.settings = settings;
        }

        @Override
        protected HuaweiPacket packet() {
            return new Weather.WeatherSunMoonSupport.Request(paramsProvider);
        }

        @Override
        protected void processResponse() throws ResponseParseException {
            if (!(receivedPacket instanceof Weather.WeatherSunMoonSupport.Response)) return;
            Weather.WeatherSunMoonSupport.Response r =
                    (Weather.WeatherSunMoonSupport.Response) receivedPacket;
            settings.sunRiseSetSupported = r.sunRiseSetSupported;
            settings.moonPhaseSupported = r.moonPhaseSupported;
            LOG.info("weather sun/moon support bitmap=0x{}",
                    Integer.toHexString(r.supportedBitmap));
        }
    }

    /** 0x0F/0x01 当前天气。 */
    private static final class CurrentRequest extends StepRequest {
        private final Weather.Settings settings;
        private final BandWeatherEncoder.Sample sample;

        CurrentRequest(HuaweiSupportProvider support, Weather.Settings settings,
                       BandWeatherEncoder.Sample sample) {
            super(support, Weather.id, Weather.CurrentWeatherRequest.id);
            this.settings = settings;
            this.sample = sample;
        }

        @Override
        protected HuaweiPacket packet() {
            Weather.WeatherIcon icon = fromOHealth(sample.conditionCode());
            Byte low = null, high = null;
            if (!sample.daily().isEmpty()) {
                BandWeatherEncoder.Day day = sample.daily().get(0);
                low = (byte) Math.round(day.minTemp());
                high = (byte) Math.round(day.maxTemp());
            }
            Short aqi = sample.aqi() != null ? sample.aqi().shortValue() : null;
            Float uv = sample.uvIndex() != null ? sample.uvIndex().floatValue() : null;
            Byte humidity = sample.humidityPercent() != null
                    ? sample.humidityPercent().byteValue() : null;
            Integer windDirection = sample.windDegree();
            Integer windSpeedValue = sample.windPower();
            Byte windSpeed = windSpeedValue != null ? windSpeedValue.byteValue() : null;
            String location = sample.locationName() == null || sample.locationName().isBlank()
                    ? sample.cityName() : sample.locationName();
            return new Weather.CurrentWeatherRequest(paramsProvider, settings, icon,
                    windDirection, windSpeed, low, high, null, location,
                    (byte) Math.round(sample.temperature()), unit(sample), aqi,
                    (int) (sample.publishedAtMs() / 1000L), uv, "OHealth",
                    humidity, windSpeedValue, null);
        }
    }

    /** 0x0F/0x08 逐时 + 逐日预报（分片包由 {@code serialize()} 自动切）。 */
    private static final class ForecastRequest extends StepRequest {
        private final Weather.Settings settings;
        private final BandWeatherEncoder.Sample sample;

        ForecastRequest(HuaweiSupportProvider support, Weather.Settings settings,
                        BandWeatherEncoder.Sample sample) {
            super(support, Weather.id, Weather.WeatherForecastData.id);
            this.settings = settings;
            this.sample = sample;
        }

        @Override
        protected HuaweiPacket packet() {
            List<Weather.WeatherForecastData.TimeData> hours = new ArrayList<>();
            for (BandWeatherEncoder.Hour hour : sample.hourly()) {
                Weather.WeatherForecastData.TimeData data =
                        new Weather.WeatherForecastData.TimeData();
                data.timestamp = (int) (hour.timeMs() / 1000L);
                data.icon = fromOHealth(hour.conditionCode());
                data.temperature = (byte) Math.round(hour.temperature());
                hours.add(data);
                if (hours.size() == 24) break;
            }

            List<Weather.WeatherForecastData.DayData> days = new ArrayList<>();
            long dayStart = utcDayStart(System.currentTimeMillis());
            for (int i = 0; i < sample.daily().size(); i++) {
                BandWeatherEncoder.Day day = sample.daily().get(i);
                long from = dayStart + i * 86_400_000L;
                long to = from + 86_400_000L;
                Weather.WeatherForecastData.DayData data =
                        new Weather.WeatherForecastData.DayData();
                data.timestamp = (int) (day.timeMs() / 1000L);
                data.icon = fromOHealth(day.dayCode());
                data.highTemperature = (byte) Math.round(day.maxTemp());
                data.lowTemperature = (byte) Math.round(day.minTemp());
                // ★ 见 fixup：日出/日落不在当天区间内会让手表完全不显示天气，
                //   越界的必须归零（归零后该标签不会被写入报文）。
                data.sunriseTime = withinDay(day.sunriseMs(), from, to);
                data.sunsetTime = withinDay(day.sunsetMs(), from, to);
                days.add(data);
                if (days.size() == 7) break;
            }

            if (hours.isEmpty() && days.isEmpty()) return null;
            return new Weather.WeatherForecastData.Request(paramsProvider, settings, hours, days);
        }
    }

    /** 当天 00:00 UTC 的纪元毫秒；与 Gadgetbridge 的 {@code dayStart(todayUTC())} 一致。 */
    private static long utcDayStart(long nowMs) {
        return (nowMs / 86_400_000L) * 86_400_000L;
    }

    /** 落在 [from, to) 内返回秒级时间戳，否则返回 0（报文里该标签会被跳过）。 */
    private static Integer withinDay(Long ms, long from, long to) {
        if (ms == null || ms <= from || ms > to) return 0;
        return (int) (ms / 1000L);
    }

    /**
     * OHealth 天气码（0..71）映射到华为天气图标枚举。
     *
     * <p>与小米分支的 {@code WeatherConditionMap} 不是同一套编码，不能复用；
     * 未覆盖的归为 {@code UNKNOWN}（{@code 0x63}），比猜一个错的图标安全。
     */
    public static Weather.WeatherIcon fromOHealth(int code) {
        return switch (code) {
            case 1, 6, 7 -> Weather.WeatherIcon.SUNNY;
            case 2, 3, 4, 5 -> Weather.WeatherIcon.CLOUDY;
            case 8 -> Weather.WeatherIcon.OVERCAST;
            case 9, 10, 11 -> Weather.WeatherIcon.LIGHT_RAIN;
            case 12, 13 -> Weather.WeatherIcon.RAIN;
            case 14, 15, 16, 30, 31, 32, 33, 34 -> Weather.WeatherIcon.HEAVY_RAIN;
            case 17 -> Weather.WeatherIcon.RAIN_STORM;
            case 18, 19, 20 -> Weather.WeatherIcon.THUNDERSTORMS;
            case 22, 23, 24, 25 -> Weather.WeatherIcon.SHOWERS;
            case 26 -> Weather.WeatherIcon.SNOW;
            case 27, 28 -> Weather.WeatherIcon.LIGHT_SNOW;
            case 29 -> Weather.WeatherIcon.SLEET;
            case 37 -> Weather.WeatherIcon.WINDY;
            case 38, 39, 40, 50, 51, 52, 53 -> Weather.WeatherIcon.HAZY;
            case 41, 42, 43, 44 -> Weather.WeatherIcon.SAND;
            case 45, 46, 47 -> Weather.WeatherIcon.DUST_STORM;
            case 48 -> Weather.WeatherIcon.FOG;
            case 49 -> Weather.WeatherIcon.HAZY;
            case 54, 55, 68 -> Weather.WeatherIcon.SUNNY;
            case 56, 57, 58, 69 -> Weather.WeatherIcon.CLOUDY;
            case 59 -> Weather.WeatherIcon.OVERCAST;
            case 60, 61, 62, 63, 64, 65, 66, 67 -> Weather.WeatherIcon.SNOWSTORMS;
            default -> Weather.WeatherIcon.UNKNOWN;
        };
    }

    private static Weather.HuaweiTemperatureFormat unit(BandWeatherEncoder.Sample sample) {
        return "f".equals(sample.unit())
                ? Weather.HuaweiTemperatureFormat.FAHRENHEIT
                : Weather.HuaweiTemperatureFormat.CELSIUS;
    }
}
