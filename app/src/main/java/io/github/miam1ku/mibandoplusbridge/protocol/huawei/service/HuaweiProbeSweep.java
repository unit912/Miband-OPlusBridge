// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol.huawei.service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.FitnessData;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests.HuaweiProbeRequest;

/**
 * 握手成功后跑一次的协议探针序列，用来问清 GT3 心率 / 睡眠数据的真实语义。
 *
 * <p><b>为什么要探针而不是直接写正式的请求类</b>：0x0A/0x0B 这套「先问条数再取
 * 数据」的流程里，{@code count} 到底是「要几条」还是「第几条」、一次 0x0B 返回
 * 窗口里的哪一段，协议文档里都没有。真机上已经撞过两次：要 25 条时手表回错误帧
 * {@code 7F 04 00 01 A1 F9}；要 1 条成功但返回的分钟记录全是空（{@code data =
 * 00}）。这些都只能靠逐个参数问出来。
 *
 * <p><b>指令从开关文件里读，而不是写死在代码里</b>：探针要反复迭代，每次为了改
 * 一组参数重新编译 + 安装（约 40 秒）会把排查节奏拖垮。现在 {@code
 * no_backup/huawei-probe.on} 的内容就是探针指令，一行一条，adb 一行就能换一整套
 * 探测，不需要重新出包。
 *
 * <p>指令格式（空行与 {@code #} 开头的行忽略）：
 * <pre>
 *   sleep-count &lt;窗口秒数&gt;     例：sleep-count 86400
 *   sleep-data  &lt;n&gt;
 *   step-count  &lt;窗口秒数&gt;     例：step-count 3600
 *   step-data   &lt;n&gt;
 *   hr-auto                     打开自动心率测量
 *   hr-realtime                 打开实时心率上报
 * </pre>
 * 文件为空或整份解析不出指令时，退回到 {@link #defaults} 里的默认序列。
 *
 * <p>执行方式：所有探测<b>串行</b>执行 —— 响应分发按 (serviceId, commandId)
 * 匹配第一个等待者，两个同命令的探针同时在飞会互相抢响应。串行靠
 * {@link HuaweiProbeRequest#finish(Runnable)}（成功/失败/超时都会触发一次），
 * 不用 {@code nextRequest}：后者在 TLV 解析失败时不会推进链子，会被一步失败
 * 全部堵死。
 */
public final class HuaweiProbeSweep {

    private HuaweiProbeSweep() { }

    /** 触发开关文件名（放在 {@code no_backup} 下，可用 adb 直接写入）。 */
    public static final String FLAG_FILE = "huawei-probe.on";

    /**
     * 探针的专用执行线程。
     *
     * <p>探针的完成回调来自 socket 读线程；在那条线程上直接发下一个请求会把
     * 「读」堵住，响应就再也进不来。丢到独立线程，读线程立刻回去继续读。
     */
    private static final java.util.concurrent.ExecutorService WORKER =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "HuaweiProbeSweep");
                thread.setDaemon(true);
                return thread;
            });

    public static void run(HuaweiSupportProvider provider, android.content.Context context) {
        List<HuaweiProbeRequest> steps = new ArrayList<>();
        List<String> spec = readSpec(context);
        if (spec.isEmpty()) spec = defaults();
        int now = (int) (System.currentTimeMillis() / 1000L);
        HuaweiPacket.ParamsProvider pp = provider.getParamsProvider();

        for (String line : spec) {
            HuaweiProbeRequest probe = build(provider, pp, line, now);
            if (probe != null) steps.add(probe);
        }
        if (steps.isEmpty()) return;

        // finish() 链：第 i 个结束（成功/失败/超时任一）后拉起第 i+1 个。
        for (int i = 0; i < steps.size(); i++) {
            int next = i + 1;
            steps.get(i).finish(() -> {
                if (next >= steps.size()) {
                    log(context, "HUAWEI_PROBE_SWEEP_DONE steps=" + steps.size());
                    return;
                }
                HuaweiProbeRequest following = steps.get(next);
                WORKER.execute(() -> {
                    try {
                        following.doPerform();
                    } catch (java.io.IOException e) {
                        log(context, "HUAWEI_PROBE_SEND_FAILED " + e.getMessage());
                    }
                });
            });
        }
        WORKER.execute(() -> {
            try {
                steps.get(0).doPerform();
            } catch (java.io.IOException e) {
                log(context, "HUAWEI_PROBE_SWEEP_FAILED " + e.getMessage());
            }
        });
    }

    private static void log(android.content.Context context, String line) {
        if (context != null) {
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(context, line);
        }
    }

    /** 指令文件的内容（按行）。文件不存在返回空列表 —— 探针不跑。 */
    private static List<String> readSpec(android.content.Context context) {
        List<String> lines = new ArrayList<>();
        if (context == null) return lines;
        try {
            File file = new File(context.getNoBackupFilesDir(), FLAG_FILE);
            if (!file.isFile()) return lines;
            for (String raw : new String(Files.readAllBytes(file.toPath()),
                    StandardCharsets.UTF_8).split("\n")) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                lines.add(line);
            }
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
        return lines;
    }

    /** 文件为空时的默认序列：把上一轮问出来的量再确认一遍。 */
    private static List<String> defaults() {
        List<String> out = new ArrayList<>();
        out.add("sleep-count 86400");
        out.add("sleep-data 1");
        out.add("step-count 3600");
        out.add("step-data 1");
        return out;
    }

    private static HuaweiProbeRequest build(HuaweiSupportProvider provider,
            HuaweiPacket.ParamsProvider pp, String line, int now) {
        String[] parts = line.split("\\s+");
        String name = parts[0];
        int arg = parts.length > 1 ? parseInt(parts[1], -1) : -1;
        switch (name) {
            case "sleep-count":
                return new HuaweiProbeRequest(provider, line,
                        new FitnessData.MessageCount.Request(pp,
                                FitnessData.MessageCount.sleepId,
                                now - (arg > 0 ? arg : 86400), now));
            case "sleep-data":
                return new HuaweiProbeRequest(provider, line,
                        new FitnessData.MessageData.Request(pp,
                                FitnessData.MessageData.sleepId,
                                (short) Math.max(1, arg)));
            case "step-count":
                return new HuaweiProbeRequest(provider, line,
                        new FitnessData.MessageCount.Request(pp,
                                FitnessData.MessageCount.stepId,
                                now - (arg > 0 ? arg : 3600), now));
            case "step-data":
                return new HuaweiProbeRequest(provider, line,
                        new FitnessData.MessageData.Request(pp,
                                FitnessData.MessageData.stepId,
                                (short) Math.max(1, arg)));
            case "hr-auto":
                return new HuaweiProbeRequest(provider, line,
                        new FitnessData.EnableAutomaticHeartrate.Request(pp, true));
            case "hr-realtime":
                return new HuaweiProbeRequest(provider, line,
                        new FitnessData.EnableRealtimeHeartRate.Request(pp, true));
            default:
                return null;
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    /** 今天零点（本地时区）的秒级时间戳。指令里用 {@code today} 作为窗口长度。 */
    static int todayStart() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return (int) (cal.getTimeInMillis() / 1000L);
    }
}
