// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol.huawei.btbr;

import android.bluetooth.BluetoothSocket;

/**
 * 非蓝牙动作基类：不执行蓝牙操作，也不等待异步结果。
 *
 * <p>对应 Gadgetbridge 的 {@code PlainAction}。
 */
public abstract class HuaweiPlainAction extends HuaweiSocketAction {

    @Override
    public boolean expectsResult() {
        return false;
    }
}