// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol.huawei.service;

import java.nio.ByteBuffer;
import java.util.Timer;
import java.util.TimerTask;

import io.github.miam1ku.mibandoplusbridge.data.HuaweiLogger;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.core.HuaweiPacket;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.FileDownloadService0A;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.packets.FileDownloadService2C;
import io.github.miam1ku.mibandoplusbridge.protocol.huawei.requests.Request;

/**
 * 华为睡眠文件下载（TruSleep 科学睡眠通道）。
 *
 * <p>GT3 的整夜睡眠主体不走 FitnessData 0x0C/0x0D 消息通道，而是走
 * {@link FileDownloadService2C}（service 0x2C）的独立文件通道。本类按 Gadgetbridge 的
 * {@code HuaweiFileDownloadManager} 移植，但只保留 GT3 需要的<b>新同步（2C）</b>路径，
 * 并适配本桥的 {@link Request} 框架：没有 Android 主线程 {@code Handler}，改用 {@link Timer}
 * 看门狗；没有双通道路由，统一走主通道。
 *
 * <p>下载流程（新同步）：
 * <ol>
 *   <li>{@code 0x01 init} → 回 fileId / fileSize（size=0 表示本窗口无数据）；</li>
 *   <li>{@code 0x03 info} → 回 timeout / maxBlockSize / noEncrypt；</li>
 *   <li>{@code 0x04} 逐块请求 + {@code 0x05} 常驻监听收数据，直到 buffer 填满；</li>
 *   <li>{@code 0x06 complete} 收尾，回调原始文件字节。</li>
 * </ol>
 *
 * <p>调用方拿到文件字节后自行解析（见 {@code HuaweiTruSleepParser}）。
 */
public final class HuaweiSleepFileDownload {
    private static final HuaweiLogger LOG = HuaweiLogger.of(HuaweiSleepFileDownload.class);

    private static final long REQUEST_TIMEOUT_MS = 15000;
    private static final long MIN_WATCHDOG_MS = 10000;

    private final HuaweiSupportProvider supportProvider;
    private final int startTime;
    private final int endTime;
    private final FileDownloadService2C.FileType fileType;
    private final String filename;
    private final Callback callback;

    // ---- 当前文件请求的状态 ----
    private byte fileId;
    private int fileSize;
    private int timeout;          // 秒
    private int maxBlockSize;
    private boolean noEncrypt;
    private ByteBuffer buffer;
    private int lastBlockStart;

    private BlockListener blockListener;
    private Timer watchdog;
    private boolean busy;
    /** 0x2C 新同步失败/报空后是否已尝试过 0x0A 旧同步回落（每次下载只回落一次）。 */
    private boolean oldSyncTried;

    // ---- 0x0A 旧同步回落状态 ----
    private String[] oldFileNames;
    private int oldFileIndex;
    private int oldMaxBlockSize;
    private int oldTimeoutSec;
    private int oldLastPacketNumber;
    private int oldBlockStart;
    private ByteBuffer oldBuffer;
    private OldBlockListener oldBlockListener;
    private final java.io.ByteArrayOutputStream stateBytes = new java.io.ByteArrayOutputStream();

    public interface Callback {
        void onComplete(byte[] fileData);
        void onError(String message);
    }

    public HuaweiSleepFileDownload(HuaweiSupportProvider supportProvider,
            FileDownloadService2C.FileType fileType, String filename,
            int startTime, int endTime, Callback callback) {
        this.supportProvider = supportProvider;
        this.fileType = fileType;
        this.filename = filename;
        this.startTime = startTime;
        this.endTime = endTime;
        this.callback = callback;
    }

    public HuaweiSleepFileDownload(HuaweiSupportProvider supportProvider,
            int startTime, int endTime, Callback callback) {
        this(supportProvider, FileDownloadService2C.FileType.SLEEP_STATE, "sleep_state.bin",
                startTime, endTime, callback);
    }

    /** 启动下载。多次调用：第一次真正发起，后续（队列）忽略。 */
    public synchronized void start() {
        if (busy) {
            LOG.debug("sleep file download already in progress, ignored");
            return;
        }
        busy = true;
        sendInit();
    }

