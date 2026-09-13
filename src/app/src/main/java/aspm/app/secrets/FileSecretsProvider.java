package aspm.app.secrets;

import aspm.sharedkernel.secrets.SecretReference;
import aspm.sharedkernel.secrets.SecretsProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code file:<relative/path>} — a secret mounted as a file. Read-only. {@code OPS-DEP-019}.
 *
 * <p>This is the mesh-native default. Kubernetes projects a {@code Secret}, a CSI secrets-store volume,
 * or an External Secrets Operator target into the pod as files under one directory, and the runtime
 * reads them per use rather than holding them in its environment for its whole life — which is what
 * {@code OPS-DEP-020} asks for and what an environment variable cannot do. Rotation is a re-mount the
 * process never has to know about, because nothing here caches.
 *
 * <p>The path is relative to the mount directory and confined to it. A reference of
 * {@code file:../../etc/shadow} normalises outside the directory and is refused; so is a symbolic link
 * whose target escapes, because the real path is checked, not the textual one. Refusing rather than
 * following is the difference between a secrets mount and a file-read primitive.
 *
 * <p>Not tenant-scoped, like {@link EnvSecretsProvider}: files are mounted per deployment. A tenant's own
 * secrets go to a writable provider.
 */
public final class FileSecretsProvider implements SecretsProvider {

    public static final String KIND = "file";
    public static final String DIRECTORY_VARIABLE = "ASPM_SECRETS_DIR";
    public static final String DEFAULT_DIRECTORY = "/var/run/secrets/aspm";

    private final Path root;

    public FileSecretsProvider(Path root) {
        this.root = Objects.requireNonNull(root).toAbsolutePath().normalize();
    }

    public static FileSecretsProvider from(java.util.Map<String, String> environment) {
        return new FileSecretsProvider(Path.of(environment.getOrDefault(DIRECTORY_VARIABLE, DEFAULT_DIRECTORY)));
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public Optional<char[]> resolve(UUID tenantId, SecretReference reference) {
        if (!KIND.equals(reference.provider())) {
            return Optional.empty();
        }
        String relative = reference.path();
        if (relative.startsWith("/") || relative.contains("\\") || relative.contains("\0")) {
            return Optional.empty();
        }
        Path candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root)) {
            return Optional.empty();
        }
        try {
            if (!Files.isRegularFile(candidate)) {
                return Optional.empty();
            }
            Path real = candidate.toRealPath();
            if (!real.startsWith(root.toRealPath())) {
                // A link that points out of the mount. Refused for the same reason as "..".
                return Optional.empty();
            }
            byte[] bytes = Files.readAllBytes(real);
            // Mounted secrets frequently end in a newline that was never part of the value.
            String text = new String(bytes, StandardCharsets.UTF_8).stripTrailing();
            return text.isEmpty() ? Optional.empty() : Optional.of(text.toCharArray());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean writable() {
        return false;
    }

    @Override
    public boolean tenantScoped() {
        return false;
    }

    @Override
    public SecretReference store(UUID tenantId, String namespace, String name, char[] value) {
        throw new SecretsException("READ_ONLY_PROVIDER",
                "a mounted secrets directory is written by the platform that mounts it, not by the application");
    }

    @Override
    public void destroy(UUID tenantId, SecretReference reference) {
        // The mount is owned by the orchestrator; nothing to do here.
    }

    @Override
    public String description() {
        return "file (mounted secrets under " + root + "; read per use, never cached)";
    }
}
