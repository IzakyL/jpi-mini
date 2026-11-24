package io.github.izakyl.jpi.auth;

import io.github.izakyl.jpi.ai.Model;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CredentialStoreTest {

    @TempDir
    Path directory;

    @Test
    void roundTripsKeysAndRemovesProviders() throws Exception {
        var path = directory.resolve("nested/credentials.json");
        var store = new CredentialStore(path);
        var key = new Credential.ApiKey("secret-key");
        assertTrue(store.get("openai").isEmpty());
        assertEquals(Set.of(), store.keys());
        store.put("openai", key);
        store.put("other", new Credential.ApiKey("other-key"));
        var reopened = new CredentialStore(path);
        assertEquals(key, reopened.get("openai").orElseThrow());
        assertEquals("other-key", reopened.get("other").orElseThrow().apiKey());
        assertEquals(Set.of("openai", "other"), reopened.keys());
        assertThrows(UnsupportedOperationException.class, () -> reopened.keys().clear());
        reopened.remove("openai");
        reopened.remove("absent");
        assertTrue(store.get("openai").isEmpty());
        assertEquals(Set.of("other"), store.keys());
        assertFalse(store.toString().contains("secret-key"));
        assertEquals("ApiKey[redacted]", key.toString());
        try (var files = Files.list(path.getParent())) {
            assertEquals(1, files.count());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{broken", "null", "[]", "{} trailing", "{\"p\":{\"apiKey\":123}}"})
    void backsUpCorruptFilesWithoutLeakingContents(String contents) throws Exception {
        var path = directory.resolve("credentials.json");
        Files.writeString(path, contents);
        var store = new CredentialStore(path);
        assertEquals(Set.of(), store.keys());
        assertFalse(Files.exists(path));
        try (var files = Files.list(directory)) {
            var backup = files.findFirst().orElseThrow();
            assertTrue(backup.getFileName().toString().startsWith("credentials.json.corrupt-"));
            assertEquals(contents, Files.readString(backup));
            assertPrivate(backup);
        }
        store.put("new", new Credential.ApiKey("replacement"));
        assertEquals("replacement", ((Credential.ApiKey) store.get("new").orElseThrow()).apiKey());
    }

    @Test
    void restrictsNewAndExistingFilesAndKeepsOldFileOnWriteFailure() throws Exception {
        var path = directory.resolve("credentials.json");
        var store = new CredentialStore(path);
        store.put("p", new Credential.ApiKey("original"));
        assertPrivate(path);
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"));
            store.get("p");
            assertPrivate(path);
        }
        var bad = directory.resolve("not-a-directory");
        Files.writeString(bad, "sentinel");
        assertThrows(IOException.class, () -> new CredentialStore(bad.resolve("credentials.json"))
                .put("p", new Credential.ApiKey("never-written")));
        assertEquals("sentinel", Files.readString(bad));
        assertEquals("original", ((Credential.ApiKey) store.get("p").orElseThrow()).apiKey());
    }

    @Test
    void concurrentStoreInstancesPreserveAllUpdates() throws Exception {
        var path = directory.resolve("credentials.json");
        var executor = Executors.newFixedThreadPool(8);
        try {
            var tasks = new ArrayList<Callable<Void>>();
            for (int index = 0; index < 48; index++) {
                var provider = "provider-" + index;
                tasks.add(() -> {
                    var store = new CredentialStore(path);
                    store.put(provider, new Credential.ApiKey(provider));
                    assertTrue(store.get(provider).isPresent());
                    return null;
                });
            }
            for (var future : executor.invokeAll(tasks, 10, TimeUnit.SECONDS)) {
                future.get();
            }
            assertEquals(48, new CredentialStore(path).keys().size());
            assertPrivate(path);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void resolverReadsUpdatedProviderKeys() throws Exception {
        var store = new CredentialStore(directory.resolve("credentials.json"));
        var resolver = new StoreApiKeyResolver(store);
        var model = Model.builder("model", "http://localhost").provider("p").build();
        assertEquals(null, resolver.resolve(model));
        store.put("other", new Credential.ApiKey("wrong-provider"));
        store.put("p", new Credential.ApiKey("first"));
        assertEquals("first", resolver.resolve(model));
        store.put("p", new Credential.ApiKey("second"));
        assertEquals("second", resolver.resolve(model));
        assertThrows(IllegalArgumentException.class, () -> store.put(" ", new Credential.ApiKey("x")));
    }

    @Test
    void readsApiKeysFromMixedLegacyStore() throws Exception {
        var path = directory.resolve("credentials.json");
        Files.writeString(path, """
                {"openai":{"apiKey":"existing"},"legacy":{"accessToken":"old","expiresAt":"2030-01-01T00:00:00Z"}}
                """);
        var store = new CredentialStore(path);
        assertEquals(Set.of("openai"), store.keys());
        assertEquals("existing", store.get("openai").orElseThrow().apiKey());
        store.put("other", new Credential.ApiKey("new"));
        assertEquals(Set.of("openai", "other"), store.keys());
        assertFalse(Files.readString(path).contains("accessToken"));
    }

    private static void assertPrivate(Path path) throws IOException {
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(path));
        }
    }
}
