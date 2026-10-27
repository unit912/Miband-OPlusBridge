// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests;

import java.util.ArrayList;
import java.util.List;

import io.github.miam1ku.mibandoplusbridge.data.HuaweiLogger;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.FitnessData;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.service.HuaweiSupportProvider;

/**
 * 拉取睡眠记录（FitnessData 0x07，两阶段：0x0C 问条数 → 0x0D 逐条取）。
 *
 * <p>本类的语义全部来自真机探针 + Gadgetbridge 上游实现，协议本身没有任何文档：
 *
 * <ul>
 *   <li><b>0x0D 的 count 是「窗口内第几条」，不是「要几条」。</b>窗口由最近一次
 *       0x0C 决定，记录在窗口内按时间从早到晚编号，GT3 上实测为 1-based。
 *       真机：24h 窗口得到 count=4，取 1 是 08:44 那段、取 3 是 13:53 那段。</li>
 *   <li><b>曾经假设「最后一条编号是尚未结束的一段」，只取 1 … count-1。</b>
 *       但真机实测（2026-10-23）整夜 5.7h 睡眠恰恰落在最后一条（count=5 时的第 5 条），
 *       按旧逻辑整场被丢弃、OPPO 睡眠页永远缺这一夜。现改为<b>总是取满 1 … count</b>，
 *       {@link io.github.miam1ku.mibandoplusbridge.data.HuaweiSleepImporter#parse} 已把
 *       {@code startSec <= 0} 的空壳段直接跳过，所以最后一条若真是未结束空壳也只是多拉一条
 *       无害数据，若为已完成睡眠则正好找回（如整夜）。Gadgetbridge 用 0-based 编到
 *       {@code count-1} 取满 count 条，与本实现殊途同归。</li>
 *   <li><b>每条记录是一串 0x83 子容器，每个子容器 = 一段睡眠分期。</b>
 *       6 字节时间戳是「前 4 字节起始秒 + 后 2 字节时长，单位分钟」
 *       （Gadgetbridge {@code GetSleepDataRequest}：
 *       {@code durationInt = (b[4] << 8) + b[5]; duration = durationInt * 60}）。
 *       type 是睡眠分期，取值与 HarmonyOS 5 的 SequenceData 分期同源：
 *       1 浅睡 / 2 REM / 3 深睡 / 4 睡中清醒 / 5 小睡。</li>
 * </ul>
 *
 * <p>所有记录会被合并成一个分段列表一次性回传给调用方 —— 跨记录的睡眠段属于
 * 同一次睡眠（例如上午一段、中午一段），只有合并后才能还原完整夜间结构。
 */
public class GetSleepDataRequest extends Request {

    private static final HuaweiLogger LOG = HuaweiLogger.of(GetSleepDataRequest.class);

    /** 两阶段各自的等待上限。 */
    private static final long TIMEOUT_MS = 15000;

    /** 睡眠记录回调。 */
    public interface SleepListener {
        /**
         * @param total      窗口内记录总条数
         * @param containers 窗口内所有记录合并后的睡眠分段；无数据时为空列表
         */
        void onSleep(int total,
                List<FitnessData.MessageData.SleepResponse.SubContainer> containers);

        void onError(String message);
    }

    private final int startSec;
    private final int endSec;
    private final SleepListener listener;

    /** 第二阶段专用：要取的记录编号。{@code < 0} 表示本实例处于第一阶段。 */
    private final int knownIndex;

    /** 第二阶段专用：第一阶段问到的总条数。 */
    private final int knownTotal;

    /** 第二阶段专用：跨实例累积的分段（每条记录一个实例，必须靠它跨请求传递）。 */
    private final List<FitnessData.MessageData.SleepResponse.SubContainer> collected;

    /** 第一阶段：查时间窗内的条数。 */
    public GetSleepDataRequest(HuaweiSupportProvider support, int startSec, int endSec,
            SleepListener listener) {
        this(support, startSec, endSec, listener, -1, 0, null);
    }