    private void sendInit() {
        InitRequest req = new InitRequest(supportProvider, filename, fileType, startTime, endTime);
        req.setFinalizeReq(new Request.RequestCallback(supportProvider) {
            @Override
            public void call(Request request) {
                InitRequest r = (InitRequest) request;
                fileId = r.fileId;
                fileSize = r.fileSize;
                if (fileSize == 0) {
                    LOG.info("TruSleep 2C init reports empty file (window anchored?), falling back");
                    fallbackToOldSync("2C init fileSize=0");
                    return;
                }
                LOG.info("HUAWEI_TRUSLEEP_INIT fileId={} fileSize={}", fileId, fileSize);
                sendInfo();
            }

            @Override
            public void handleException(Request request, Request.ResponseParseException e) {
                fail("init failed: " + e.getMessage());
            }
        });
        try {
            req.doPerform();
        } catch (java.io.IOException e) {
            fail("init send failed: " + e.getMessage());
        }
    }

    private void sendInfo() {
        InfoRequest req = new InfoRequest(supportProvider, fileId);
        req.setFinalizeReq(new Request.RequestCallback(supportProvider) {
            @Override
            public void call(Request request) {
                InfoRequest r = (InfoRequest) request;
                if (fileId != r.fileId) {
                    fail("info fileId mismatch");
                    return;
                }
                timeout = r.timeout;
                maxBlockSize = r.maxBlockSize;
                noEncrypt = r.noEncrypt;
                LOG.info("HUAWEI_TRUSLEEP_INFO timeout={} burst={} maxBlock={} noEncrypt={}",
                        timeout, r.burstSize, maxBlockSize, noEncrypt);
                if (fileSize <= 0) {
                    fallbackToOldSync("2C info fileSize<=0");
                    return;
                }
                if (maxBlockSize <= 0) {
                    fail("invalid maxBlockSize " + maxBlockSize);
                    return;
                }
                buffer = ByteBuffer.allocate(fileSize);
                registerListener();
                downloadNextBlock();
            }

            @Override
            public void handleException(Request request, Request.ResponseParseException e) {
                fail("info failed: " + e.getMessage());
            }
        });
        try {
            req.doPerform();
        } catch (java.io.IOException e) {
            fail("info send failed: " + e.getMessage());
        }
    }

    private synchronized void registerListener() {
        if (blockListener == null) {
            blockListener = new BlockListener(supportProvider);
            blockListener.setFinalizeReq(new Request.RequestCallback(supportProvider) {
                @Override
                public void call(Request request) {
                    // 0x05 数据到达：解出的 fileId / data 在 listener 字段里。
                    byte[] data = blockListener.data;
                    LOG.info("HUAWEI_2C_LISTENER want={} got={} dataLen={} pos={}/{}",
                            fileId, blockListener.number, data == null ? -1 : data.length,
                            buffer == null ? -1 : buffer.position(), fileSize);
                    if (fileId != blockListener.number) {
                        fail("block fileId mismatch: want " + fileId + " got " + blockListener.number);
                        return;
                    }
                    if (data == null || data.length == 0) {
                        LOG.info("HUAWEI_2C_EMPTY_BLOCK ignored (no payload in 0x05 frame)");
                        return;
                    }
                    try {
                        buffer.put(data);
                    } catch (Exception e) {
                        fail("buffer overflow: " + e.getMessage());
                        return;
                    }
                    rearmWatchdog();
                    if (buffer.position() >= fileSize) {
                        fileComplete();
                    } else if (buffer.position() - lastBlockStart >= maxBlockSize) {
                        downloadNextBlock();
                    }
                    // 否则等待同一 block 的后续 0x05 帧（手表可能把一块拆成多帧）。
                }

                @Override
                public void handleException(Request request, Request.ResponseParseException e) {
                    fail("block listener error: " + e.getMessage());
                }
            });
            supportProvider.addInProgressRequest(blockListener);
        }
    }

