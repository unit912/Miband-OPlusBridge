// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol.huawei.br;

import android.bluetooth.BluetoothSocket;
import io.github.miam1ku.mibandoplusbridge.data.HuaweiLogger;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.btbr.HuaweiFunctionAction;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.btbr.HuaweiSleepAction;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.btbr.HuaweiSocketCallback;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.btbr.HuaweiTransaction;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.btbr.HuaweiWriteAction;
import java.util.function.Predicate;

/**
 * 构造一批要按序写出的字节动作，最后 {@link #queue()} 交给传输层。
 *
 * <p>对应 Gadgetbridge 的 {@code service.btbr.TransactionBuilder}。原类里
 * {@code setDeviceState} / {@code setProgress} / {@code setBusyTask} 三个方法操作
 * GB 的设备模型与 UI 进度条，在 bridge 里没有对应物，已去掉；华为协议只用
 * {@code write} / {@code sleep} / {@code setChannel}。
 *
 * <p>一个 builder 只能 {@link #queue()} 一次，重复使用会抛异常 —— 华为的
 * {@code Request} 依赖这一点来确保响应监听与写出严格配对。
 */
public final class HuaweiTransactionBuilder {

    private static final HuaweiLogger LOG = HuaweiLogger.of(HuaweiTransactionBuilder.class);

    private final HuaweiBrTransport transport;
    private final HuaweiTransaction transaction;
    private int channel = HuaweiSocketCallback.MAIN_CHANNEL;
    private boolean queued;

    public HuaweiTransactionBuilder(String taskName, HuaweiBrTransport transport) {
        this.transport = transport;
        this.transaction = new HuaweiTransaction(taskName);
    }

    /** 追加一次写。{@code null} 会被忽略（对齐 GB 的容错行为）。 */
    public HuaweiTransactionBuilder write(byte... data) {
        if (data == null) {
            LOG.error("attempting to write null data");
            return this;
        }
        return add(new HuaweiWriteAction(data));
    }

    /** 让写线程休眠一段时间。华为请求用它给手表留出响应窗口。 */
    public HuaweiTransactionBuilder sleep(int millis) {
        return add(new HuaweiSleepAction(millis));
    }

    /** 在写线程上执行一段代码，返回 {@code false} 或抛异常都会中止事务后续动作。 */
    public HuaweiTransactionBuilder run(Predicate<? super BluetoothSocket> predicate) {
        return add(new HuaweiFunctionAction(predicate));
    }

    public HuaweiTransactionBuilder run(Runnable runnable) {
        return add(new HuaweiFunctionAction(runnable));
    }

    public HuaweiTransactionBuilder add(io.github.miam1ku.mibandoplusbridge.protocol.huawei.btbr.HuaweiSocketAction action) {
        transaction.add(action);
        return this;
    }

    /** 事务执行期间接收 IO 回调的目标，可为 {@code null}。 */
    public void setCallback(HuaweiSocketCallback callback) {
        transaction.setCallback(callback);
    }

    /**
     * 路由到指定 RFCOMM 通道。
     *
     * @param channel {@link HuaweiSocketCallback#MAIN_CHANNEL}（默认，主 socket）或
     *                协商出的 aux 通道号；aux 未连通时透明回落主 socket
     */
    public HuaweiTransactionBuilder setChannel(int channel) {
        this.channel = channel;
        return this;
    }

    /** 交给传输层执行。会抛 {@link IllegalStateException} 如果已经用过。 */
    public void queue() {
        if (queued) {
            throw new IllegalStateException("builder already queued, it must not be reused");
        }
        queued = true;
        transport.add(transaction, channel);
    }

    public String getTaskName() {
        return transaction.getTaskName();
    }
}