    private GetSleepDataRequest(HuaweiSupportProvider support, int startSec, int endSec,
            SleepListener listener, int knownIndex, int knownTotal,
            List<FitnessData.MessageData.SleepResponse.SubContainer> collected) {
        super(support, TIMEOUT_MS);
        this.serviceId = FitnessData.id;
        this.commandId = knownIndex < 0
                ? FitnessData.MessageCount.sleepId
                : FitnessData.MessageData.sleepId;
        this.startSec = startSec;
        this.endSec = endSec;
        this.listener = listener;
        this.knownIndex = knownIndex;
        this.knownTotal = knownTotal;
        this.collected = collected;
        // Request 自身没有 handleException 入口，失败一律经 finalizeReq 上报。
        setFinalizeReq(new RequestCallback(support) {
            @Override
            public void call(Request request) {
                // 成功路径已在 processResponse() 里回调过。
            }

            @Override
            public void handleException(Request request, ResponseParseException e) {
                LOG.error("SleepData request failed", e);
                if (listener != null) {
                    listener.onError(e.getMessage());
                }
            }
        });
    }

    @Override
    protected List<byte[]> createRequest() throws RequestCreationException {
        try {
            if (knownIndex < 0) {
                return new FitnessData.MessageCount.Request(
                        paramsProvider, FitnessData.MessageCount.sleepId, startSec, endSec)
                        .serialize();
            }
            return new FitnessData.MessageData.Request(
                    paramsProvider, FitnessData.MessageData.sleepId, (short) knownIndex)
                    .serialize();
        } catch (HuaweiPacket.CryptoException e) {
            throw new RequestCreationException(e);
        }
    }

    @Override
    protected void processResponse() throws ResponseParseException {
        if (knownIndex < 0) {
            if (!(receivedPacket instanceof FitnessData.MessageCount.Response)) {
                throw new ResponseTypeMismatchException(receivedPacket,
                        FitnessData.MessageCount.Response.class);
            }
            FitnessData.MessageCount.Response response =
                    (FitnessData.MessageCount.Response) receivedPacket;
            int count = response.count & 0xFFFF;
            LOG.info("SleepData count={} window=[{}, {}]", count, startSec, endSec);
            if (count <= 0) {
                // 真·零条：窗口内没有任何睡眠，无需发起第二阶段。
                if (listener != null) {
                    listener.onSleep(count, new ArrayList<>());
                }
                return;
            }
            LOG.info("SleepData requesting record 1 of {}", count);
            nextRequest(new GetSleepDataRequest(supportProvider, startSec, endSec, listener,
                    1, count, new ArrayList<>()));
            return;
        }

        if (!(receivedPacket instanceof FitnessData.MessageData.SleepResponse)) {
            throw new ResponseTypeMismatchException(receivedPacket,
                    FitnessData.MessageData.SleepResponse.class);
        }
        FitnessData.MessageData.SleepResponse response =
                (FitnessData.MessageData.SleepResponse) receivedPacket;
        List<FitnessData.MessageData.SleepResponse.SubContainer> segments =
                response.containers == null ? new ArrayList<>() : response.containers;
        List<FitnessData.MessageData.SleepResponse.SubContainer> all =
                collected == null ? new ArrayList<>() : collected;
        all.addAll(segments);
        LOG.info("SleepData record={}/{} segments={}", knownIndex, knownTotal, segments.size());

        // 还剩记录没取：串下一个实例。注意不能用同一个实例重复 perform --
        // timeoutWatcher 不能 start 两次。末条（knownIndex == knownTotal）也要取：
        // 整夜睡眠常落在最后一条，旧逻辑 knownIndex+1<knownTotal 会把它整场丢弃。
        // 末条若是未结束空壳，parse() 会按零时间戳过滤，不会引入脏数据。
        if (knownIndex + 1 <= knownTotal) {
            LOG.info("SleepData requesting record {} of {}", knownIndex + 1, knownTotal);
            nextRequest(new GetSleepDataRequest(supportProvider, startSec, endSec, listener,
                    knownIndex + 1, knownTotal, all));
            return;
        }
        if (listener != null) {
            listener.onSleep(knownTotal, all);
        }
    }
}
