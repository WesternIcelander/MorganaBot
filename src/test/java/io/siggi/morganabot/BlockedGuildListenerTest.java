package io.siggi.morganabot;

import io.siggi.morganabot.audit.AuditEntry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BlockedGuildListenerTest {

    @Test
    void logLineNamesServerOwnerAndMembers() {
        assertEquals("[Blocked server] Server \"Bad\" (100, owner 7, 42 members) tried to add the bot; leaving.",
                BlockedGuildListener.logLine(100L, "Bad", 7L, 42, BlockedGuildListener.Reason.JOIN));
        assertEquals("[Blocked server] Server \"Bad\" (100, owner 7, 1 member) was still joined at startup; leaving.",
                BlockedGuildListener.logLine(100L, "Bad", 7L, 1, BlockedGuildListener.Reason.STARTUP));
    }

    @Test
    void auditEntryForAJoinAttempt() {
        AuditEntry entry = BlockedGuildListener.auditEntry(
                new BlockedGuildListener.Visit(100L, "Bad", 7L, "owner_name", 42, 8L, "inviter"), BlockedGuildListener.Reason.JOIN);
        assertEquals(AuditEntry.BLOCKED_JOIN, entry.type);
        assertTrue(entry.isSuperAdminOnly());
        assertEquals(AuditEntry.Value.SERVER, entry.subject.kind);
        assertEquals("100", entry.subject.id);
        assertEquals("Bad", entry.subject.name);
        assertEquals(3, entry.facts.size());
        assertEquals("owner", entry.facts.get(0).field);
        assertEquals("7", entry.facts.get(0).value.id);
        assertEquals("owner_name", entry.facts.get(0).value.name);
        assertEquals("memberCount", entry.facts.get(1).field);
        assertEquals("42", entry.facts.get(1).value.value);
        assertEquals("addedBy", entry.facts.get(2).field);
        assertEquals(AuditEntry.Value.USER, entry.facts.get(2).value.kind);
        assertEquals("8", entry.facts.get(2).value.id);
        assertEquals("inviter", entry.facts.get(2).value.name);
    }

    @Test
    void auditEntryAtStartupWithUnknownInviter() {
        AuditEntry entry = BlockedGuildListener.auditEntry(
                new BlockedGuildListener.Visit(100L, "Bad", 7L, null, 1, 0L, null), BlockedGuildListener.Reason.STARTUP);
        assertEquals(AuditEntry.BLOCKED_STARTUP_LEAVE, entry.type);
        assertNull(entry.facts.get(0).value.name, "looked up when recorded");
        AuditEntry.Fact addedBy = entry.facts.get(2);
        assertEquals(AuditEntry.Value.TEXT, addedBy.value.kind);
        assertEquals("unknown", addedBy.value.value);
    }
}