    private void downloadNextBlock() {
        if (buffer.position() >= fileSize) {
            fileComplete();
            return;
        }
        lastBlockStart = buffer.position();
        int remaining = fileSize - buffer.position();
        int blockSize = Math.min(remaining, maxBlockSize);
        // 块请求是「发射后不管」：数据由常驻 0x05 监听接收，自身不等待 0x04 回包。
        BlockRequest req = new BlockRequest(supportProvider, fileId, lastBlockStart, blockSize, noEncrypt);
        try {
            req.doPerform();
        } catch (java.io.IOException e) {
            fail("block send failed: " + e.getMessage());
            return;
        }
        rearmWatchdog();
    }

    private void fileComplete() {
        unregisterListener();
        cancelWatchdog();
        CompleteRequest req = new CompleteRequest(supportProvider, fileId, (byte) 1);
        try {
            req.doPerform();
        } catch (java.io.IOException e) {
            LOG.warn("complete send failed (non-fatal): {}", e.getMessage());
        }
        LOG.info("TruSleep download complete, bytes={}", buffer == null ? 0 : buffer.position());
        finishWith(buffer == null ? new byte[0] : buffer.array());
    }

    private synchronized void unregisterListener() {
        if (blockListener != null) {
            supportProvider.removeInProgressRequests(blockListener);
            blockListener = null;
        }
    }

    private synchronized void rearmWatchdog() {
        cancelWatchdog();
        int timeoutSec = Math.max(timeout, oldTimeoutSec);
        long ms = Math.max(MIN_WATCHDOG_MS, (long) timeoutSec * 1000L);
        watchdog = new Timer("TruSleepWatchdog", true);
        final HuaweiSleepFileDownload self = this;
        watchdog.schedule(new TimerTask() {
            @Override
            public void run() {
                self.fail("watchdog: no block data within " + ms + "ms");
            }
        }, ms);
    }

    private synchronized void cancelWatchdog() {
        if (watchdog != null) {
            watchdog.cancel();
            watchdog = null;
        }
    }

    private synchronized void finishWith(byte[] data) {
        cancelWatchdog();
        unregisterListener();
        busy = false;
        if (callback != null) {
            try {
                callback.onComplete(data);
            } catch (RuntimeException e) {
                LOG.error("TruSleep callback threw", e);
            }
        }
    }

    /**
     * 0x2C 新同步拿不到数据时回落 0x0A 旧同步。
     *
     * <p>GT3 声明 truSleepNewSync=true，但 2C 块传输实测经常一块都收不到（watchdog 超时），
     * 而 0x0A 旧同步是另一条独立的睡眠文件通道（按文件名列表下载 sleep_state）。
     * 此前 {@link #startOldSync()} 从未被调用，2C 一失败整夜睡眠就整体缺失。
     */
    private synchronized void fallbackToOldSync(String reason) {
        if (oldSyncTried) {
            LOG.info("TruSleep old sync already tried, giving up: {}", reason);
            finishWith(new byte[0]);
            return;
        }
        oldSyncTried = true;
        LOG.info("HUAWEI_TRUSLEEP_FALLBACK_OLD_SYNC reason={}", reason);
        startOldSync();
    }

    private synchronized void fail(String message) {
        LOG.error("HUAWEI_TRUSLEEP_FAIL {}", message);
        cancelWatchdog();
        unregisterListener();
        // 2C 任何环节失败都先试 0x0A 旧同步，两条通道都失败才向上报 error。
        if (!oldSyncTried) {
            fallbackToOldSync(message);
            return;
        }
        busy = false;
        if (callback != null) {
            try {
                callback.onError(message);
            } catch (RuntimeException e) {
                LOG.error("TruSleep error callback threw", e);
            }
        }
    }

    // ------------------------------------------------------- 0x0A 旧同步回落

