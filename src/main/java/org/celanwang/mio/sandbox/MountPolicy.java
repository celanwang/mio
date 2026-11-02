package org.celanwang.mio.sandbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Rejects mounts that would expose host credentials or an overly broad directory. */
public final class MountPolicy {
    private static final List<String> CREDENTIAL_NAMES = List.of(
            ".env", ".weibo", ".ssh", ".aws", ".gnupg", ".config");

    private MountPolicy() {
    }

    public static void assertNarrow(Path path, boolean hostCredentialDirsAllowed) {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.getParent() == null) {
            throw new SandboxRefusedException("MOUNT_TOO_BROAD", "拒绝挂载文件系统根");
        }
        if (hostCredentialDirsAllowed) {
            return;
        }
        for (Path part : normalized) {
            if (CREDENTIAL_NAMES.contains(part.toString())) {
                throw new SandboxRefusedException("CREDENTIAL_MOUNT", "拒绝挂载宿主机凭据目录");
            }
        }
        for (String name : CREDENTIAL_NAMES) {
            if (Files.exists(normalized.resolve(name))) {
                throw new SandboxRefusedException("CREDENTIAL_MOUNT", "拒绝挂载包含凭据目录的路径");
            }
        }
    }
}
