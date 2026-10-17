// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol.huawei.btbr;

import android.bluetooth.BluetoothSocket;

/**
 * 让写线程休眠指定毫秒。
 *
 * <p>对应 Gadgetbridge 的 {@code SleepAction}。华为的 {@code Request.doPerform()} 用它在
 * 写入后留出一个间隔再启动响应等待，避免连续包被手表丢弃。
 */
public class HuaweiSleepAction extends HuaweiPlainAction {

    private final int millis;

    public HuaweiSleepAction(int millis) {
        this.millis = millis;
    }

    @Override
    public boolean run(BluetoothSocket socket) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}