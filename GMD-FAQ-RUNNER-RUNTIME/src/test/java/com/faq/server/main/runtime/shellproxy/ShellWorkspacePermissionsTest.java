package com.gmd.faq.server.main.runtime.shellproxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ShellWorkspacePermissionsTest {
    @TempDir
    Path tempDir;

    @Test
    void acceptsOnlySessionWorkspaceDirectories() throws IOException {
        Path tenants = tempDir.resolve(".baibaiyu/tenants");
        Path workspace = tenants.resolve("tenant-a/workspaces/session-1");
        Files.createDirectories(workspace);
        var owner = Files.getOwner(workspace);

        if (Files.getFileAttributeView(workspace, PosixFileAttributeView.class) != null) {
            assertDoesNotThrow(() -> ShellWorkspacePermissions.prepare(workspace, tenants, owner));
        }
        assertThrows(IOException.class, () -> ShellWorkspacePermissions.prepare(tenants, tenants, owner));
        assertThrows(IOException.class, () -> ShellWorkspacePermissions.prepare(
                tenants.resolve("tenant-a/skills/skill-1"), tenants, owner));
        assertThrows(IOException.class, () -> ShellWorkspacePermissions.prepare(
                tempDir.resolve("outside"), tenants, owner));
    }

    @Test
    void rejectsSymbolicLinkAsWorkspace() throws IOException {
        Path tenants = tempDir.resolve(".baibaiyu/tenants");
        Path sessions = tenants.resolve("tenant-a/workspaces");
        Files.createDirectories(sessions);
        Path other = Files.createDirectory(tempDir.resolve("other"));
        Path link = sessions.resolve("session-1");
        try {
            Files.createSymbolicLink(link, other);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            return;
        }

        var owner = Files.getOwner(sessions);
        assertThrows(IOException.class, () -> ShellWorkspacePermissions.prepare(link, tenants, owner));
    }

    @Test
    void visitsExistingNestedFilesWithoutFollowingLinks() throws IOException {
        Path tenants = tempDir.resolve(".baibaiyu/tenants");
        Path workspace = tenants.resolve("tenant-a/workspaces/session-1");
        Path nested = Files.createDirectories(workspace.resolve("output"));
        Path file = Files.writeString(nested.resolve("draft.txt"), "draft");
        Path outside = Files.writeString(tempDir.resolve("outside.txt"), "outside");
        List<Path> visited = new ArrayList<>();

        ShellWorkspacePermissions.prepare(workspace, tenants, Files.getOwner(workspace),
                (path, owner) -> visited.add(path));
        assertEquals(List.of(workspace, nested, file), visited);

        Path link = workspace.resolve("outside-link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            return;
        }
        visited.clear();
        ShellWorkspacePermissions.prepare(workspace, tenants, Files.getOwner(workspace),
                (path, owner) -> visited.add(path));
        assertEquals(List.of(workspace, nested, file), visited);
    }

    @Test
    void transfersOwnershipOfExistingNestedFilesWhenPrivileged() throws IOException {
        assumeTrue("root".equals(System.getProperty("user.name")));
        Path tenants = tempDir.resolve(".baibaiyu/tenants");
        Path workspace = tenants.resolve("tenant-a/workspaces/session-1");
        Path nested = Files.createDirectories(workspace.resolve("output"));
        Path file = Files.writeString(nested.resolve("draft.txt"), "draft");
        assumeTrue(Files.getFileAttributeView(workspace, PosixFileAttributeView.class) != null);
        var initialOwner = Files.getOwner(file);
        var shellUser = workspace.getFileSystem().getUserPrincipalLookupService();
        try {
            var proxyOwner = shellUser.lookupPrincipalByName("shellproxy");
            assumeTrue(!initialOwner.equals(proxyOwner));
            ShellWorkspacePermissions.prepare(workspace, tenants, proxyOwner);
            assertEquals(proxyOwner, Files.getOwner(workspace));
            assertEquals(proxyOwner, Files.getOwner(nested));
            assertEquals(proxyOwner, Files.getOwner(file));
            assertNotEquals(proxyOwner, initialOwner);
        } catch (UserPrincipalNotFoundException e) {
            assumeTrue(false, "shellproxy user is not available");
        }
    }
}
