// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * GT3 表盘预览合成器 —— 设备页顶部那块「暗色静态图」的替代品。
 *
 * <p>背景：宿主设备页（{@code set_hand_img_band2}）按展示模型加载的是
 * W301CN 的黑屏产品渲染图；华为协议没有「把当前表盘截图发过来」的命令
 * （0x27 只给列表/名字），真实预览图拿不到。于是桥接自己画一张
 * GT3 风格的表盘位图：当前时间 + 日期 + 步数/心率/电量，数据全部来自
 * {@link HuaweiDeviceRegistry} 的实时快照。
 *
 * <p>供给路径：PNG 落在 {@code filesDir/watchface_preview.png}，
 * {@code DeviceCardProvider.openFile} 暴露为
 * {@code content://io.github.miam1ku.mibandoplusbridge.devices/watchface_preview}，
 * 宿主进程的 {@code OHealthWatchfaceHook} 在 ImageView 回调里替换。
 *
 * <p>重画节流：publish 高频（HR/步数/电量都会触发），30 秒内只画一次；
 * 位图绘制本身是纯内存操作（~10ms），异步线程执行避免卡 publish 调用方。
 */
public final class WatchfacePreviewRenderer {

    private static final String FILE = "watchface_preview.png";
    /** 画布尺寸：640×640，圆表直径 600（四边留 20 透明边防裁切）。 */
    private static final int SIZE = 640;
    private static final int CENTER = SIZE / 2;
    private static final float R_CASE = 308f;   // 表壳外径
    private static final float R_SCREEN = 288f; // 屏幕外径

    private static final long MIN_INTERVAL_MS = 30_000L;
    private static volatile long lastRenderAtMs;

    private WatchfacePreviewRenderer() {}

    /** 预览 PNG 的落盘位置。 */
    public static File file(Context context) {
        return new File(context.getFilesDir(), FILE);
    }

    /** publish 后调用：30s 节流的异步重画。 */
    public static void renderAsync(Context context) {
        long now = System.currentTimeMillis();
        if (now - lastRenderAtMs < MIN_INTERVAL_MS) return;
        lastRenderAtMs = now;
        new Thread(() -> render(context), "WatchfacePreview").start();
    }

    /** 同步画一张（provider openFile 发现缺文件时的兜底）。 */
    public static synchronized void render(Context context) {
        lastRenderAtMs = System.currentTimeMillis();
        Bitmap bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        try {
            draw(new Canvas(bmp), context);
            File out = file(context);
            File tmp = new File(context.getFilesDir(), FILE + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.getFD().sync();
            }
            if (!tmp.renameTo(out)) {
                // rename 跨句柄偶发失败：退化为直接覆盖写。
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                }
            }
        } catch (IOException | RuntimeException failure) {
            SessionLog.line(context, "WATCHFACE_PREVIEW_RENDER_FAILED "
                    + failure.getClass().getSimpleName());
        } finally {
            bmp.recycle();
        }
    }

    private static void draw(Canvas c, Context context) {
        long now = System.currentTimeMillis();
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(now);

        // ---- 表壳：深灰金属环 + 右侧表冠 ----
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new RadialGradient(CENTER, CENTER, R_CASE,
                new int[]{0xFF3A3E44, 0xFF22252A, 0xFF15171B},
                new float[]{0f, 0.75f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(CENTER, CENTER, R_CASE, paint);
        paint.setShader(null);
        // 表冠（右侧小凸起）
        paint.setColor(0xFF2B2F35);
        c.drawRoundRect(new RectF(SIZE - 14f, CENTER - 26f, SIZE + 2f, CENTER + 26f),
                8f, 8f, paint);

        // ---- 屏幕：近黑 + 极弱径向提亮 ----
        paint.setShader(new RadialGradient(CENTER, CENTER - 60f, R_SCREEN,
                new int[]{0xFF101418, 0xFF07090C, 0xFF020304},
                new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(CENTER, CENTER, R_SCREEN, paint);
        paint.setShader(null);

        // ---- 表盘内容（GT3 默认数字风格）----
        // 日期：10-18 周日（DAY_OF_WEEK 周日=1..周六=7 → 映射到 MON=0..SUN=6）
        String[] weekDays = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        int dow = (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7;
        String date = (cal.get(java.util.Calendar.MONTH) + 1) + "-"
                + cal.get(java.util.Calendar.DAY_OF_MONTH) + " "
                + weekDays[dow];
        paint.setColor(0xFF9AA0A6);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setTextSize(34f);
        paint.setTextAlign(Paint.Align.CENTER);
        c.drawText(date, CENTER, 160f, paint);

        // 时间：HH:MM 大字
        paint.setColor(Color.WHITE);
        paint.setTypeface(Typeface.create("sans-serif-light", Typeface.BOLD));
        paint.setTextSize(150f);
        String hh = String.format(java.util.Locale.ROOT, "%02d",
                cal.get(java.util.Calendar.HOUR_OF_DAY));
        String mm = String.format(java.util.Locale.ROOT, "%02d",
                cal.get(java.util.Calendar.MINUTE));
        c.drawText(hh + ":" + mm, CENTER, 330f, paint);

        // 数据行：步数 · 心率 · 电量（来自 registry 实时快照）
        int steps = HuaweiDeviceRegistry.lastSteps(context);
        int hr = HuaweiDeviceRegistry.lastHeartRate(context);
        int battery = HuaweiDeviceRegistry.lastBattery(context);
        StringBuilder data = new StringBuilder();
        if (steps > 0) data.append(steps).append(" 步");
        if (hr > 0) {
            if (data.length() > 0) data.append("   ");
            data.append(hr).append(" bpm");
        }
        if (battery > 0) {
            if (data.length() > 0) data.append("   ");
            data.append(battery).append("%");
        }
        paint.setColor(0xFF67E8C4);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setTextSize(38f);
        c.drawText(data.toString(), CENTER, 440f, paint);

        // 底部小字：设备名
        paint.setColor(0xFF5F6368);
        paint.setTextSize(26f);
        c.drawText("HUAWEI WATCH GT3", CENTER, 520f, paint);
    }
}
