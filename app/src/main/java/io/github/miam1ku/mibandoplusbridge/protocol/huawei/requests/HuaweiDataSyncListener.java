// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests;

import io.github.miam1ku.mibandoplusbridge.data.HuaweiLogger;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.GB;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.datasync.HuaweiDataSyncCommon;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.DataSync;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.service.HuaweiSupportProvider;

/**
 * 数据同步（service 0x37）常驻监听器。
 *
 * <p>手表侧的步数/心率/事件上报都走这个服务的四个命令：
 * <ul>
 *   <li>{@link DataSync.ConfigCommand} 0x01 —— 配置查询/上报（configId + action + data）</li>
 *   <li>{@link DataSync.EventCommand} 0x02 —— 事件上报（eventId + eventLevel + data）</li>
 *   <li>{@link DataSync.DataCommand} 0x03 —— 数据块（当前 GB 侧未解码）</li>
 *   <li>{@link DataSync.DictDataCommand} 0x04 —— 字典数据（当前 GB 侧未解码）</li>
 * </ul>
 *
 * <p><b>为什么是常驻的 Request 而不是链式请求</b>：这些帧是手表<b>主动推送</b>的，
 * 没有「我们发一个请求、它回一个响应」的配对关系。链式请求的超时/摘除语义
 * （{@link Request#autoRemoveFromResponseHandler()}）会把监听器在收完第一帧后
 * 摘掉，第二帧就没人认领。因此这里覆盖为永不摘除，且构造时 timeoutMillis = 0
 * （不启动超时线程）。
 *
 * <p><b>类型转换安全的依据</b>：{@code HuaweiPacket.parse()} 在重组完整帧时会按
 * serviceId/commandId 把包转成对应的 {@code Response} 对象
 * （0x37/0x01 → {@link DataSync.ConfigCommand.Response}…），因此
 * {@code receivedPacket} 可以直接强转，无需再解析一遍。
 */
public class HuaweiDataSyncListener extends Request {

    private static final HuaweiLogger LOG = HuaweiLogger.of(HuaweiDataSyncListener.class);

    /** 本实例监听的命令号（仅用于日志）。 */
    private final byte listenCommandId;

    public HuaweiDataSyncListener(HuaweiSupportProvider support, byte commandId) {
        // timeoutMillis = 0 → Request 构造不会创建超时线程。
        super(support);
        this.serviceId = DataSync.id;
        this.commandId = commandId;
        this.listenCommandId = commandId;
        // 本实例不通过 doPerform() 发送，addToResponse 无意义，显式关掉以防误用。
        this.addToResponse = false;
    }

    /** 常驻监听：处理完一帧后继续等下一帧，绝不摘除。 */
    @Override
    public boolean autoRemoveFromResponseHandler() {
        return false;
    }

    /**
     * 把上报内容完整打进会话日志。
     *
     * <p>第一轮接线刻意不做任何解析：先看清手表到底推哪些帧、srcPackage 用的是
     * 什么字符串（{@code HuaweiDataSyncManager} 按 srcPackage 查业务回调，猜错
     * key 就等于收不到），再写步数/心率的解码。
     */
    @Override
    protected void processResponse() throws ResponseParseException {
        HuaweiPacket packet = receivedPacket;
        if (packet instanceof DataSync.ConfigCommand.Response) {
            DataSync.ConfigCommand.Response resp = (DataSync.ConfigCommand.Response) packet;
            StringBuilder dump = new StringBuilder();
            if (resp.data.getConfigDataList() != null) {
                for (HuaweiDataSyncCommon.ConfigData cd : resp.data.getConfigDataList()) {
                    dump.append(" [id=").append(cd.configId)
                            .append(" action=").append(cd.configAction)
                            .append(" data=").append(hex(cd.configData))
                            .append(" unknown=").append(cd.configUnknown)
                            .append(']');
                }
            }
            LOG.info("DataSync config report src=" + resp.srcPackage
                    + " dst=" + resp.dstPackage
                    + " code=" + resp.data.getCode()
                    + " items=" + (resp.data.getConfigDataList() == null
                            ? "null" : resp.data.getConfigDataList().size())
                    + dump);
            supportProvider.getHuaweiDataSyncManager()
                    .handleConfigCommandResponse(resp.srcPackage, resp.dstPackage, resp.data);
        } else if (packet instanceof DataSync.EventCommand.Response) {
            DataSync.EventCommand.Response resp = (DataSync.EventCommand.Response) packet;
            LOG.info("DataSync event report src=" + resp.srcPackage
                    + " dst=" + resp.dstPackage
                    + " code=" + resp.data.getCode()
                    + " eventId=" + resp.data.getEventId()
                    + " level=" + resp.data.getEventLevel()
                    + " data=" + hex(resp.data.getData()));
            supportProvider.getHuaweiDataSyncManager()
                    .handleEventCommandResponse(resp.srcPackage, resp.dstPackage, resp.data);
        } else if (packet instanceof DataSync.DataCommand.Response) {
            DataSync.DataCommand.Response resp = (DataSync.DataCommand.Response) packet;
            LOG.info("DataSync data report: src={} dst={} code={}",
                    resp.srcPackage, resp.dstPackage, resp.data.getCode());
            supportProvider.getHuaweiDataSyncManager()
                    .handleDataCommandResponse(resp.srcPackage, resp.dstPackage, resp.data);
        } else if (packet instanceof DataSync.DictDataCommand.Response) {
            DataSync.DictDataCommand.Response resp = (DataSync.DictDataCommand.Response) packet;
            LOG.info("DataSync dict report: src={} dst={} code={}",
                    resp.srcPackage, resp.dstPackage, resp.data.getCode());
            supportProvider.getHuaweiDataSyncManager()
                    .handleDictDataCommandResponse(resp.srcPackage, resp.dstPackage, resp.data);
        } else {
            LOG.warn("DataSync listener 0x" + Integer.toHexString(listenCommandId & 0xFF)
                    + " got unexpected packet type: "
                    + (packet == null ? "null" : packet.getClass().getSimpleName()));
        }
    }

    /** 截断到前 32 字节：整包 hexdump 会淹没会话日志，而前 32 字节足够判断语义。 */
    private static String hex(byte[] data) {
        if (data == null) return "null";
        int len = Math.min(32, data.length);
        return GB.hexdump(data, 0, len) + (data.length > len ? "...(" + data.length + "B)" : "");
    }
}
