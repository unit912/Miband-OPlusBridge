// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests;

import java.util.List;

import io.github.miam1ku.mibandoplusbridge.data.HuaweiLogger;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiTLV;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.StringUtils;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.FitnessData;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.service.HuaweiSupportProvider;

/**
 * 诊断用的一次性请求：发出任意已构造好的华为报文，把响应<b>原样</b>打到会话日志。
 *
 * <p>存在的理由：GT3 的健身数据（心率 / 睡眠）在 Gadgetbridge 里只有"能用"的
 * 实现，没有"为什么能用"的说明。0x0A/0x0B 的 {@code count} 到底是「记录条数」
 * 还是「包数」、一次能要几条、返回的是窗口里哪一段 —— 这些只能靠真机问出来。
 * 与其猜完再改正式请求类，不如先用一个能把响应字节原样吐出来的探针把语义问清。
 *
 * <p>用法：{@code new HuaweiProbeRequest(provider, "step-data-5",
 * new FitnessData.MessageData.Request(pp, stepId, (short) 5))}。serviceId /
 * commandId 从报文自身取，响应分发与正式请求完全一致。
 *
 * <p>它不解析业务字段，只负责三件事：打印响应类名、打印顶层 TLV 的十六进制、
 * 对已知响应类型补一行人能读的摘要。
 */
public class HuaweiProbeRequest extends Request {

    private static final HuaweiLogger LOG = HuaweiLogger.of(HuaweiProbeRequest.class);

    /** 探针的等待上限。与正式数据请求一致，给足 15 秒。 */
    private static final long TIMEOUT_MS = 15000;

    private final String label;
    private final HuaweiPacket outgoing;

    /** 本探针结束后要做的事。成功与失败都要调用 —— 见 {@link #finish(Runnable)}。 */
    private Runnable after;

    public HuaweiProbeRequest(HuaweiSupportProvider support, String label, HuaweiPacket outgoing) {
        super(support, TIMEOUT_MS);
        this.serviceId = outgoing.serviceId;
        this.commandId = outgoing.commandId;
        this.label = label;
        this.outgoing = outgoing;
        // 探针失败不能影响链路：只在日志里留一行，绝不向上抛。
        setFinalizeReq(new RequestCallback(support) {
            @Override
            public void call(Request request) {
                fireAfter();
            }

            @Override
            public void handleException(Request request, ResponseParseException e) {
                LOG.warn("PROBE " + label + " FAILED " + e.getMessage());
                fireAfter();
            }
        });
    }

    /**
     * 设置探针结束回调（成功、失败、超时都会触发一次）。
     *
     * <p>探针序列不能靠 {@code nextRequest} 串链：{@link Request#handleResponse()}
     * 在 TLV 解析失败时直接走 {@code handleException} 并 return，<b>不会</b>
     * 调 {@code handleNext()} —— 一旦某一步撞上手表的错误帧
     * （如要太多条时的 {@code 7F 04 00 01 A1 F9}），整条链就断了，
     * 后面的探测全部不执行。而"某一步必然失败"恰恰是探针要问的事情之一。
     */
    public HuaweiProbeRequest finish(Runnable after) {
        this.after = after;
        return this;
    }

    private void fireAfter() {
        Runnable next = after;
        after = null;
        if (next != null) next.run();
    }

    @Override
    protected List<byte[]> createRequest() throws RequestCreationException {
        try {
            return outgoing.serialize();
        } catch (HuaweiPacket.CryptoException e) {
            throw new RequestCreationException(e);
        }
    }

    @Override
    protected void processResponse() throws ResponseParseException {
        String type = receivedPacket == null ? "null" : receivedPacket.getClass().getSimpleName();
        HuaweiTLV tlv = receivedPacket == null ? null : receivedPacket.getTlv();
        StringBuilder dump = new StringBuilder();
        if (tlv != null) {
            for (HuaweiTLV.TLV item : tlv.get()) {
                dump.append(item.toString());
            }
        } else if (receivedPacket != null && receivedPacket.getPayloadForDiagnostics() != null) {
            dump.append("raw=")
                    .append(StringUtils.bytesToHex(receivedPacket.getPayloadForDiagnostics()));
        } else {
            dump.append("<empty>");
        }
        // 顶层 TLV 直接展开：嵌套容器（0x81/0x83/0x84）只打印字节，
        // 语义由下面的摘要行给出，避免日志被十六进制淹没。
        LOG.info("PROBE {} type={} tlv={}", label, type, dump.toString());
        LOG.info("PROBE {} summary={}", label, summarize());
    }

    private String summarize() {
        if (receivedPacket instanceof FitnessData.MessageCount.Response) {
            FitnessData.MessageCount.Response r =
                    (FitnessData.MessageCount.Response) receivedPacket;
            return "count=" + (r.count & 0xFFFF);
        }
        if (receivedPacket instanceof FitnessData.MessageData.StepResponse) {
            FitnessData.MessageData.StepResponse r =
                    (FitnessData.MessageData.StepResponse) receivedPacket;
            StringBuilder out = new StringBuilder();
            out.append("number=").append(r.number)
                    .append(" baseTs=").append(r.timestamp)
                    .append(" containers=").append(r.containers == null ? 0 : r.containers.size());
            if (r.containers != null) {
                int nonEmpty = 0;
                int lastHr = -1;
                int lastSteps = -1;
                int firstTs = -1;
                int lastTs = -1;
                for (FitnessData.MessageData.StepResponse.SubContainer c : r.containers) {
                    if (c.timestamp > 0) {
                        if (firstTs < 0) firstTs = c.timestamp;
                        lastTs = c.timestamp;
                    }
                    if (c.heartrate >= 0) lastHr = c.heartrate;
                    if (c.steps >= 0) lastSteps = c.steps;
                    if (c.data != null && c.data.length > 1) nonEmpty++;
                }
                out.append(" nonEmpty=").append(nonEmpty)
                        .append(" firstTs=").append(firstTs)
                        .append(" lastTs=").append(lastTs)
                        .append(" lastSteps=").append(lastSteps)
                        .append(" lastHr=").append(lastHr);
            }
            return out.toString();
        }
        if (receivedPacket instanceof FitnessData.MessageData.SleepResponse) {
            FitnessData.MessageData.SleepResponse r =
                    (FitnessData.MessageData.SleepResponse) receivedPacket;
            StringBuilder out = new StringBuilder();
            out.append("number=").append(r.number)
                    .append(" containers=").append(r.containers == null ? 0 : r.containers.size());
            if (r.containers != null) {
                for (FitnessData.MessageData.SleepResponse.SubContainer c : r.containers) {
                    out.append(" [type=").append(c.type)
                            .append(" ts=").append(StringUtils.bytesToHex(c.timestamp))
                            .append("]");
                }
            }
            return out.toString();
        }
        return "-";
    }
}
