// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests;

import java.util.ArrayList;
import java.util.List;

import io.github.miam1ku.mibandoplusbridge.data.HuaweiLogger;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.FitnessData;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.service.HuaweiSupportProvider;

/**
 * 拉取步数分钟明细（FitnessData 0x07，两阶段：先问条数，再逐包取数据）。
 *
 * <p>华为的健身数据不是「问一次给全部」，而是两问：
 * <ol>
 *   <li>{@code MessageCount}（命令 0x0A）：给时间窗 [start, end]，手表回「这段
 *       时间里有几条记录」；</li>
 *   <li>{@code MessageData}（命令 0x0B）：给条数，手表回一包
 *       {@code StepResponse}，里面每条记录带 steps / calories / distance /
 *       heartrate / spo。</li>
 * </ol>
 *
 * <p><b>为什么不复用同一实例两次 doPerform</b>：{@code performConnected()} 会
 * {@code timeoutWatcher.start()}，而一个 Thread 只能 start 一次 —— 第二阶段复用
 * 实例会直接抛 IllegalThreadStateException。因此第二阶段是一个<b>新实例</b>，
 * 通过 {@code nextRequest} 挂在第一阶段后面，由框架在收到条数后自动拉起。
 *
 * <p><b>一条 0x0B 记录只覆盖 48 分钟</b>（真机：24h 窗口回 27~30 条，第 N 条的
 * 基准时间戳是 {@code base + (N-1) * 2880} 秒，2880 秒 = 48 分钟）。要拿整天曲线
 * 就得把窗口里每条记录都取一遍 —— {@code full} 开关就是干这个的：沿编号串一整条
 * 请求链，把分钟样本累积起来一次性回传。心跳这种只要「最近一分钟」的场合用
 * {@code full = false}，一次只取第一批，省掉 27 次往返。
 *
 * <p>条数为 0 时不再发第二阶段（手表对空窗口的 0x0B 不保证有响应），直接以空
 * 列表回调，让上层能区分「真的没数据」和「请求失败」。
 */
public class GetFitnessDataRequest extends Request {

    private static final HuaweiLogger LOG = HuaweiLogger.of(GetFitnessDataRequest.class);

    /** 两阶段各自的等待上限。数据拉取比握手慢，给足 15 秒。 */
    private static final long TIMEOUT_MS = 15000;

    /** 单批请求条数上限。GT3 对一次 25 条的请求直接回错误码 0x0001A1F9。 */
    private static final int BATCH = 1;

    /** 步数样本回调。 */
    public interface StepsListener {
        void onSteps(List<FitnessData.MessageData.StepResponse.SubContainer> samples);

        void onError(String message);
    }

    private final int startSec;
    private final int endSec;
    private final StepsListener listener;

    /** 取满整窗（{@code true}）还是只取第一批（{@code false}）。 */
    private final boolean full;

    /** 第二阶段专用：本次要取的记录编号。{@code < 0} 表示本实例处于第一阶段。 */
    private final short knownIndex;

    /** 第二阶段专用：第一阶段问到的总条数。 */
    private final short knownTotal;

    /** 第二阶段专用：跨实例累积的分钟样本。每条记录一个实例，只能靠它传递。 */
    private final List<FitnessData.MessageData.StepResponse.SubContainer> collected;

    /** 第一阶段：查时间窗内的条数，只取第一批。 */
    public GetFitnessDataRequest(HuaweiSupportProvider support, int startSec, int endSec,
            StepsListener listener) {
        this(support, startSec, endSec, listener, false);
    }

    /** 第一阶段：查时间窗内的条数。{@code full} 为真时逐条取满整窗。 */
    public GetFitnessDataRequest(HuaweiSupportProvider support, int startSec, int endSec,
            StepsListener listener, boolean full) {
        this(support, startSec, endSec, listener, full, (short) -1, (short) 0, null);
    }

