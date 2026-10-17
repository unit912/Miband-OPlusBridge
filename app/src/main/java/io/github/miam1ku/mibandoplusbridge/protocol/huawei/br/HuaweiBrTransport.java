// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol.huawei.br;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Process;
import io.github.miam1ku.mibandoplusbridge.data.HuaweiLogger;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.btbr.HuaweiSocketAction;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.btbr.HuaweiSocketCallback;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.btbr.HuaweiTransaction;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.GB;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiConstants;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * GT3 的 BR/EDR（RFCOMM/SPP）传输层。
 *
 * <p>对应 Gadgetbridge 的 {@code service.btbr.AbstractBTBRDeviceSupport} +
 * {@code BtBRQueue} 两个类的华为用途子集。原实现把连接状态托管给 {@code GBDevice} 与
 * {@code GBApplication.getPrefs()}，并把 {@code setProgress}/{@code setBusyTask} 这类
 * UI 动作也做成了事务动作 —— 那些在 bridge 里都没有意义。本类改为：
 *
 * <ul>
 *   <li>连接状态通过 {@link ConnectionListener} 回调交给宿主（{@code BandLiveService}），
 *       不引入 GB 设备模型；</li>
 *   <li>事务动作只保留协议真正用到的 write / sleep / run；</li>
 *   <li>保留主 socket 与 aux（双通道）socket 的多流支持，这是华为 0x3C 协商出来的能力。</li>
 * </ul>
 *
 * <p>读写分离：写操作串行在独立 HandlerThread 上执行，读线程各自阻塞在
 * {@code InputStream.read()}，两者不共享锁。表带在写入瞬间不会回数据，所以读线程不会
 * 与写线程互相拖死。
 */
public final class HuaweiBrTransport {

    private static final HuaweiLogger LOG = HuaweiLogger.of(HuaweiBrTransport.class);
    private static final AtomicLong QUEUE_COUNTER = new AtomicLong();

    private static final int SUBJECT_CONNECT = 0;
    private static final int SUBJECT_RUN_TRANSACTION = 1;

    /** 连接状态变化的通知。宿主据此驱动 UI / 上层状态机。 */
    public interface ConnectionListener {
        void onConnected();

        /** 链路断开。{@code deliberate} 为 {@code true} 表示本端主动关闭。 */
        void onDisconnected(boolean deliberate);
    }

    private final BluetoothAdapter adapter;
    private final Context context;
    private final HuaweiSocketCallback callback;
    private final ConnectionListener listener;
    private final String deviceAddress;
    private final int bufferSize;

    private final Object lock = new Object();

    private HuaweiSocketChannel primary;
    private final Map<Integer, HuaweiSocketChannel> auxChannels = new ConcurrentHashMap<>();

    public HuaweiBrTransport(Context context, BluetoothAdapter adapter, String deviceAddress,
            HuaweiSocketCallback callback, ConnectionListener listener, int bufferSize) {
        this.context = context;
        this.adapter = adapter;
        this.deviceAddress = deviceAddress;
        this.callback = callback;
        this.listener = listener;
        this.bufferSize = bufferSize;
    }

    /** 单条 RFCOMM 通道：自己的 socket、读线程、写 Handler。 */
    private final class HuaweiSocketChannel {
        private final int channel;
        private final boolean isAux;
        private final AtomicBoolean disposed = new AtomicBoolean();
        private final HandlerThread writeThread =
                new HandlerThread("HuaweiBr_write_" + QUEUE_COUNTER.getAndIncrement(),
                        Process.THREAD_PRIORITY_BACKGROUND);
        private final Handler writeHandler;
        private BluetoothSocket socket;
        private Thread readThread;
        private volatile boolean connected;

        HuaweiSocketChannel(int channel, boolean isAux) {
            this.channel = channel;
            this.isAux = isAux;
            writeThread.start();
            writeHandler = new Handler(writeThread.getLooper()) {
                @Override
                public void handleMessage(Message msg) {
                    switch (msg.what) {
                        case SUBJECT_CONNECT:
                            doConnect();
                            break;
                        case SUBJECT_RUN_TRANSACTION:
                            runTransaction((HuaweiTransaction) msg.obj);
                            break;
                        default:
                            LOG.warn("unhandled write message {}", msg.what);
                    }
                }
            };
        }

        @SuppressLint("MissingPermission")
        private void doConnect() {
            if (socket == null) {
                LOG.error("connect requested but socket is null (channel {})", channel);
                if (!isAux) {
                    listener.onDisconnected(true);
                }
                return;
            }
            try {
                socket.connect();
                connected = true;
                LOG.info("RFCOMM connected on channel {}", channel);
                if (readThread == null || !readThread.isAlive()) {
                    readThread = createReadThread();
                }
                readThread.start();
                // aux socket 不参与主连接状态，也不再触发设备初始化。
                if (!isAux) {
                    listener.onConnected();
                    callback.onConnectionEstablished();
                }
            } catch (IOException e) {
                LOG.error("RFCOMM connect failed on channel {}", e);
                fail();
            } catch (SecurityException e) {
                LOG.error("RFCOMM connect denied on channel {}", e);
                fail();
            }
        }

        private void fail() {
            closeSocket();
            if (!isAux) {
                listener.onDisconnected(false);
            }
        }

