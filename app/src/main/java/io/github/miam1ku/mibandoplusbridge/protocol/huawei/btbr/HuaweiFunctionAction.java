// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol.huawei.btbr;

import android.bluetooth.BluetoothSocket;
import java.util.function.Predicate;

/**
 * 在写线程上执行一段代码，返回 {@code false} 或抛异常都会中止后续动作。
 *
 * <p>对应 Gadgetbridge 的 {@code FunctionAction}。华为侧用它做
 * 「握手完成后切到加密通道」这类一次性、不属于协议包本身的写入。
 */
public class HuaweiFunctionAction extends HuaweiPlainAction {

    private final Predicate<? super BluetoothSocket> predicate;

    public HuaweiFunctionAction(Predicate<? super BluetoothSocket> predicate) {
        this.predicate = predicate;
    }

    public HuaweiFunctionAction(Runnable runnable) {
        this(socket -> {
            runnable.run();
            return Boolean.TRUE;
        });
    }

    @Override
    public boolean run(BluetoothSocket socket) {
        try {
            return Boolean.TRUE.equals(predicate.test(socket));
        } catch (Exception e) {
            throw new IllegalStateException("FunctionAction failed", e);
        }
    }
}