    private GetFitnessDataRequest(HuaweiSupportProvider support, int startSec, int endSec,
            StepsListener listener, boolean full, short knownIndex, short knownTotal,
            List<FitnessData.MessageData.StepResponse.SubContainer> collected) {
        super(support, TIMEOUT_MS);
        this.serviceId = FitnessData.id;
        this.commandId = knownIndex < 0
                ? FitnessData.MessageCount.stepId
                : FitnessData.MessageData.stepId;
        this.startSec = startSec;
        this.endSec = endSec;
        this.listener = listener;
        this.full = full;
        this.knownIndex = knownIndex;
        this.knownTotal = knownTotal;
        this.collected = collected;
        // 错误只能经 finalizeReq 上报：Request 自身没有 handleException 入口，
        // 失败路径（TLV 解析失败 / 超时 / 链路断开）全部汇总到 RequestCallback。
        setFinalizeReq(new RequestCallback(support) {
            @Override
            public void call(Request request) {
                // 成功路径已在 processResponse() 里回调过样本，这里无需重复。
            }

            @Override
            public void handleException(Request request, ResponseParseException e) {
                LOG.error("FitnessData request failed", e);
                if (listener == null) return;
                // 串链中途断掉时把已经取到的分钟样本交出去：整窗 30 条里丢一条，
                // 不该让前面 29 条白跑。
                if (collected != null && !collected.isEmpty()) {
                    LOG.warn("FitnessData partial: {} minutes before failure", collected.size());
                    listener.onSteps(collected);
                    return;
                }
                listener.onError(e.getMessage());
            }
        });
    }

    @Override
    protected List<byte[]> createRequest() throws RequestCreationException {
        try {
            if (knownIndex < 0) {
                return new FitnessData.MessageCount.Request(
                        paramsProvider, FitnessData.MessageCount.stepId, startSec, endSec)
                        .serialize();
            }
            return new FitnessData.MessageData.Request(
                    paramsProvider, FitnessData.MessageData.stepId, knownIndex)
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
            LOG.info("FitnessData count={} window=[{}, {}]", count, startSec, endSec);
            if (count <= 0) {
                if (listener != null) {
                    listener.onSteps(new ArrayList<>());
                }
                return;
            }
            // 挂第二阶段。必须用新实例：同一个 Request 的超时线程不能 start 两次。
            //
            // 一次只取 BATCH 条：真机实测（GT3）一开口要 25 条，手表直接回错误帧
            // 7F 04 00 01 A1 F9（错误码 0x0001A1F9），连 0x81 容器都不给，
            // StepResponse.parseTlv() 因此抛 Missing tag: 81。
            //
            // 最后一条记录不取：和睡眠侧一样，GT3 把「当前还没写完的一段」也算进
            // 条数，取它拿到的是解析不了的响应（真机：24h 窗口 31 条，第 31 条
            // 直接 parse TLV failed）。
            short total = (short) Math.max(1, count - 1);
            short batch = (short) Math.min(total, BATCH);
            LOG.info("FitnessData requesting {} of {} records in one batch", batch, total);
            nextRequest(new GetFitnessDataRequest(supportProvider, startSec, endSec, listener,
                    full, (short) 1, total,
                    full ? new ArrayList<>() : null));
            return;
        }

        if (!(receivedPacket instanceof FitnessData.MessageData.StepResponse)) {
            throw new ResponseTypeMismatchException(receivedPacket,
                    FitnessData.MessageData.StepResponse.class);
        }
        FitnessData.MessageData.StepResponse response =
                (FitnessData.MessageData.StepResponse) receivedPacket;
        List<FitnessData.MessageData.StepResponse.SubContainer> samples =
                response.containers == null ? new ArrayList<>() : response.containers;
        LOG.info("FitnessData record={}/{} samples={} baseTimestamp={}", knownIndex, knownTotal,
                samples.size(), response.timestamp);
        for (FitnessData.MessageData.StepResponse.SubContainer sample : samples) {
            if (sample.parsedDataError != null && !sample.parsedDataError.isEmpty()) {
                LOG.warn("FitnessData sample parse warning: " + sample.parsedDataError);
            }
        }
        if (!full) {
            if (listener != null) {
                listener.onSteps(samples);
            }
            return;
        }
        List<FitnessData.MessageData.StepResponse.SubContainer> all =
                collected == null ? new ArrayList<>() : collected;
        all.addAll(samples);
        if (knownIndex < knownTotal) {
            nextRequest(new GetFitnessDataRequest(supportProvider, startSec, endSec, listener,
                    full, (short) (knownIndex + 1), knownTotal, all));
            return;
        }
        if (listener != null) {
            listener.onSteps(all);
        }
    }
}