    /** 旧同步入口：0x0A 0x01 SleepFilesRequest 列出窗口内的睡眠文件。 */
    private void startOldSync() {
        OldInitRequest req = new OldInitRequest(supportProvider, startTime, endTime);
        req.setFinalizeReq(new Request.RequestCallback(supportProvider) {
            @Override
            public void call(Request request) {
                OldInitRequest r = (OldInitRequest) request;
                oldFileNames = r.fileNames == null ? new String[0] : r.fileNames;
                StringBuilder names = new StringBuilder();
                for (String n : oldFileNames) {
                    names.append(n).append(";");
                }
                LOG.info("HUAWEI_OLDSYNC_FILES count={} {}", oldFileNames.length, names);
                if (oldFileNames.length == 0) {
                    finishWith(new byte[0]);
                    return;
                }
                sendOldParams();
            }

            @Override
            public void handleException(Request request, Request.ResponseParseException e) {
                fail("old sync init failed: " + e.getMessage());
            }
        });
        try {
            req.doPerform();
        } catch (java.io.IOException e) {
            fail("old sync init send failed: " + e.getMessage());
        }
    }

    /** 0x0A 0x02 FileParameters：拿 maxBlockSize / timeout（fileType 0x01 = 睡眠文件）。 */
    private void sendOldParams() {
        OldParamsRequest req = new OldParamsRequest(supportProvider, (byte) 0x01);
        req.setFinalizeReq(new Request.RequestCallback(supportProvider) {
            @Override
            public void call(Request request) {
                OldParamsRequest r = (OldParamsRequest) request;
                oldMaxBlockSize = r.maxBlockSize;
                oldTimeoutSec = r.timeout;
                LOG.info("TruSleep old sync params maxBlock={} timeout={}", oldMaxBlockSize, oldTimeoutSec);
                if (oldMaxBlockSize <= 0) {
                    fail("old sync invalid maxBlockSize " + oldMaxBlockSize);
                    return;
                }
                downloadOldFile(0);
            }

            @Override
            public void handleException(Request request, Request.ResponseParseException e) {
                fail("old sync params failed: " + e.getMessage());
            }
        });
        try {
            req.doPerform();
        } catch (java.io.IOException e) {
            fail("old sync params send failed: " + e.getMessage());
        }
    }

    /** 逐文件：只下载文件名含 "state" 的（sleep_data 是 PPG 原始波形，不解析）。 */
    private void downloadOldFile(int index) {
        while (index < oldFileNames.length
                && !oldFileNames[index].toLowerCase(java.util.Locale.ROOT).contains("state")) {
            LOG.info("TruSleep old sync skip non-state file: {}", oldFileNames[index]);
            index++;
        }
        if (index >= oldFileNames.length) {
            LOG.info("TruSleep old sync done, state bytes={}", stateBytes.size());
            finishWith(stateBytes.toByteArray());
            return;
        }
        oldFileIndex = index;
        OldFileInfoRequest req = new OldFileInfoRequest(supportProvider, oldFileNames[index]);
        req.setFinalizeReq(new Request.RequestCallback(supportProvider) {
            @Override
            public void call(Request request) {
                OldFileInfoRequest r = (OldFileInfoRequest) request;
                int len = r.fileLength;
                LOG.info("HUAWEI_OLDSYNC_FILE_INFO file={} length={}", oldFileNames[oldFileIndex], len);
                if (len <= 0) {
                    downloadOldFile(oldFileIndex + 1);
                    return;
                }
                oldBuffer = ByteBuffer.allocate(len);
                oldLastPacketNumber = -1;
                oldBlockStart = 0;
                registerOldListener();
                sendOldBlock();
            }

            @Override
            public void handleException(Request request, Request.ResponseParseException e) {
                fail("old sync fileinfo failed: " + e.getMessage());
            }
        });
        try {
            req.doPerform();
        } catch (java.io.IOException e) {
            fail("old sync fileinfo send failed: " + e.getMessage());
        }
    }

    private void sendOldBlock() {
        if (oldBuffer.position() >= oldBuffer.capacity()) {
            oldFileComplete();
            return;
        }
        oldBlockStart = oldBuffer.position();
        int remaining = oldBuffer.capacity() - oldBuffer.position();
        int blockSize = Math.min(remaining, oldMaxBlockSize);
        OldBlockRequest req = new OldBlockRequest(supportProvider, oldFileNames[oldFileIndex],
                oldBlockStart, blockSize);
        try {
            req.doPerform();
        } catch (java.io.IOException e) {
            fail("old sync block send failed: " + e.getMessage());
            return;
        }
        rearmWatchdog();
    }

