// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol.huawei.btbr;

import android.bluetooth.BluetoothSocket;
import io.github.miam1ku.mibandoplusbridge.data.HuaweiLogger;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.GB;
import java.io.IOException;
import java.io.OutputStream;

/**
 * 向 socket 写入一段字节。
 *
 * <p>对应 Gadgetbridge 的 {@code WriteAction}。原实现的 {@code GB.hexdump(value)}
 * 单参重载在 bridge 侧的 {@link GB} 中不存在，这里改用 {@code hexdump(value, 0, value.length)}，
 * 输出完全等价。
 */
public class HuaweiWriteAction extends HuaweiSocketAction {

    private static final HuaweiLogger LOG = HuaweiLogger.of(HuaweiWriteAction.class);

    private final byte[] value;
    private OutputStream output;

    public HuaweiWriteAction(byte[] value) {
        this.value = value;
    }

    @Override
    public boolean run(BluetoothSocket socket) {
        try {
            output = socket.getOutputStream();
            if (output == null) {
                LOG.error("output stream is null");
                return false;
            }
            return writeValue(value);
        } catch (IOException e) {
            LOG.error("cannot get the output stream", e);
        }
        return false;
    }

    protected boolean writeValue(byte[] data) {
        LOG.debug("writing to socket: {}", GB.hexdump(data, 0, data.length));
        try {
            output.write(data);
            output.flush();
            return true;
        } catch (IOException e) {
            LOG.error("error writing to socket", e);
        }
        return false;
    }

    protected final byte[] getValue() {
        return value;
    }

    @Override
    public boolean expectsResult() {
        return true;
    }
}