package io.github.miam1ku.mibandoplusbridge.protocol.huawei.core;

/**
 * 华为设备类型标识。
 *
 * <p>提取自 Gadgetbridge 的 {@code HuaweiCoordinator.HuaweiDeviceType}。
 * 该枚举原本嵌套在 GB 的DeviceCoordinator 体系中，而 {@code HuaweiCoordinator}
 * 本身有 45 处外部依赖，无法整体搬入——但协议栈实际只用到 {@code AW} 与
 * {@code BR} 两个分支的判断，因此把枚举本身提取为独立文件。
 *
 * <p>协议用途：{@code DeviceConfig} 在构造请求时需要区分设备是否走
 * BLE 通道（AW/BLE/SMART）还是经典蓝牙通道（BR）。
 *
 * <p>本项目目标设备华为 Watch GT3 走 {@link #BR}（BR/EDR + RFCOMM SDP）。
 */
public enum HuaweiDeviceType {
    /** BLE 通道，设备在蓝牙栈之后（旧款手环/耳机）。 */
    AW(0),
    /** 经典蓝牙 BR/EDR + RFCOMM。本项目 GT3 走此通道。 */
    BR(1),
    /** BLE 通道。 */
    BLE(2),
    /** BLE 通道，设备在蓝牙栈之后。 */
    SMART(5);

    public final int huaweiType;

    HuaweiDeviceType(int huaweiType) {
        this.huaweiType = huaweiType;
    }

    public static HuaweiDeviceType fromValue(final int value) {
        for (HuaweiDeviceType type : values()) {
            if (type.huaweiType == value) {
                return type;
            }
        }
        return null;
    }
}