        private Thread createReadThread() {
            return new Thread("HuaweiBr_read_" + QUEUE_COUNTER.getAndIncrement()) {
                @Override
                public void run() {
                    byte[] buffer = new byte[bufferSize];
                    while (!disposed.get()) {
                        int read;
                        try {
                            if (socket == null) {
                                throw new IOException("socket was null");
                            }
                            read = socket.getInputStream().read(buffer);
                            if (read == -1) {
                                throw new IOException("end of stream");
                            }
                        } catch (IOException e) {
                            LOG.error("read loop ended on channel {}", e);
                            break;
                        }
                        LOG.debug("received {} bytes on channel {}: {}",
                                read, channel, GB.hexdump(buffer, 0, read));
                        try {
                            callback.onSocketRead(Arrays.copyOf(buffer, read), channel);
                        } catch (Throwable e) {
                            LOG.error("onSocketRead callback failed", e);
                        }
                    }
                    closeSocket();
                    if (!isAux && !disposed.get()) {
                        listener.onDisconnected(false);
                    }
                }
            };
        }

        private void runTransaction(HuaweiTransaction transaction) {
            if (!connected || socket == null) {
                LOG.debug("dropping transaction {}: channel {} not connected",
                        transaction.getTaskName(), channel);
                return;
            }
            for (HuaweiSocketAction action : transaction.getActions()) {
                LOG.debug("running action on channel {}: {}", channel, action);
                boolean ok;
                try {
                    ok = action.run(socket);
                } catch (Throwable e) {
                    LOG.error("action threw, aborting transaction {}", transaction.getTaskName(), e);
                    return;
                }
                if (!ok) {
                    LOG.error("action failed, cancelling rest of transaction {}",
                            transaction.getTaskName());
                    return;
                }
            }
        }

        @SuppressLint("MissingPermission")
        boolean connect() {
            if (connected || socket != null || disposed.get()) {
                return false;
            }
            try {
                adapter.cancelDiscovery();
                BluetoothDevice device = adapter.getRemoteDevice(deviceAddress);
                if (channel >= 0) {
                    // 隐藏 API createRfcommSocket(int)：只有已知通道号时用得上。
                    Method create = BluetoothDevice.class.getMethod("createRfcommSocket", int.class);
                    socket = (BluetoothSocket) create.invoke(device, channel);
                } else {
                    socket = device.createRfcommSocketToServiceRecord(
                            HuaweiConstants.UUID_SERVICE_HUAWEI_SDP);
                }
            } catch (ReflectiveOperationException | IOException e) {
                LOG.error("cannot create RFCOMM socket on channel {}", e);
                closeSocket();
                if (!isAux) {
                    listener.onDisconnected(true);
                }
                return false;
            }
            writeHandler.obtainMessage(SUBJECT_CONNECT).sendToTarget();
            return true;
        }

        boolean isConnected() {
            return connected && socket != null;
        }

        void add(HuaweiTransaction transaction) {
            if (!transaction.isEmpty()) {
                writeHandler.obtainMessage(SUBJECT_RUN_TRANSACTION, transaction).sendToTarget();
            }
        }

        void closeSocket() {
            connected = false;
            BluetoothSocket current = socket;
            if (current != null) {
                try {
                    current.close();
                } catch (IOException ignored) {
                    // socket 已不可用，close 失败无需处理
                }
                socket = null;
            }
        }

        void shutdown() {
            if (disposed.getAndSet(true)) {
                return;
            }
            closeSocket();
            writeThread.quit();
            Thread current = readThread;
            if (current != null && current.isAlive()) {
                current.interrupt();
            }
            readThread = null;
        }
    }

    /** 连接主 socket。 */
    @SuppressLint("MissingPermission")
    public boolean connect() {
        synchronized (lock) {
            if (primary != null && primary.isConnected()) {
                return false;
            }
            primary = new HuaweiSocketChannel(HuaweiSocketCallback.MAIN_CHANNEL, false);
            return primary.connect();
        }
    }

    /**
     * 打开协商出的 aux（双通道）socket。
     *
     * @param channel 0x3C 协商得到的 RFCOMM 通道号
     * @return 是否成功触发连接
     */
    @SuppressLint("MissingPermission")
    public boolean openAuxChannel(int channel) {
        synchronized (lock) {
            if (channel <= 0 || auxChannels.containsKey(channel)) {
                return false;
            }
            HuaweiSocketChannel aux = new HuaweiSocketChannel(channel, true);
            auxChannels.put(channel, aux);
            return aux.connect();
        }
    }

    public void closeAuxChannel(int channel) {
        synchronized (lock) {
            HuaweiSocketChannel aux = auxChannels.remove(channel);
            if (aux != null) {
                aux.shutdown();
            }
        }
    }

    /** 主动断开全部连接。 */
    public void disconnect() {
        synchronized (lock) {
            for (HuaweiSocketChannel aux : auxChannels.values()) {
                aux.shutdown();
            }
            auxChannels.clear();
            if (primary != null) {
                primary.shutdown();
                primary = null;
            }
        }
        listener.onDisconnected(true);
    }

    /** 像链路掉线一样丢弃主连接，让上层走常规重连路径。 */
    public void dropConnection() {
        synchronized (lock) {
            if (primary != null) {
                primary.closeSocket();
            }
        }
    }

    public boolean isConnected() {
        synchronized (lock) {
            return primary != null && primary.isConnected();
        }
    }

    /**
     * 把事务路由到指定通道。aux 通道尚未连通时透明回落到主 socket，
     * 避免协商完成前的包被丢掉。
     */
    public void add(HuaweiTransaction transaction, int channel) {
        synchronized (lock) {
            if (channel >= 0) {
                HuaweiSocketChannel aux = auxChannels.get(channel);
                if (aux != null && aux.isConnected()) {
                    aux.add(transaction);
                    return;
                }
            }
            if (primary != null) {
                primary.add(transaction);
            }
        }
    }

    public Context getContext() {
        return context;
    }
}