    private synchronized void registerOldListener() {
        if (oldBlockListener == null) {
            oldBlockListener = new OldBlockListener(supportProvider);
            oldBlockListener.setFinalizeReq(new Request.RequestCallback(supportProvider) {
                @Override
                public void call(Request request) {
                    OldBlockListener l = (OldBlockListener) request;
                    if (l.data == null || l.data.length == 0) {
                        return;
                    }
                    if (oldLastPacketNumber != l.number - 1) {
                        // 旧同步按序号连续性校验（GB 同款），乱序说明丢帧。
                        fail("old sync block number mismatch: want "
                                + (oldLastPacketNumber + 1) + " got " + l.number);
                        return;
                    }
                    oldLastPacketNumber = l.number;
                    try {
                        oldBuffer.put(l.data);
                    } catch (Exception e) {
                        fail("old sync buffer overflow: " + e.getMessage());
                        return;
                    }
                    rearmWatchdog();
                    if (oldBuffer.position() >= oldBuffer.capacity()) {
                        oldFileComplete();
                    } else if (oldBuffer.position() - oldBlockStart >= oldMaxBlockSize) {
                        sendOldBlock();
                    }
                    // 否则等待同一 block 的后续 0x05 帧。
                }

                @Override
                public void handleException(Request request, Request.ResponseParseException e) {
                    fail("old sync block listener error: " + e.getMessage());
                }
            });
            supportProvider.addInProgressRequest(oldBlockListener);
        }
    }

    private synchronized void unregisterOldListener() {
        if (oldBlockListener != null) {
            supportProvider.removeInProgressRequests(oldBlockListener);
            oldBlockListener = null;
        }
    }

    private void oldFileComplete() {
        unregisterOldListener();
        cancelWatchdog();
        OldCompleteRequest req = new OldCompleteRequest(supportProvider);
        try {
            req.doPerform();
        } catch (java.io.IOException e) {
            LOG.warn("old sync complete send failed (non-fatal): {}", e.getMessage());
        }
        byte[] fileData = new byte[oldBuffer.position()];
        // allocate 后 array() 含全部容量，需按实际写入量裁剪。
        System.arraycopy(oldBuffer.array(), 0, fileData, 0, oldBuffer.position());
        // 记录对齐信息：parseState 按 16 字节记录顺序读取，非 16 倍数文件
        // 与后续文件拼接会错位，此处仅拼接对齐文件并告警跳过未对齐文件。
        if (fileData.length % 16 == 0) {
            stateBytes.write(fileData, 0, fileData.length);
        } else {
            LOG.warn("TruSleep old sync state file not 16-byte aligned ({}), skipped",
                    fileData.length);
        }
        LOG.info("HUAWEI_OLDSYNC_FILE_DONE file={} bytes={}", oldFileNames[oldFileIndex],
                fileData.length);
        downloadOldFile(oldFileIndex + 1);
    }

    // ---------------------------------------------------------------- 请求类

    /** 0x2C 0x01 init：回 fileId / fileSize。 */
    private class InitRequest extends Request {
        public String fileName;
        public FileDownloadService2C.FileType fileType;
        public byte fileId;
        public int fileSize;

        InitRequest(HuaweiSupportProvider support, String filename,
                FileDownloadService2C.FileType type, int startTime, int endTime) {
            super(support, REQUEST_TIMEOUT_MS);
            this.serviceId = FileDownloadService2C.id;
            this.commandId = FileDownloadService2C.FileDownloadInit.id;
            this.fileName = filename;
            this.fileType = type;
            this.addToResponse = true;
        }

        @Override
        protected java.util.List<byte[]> createRequest() throws RequestCreationException {
            try {
                return new FileDownloadService2C.FileDownloadInit.Request(
                        paramsProvider, fileName, fileType, startTime, endTime, 0).serialize();
            } catch (HuaweiPacket.CryptoException e) {
                throw new RequestCreationException(e);
            }
        }

