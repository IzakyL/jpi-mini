package io.github.izakyl.jpi.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.github.izakyl.jpi.ai.internal.StrictJson;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plaintext JSON storage. Operations on the same normalized path are serialized within this JVM.
 * Keys are arbitrary nonblank strings. Use namespaced keys such as modid.something for shared stores.
 * Each operation reads the file again so separate store instances and external edits are visible.
 * Concurrent writers in separate processes require external coordination.
 */
public final class CredentialStore {

    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

    private static final ConcurrentHashMap<Path, Object> LOCKS = new ConcurrentHashMap<>();

    private final Path file;

    private final Object lock;

    public CredentialStore(Path file) {
        this.file = Objects.requireNonNull(file).toAbsolutePath().normalize();
        lock = LOCKS.computeIfAbsent(this.file, ignored -> new Object());
    }

    public Path path() {
        return file;
    }

    public Optional<Credential> get(String key) throws IOException {
        synchronized (lock) {
            return Optional.ofNullable(read().get(requireKey(key)));
        }
    }

    public void put(String key, Credential credential) throws IOException {
        requireKey(key);
        Objects.requireNonNull(credential);
        synchronized (lock) {
            var credentials = read();
            credentials.put(key, credential);
            write(credentials);
        }
    }

    public void remove(String key) throws IOException {
        requireKey(key);
        synchronized (lock) {
            var credentials = read();
            if (credentials.remove(key) != null) {
                write(credentials);
            }
        }
    }

    public Set<String> keys() throws IOException {
        synchronized (lock) {
            return Set.copyOf(read().keySet());
        }
    }

    private Map<String, Credential> read() throws IOException {
        String text;
        try {
            secure(file);
            text = Files.readString(file);
        } catch (NoSuchFileException ignored) {
            return new HashMap<>();
        }
        try {
            var root = StrictJson.parse(text).getAsJsonObject();
            var result = new HashMap<String, Credential>();
            for (var entry : root.entrySet()) {
                var value = entry.getValue().getAsJsonObject();
                if (!value.has("apiKey") && value.has("accessToken") && value.has("expiresAt")) {
                    // Ignore entries from older stores so valid API keys remain usable.
                    continue;
                }
                result.put(requireKey(entry.getKey()), new Credential.ApiKey(string(value, "apiKey")));
            }
            return result;
        } catch (RuntimeException ignored) {
            // Preserve corrupt data for manual recovery without exposing its contents in an exception or log.
            var backup = file.resolveSibling(file.getFileName() + ".corrupt-" + System.nanoTime());
            move(file, backup);
            secure(backup);
            return new HashMap<>();
        }
    }

    private void write(Map<String, Credential> credentials) throws IOException {
        var root = new JsonObject();
        credentials.forEach((name, credential) -> {
            var value = new JsonObject();
            value.addProperty("apiKey", credential.apiKey());
            root.add(name, value);
        });
        Files.createDirectories(file.getParent());
        var posix = Files.getFileAttributeView(file.getParent(), PosixFileAttributeView.class) != null;
        var temporary = posix
                ? Files.createTempFile(file.getParent(), ".credentials-", ".tmp",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                : Files.createTempFile(file.getParent(), ".credentials-", ".tmp");
        try {
            secure(temporary);
            Files.writeString(temporary, JSON.toJson(root) + "\n");
            move(temporary, file);
            secure(file);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void secure(Path path) throws IOException {
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
            return;
        }
        var acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (acl != null) {
            var ownerOnly = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(acl.getOwner())
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .build();
            acl.setAcl(List.of(ownerOnly));
            if (acl.getAcl().stream().anyMatch(entry -> entry.type() == AclEntryType.ALLOW
                    && !entry.principal().equals(ownerOnly.principal()))) {
                throw new IOException("Credential file ACL allows access by a non-owner");
            }
        }
    }

    private static String requireKey(String key) {
        if (Objects.requireNonNull(key).isBlank()) {
            throw new IllegalArgumentException("Credential key must not be blank");
        }
        return key;
    }

    private static String string(JsonObject value, String key) {
        var element = value.getAsJsonPrimitive(key);
        if (element == null || !element.isString()) {
            throw new IllegalArgumentException("Invalid credential field");
        }
        return element.getAsString();
    }
}
