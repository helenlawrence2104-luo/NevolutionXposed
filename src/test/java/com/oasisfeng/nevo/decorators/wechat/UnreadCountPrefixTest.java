package com.oasisfeng.nevo.decorators.wechat;

import org.junit.Test;
import static org.junit.Assert.*;

public class UnreadCountPrefixTest {
    @Test public void parsesLocalizedAndLegacyCountsWithoutEatingMessageText() {
        for (String prefix : new String[] { "[2条]", "[2條]", "[2則]", "[2则]", "[2]" }) {
            String message = prefix + "朋友: [图片]";
            UnreadCountPrefix parsed = UnreadCountPrefix.parse(message);
            assertNotNull(message, parsed);
            assertEquals(2, parsed.count);
            assertEquals("朋友: [图片]", message.substring(parsed.end));
        }
    }

    @Test public void malformedAndOrdinaryBracketedMessagesRemainUntouched() {
        for (String message : new String[] { "", "[", "[]", "[条]", "[测试", "[图片]",
                "[得意]", "[abc条]正文", "[2a]正文", "[-2条]正文", "[+2条]正文",
                "[ 2条]正文", "[0条]正文", "hello[2条]", "[٢条]正文" }) {
            assertNull(message, UnreadCountPrefix.parse(message));
        }
        assertNull(UnreadCountPrefix.parse(null));
    }

    @Test public void integerBoundaryIsCheckedWithoutThrowing() {
        assertEquals(Integer.MAX_VALUE, UnreadCountPrefix.parse("[2147483647条]正文").count);
        assertNull(UnreadCountPrefix.parse("[2147483648条]正文"));
        assertNull(UnreadCountPrefix.parse("[999999999999999999999条]正文"));
    }

    @Test public void incompletePrefixNeverIndexesBeforeStart() {
        for (int i = 1; i < "[2147483647条]".length(); i++) {
            assertNull(UnreadCountPrefix.parse("[2147483647条]".substring(0, i)));
        }
    }
}
