package org.celanwang.mio.sandbox;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/** Per-principal directory jail. Paths are compared by normalized name components, not string prefix. */
@Component
public class WorkspaceLayout {
    private final Path root;

    public WorkspaceLayout(SandboxProperties properties) {
        this(properties.getRoot());
    }

    public WorkspaceLayout(Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        if (normalized.getParent() == null) {
            throw new IllegalArgumentException("沙箱根目录不能是文件系统根");
        }
        this.root = normalized;
    }

    public Path root() {
        return root;
    }

    public Path principalRoot(PrincipalId principal) {
        Path dir = root.resolve(principal.value()).normalize();
        requireInside(root, dir);
        return dir;
    }

    public Path ensure(PrincipalId principal) {
        Path dir = principalRoot(principal);
        try {
            Files.createDirectories(dir);
            try {
                Files.setPosixFilePermissions(dir, Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX volumes still keep the path jail.
            }
            return dir;
        } catch (IOException failure) {
            throw new SandboxUnavailableException("WORKSPACE_UNAVAILABLE", "主体工作区无法创建", failure);
        }
    }

    public void requireInside(Path parent, Path candidate) {
        Path base = parent.toAbsolutePath().normalize();
        Path path = candidate.toAbsolutePath().normalize();
        if (!path.startsWith(base)) {
            throw new SandboxRefusedException("CROSS_PRINCIPAL", "路径越出主体工作区，已拒绝");
        }
    }

    /** Ride state for the local owner stays in the existing data directory. Other principals get a private tree. */
    public Path rideState(PrincipalId principal, Path localOwnerDataDirectory) {
        if (principal.isLocalOwner()) {
            Path data = localOwnerDataDirectory.toAbsolutePath().normalize();
            if (data.getParent() == null) {
                throw new SandboxRefusedException("MOUNT_TOO_BROAD", "出行数据目录不能是文件系统根");
            }
            return data;
        }
        return ensure(principal).resolve("tencent-ride").normalize();
    }
}
