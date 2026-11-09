package io.siggi.morganabot;

import com.google.gson.Gson;
import io.siggi.morganabot.Access.Level;
import io.siggi.morganabot.Access.MemberView;
import io.siggi.morganabot.Access.MentionPermissions;
import io.siggi.morganabot.config.ServerInfo;
import io.siggi.morganabot.config.ServerInfoStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AccessTest {

    private static final long GUILD = 100L;
    private static final long ADMIN_ROLE = 10L;
    private static final long VIP = 11L;
    private static final long FAN = 12L;
    private static final long PING_A = 20L;
    private static final long PING_B = 21L;
    private static final long PING_C = 22L;
    private static final long SUPER = 999L;

    @TempDir
    Path tempDir;

    private ServerInfoStore store;
    private Access access;

    @BeforeEach
    void setUp() {
        store = new ServerInfoStore(tempDir.toFile(), new Gson());
        access = new Access(store, List.of(SUPER));
        store.modify(GUILD, info -> {
            info.adminRoles.add(ADMIN_ROLE);
            info.streamers.add(new ServerInfo.Streamer(0L, "1"));
            info.streamers.add(new ServerInfo.Streamer(201L, "2"));
            info.messageCustomizationRules.add(new ServerInfo.MessageCustomizationRule(VIP, false, List.of(PING_A)));
            info.messageCustomizationRules.add(new ServerInfo.MessageCustomizationRule(FAN, true, List.of(PING_B, PING_C)));
            return null;
        });
    }

    private static MemberView member(long userId, boolean administrator, Long... roles) {
        return new MemberView(GUILD, userId, administrator, List.of(roles));
    }

    @Test
    void levels() {
        assertEquals(Level.ADMIN, access.of(member(300L, true)));
        assertEquals(Level.ADMIN, access.of(member(300L, false, ADMIN_ROLE)));
        // A streamer with an admin role is an admin.
        assertEquals(Level.ADMIN, access.of(member(201L, false, ADMIN_ROLE)));
        assertEquals(Level.STREAMER, access.of(member(201L, false, VIP)));
        assertEquals(Level.NONE, access.of(member(300L, false, VIP)));
        // An unlinked streamer entry (discordId 0) doesn't make anyone a streamer.
        assertEquals(Level.NONE, access.of(member(0L, false)));
        assertEquals(Level.ADMIN, access.of(MemberView.outsider(GUILD, SUPER)));
        assertEquals(Level.NONE, access.of(MemberView.outsider(GUILD, 300L)));
    }

    @Test
    void removingAnAdminRoleTakesEffectStraightAway() {
        MemberView m = member(300L, false, ADMIN_ROLE);
        assertEquals(Level.ADMIN, access.of(m));
        store.modify(GUILD, info -> {
            info.adminRoles.clear();
            return null;
        });
        assertEquals(Level.NONE, access.of(m));
    }

    @Test
    void onlyAdministratorsAndSuperAdminsEditAdminRoles() {
        assertTrue(access.canEditAdminRoles(member(300L, true)));
        assertTrue(access.canEditAdminRoles(MemberView.outsider(GUILD, SUPER)));
        assertFalse(access.canEditAdminRoles(member(300L, false, ADMIN_ROLE)));
        assertFalse(access.canEditAdminRoles(member(201L, false, VIP)));
    }

    @Test
    void canCustomizeMessage() {
        assertTrue(access.canCustomizeMessage(member(300L, true)));
        assertTrue(access.canCustomizeMessage(member(300L, false, ADMIN_ROLE)));
        assertTrue(access.canCustomizeMessage(member(201L, false, VIP)));
        assertTrue(access.canCustomizeMessage(member(201L, false, FAN)));
        assertFalse(access.canCustomizeMessage(member(201L, false)));
        assertFalse(access.canCustomizeMessage(member(201L, false, PING_A)));
    }

    @Test
    void allowedMentionsCombineAllMatchingRules() {
        MentionPermissions vip = access.allowedMentions(member(201L, false, VIP));
        assertFalse(vip.all());
        assertFalse(vip.allowsEveryone());
        assertEquals(Set.of(PING_A), vip.roles());
        assertFalse(vip.allowsUsers());

        MentionPermissions both = access.allowedMentions(member(201L, false, VIP, FAN));
        assertTrue(both.allowsEveryone());
        assertEquals(Set.of(PING_A, PING_B, PING_C), both.roles());

        assertEquals(MentionPermissions.NONE.roles(), access.allowedMentions(member(201L, false)).roles());
        assertFalse(access.allowedMentions(member(201L, false)).allowsEveryone());

        MentionPermissions admin = access.allowedMentions(member(300L, true));
        assertTrue(admin.all());
        assertTrue(admin.allowsRole(12345L));
        assertTrue(admin.allowsUsers());
    }

    @Test
    void nullListsInAHandEditedFileAreSafe() {
        store.modify(GUILD, info -> {
            info.adminRoles = null;
            info.messageCustomizationRules = null;
            return null;
        });
        assertEquals(Level.STREAMER, access.of(member(201L, false, ADMIN_ROLE)));
        assertFalse(access.canCustomizeMessage(member(201L, false, VIP)));
    }
}
