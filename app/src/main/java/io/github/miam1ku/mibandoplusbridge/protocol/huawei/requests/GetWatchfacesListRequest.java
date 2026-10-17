// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import io.github.miam1ku.mibandoplusbridge.data.HuaweiLogger;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.Watchface;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.service.HuaweiSupportProvider;

/**
 * 拉取手表已安装的表盘列表（Watchface 0x27，两阶段：0x02 列表 → 0x06 名字）。
 *
 * <p>语义来自 Gadgetbridge 上游 {@code HuaweiWatchfaceManager}（requestWatchfaceList →
 * updateWatchfacesNames）与 packets 定义：
 * <ul>
 *   <li><b>0x02</b> 回 {@code 0x81/0x82} 嵌套 TLV，每条一个已装表盘：
 *       {@code fileName}（{@code 名字_版本} 的前半）、{@code version}、{@code type}
 *       位标志（bit0=当前表盘、bit1=出厂预置、bit3=可编辑）。GT3 实测列表非空、
 *       恰有一条 {@code isCurrent()}。</li>
 *   <li><b>0x06</b> 按 fileName 批量问显示名，回 {@code fileName → 中文名} 映射。
 *       没有名字的条目（多数预置表盘）用 fileName 本身。</li>
 * </ul>
 *
 * <p>这条链是「表盘预览」特性的数据源：宿主设备页的表盘区按设备信息渲染，
 * 而当前表盘是哪一款只能问手表。0x27 不返回预览图 —— 华为协议没有
 * 「把当前表盘截图发过来」的命令（Gadgetbridge 的预览同样只来自用户手动
 * 导入的 .hwt 包内 preview/cover.jpg），预览图只能由桥接侧合成。
 */
public class GetWatchfacesListRequest extends Request {

    private static final HuaweiLogger LOG = HuaweiLogger.of(GetWatchfacesListRequest.class);

    private static final long TIMEOUT_MS = 15000;

    /** 表盘列表回调。{@code names} 可能为空映射（0x06 失败时降级）。 */
    public interface WatchfaceListener {
        void onWatchfaces(List<Watchface.InstalledWatchfaceInfo> watchfaces,
                HashMap<String, String> names);

        void onError(String message);
    }

    /** 第二阶段专用：第一阶段拿到的列表。{@code null} 表示本实例处于第一阶段。 */
    private final List<Watchface.InstalledWatchfaceInfo> known;

    private final WatchfaceListener listener;

    /** 第一阶段：问已装表盘列表。 */
    public GetWatchfacesListRequest(HuaweiSupportProvider support,
            WatchfaceListener listener) {
        this(support, listener, null);
    }

    private GetWatchfacesListRequest(HuaweiSupportProvider support,
            WatchfaceListener listener,
            List<Watchface.InstalledWatchfaceInfo> known) {
        super(support, TIMEOUT_MS);
        this.serviceId = Watchface.id;
        this.commandId = known == null
                ? Watchface.DeviceWatchInfo.id
                : Watchface.WatchfaceNameInfo.id;
        this.known = known;
        this.listener = listener;
        setFinalizeReq(new RequestCallback(support) {
            @Override
            public void call(Request request) {
                // 成功路径已在 processResponse() 里回调过。
            }

            @Override
            public void handleException(Request request, ResponseParseException e) {
                LOG.error("Watchfaces request failed", e);
                if (listener != null) {
                    // 名字阶段失败也要把列表交出去 —— 名字只是锦上添花。
                    if (known != null) {
                        listener.onWatchfaces(known, new HashMap<>());
                    } else {
                        listener.onError(e.getMessage());
                    }
                }
            }
        });
    }

    @Override
    protected List<byte[]> createRequest() throws RequestCreationException {
        try {
            if (known == null) {
                return new Watchface.DeviceWatchInfo.Request(paramsProvider).serialize();
            }
            return new Watchface.WatchfaceNameInfo.Request(paramsProvider, known).serialize();
        } catch (HuaweiPacket.CryptoException e) {
            throw new RequestCreationException(e);
        }
    }

    @Override
    protected void processResponse() throws ResponseParseException {
        if (known == null) {
            if (!(receivedPacket instanceof Watchface.DeviceWatchInfo.Response)) {
                throw new ResponseTypeMismatchException(receivedPacket,
                        Watchface.DeviceWatchInfo.Response.class);
            }
            List<Watchface.InstalledWatchfaceInfo> list =
                    ((Watchface.DeviceWatchInfo.Response) receivedPacket).watchfaceInfoList;
            if (list == null) list = new ArrayList<>();
            LOG.info("Watchfaces installed={}", list.size());
            for (Watchface.InstalledWatchfaceInfo info : list) {
                LOG.info("Watchface entry name={} version={} type={} current={}",
                        info.fileName, info.version, info.type, info.isCurrent());
            }
            if (list.isEmpty()) {
                if (listener != null) listener.onWatchfaces(list, new HashMap<>());
                return;
            }
            // 串名字阶段：不用同一实例重复 perform（timeoutWatcher 不能 start 两次）。
            nextRequest(new GetWatchfacesListRequest(supportProvider, listener, list));
            return;
        }

        if (!(receivedPacket instanceof Watchface.WatchfaceNameInfo.Response)) {
            throw new ResponseTypeMismatchException(receivedPacket,
                    Watchface.WatchfaceNameInfo.Response.class);
        }
        HashMap<String, String> names =
                ((Watchface.WatchfaceNameInfo.Response) receivedPacket).watchFaceNames;
        if (names == null) names = new HashMap<>();
        if (listener != null) {
            listener.onWatchfaces(known, names);
        }
    }
}
