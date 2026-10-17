// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests;

import java.util.List;

import io.github.miam1ku.mibandoplusbridge.data.HuaweiLogger;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.FitnessData;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.service.HuaweiSupportProvider;

/**
 * 拉取总步数/卡路里/距离（FitnessData 0x07 / FitnessTotals 0x03）。
 *
 * <p>与 {@link GetFitnessDataRequest} 的分工：那边拿的是<b>分钟级明细</b>
 * （MessageCount 问条数 → MessageData 取记录，实测 GT3 一次只肯给一小时的
 * 记录，且无活动时每条的 data 是单字节 0x00）；这条命令直接问<b>当日汇总</b>，
 * 一问一答，是接入健康应用时最该先拿的指标。
 *
 * <p>请求体只有一个空 tag 0x01 —— 协议里没有时间参数，手表返回的是它自己
 * 认定的"今天"。
 */
public class GetFitnessTotalsRequest extends Request {

    private static final HuaweiLogger LOG = HuaweiLogger.of(GetFitnessTotalsRequest.class);

    /** 汇总读取的等待上限。 */
    private static final long TIMEOUT_MS = 15000;

    /** 汇总数据回调。 */
    public interface TotalsListener {
        void onTotals(int steps, int calories, int distance);

        void onError(String message);
    }

    private final TotalsListener listener;

    public GetFitnessTotalsRequest(HuaweiSupportProvider support, TotalsListener listener) {
        super(support, TIMEOUT_MS);
        this.serviceId = FitnessData.id;
        this.commandId = FitnessData.FitnessTotals.id;
        this.listener = listener;
        // Request 自身没有 handleException 入口，失败一律经 finalizeReq 上报。
        setFinalizeReq(new RequestCallback(support) {
            @Override
            public void call(Request request) {
                // 成功路径已在 processResponse() 里回调。
            }

            @Override
            public void handleException(Request request, ResponseParseException e) {
                LOG.error("FitnessTotals request failed", e);
                if (listener != null) {
                    listener.onError(e.getMessage());
                }
            }
        });
    }

    @Override
    protected List<byte[]> createRequest() throws RequestCreationException {
        try {
            return new FitnessData.FitnessTotals.Request(paramsProvider).serialize();
        } catch (HuaweiPacket.CryptoException e) {
            throw new RequestCreationException(e);
        }
    }

    @Override
    protected void processResponse() throws ResponseParseException {
        if (!(receivedPacket instanceof FitnessData.FitnessTotals.Response)) {
            throw new ResponseTypeMismatchException(receivedPacket,
                    FitnessData.FitnessTotals.Response.class);
        }
        FitnessData.FitnessTotals.Response response =
                (FitnessData.FitnessTotals.Response) receivedPacket;
        LOG.info("FitnessTotals steps={} calories={} distance={}",
                response.totalSteps, response.totalCalories, response.totalDistance);
        if (listener != null) {
            listener.onTotals(response.totalSteps, response.totalCalories, response.totalDistance);
        }
    }
}
