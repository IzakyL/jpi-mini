package io.github.izakyl.jpi.mc;

import com.google.gson.Gson;
import java.io.DataInputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UniversalJarTest {

    @TempDir
    Path gameDir;

    private final Path jar = Path.of(System.getProperty("jpimini.testJar"));

    @Test
    void containsExpandedMetadataAndJava17ClassesWithoutStubsOrGson() throws Exception {
        try (var archive = new JarFile(jar.toFile())) {
            for (var name : List.of("fabric.mod.json", "quilt.mod.json", "META-INF/mods.toml",
                    "META-INF/neoforge.mods.toml", "jpimini.properties")) {
                var entry = archive.getJarEntry(name);
                assertNotNull(entry, name);
                try (var input = archive.getInputStream(entry)) {
                    var text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    assertFalse(text.contains("${version}"), name);
                    assertTrue(text.contains(System.getProperty("jpimini.testVersion")), name);
                }
            }
            assertNotNull(archive.getJarEntry("pack.mcmeta"));
            for (var entry : archive.stream().filter(item -> item.getName().endsWith(".class")).toList()) {
                assertTrue(entry.getName().startsWith("io/github/izakyl/jpi/"), entry.getName());
                try (var input = new DataInputStream(archive.getInputStream(entry))) {
                    assertEquals(0xcafebabe, input.readInt());
                    input.readUnsignedShort();
                    assertEquals(61, input.readUnsignedShort(), entry.getName());
                }
            }
        }
    }

    @Test
    void loadsPublicServicesAndBothEntriesWithOnlyTheJarAndGson() throws Exception {
        var gson = Gson.class.getProtectionDomain().getCodeSource().getLocation();
        var urls = new URL[] {jar.toUri().toURL(), gson};
        var previous = System.getProperty("jpimini.gameDir");
        System.setProperty("jpimini.gameDir", gameDir.toString());
        try (var isolated = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            var entry = isolated.loadClass("io.github.izakyl.jpi.mc.JpiMini");
            assertEquals(System.getProperty("jpimini.testVersion"), entry.getMethod("version").invoke(null));
            var store = entry.getMethod("credentials").invoke(null);
            assertNotNull(store.getClass().getMethod("keys").invoke(store));
            assertNotNull(entry.getMethod("providers").invoke(null));
            for (var name : List.of("ForgeEntry", "NeoForgeEntry")) {
                var type = isolated.loadClass("io.github.izakyl.jpi.mc." + name);
                assertEquals(0, type.getDeclaredAnnotations().length);
                assertNotNull(type.getConstructor().newInstance());
            }
        } finally {
            if (previous == null) {
                System.clearProperty("jpimini.gameDir");
            } else {
                System.setProperty("jpimini.gameDir", previous);
            }
        }
    }
}
