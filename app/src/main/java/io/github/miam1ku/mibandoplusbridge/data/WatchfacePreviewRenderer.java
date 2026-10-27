// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * GT3 表盘预览合成器 —— 设备页/设备空间里那块「暗色静态图」的来源。
 *
 * <p>2026-10-26 v175：机身素材升级为<strong>可切换的官方渲染图库</strong>
 * （9 款表带/配色，见 {@link WatchfaceBodies}）。所有素材经表壳圆归一化，
 * 屏幕圆几何完全一致（实测屏幕半径 322–325 ≥ 表盘 320.1），切换只换位图。
 * 用户在 LabActivity 点选，存 LocalPrefs，桥接进程内直接生效。
 *
 * <p>几何（相对 assets 下任一机身素材，720×1145）：表壳圆心 (922, 958)、
 * 半径 519；玻璃边界 r=470（素材坐标系，scale 0.66977、crop 原点 (388,111)）。
 * 换算到成品坐标：屏幕圆心 (357.7, 567.3)、表盘半径 320.1（表壳半径 347.6，
 * 仅参考）。
 *
 * <p>供给路径：PNG 落在 {@code filesDir/watchface_preview.png}，
 * {@code DeviceCardProvider.openFile} 暴露为
 * {@code content://io.github.miam1ku.mibandoplusbridge.devices/watchface_preview}
 * 与 {@code /device_image[/<name>.png]}，宿主进程的 {@code OHealthWatchfaceHook}
 * 与 mydevices 列表卡/详情页共用同一张。
 *
 * <p>重画节流：publish 高频（HR/步数/电量都会触发），30 秒内只画一次；
 * 位图绘制本身是纯内存操作，异步线程执行避免卡 publish 调用方。
 */
public final class WatchfacePreviewRenderer {

    private static final String FILE = "watchface_preview.png";

    /** 画布 = 机身素材原尺寸；屏幕圆几何见类注释（官方渲染版）。 */
    private static final int W = 720;
    private static final int H = 1145;
    private static final float SCX = 357.7f;
    private static final float SCY = 567.3f;
    private static final float SR = 320.1f;
    /** 屏幕底色（与素材抹屏用的黑色一致，接缝不可见）。 */
    private static final int SCREEN_BG = 0xFF060709;

    private static final long MIN_INTERVAL_MS = 30_000L;
    private static volatile long lastRenderAtMs;
    /** 上一次画进预览的表盘名；变化时绕过 30s 节流立刻重画。 */
    private static volatile String lastWatchfaceKey = "";
    /** 上一次使用的机身 key；切换时同样绕过节流并换位图。 */
    private static volatile String lastBodyKey = "";
    /** 机身位图缓存（桥接进程一份，~3.3MB/张）。 */
    private static volatile Bitmap bodyBitmap;
    /** bodyBitmap 对应的素材名；与选中 key 不一致时重载。 */
    private static volatile String bodyBitmapKey = "";

    /** GT3 风格主题色板：按表盘名取色，切表盘时预览配色随之变化。 */
    private static final int[] ACCENTS = {
            0xFF67E8C4, 0xFFFFB35C, 0xFF6FB6FF, 0xFFB58CFF, 0xFFFF7E9C, 0xFF9CE867,
    };

    private WatchfacePreviewRenderer() {}

    /** 预览 PNG 的落盘位置。 */
    public static File file(Context context) {
        return new File(context.getFilesDir(), FILE);
    }

    /** publish 后调用：30s 节流的异步重画；表盘名或机身变化时立刻重画。 */
    public static void renderAsync(Context context) {
        String wf = currentWatchfaceName(context);
        String bodyKey = WatchfaceBodies.selectedKey(context);
        long now = System.currentTimeMillis();
        boolean forced = !wf.equals(lastWatchfaceKey) || !bodyKey.equals(lastBodyKey);
        if (!forced && now - lastRenderAtMs < MIN_INTERVAL_MS) return;
        lastRenderAtMs = now;
        lastWatchfaceKey = wf;
        lastBodyKey = bodyKey;
        new Thread(() -> render(context), "WatchfacePreview").start();
    }

    /** 当前表盘的 display 名（0x27 列表里 current=true 的条目），空串表示未知。 */
    private static String currentWatchfaceName(Context context) {
        String json = HuaweiDeviceRegistry.watchfaces(context);
        if (json == null || json.isBlank()) return "";
        try {
            org.json.JSONArray arr = new org.json.JSONArray(json);
            int pick = -1;
            for (int i = 0; i < arr.length(); i++) {
                if (arr.getJSONObject(i).optBoolean("current", false)) { pick = i; break; }
            }
            if (pick < 0 && arr.length() > 0) pick = 0;
            if (pick < 0) return "";
            org.json.JSONObject o = arr.getJSONObject(pick);
            String display = o.optString("display", "");
            return display.isBlank() ? o.optString("name", "") : display;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static int accentFor(String key) {
        if (key == null || key.isBlank()) return ACCENTS[0];
        return ACCENTS[Math.floorMod(key.hashCode(), ACCENTS.length)];
    }

    private static Bitmap body(Context context) {
        String key = WatchfaceBodies.selectedKey(context);
        Bitmap cached = bodyBitmap;
        if (cached != null && !cached.isRecycled() && key.equals(bodyBitmapKey)) return cached;
        synchronized (WatchfacePreviewRenderer.class) {
            if (bodyBitmap != null && !bodyBitmap.isRecycled()
                    && key.equals(bodyBitmapKey)) return bodyBitmap;
            String asset = WatchfaceBodies.selectedFile(context);
            if (asset == null) return bodyBitmap;
            try (InputStream is = context.getAssets().open(asset)) {
                Bitmap bmp = BitmapFactory.decodeStream(is);
                if (bmp != null) {
                    bodyBitmap = bmp;
                    bodyBitmapKey = key;
                }
            } catch (IOException ignored) {
                // 素材缺失走 draw() 里的纯色兜底
            }
            return bodyBitmap;
        }
    }

    /** 同步画一张（provider openFile 发现缺文件时的兜底）。 */
    public static synchronized void render(Context context) {
        lastRenderAtMs = System.currentTimeMillis();
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
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
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Bitmap body = body(context);
        if (body != null) {
            c.drawBitmap(body, 0f, 0f, paint);
        } else {
            // 素材缺失兜底：整幅深色底 + 屏幕位圆，至少不裂图。
            c.drawColor(0xFF0C0D0F);
            paint.setColor(SCREEN_BG);
            c.drawCircle(SCX, SCY, SR, paint);
        }

        java.util.Calendar cal = java.util.Calendar.getInstance();
        int steps = HuaweiDeviceRegistry.lastSteps(context);
        int hr = HuaweiDeviceRegistry.lastHeartRate(context);
        int battery = HuaweiDeviceRegistry.lastBattery(context);
        String wf = currentWatchfaceName(context);
        int accent = accentFor(wf);

        // ---- 屏幕内容：圆内裁剪 ----
        Path clip = new Path();
        clip.addCircle(SCX, SCY, SR - 1f, Path.Direction.CW);
        c.save();
        c.clipPath(clip);

        // 屏幕：近黑底 + 中下部极弱暖色余晖（呼应 AMOLED 深色表盘的氛围）
        paint.setShader(new RadialGradient(SCX, SCY + SR * 0.45f, SR * 1.15f,
                new int[]{SCREEN_BG, 0xFF120A08, SCREEN_BG},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(SCX, SCY, SR, paint);
        paint.setShader(null);

        String[] weekDays = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        int dow = (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7;
        String date = (cal.get(java.util.Calendar.MONTH) + 1) + "-"
                + cal.get(java.util.Calendar.DAY_OF_MONTH) + " "
                + weekDays[dow];

        paint.setTextAlign(Paint.Align.CENTER);

        // 电量（顶部小字）
        if (battery > 0) {
            paint.setColor(0xFFDCE1EB);
            paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            paint.setTextSize(30f);
            c.drawText(battery + "%", SCX, SCY - SR + 58f, paint);
        }

        // 日期
        paint.setColor(0xFFC8CDD7);
        paint.setTypeface(Typeface.create("sans-serif-regular", Typeface.NORMAL));
        paint.setTextSize(33f);
        c.drawText(date, SCX, SCY - SR + 108f, paint);

        // 时间：HH:MM 大字
        paint.setColor(Color.WHITE);
        paint.setTypeface(Typeface.create("sans-serif-light", Typeface.BOLD));
        paint.setTextSize(171f);
        String hh = String.format(java.util.Locale.ROOT, "%02d",
                cal.get(java.util.Calendar.HOUR_OF_DAY));
        String mm = String.format(java.util.Locale.ROOT, "%02d",
                cal.get(java.util.Calendar.MINUTE));
        c.drawText(hh + ":" + mm, SCX, SCY + 11f, paint);

        // 数据芯片：步数（主题色）/ 心率（红粉），有数据才画
        float chipY = SCY + 105f;
        float chipH = 63f;
        paint.setTextSize(41f);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        boolean hasSteps = steps > 0;
        boolean hasHr = hr > 0;
        float leftW = hasSteps ? 210f : 0f;
        float rightW = hasHr ? 210f : 0f;
        float gap = hasSteps && hasHr ? 36f : 0f;
        float total = leftW + gap + rightW;
        float x = SCX - total / 2f;
        if (hasSteps) {
            paint.setColor(0x66B34A18);
            c.drawRoundRect(new RectF(x, chipY - chipH / 2f, x + leftW, chipY + chipH / 2f),
                    22f, 22f, paint);
            paint.setColor(accent);
            c.drawText(steps + " 步", x + leftW / 2f, chipY + 14f, paint);
            x += leftW + gap;
        }
        if (hasHr) {
            paint.setColor(0x66A61E33);
            c.drawRoundRect(new RectF(x, chipY - chipH / 2f, x + rightW, chipY + chipH / 2f),
                    22f, 22f, paint);
            paint.setColor(0xFFFF8C9B);
            c.drawText(hr + " bpm", x + rightW / 2f, chipY + 14f, paint);
        }

        // 表盘名 + 设备名
        paint.setColor(0xFFBEC3CD);
        paint.setTypeface(Typeface.create("sans-serif-regular", Typeface.NORMAL));
        paint.setTextSize(29f);
        c.drawText(wf.isBlank() ? "Multi-service wide" : wf, SCX, SCY + 201f, paint);
        paint.setColor(0xFF8C919B);
        paint.setTextSize(23f);
        c.drawText("HUAWEI WATCH GT3", SCX, SCY + 248f, paint);

        // 玻璃高光：左上弧面弱反光，让贴屏有「玻璃感」
        paint.setShader(new RadialGradient(SCX - SR * 0.45f, SCY - SR * 0.6f, SR * 0.9f,
                new int[]{0x1AFFFFFF, 0x00000000},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(SCX, SCY, SR, paint);
        paint.setShader(null);

        c.restore();
    }
}
