// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import android.content.Context;

/**
 * 华为协议栈（以及移植进来的 btbr 传输层）使用的日志门面。
 *
 * <p>被搬入的 Gadgetbridge 华为代码统一使用 {@code org.slf4j.Logger}，但 bridge
 * 本身不引入 slf4j（那会为一个 Xposed 模块引入完整的日志实现 + 绑定器）。本类提供
 * slf4j 中实际被用到的子集：{@code debug}/{@code info}/{@code error}，支持
 * {@code {}} 占位符与末尾 {@link Throwable} 的双参数重载，语义与 slf4j 一致。
 *
 * <p>输出复用 {@link SessionLog}：写入前会丢掉 MAC 形状的地址与控制字符，行长上限
 * 160 字符。协议栈的调试信息里含有账号 ID、握手随机数等敏感值，调用方不应把它们
 * 直接拼进消息；需要落盘标识时请用 {@link SessionLog#redact(String)}。
 *
 * <p>未绑定 Context 时静默丢弃。Context 由宿主进程在服务启动时通过
 * {@link #bind(Context)} 注入一次。
 */
public final class HuaweiLogger {

    private static volatile Context context;

    private final String tag;

    private HuaweiLogger(String tag) {
        this.tag = tag;
    }

    public static HuaweiLogger of(Class<?> type) {
        return new HuaweiLogger(type.getSimpleName());
    }

    public static HuaweiLogger of(String tag) {
        return new HuaweiLogger(tag);
    }

    /** 注入宿主 Context。由服务启动路径调用一次，未绑定时所有日志静默丢弃。 */
    public static void bind(Context context) {
        HuaweiLogger.context = context;
    }

    public boolean isDebugEnabled() {
        return true;
    }

    public void debug(String message) {
        write("D", message, null, null);
    }

    public void debug(String format, Object arg) {
        write("D", format, new Object[]{arg}, null);
    }

    public void debug(String format, Object a, Object b) {
        write("D", format, new Object[]{a, b}, null);
    }

    public void debug(String format, Object... args) {
        write("D", format, args, null);
    }

    public void info(String message) {
        write("I", message, null, null);
    }

    public void info(String format, Object arg) {
        write("I", format, new Object[]{arg}, null);
    }

    public void info(String format, Object... args) {
        write("I", format, args, null);
    }

    public void warn(String message) {
        write("W", message, null, null);
    }

    public void warn(String format, Object arg) {
        write("W", format, new Object[]{arg}, null);
    }

    public void error(String message) {
        write("E", message, null, null);
    }

    public void error(String format, Object arg) {
        write("E", format, new Object[]{arg}, null);
    }

    public void error(String format, Object a, Object b) {
        write("E", format, new Object[]{a, b}, null);
    }

    public void error(String format, Object... args) {
        write("E", format, args, null);
    }

    /** slf4j 把最后一个 {@link Throwable} 单独当异常处理的写法。 */
    public void error(String message, Throwable error) {
        write("E", message, null, error);
    }

    private void write(String level, String format, Object[] args, Throwable error) {
        Context ctx = context;
        if (ctx == null || format == null) return;
        String text = args == null || args.length == 0 ? format : format(format, args);
        if (error != null) {
            String detail = error.getClass().getSimpleName()
                    + (error.getMessage() == null ? "" : ": " + error.getMessage());
            text = text + " | " + detail;
        }
        SessionLog.line(ctx, tag + " " + level + " " + text);
        // 同步进 logcat。落盘日志在手机上得靠 adb pull + 挑文件才能看，
        // 而真机排障往往要盯着时间戳实时判断某一步有没有超时；
        // 这里转发一份，让协议栈每一步都能用
        // adb logcat -s Huawei 直接读到，不必来回拉文件。
        android.util.Log.i("Huawei", tag + " " + level + " " + text);
    }

    /**
     * 按 slf4j 的规则替换 {@code {}} 占位符：按顺序消费参数，多余参数追加为
     * {@code [a, b]}，参数多于占位符时不会被丢弃（便于排障）。
     */
    static String format(String pattern, Object[] args) {
        StringBuilder out = new StringBuilder(pattern.length() + 32);
        int arg = 0;
        int i = 0;
        while (i < pattern.length()) {
            int next = pattern.indexOf("{}", i);
            if (next < 0 || arg >= args.length) {
                out.append(pattern, i, pattern.length());
                return out.toString();
            }
            out.append(pattern, i, next);
            Object value = args[arg++];
            out.append(value instanceof Object[] ? java.util.Arrays.deepToString((Object[]) value) : value);
            i = next + 2;
        }
        out.append(pattern, i, pattern.length());
        if (arg < args.length) {
            out.append(" [");
            for (int extra = arg; extra < args.length; extra++) {
                if (extra > arg) out.append(", ");
                out.append(args[extra]);
            }
            out.append(']');
        }
        return out.toString();
    }
}