        @Override
        protected void processResponse() throws Request.ResponseParseException {
            if (!(receivedPacket instanceof FileDownloadService2C.FileDownloadInit.Response)) {
                throw new ResponseTypeMismatchException(receivedPacket,
                        FileDownloadService2C.FileDownloadInit.Response.class);
            }
            FileDownloadService2C.FileDownloadInit.Response r =
                    (FileDownloadService2C.FileDownloadInit.Response) receivedPacket;
            fileName = r.fileName;
            fileType = r.fileType;
            fileId = r.fileId;
            fileSize = r.fileSize;
        }
    }

    /** 0x2C 0x03 info：回 timeout / maxBlockSize / noEncrypt。 */
    private class InfoRequest extends Request {
        public byte fileId;
        public byte timeout;
        public short burstSize;
        public int maxBlockSize;
        public boolean noEncrypt;

        InfoRequest(HuaweiSupportProvider support, byte fileId) {
            super(support, REQUEST_TIMEOUT_MS);
            this.serviceId = FileDownloadService2C.id;
            this.commandId = FileDownloadService2C.FileInfo.id;
            this.fileId = fileId;
            this.addToResponse = true;
        }

        @Override
        protected java.util.List<byte[]> createRequest() throws RequestCreationException {
            try {
                return new FileDownloadService2C.FileInfo.Request(paramsProvider, fileId).serialize();
            } catch (HuaweiPacket.CryptoException e) {
                throw new RequestCreationException(e);
            }
        }

        @Override
        protected void processResponse() throws Request.ResponseParseException {
            if (!(receivedPacket instanceof FileDownloadService2C.FileInfo.Response)) {
                throw new ResponseTypeMismatchException(receivedPacket,
                        FileDownloadService2C.FileInfo.Response.class);
            }
            FileDownloadService2C.FileInfo.Response r =
                    (FileDownloadService2C.FileInfo.Response) receivedPacket;
            fileId = r.fileId;
            timeout = r.timeout;
            burstSize = r.burstSize;
            maxBlockSize = r.maxBlockSize;
            noEncrypt = r.noEncrypt;
        }
    }

    /** 0x2C 0x04 块请求：发射后不管，数据由 BlockListener(0x05) 接收。 */
    private class BlockRequest extends Request {
        private final byte fileId;
        private final int offset;
        private final int size;
        private final boolean noEncrypt;

        BlockRequest(HuaweiSupportProvider support, byte fileId, int offset, int size, boolean noEncrypt) {
            super(support, 0);
            this.serviceId = FileDownloadService2C.id;
            this.commandId = FileDownloadService2C.RequestBlock.id;
            this.addToResponse = false; // 不等 0x04 回包，数据走 0x05
            this.fileId = fileId;
            this.offset = offset;
            this.size = size;
            this.noEncrypt = noEncrypt;
        }

        @Override
        protected java.util.List<byte[]> createRequest() throws RequestCreationException {
            try {
                return new FileDownloadService2C.RequestBlock(
                        paramsProvider, fileId, offset, size, noEncrypt, 0).serialize();
            } catch (HuaweiPacket.CryptoException e) {
                throw new RequestCreationException(e);
            }
        }

        @Override
        protected void processResponse() throws Request.ResponseParseException {
            // 不应被调用（addToResponse=false）。
        }
    }

    /** 0x2C 0x06 收尾。 */
    private class CompleteRequest extends Request {
        CompleteRequest(HuaweiSupportProvider support, byte fileId, byte status) {
            super(support, REQUEST_TIMEOUT_MS);
            this.serviceId = FileDownloadService2C.id;
            this.commandId = FileDownloadService2C.FileDownloadCompleteRequest.id;
            this.addToResponse = false; // 收尾无需等回包
        }

        @Override
        protected java.util.List<byte[]> createRequest() throws RequestCreationException {
            try {
                return new FileDownloadService2C.FileDownloadCompleteRequest(
                        paramsProvider, fileId, (byte) 1).serialize();
            } catch (HuaweiPacket.CryptoException e) {
                throw new RequestCreationException(e);
            }
        }

        @Override
        protected void processResponse() throws Request.ResponseParseException {
        }
    }

    /** 0x2C 0x05 常驻监听：接收块数据帧。autoRemove=false，整个文件期间持续。 */
    private class BlockListener extends Request {
        public byte number;
        public byte[] data;

