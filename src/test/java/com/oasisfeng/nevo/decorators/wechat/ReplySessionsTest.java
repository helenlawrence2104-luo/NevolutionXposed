package com.oasisfeng.nevo.decorators.wechat;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class ReplySessionsTest {
    @Test public void rejectsUnknownForgedAndOtherConversationTokens() {
        ReplySessions sessions = new ReplySessions(() -> 100L);
        String a = sessions.issue(1, "a");
        String b = sessions.issue(2, "b");
        assertTrue(sessions.accepts(1, a));
        assertFalse(sessions.accepts(1, b));
        assertFalse(sessions.accepts(1, null));
        assertFalse(sessions.accepts(3, a));
        assertFalse(sessions.accepts(1, "forged"));
    }

    @Test public void recycledIdInvalidatesOldRecipientAndKeepsCurrentTokenStable() {
        ReplySessions sessions = new ReplySessions(() -> 100L);
        String old = sessions.issue(5, "tagA|recipientA|timestamp1");
        assertEquals(old, sessions.issue(5, "tagA|recipientA|timestamp1"));
        String next = sessions.issue(5, "tagB|recipientB|timestamp2");
        assertNotEquals(old, next);
        assertFalse(sessions.accepts(5, old));
        assertTrue(sessions.accepts(5, next));
    }

    @Test public void expiresAtBoundaryAndOnClockReset() {
        AtomicLong clock = new AtomicLong(100);
        ReplySessions sessions = new ReplySessions(clock::get);
        String token = sessions.issue(1, "a");
        clock.addAndGet(30 * 60 * 1000 - 1);
        assertTrue(sessions.accepts(1, token));
        clock.incrementAndGet();
        assertFalse(sessions.accepts(1, token));
        token = sessions.issue(1, "a");
        clock.set(0);
        assertFalse(sessions.accepts(1, token));
    }

    @Test public void boundsMemoryAndClearsOnConversationRemoval() {
        ReplySessions sessions = new ReplySessions(() -> 100L);
        String first = sessions.issue(0, "a");
        for (int i = 1; i <= 50; i++) sessions.issue(i, "recipient" + i);
        assertFalse(sessions.accepts(0, first));
        String latest = sessions.issue(50, "recipient50");
        sessions.remove(50);
        assertFalse(sessions.accepts(50, latest));
        String another = sessions.issue(2, "recipient2");
        sessions.clear();
        assertFalse(sessions.accepts(2, another));
    }
}
