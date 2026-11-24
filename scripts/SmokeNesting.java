import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.tools.ToolProvider;

/** Builds two nested-library hosts and a runtime probe using only the JDK and cached loader. */
class SmokeNesting {

    public static void main(String[] args) throws IOException {
        var library = Path.of(args[0]);
        var mods = Path.of(args[1]);
        var probe = mods.getParent().resolve("probe");
        Files.createDirectories(probe);
        var source = probe.resolve("Probe.java");
        Files.writeString(source, """
                package io.github.izakyl.jpi.smoke;

                import io.github.izakyl.jpi.mc.JpiMini;
                import net.fabricmc.api.ModInitializer;
                import net.fabricmc.loader.api.FabricLoader;

                public final class Probe implements ModInitializer {

                    @Override
                    public void onInitialize() {
                        var copies = FabricLoader.getInstance().getAllMods().stream()
                                .filter(mod -> mod.getMetadata().getId().equals("jpimini")).count();
                        if (copies != 1) {
                            throw new IllegalStateException("Expected one jpimini mod, got " + copies);
                        }
                        var credentials = JpiMini.credentials();
                        if (credentials != JpiMini.credentials() || JpiMini.providers() != JpiMini.providers()) {
                            throw new IllegalStateException("Services were not shared");
                        }
                        System.out.println("jpimini nested probe: copies=1; shared services available; version="
                                + JpiMini.version());
                    }
                }
                """);
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("The nesting fixture requires a full JDK");
        }
        var classpath = library + System.getProperty("path.separator") + args[2];
        var options = new ArrayList<>(List.of("--release", "17", "-classpath", classpath, "-d", probe.toString()));
        options.add(source.toString());
        if (compiler.run(null, null, null, options.toArray(String[]::new)) != 0) {
            throw new IllegalStateException("Could not compile nested-library probe");
        }
        for (var id : new String[] {"smokehost_a", "smokehost_b"}) {
            var entrypoints = id.equals("smokehost_a")
                    ? ",\"entrypoints\":{\"main\":[\"io.github.izakyl.jpi.smoke.Probe\"]}" : "";
            var metadata = """
                    {
                      "schemaVersion": 1,
                      "id": "%s",
                      "version": "1.0.0",
                      "jars": [{"file": "META-INF/jars/jpi-mini-mc.jar"}],
                      "depends": {"jpimini": ">=0.1.0"}%s
                    }
                    """.formatted(id, entrypoints);
            try (var output = new ZipOutputStream(Files.newOutputStream(mods.resolve(id + ".jar")))) {
                output.putNextEntry(new ZipEntry("fabric.mod.json"));
                output.write(metadata.getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
                output.putNextEntry(new ZipEntry("META-INF/jars/jpi-mini-mc.jar"));
                Files.copy(library, output);
                output.closeEntry();
                if (id.equals("smokehost_a")) {
                    var name = "io/github/izakyl/jpi/smoke/Probe.class";
                    output.putNextEntry(new ZipEntry(name));
                    Files.copy(probe.resolve(name), output);
                    output.closeEntry();
                }
            }
        }
    }
}