        BlockListener(HuaweiSupportProvider support) {
            super(support, 0);
            this.serviceId = FileDownloadService2C.id;
            this.commandId = FileDownloadService2C.BlockResponse.id;
            this.addToResponse = true;
        }

        // 2026-10-26: 此处原有一个 handleResponse(HuaweiPacket) 覆写，只做
        // service/command 匹配却漏了基类的 `receivedPacket = response` 赋值，
        // 于是 Request.handleResponse() 里 receivedPacket.parseTlv() 必定 NPE，
        // 0x05 块数据每次都被丢弃，最终 watchdog 超时、整夜睡眠永远导不进来。
        // 基类实现与本覆写逻辑等价且会赋值，直接沿用基类即可。

        @Override
        public boolean autoRemoveFromResponseHandler() {
            return false;
        }

        @Override
        protected void processResponse() throws Request.ResponseParseException {
            if (!(receivedPacket instanceof FileDownloadService2C.BlockResponse)) {
                throw new ResponseTypeMismatchException(receivedPacket,
                        FileDownloadService2C.BlockResponse.class);
            }
            FileDownloadService2C.BlockResponse r =
                    (FileDownloadService2C.BlockResponse) receivedPacket;
            number = r.fileId;
            data = r.data;
        }
    }

    // ------------------------------------------------------ 0x0A 旧同步请求类

    /** 0x0A 0x01 SleepFilesRequest → 回窗口内睡眠文件名列表。 */
    private class OldInitRequest extends Request {
        public String[] fileNames;

        OldInitRequest(HuaweiSupportProvider support, int start, int end) {
            super(support, REQUEST_TIMEOUT_MS);
            this.serviceId = FileDownloadService0A.id;
            this.commandId = FileDownloadService0A.FileDownloadInit.id;
            this.addToResponse = true;
            this.startTime = start;
            this.endTime = end;
        }

        private final int startTime;
        private final int endTime;

        @Override
        protected java.util.List<byte[]> createRequest() throws RequestCreationException {
            try {
                return new FileDownloadService0A.FileDownloadInit.SleepFilesRequest(
                        paramsProvider, startTime, endTime).serialize();
            } catch (HuaweiPacket.CryptoException e) {
                throw new RequestCreationException(e);
            }
        }

        @Override
        protected void processResponse() throws Request.ResponseParseException {
            if (!(receivedPacket instanceof FileDownloadService0A.FileDownloadInit.Response)) {
                throw new ResponseTypeMismatchException(receivedPacket,
                        FileDownloadService0A.FileDownloadInit.Response.class);
            }
            fileNames = ((FileDownloadService0A.FileDownloadInit.Response) receivedPacket).fileNames;
        }
    }

    /** 0x0A 0x02 FileParameters → 回 maxBlockSize / timeout。 */
    private class OldParamsRequest extends Request {
        public short maxBlockSize;
        public short timeout;

        OldParamsRequest(HuaweiSupportProvider support, byte fileType) {
            super(support, REQUEST_TIMEOUT_MS);
            this.serviceId = FileDownloadService0A.id;
            this.commandId = FileDownloadService0A.FileParameters.id;
            this.addToResponse = true;
            this.fileType = fileType;
        }

        @Override
        protected java.util.List<byte[]> createRequest() throws RequestCreationException {
            try {
                return new FileDownloadService0A.FileParameters.Request(
                        paramsProvider, fileType).serialize();
            } catch (HuaweiPacket.CryptoException e) {
                throw new RequestCreationException(e);
            }
        }

        private final byte fileType;

        @Override
        protected void processResponse() throws Request.ResponseParseException {
            if (!(receivedPacket instanceof FileDownloadService0A.FileParameters.Response)) {
                throw new ResponseTypeMismatchException(receivedPacket,
                        FileDownloadService0A.FileParameters.Response.class);
            }
            FileDownloadService0A.FileParameters.Response r =
                    (FileDownloadService0A.FileParameters.Response) receivedPacket;
            maxBlockSize = r.maxBlockSize;
            timeout = r.timeout;
        }
    }

