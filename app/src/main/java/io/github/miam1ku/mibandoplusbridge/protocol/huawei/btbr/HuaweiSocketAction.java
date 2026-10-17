// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol.huawei.btbr;

import android.bluetooth.BluetoothSocket;

/**
 * 一次 socket 操作的封装。
 *
 * <p>对应 Gadgetbridge 的 {@code BtBRAction}。原类引入 {@code DateTimeUtils} 仅为了
 * {@code toString()} 里显示本地时间；本项目改为直接用 {@link java.text.DateFormat}，
 * 不搬入 GB 的工具类。
 */
public abstract class HuaweiSocketAction {

    private final long creationTimestamp = System.currentTimeMillis();

    /**
     * @return 若该动作需要等待（异步）结果，则为 {@code true}。
     */
    public abstract boolean expectsResult();

    /**
     * 执行动作。
     *
     * @param socket 目标 socket
     * @return 成功返回 {@code true}
     */
    public abstract boolean run(BluetoothSocket socket);

    protected String getCreationTime() {
        return java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
                .format(new java.util.Date(creationTimestamp));
    }

    @Override
    public String toString() {
        return getCreationTime() + ": " + getClass().getSimpleName();
    }
}