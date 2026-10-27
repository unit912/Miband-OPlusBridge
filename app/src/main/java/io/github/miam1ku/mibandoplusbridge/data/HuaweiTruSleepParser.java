// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import io.github.miam1ku.mibandoplusbridge.data.HuaweiLogger;

/**
 * 解析华为 TruSleep（科学睡眠）文件。
 *
 * <p>GT3 的<b>整夜睡眠主体</b>不走 FitnessData 0x0C/0x0D 消息通道（那条只返回白天小睡碎片），
 * 而是走 {@code FileDownloadService}（service 0x2C）的独立文件通道。其中：
 *
 * <ul>
 *   <li>{@code SLEEP_STATE} 文件（fileType 0x0e）= 一夜睡眠的<b>起止区间</b>，每条 16 字节；
 *       这是 OPPO 健康睡眠页要的「夜眠时长」来源。</li>
 *   <li>{@code SLEEP_DATA} 文件（fileType 0x0f）= PPG/ACC 原始波形，需重做睡眠分期算法
 *       （Gadgetbridge 的 analyze 仍是 FIXME 未实现），本期不解析，区间先用整段浅睡占位。</li>
 * </ul>
 *
 * <p>本类移植自 Gadgetbridge 的 {@code HuaweiTruSleepParser}，去掉了 slf4j / androidx 依赖，
 * 只保留本桥需要的部分。
 */
public final class HuaweiTruSleepParser {
    private static final HuaweiLogger LOG = HuaweiLogger.of(HuaweiTruSleepParser.class);

    private HuaweiTruSleepParser() {}

    /** 一段夜眠的起止（秒级 Unix 时间）。 */
    public record TruSleepInterval(long startSec, long endSec) {
        public long spanMs() {
            return (endSec - startSec) * 1000L;
        }
    }

    /**
     * 解析 {@code SLEEP_STATE} 文件，返回夜眠起止区间列表。
     *
     * <p>每条记录固定 16 字节：startTime(int) + endTime(int) + 8 字节未知/填充。
     * 多段睡眠会有多条记录。
     */
    public static List<TruSleepInterval> parseState(byte[] stateData) {
        List<TruSleepInterval> retv = new ArrayList<>();
        if (stateData == null || stateData.length < 0x10) {
            return retv;
        }
        ByteBuffer buffer = ByteBuffer.wrap(stateData);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        while (stateData.length - buffer.position() >= 0x10) {
            int startTime = buffer.getInt();
            int endTime = buffer.getInt();
            // 8 字节未知字段直接丢弃（Gadgetbridge 同样未使用）。
            buffer.get(); buffer.get(); buffer.get();
            buffer.get(); buffer.get(); buffer.get(); buffer.get(); buffer.get();
            if (startTime <= 0 || endTime <= startTime) {
                LOG.warn("TruSleep state entry invalid: start=" + startTime + " end=" + endTime);
                continue;
            }
            retv.add(new TruSleepInterval(startTime, endTime));
        }
        return retv;
    }
}
