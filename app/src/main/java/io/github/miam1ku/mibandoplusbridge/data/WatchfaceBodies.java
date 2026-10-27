// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import android.content.Context;

/**
 * 表盘机身素材库：设备空间/健康里那块「表」的外壳图，可由用户在
 * {@code LabActivity} 里点选切换。
 *
 * <p>9 张素材全部来自华为官方产品渲染图（用户 2026-10-26 提供），经 rembg 抠图 +
 * 表壳圆归一化（case → 最终画布 (357.7, 567.3) 半径 347.6，屏幕半径实测 322–325
 * 全部 ≥ 表盘半径 320.1），因此<strong>九张共用同一套表盘几何</strong>，
 * 切换只换位图、不动坐标。几何常量与推导见 {@link WatchfacePreviewRenderer}。
 *
 * <p>选择存 {@code LocalPrefs}（no_backup JSON）。渲染器跑在桥接进程
 * （{@code HuaweiDeviceRegistry} 同步后回调），与 LabActivity 同进程同目录，
 * 写完下一次渲染即生效，无需跨进程通道。
 */
public final class WatchfaceBodies {

    /** 选择项的 prefs 名与键。 */
    public static final String PREFS = "watchface-body";
    public static final String KEY_SELECTED = "selected";
    /** 素材缺失等异常时的兜底（黑氟橡胶 46mm，与实机同款）。 */
    public static final String DEFAULT = "img1_black_fluoro";

    /** keys/names/files 一一对应；files 是 assets 里的文件名。 */
    private static final String[] KEYS = {
            "img1_black_fluoro", "img2_white_leather", "img3_pro_leather",
            "img4_nylon", "img5_steel", "img6_brown",
            "img7_gold", "img8_pink", "img9_rose",
    };
    private static final String[] NAMES = {
            "黑氟橡胶", "白色真皮", "Pro 真皮",
            "尼龙", "钢表带", "棕色真皮",
            "金色", "粉色", "玫瑰金",
    };
    private static final String[] FILES = {
            "img1_black_fluoro.png", "img2_white_leather.png", "img3_pro_leather.png",
            "img4_nylon.png", "img5_steel.png", "img6_brown.png",
            "img7_gold.png", "img8_pink.png", "img9_rose.png",
    };

    private WatchfaceBodies() {}

    /** 机身数量。 */
    public static int count() {
        return KEYS.length;
    }

    /** 第 {@code i} 个机身的 key。 */
    public static String key(int i) {
        return KEYS[i];
    }

    /** 第 {@code i} 个机身的展示名（表带款色）。 */
    public static String name(int i) {
        return NAMES[i];
    }

    /** 当前选中的机身在 assets 里的文件名；未知 key 落回默认款。 */
    public static String selectedFile(Context context) {
        return fileOf(selectedKey(context));
    }

    /** 当前选中的 key（含兜底）。 */
    public static String selectedKey(Context context) {
        String v = LocalPrefs.open(context, PREFS).getString(KEY_SELECTED, DEFAULT);
        return fileOf(v) == null ? DEFAULT : v;
    }

    /** key → 展示名；未知 key 返回空串。 */
    public static String nameOf(String key) {
        for (int i = 0; i < KEYS.length; i++) {
            if (KEYS[i].equals(key)) return NAMES[i];
        }
        return "";
    }

    /** key → assets 文件名；未知 key 返回 null。 */
    public static String fileOf(String key) {
        for (int i = 0; i < KEYS.length; i++) {
            if (KEYS[i].equals(key)) return FILES[i];
        }
        return null;
    }

    /** 写入选择；未知 key 拒绝。返回是否成功。 */
    public static boolean select(Context context, String key) {
        if (fileOf(key) == null) return false;
        return LocalPrefs.open(context, PREFS).edit()
                .putString(KEY_SELECTED, key)
                .commit();
    }
}
