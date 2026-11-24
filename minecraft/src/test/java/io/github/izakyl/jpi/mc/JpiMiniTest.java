package io.github.izakyl.jpi.mc;

import io.github.izakyl.jpi.auth.Credential;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JpiMiniTest {

    @TempDir
    Path gameDir;

    @Test
    void initializesWithoutLoadersAndSharesServicesAcrossThreads() throws Exception {
        var previous = System.getProperty("jpimini.gameDir");
        System.setProperty("jpimini.gameDir", gameDir.toString());
        var executor = Executors.newFixedThreadPool(8);
        try {
            var tasks = IntStream.range(0, 32).mapToObj(index -> (Callable<Object[]>) () ->
                    new Object[] {JpiMini.credentials(), JpiMini.providers(), JpiMini.version()}).toList();
            for (var future : executor.invokeAll(tasks)) {
                var services = future.get();
                assertSame(JpiMini.credentials(), services[0]);
                assertSame(JpiMini.providers(), services[1]);
                assertEquals(System.getProperty("jpimini.testVersion"), services[2]);
            }
            var path = gameDir.resolve("config/jpi-mini/credentials.json");
            assertFalse(Files.exists(path));
            JpiMini.credentials().put("test", new Credential.ApiKey("value"));
            assertTrue(Files.exists(path));
            assertEquals(new Credential.ApiKey("value"), JpiMini.credentials().get("test").orElseThrow());
            new ForgeEntry();
            new NeoForgeEntry();
        } finally {
            executor.shutdownNow();
            if (previous == null) {
                System.clearProperty("jpimini.gameDir");
            } else {
                System.setProperty("jpimini.gameDir", previous);
            }
        }
    }
}
