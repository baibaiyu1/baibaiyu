package com.gmd.faq.server.main.runtime.shellproxy;

import com.gmd.faq.server.main.common.consts.SysConst;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.UserPrincipal;

/** Grants the unprivileged shell proxy access to one session workspace. */
public final class ShellWorkspacePermissions {
    private static final String SHELL_USER = "shellproxy";

    private ShellWorkspacePermissions() {
    }

    public static void prepare(Path workspaceDir) throws IOException {
        if (workspaceDir == null) {
            throw new IOException("Shell session workspace is not configured");
        }
        Path tenantsRoot = Path.of(System.getProperty("user.dir"),
                SysConst.ROOT_DIR_NAME, SysConst.TENANTS_DIR_NAME).toAbsolutePath().normalize();
        UserPrincipal shellUser = workspaceDir.getFileSystem()
                .getUserPrincipalLookupService().lookupPrincipalByName(SHELL_USER);
        prepare(workspaceDir, tenantsRoot, shellUser);
    }

    static void prepare(Path workspaceDir, Path tenantsRoot, UserPrincipal shellUser) throws IOException {
        prepare(workspaceDir, tenantsRoot, shellUser, ShellWorkspacePermissions::setOwner);
    }

    static void prepare(Path workspaceDir, Path tenantsRoot, UserPrincipal shellUser,
                        OwnerSetter ownerSetter) throws IOException {
        Path workspace = workspaceDir.toAbsolutePath().normalize();
        Path root = tenantsRoot.toAbsolutePath().normalize();
        if (!workspace.startsWith(root)) {
            throw new IOException("Shell working directory is outside tenant workspaces: " + workspace);
        }
        Path relative = root.relativize(workspace);
        if (relative.getNameCount() != 3
                || !relative.getName(1).toString().equals(SysConst.WORKSPACE_DIR_NAME)) {
            throw new IOException("Shell working directory is not a session workspace: " + workspace);
        }

        Path current = root.getParent().getParent();
        for (Path part : current.relativize(workspace)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Symbolic links are not allowed in shell workspace paths: " + current);
            }
        }
        if (!Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Session workspace does not exist: " + workspace);
        }

        Files.walkFileTree(workspace, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                ownerSetter.setOwner(directory, shellUser);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (!attributes.isSymbolicLink()) {
                    ownerSetter.setOwner(file, shellUser);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @FunctionalInterface
    interface OwnerSetter {
        void setOwner(Path path, UserPrincipal owner) throws IOException;
    }

    private static void setOwner(Path path, UserPrincipal owner) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(
                path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            throw new IOException("POSIX ownership is required for shell workspace: " + path);
        }
        if (!view.readAttributes().owner().equals(owner)) {
            view.setOwner(owner);
        }
    }
}
