// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol.huawei.btbr;

/**
 * {@link HuaweiSocketCallback} 的 socket IO 事件接口。
 *
 * <p>对应 Gadgetbridge 的 {@code SocketCallback}。华为握手需要知道「连接已建立」，
 * 后续所有响应都从 {@link #onSocketRead(byte[], int)} 进入。
 */
public interface HuaweiSocketCallback {

    /** RFCOMM 主 socket（通过 SDP 解析）。负值，不会与协商出的 aux 通道冲突。 */
    int MAIN_CHANNEL = -1;

    /** socket 握手完成，可以开始跑设备初始化事务。 */
    void onConnectionEstablished();

    /** 主 socket 读到数据。 */
    void onSocketRead(byte[] data);

    /**
     * 某个 RFCOMM 通道读到数据。
     *
     * <p>华为的双通道（dual channel）会让不同包走不同 socket，重组状态必须按通道隔离，
     * 否则一条流的碎片会污染另一条正在重组的包。
     *
     * @param data    收到的字节
     * @param channel 该 socket 打开时的 RFCOMM 通道，主 socket 报 {@link #MAIN_CHANNEL}
     */
    default void onSocketRead(byte[] data, int channel) {
        onSocketRead(data);
    }
}