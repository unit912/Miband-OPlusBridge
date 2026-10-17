// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * {@link HuaweiLogger} 占位符替换的单元测试。
 *
 * <p>华为协议栈的日志大量使用 {@code {}} 形式（从 GB 搬入）。替换逻辑出错会让
 * 排障信息错位，因此这里固定住与 slf4j 一致的行为。
 */
public class HuaweiLoggerTest {

    @Test
    public void noPlaceholderLeavesPatternUnchanged() {
        assertEquals("plain message",
                HuaweiLogger.format("plain message", new Object[]{"unused"}));
    }

    @Test
    public void singlePlaceholderIsFilled() {
        assertEquals("service 0x31",
                HuaweiLogger.format("service 0x{}", new Object[]{0x31}));
    }

    @Test
    public void placeholdersAreFilledInOrder() {
        assertEquals("a=1 b=2",
                HuaweiLogger.format("a={} b={}", new Object[]{1, 2}));
    }

    @Test
    public void surplusArgumentsAreAppended() {
        assertEquals("first=1 [2, 3]",
                HuaweiLogger.format("first={}", new Object[]{1, 2, 3}));
    }

    @Test
    public void nullArgumentRendersAsNull() {
        assertEquals("data=null",
                HuaweiLogger.format("data={}", new Object[]{null}));
    }

    @Test
    public void missingArgumentsLeaveRemainingPlaceholders() {
        assertEquals("a=1 b={}",
                HuaweiLogger.format("a={} b={}", new Object[]{1}));
    }

    @Test
    public void objectArrayArgumentIsDeepStringified() {
        assertEquals("bytes=[01, 02]",
                HuaweiLogger.format("bytes={}", new Object[]{new byte[]{1, 2}}));
    }

    @Test
    public void emptyFormatYieldsEmptyString() {
        assertEquals("", HuaweiLogger.format("", new Object[]{"ignored"}));
    }
}