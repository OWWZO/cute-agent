package org.wwz.ai.application.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves the JWT signing secret without requiring local development setup.
 */
@Component
public class JwtSecretProvider {

    private static final int SECRET_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String configuredSecret;
    private final Path secretFile;

    @Autowired
    public JwtSecretProvider(@Value("${auth.jwt.secret:}") String configuredSecret) {
        this(configuredSecret, defaultSecretFile());
    }

    public JwtSecretProvider(String configuredSecret, Path secretFile) {
        this.configuredSecret = configuredSecret;
        this.secretFile = secretFile.toAbsolutePath().normalize();
    }

    public synchronized String resolve() {
        String explicitSecret = normalize(configuredSecret);
        if (explicitSecret != null) {
            return explicitSecret;
        }

        try {
            if (Files.exists(secretFile)) {
                return readSecret();
            }

            Path parent = secretFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            String generatedSecret = generateSecret();
            try {
                createOwnerOnlySecretFile(generatedSecret);
                return generatedSecret;
            } catch (FileAlreadyExistsException ignored) {
                return readSecret();
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Unable to load or create JWT secret at " + secretFile
                            + "; set CUTE_AUTH_JWT_SECRET or make the runtime data directory writable",
                    e
            );
        }
    }

    private void createOwnerOnlySecretFile(String generatedSecret) throws IOException {
        Set<PosixFilePermission> ownerOnly =
                PosixFilePermissions.fromString("rw-------");
        boolean created = false;
        try {
            try {
                Files.createFile(secretFile, PosixFilePermissions.asFileAttribute(ownerOnly));
                created = true;
            } catch (UnsupportedOperationException e) {
                Files.createFile(secretFile);
                created = true;
                restrictToOwner(secretFile, ownerOnly);
            }
            Files.writeString(
                    secretFile,
                    generatedSecret + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
        } catch (IOException | RuntimeException e) {
            if (created) {
                try {
                    Files.deleteIfExists(secretFile);
                } catch (IOException cleanupError) {
                    e.addSuppressed(cleanupError);
                }
            }
            throw e;
        }
    }

    private static void restrictToOwner(Path file, Set<PosixFilePermission> ownerOnly) throws IOException {
        FileStore store = Files.getFileStore(file);
        if (store.supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(file, ownerOnly);
            return;
        }
        if (store.supportsFileAttributeView("acl")) {
            AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class);
            if (view == null) {
                throw new IOException("ACL view unavailable for JWT secret file: " + file);
            }
            AclEntry ownerAccess = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(view.getOwner())
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .build();
            view.setAcl(List.of(ownerAccess));
            return;
        }
        throw new IOException("Owner-only permissions are unavailable for JWT secret file: " + file);
    }

    private String readSecret() throws IOException {
        String secret = normalize(Files.readString(secretFile, StandardCharsets.UTF_8));
        if (secret == null) {
            throw new IOException("JWT secret file is empty: " + secretFile);
        }
        return secret;
    }

    private String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static Path defaultSecretFile() {
        return Path.of(System.getProperty("user.dir", "."), "data", "auth.jwt.secret")
                .toAbsolutePath()
                .normalize();
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