    /** 0x0A 0x03 FileInfo → 回文件长度。 */
    private class OldFileInfoRequest extends Request {
        public int fileLength = -1;
        private final String fileName;

        OldFileInfoRequest(HuaweiSupportProvider support, String fileName) {
            super(support, REQUEST_TIMEOUT_MS);
            this.serviceId = FileDownloadService0A.id;
            this.commandId = FileDownloadService0A.FileInfo.id;
            this.addToResponse = true;
            this.fileName = fileName;
        }

        @Override
        protected java.util.List<byte[]> createRequest() throws RequestCreationException {
            try {
                return new FileDownloadService0A.FileInfo.Request(
                        paramsProvider, fileName).serialize();
            } catch (HuaweiPacket.CryptoException e) {
                throw new RequestCreationException(e);
            }
        }

        @Override
        protected void processResponse() throws Request.ResponseParseException {
            if (!(receivedPacket instanceof FileDownloadService0A.FileInfo.Response)) {
                throw new ResponseTypeMismatchException(receivedPacket,
                        FileDownloadService0A.FileInfo.Response.class);
            }
            fileLength = ((FileDownloadService0A.FileInfo.Response) receivedPacket).fileLength;
        }
    }

    /** 0x0A 0x04 块请求：发射后不管，数据由 OldBlockListener(0x05) 接收。 */
    private class OldBlockRequest extends Request {
        private final String fileName;
        private final int offset;
        private final int size;

        OldBlockRequest(HuaweiSupportProvider support, String fileName, int offset, int size) {
            super(support, 0);
            this.serviceId = FileDownloadService0A.id;
            this.commandId = FileDownloadService0A.RequestBlock.id;
            this.addToResponse = false;
            this.fileName = fileName;
            this.offset = offset;
            this.size = size;
        }

        @Override
        protected java.util.List<byte[]> createRequest() throws RequestCreationException {
            try {
                return new FileDownloadService0A.RequestBlock.Request(
                        paramsProvider, fileName, offset, size).serialize();
            } catch (HuaweiPacket.CryptoException e) {
                throw new RequestCreationException(e);
            }
        }

        @Override
        protected void processResponse() throws Request.ResponseParseException {
        }
    }

    /** 0x0A 0x06 收尾。 */
    private class OldCompleteRequest extends Request {
        OldCompleteRequest(HuaweiSupportProvider support) {
            super(support, 0);
            this.serviceId = FileDownloadService0A.id;
            this.commandId = FileDownloadService0A.FileDownloadCompleteRequest.id;
            this.addToResponse = false;
        }

        @Override
        protected java.util.List<byte[]> createRequest() throws RequestCreationException {
            try {
                return new FileDownloadService0A.FileDownloadCompleteRequest(
                        paramsProvider).serialize();
            } catch (HuaweiPacket.CryptoException e) {
                throw new RequestCreationException(e);
            }
        }

        @Override
        protected void processResponse() throws Request.ResponseParseException {
        }
    }

    /** 0x0A 0x05 常驻监听：接收块数据帧（旧同步，序号必须连续）。 */
    private class OldBlockListener extends Request {
        public byte number;
        public byte[] data;

        OldBlockListener(HuaweiSupportProvider support) {
            super(support, 0);
            this.serviceId = FileDownloadService0A.id;
            this.commandId = FileDownloadService0A.BlockResponse.id;
            this.addToResponse = true;
        }

        // 同 2C BlockListener：不再覆写 handleResponse，沿用基类以免漏赋值
        // receivedPacket（否则 0x0A 旧同步的块数据同样会被 NPE 吞掉）。

        @Override
        public boolean autoRemoveFromResponseHandler() {
            return false;
        }

        @Override
        protected void processResponse() throws Request.ResponseParseException {
            if (!(receivedPacket instanceof FileDownloadService0A.BlockResponse)) {
                throw new ResponseTypeMismatchException(receivedPacket,
                        FileDownloadService0A.BlockResponse.class);
            }
            FileDownloadService0A.BlockResponse r =
                    (FileDownloadService0A.BlockResponse) receivedPacket;
            number = r.number;
            data = r.data;
        }
    }
}
