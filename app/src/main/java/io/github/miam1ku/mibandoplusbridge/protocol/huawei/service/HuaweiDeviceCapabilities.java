// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol.huawei.service;

/**
 * 设备上报的能力位。
 *
 * <p>对应 Gadgetbridge {@code devices.huawei.HuaweiState} 的 expand-capabilities 部分。
 * 原类把这些能力连同整个设置偏好、表盘、通知约束、双通道协商等 UI 状态混在一个
 * 1097 行的类里，并通过 {@code GBDevice} 共享。这里只保留协议层真正要查询的四个
 * 数据同步能力位。
 *
 * <p><b>数据来源</b>：设备在连接握手中上报的 expand capabilities 字节数组，由
 * {@link #setExpandCapabilities(byte[])} 写入。没有能力数据时所有查询返回
 * {@code false} —— 这与原实现一致（{@code expandCapabilities == null} 即不支持），
 * 表现为数据同步命令被跳过，而不是发出一个必然失败的包。
 */
public final class HuaweiDeviceCapabilities {

    /**
     * 原始能力位图。返回副本 —— 调用方（诊断界面）只应读，不应改。
     * 未协商成功时返回 {@code null}。
     */
    public byte[] getExpandCapabilities() {
        return expandCapabilities == null ? null : expandCapabilities.clone();
    }

    /** 数据同步：配置命令（0x31）。 */
    private static final int CAP_DEVICE_COMMAND_CONFIG = 83;
    /** 数据同步：事件命令。 */
    private static final int CAP_DEVICE_COMMAND_EVENT = 84;
    /** 数据同步：数据命令。 */
    private static final int CAP_DEVICE_COMMAND_DATA = 85;
    /** 数据同步：字典数据命令。 */
    private static final int CAP_DEVICE_COMMAND_DICT_DATA = 173;

    private byte[] expandCapabilities;

    /** 写入握手阶段拿到的 expand capabilities。传 {@code null} 表示清空。 */
    public void setExpandCapabilities(byte[] capabilities) {
        this.expandCapabilities = capabilities == null ? null : capabilities.clone();
    }

    public boolean supportsDeviceCommandConfig() {
        return supportsExpandCapability(CAP_DEVICE_COMMAND_CONFIG);
    }

    public boolean supportsDeviceCommandEvent() {
        return supportsExpandCapability(CAP_DEVICE_COMMAND_EVENT);
    }

    public boolean supportsDeviceCommandData() {
        return supportsExpandCapability(CAP_DEVICE_COMMAND_DATA);
    }

    public boolean supportsDeviceCommandDictData() {
        return supportsExpandCapability(CAP_DEVICE_COMMAND_DICT_DATA);
    }

    /**
     * 查询第 {@code which} 位能力。
     *
     * <p>每个字节代表一个「服务」，字节内按位表示该服务下的能力编号。
     */
    private boolean supportsExpandCapability(int which) {
        byte[] capabilities = expandCapabilities;
        if (capabilities == null || which >= capabilities.length * 8) {
            return false;
        }
        int mask = 1 << (which % 8);
        return (capabilities[which / 8] & mask) == mask;
    }

    // ---------------------------------------------------------------- 运行状态
    //
    // 这些不是「能力」，但同属握手后需要持续跟踪的设备状态，且在 GB 里
    // 也放在 HuaweiState 内。为了不再造第二个状态容器，这里一并收着 ——
    // 名字里的 Capabilities 是历史遗留，读代码时知道这点即可。

    /** 电量，0-100。未知时为 {@link #BATTERY_UNKNOWN}。 */
    private volatile int batteryLevel = BATTERY_UNKNOWN;

    /** 充电状态。 */
    private volatile boolean batteryCharging;

    /**
     * 充电状态未知时用这个值。
     *
     * <p>不用 -1：电量取自协议里的无符号字节，0 是合法值（手表没电）。
     * 负数留给「尚未读到」，语义不重叠。
     */
    public static final int BATTERY_UNKNOWN = -1;

    /** 最近一次上报的电量，0-100；从未上报时为 {@link #BATTERY_UNKNOWN}。 */
    public int getBatteryLevel() {
        return batteryLevel;
    }

    public boolean isBatteryCharging() {
        return batteryCharging;
    }

    /**
     * 写入电量。
     *
     * @param level  0-100，超范围的值按未知处理 —— 手表不会返回 101，
     *               真收到说明解析错了，宁可显示未知也不要显示错数。
     * @param charging 是否充电中
     */
    public void setBattery(int level, boolean charging) {
        this.batteryLevel = (level >= 0 && level <= 100) ? level : BATTERY_UNKNOWN;
        this.batteryCharging = charging;
    }

    /** 链路断开时清掉运行状态，避免把上一次连接的电量当成本次的。 */
    public void clearRuntimeState() {
        this.batteryLevel = BATTERY_UNKNOWN;
        this.batteryCharging = false;
    }
}