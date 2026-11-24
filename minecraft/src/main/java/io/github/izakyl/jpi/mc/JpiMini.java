package io.github.izakyl.jpi.mc;

import io.github.izakyl.jpi.ai.ProviderRegistry;
import io.github.izakyl.jpi.auth.CredentialStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Properties;
import java.util.function.Supplier;

/** Shared services available without any Minecraft loader initialization. */
public final class JpiMini {

    private JpiMini() {
    }

    public static CredentialStore credentials() {
        return CredentialsHolder.STORE;
    }

    public static ProviderRegistry providers() {
        return ProvidersHolder.REGISTRY;
    }

    public static String version() {
        return VersionHolder.VERSION;
    }

    private static final class CredentialsHolder {

        private static final CredentialStore STORE = new CredentialStore(
                gameDirectory().resolve("config/jpi-mini/credentials.json"));

        private static Path gameDirectory() {
            String override = System.getProperty("jpimini.gameDir");
            if (override != null) {
                return Path.of(override);
            }
            Path path = loaderDirectory(() -> net.neoforged.fml.loading.FMLPaths.GAMEDIR.get());
            if (path == null) {
                path = loaderDirectory(() -> net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get());
            }
            if (path == null) {
                // Quilt also exposes the Fabric Loader API.
                path = loaderDirectory(() -> net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir());
            }
            return path == null ? Path.of(System.getProperty("user.dir")) : path;
        }

        private static Path loaderDirectory(Supplier<Path> lookup) {
            try {
                return lookup.get();
            } catch (LinkageError | IllegalStateException unavailable) {
                return null;
            }
        }
    }

    private static final class ProvidersHolder {

        private static final ProviderRegistry REGISTRY = ProviderRegistry.defaults();
    }

    private static final class VersionHolder {

        private static final String VERSION = readVersion();

        private static String readVersion() {
            try (var input = JpiMini.class.getResourceAsStream("/jpimini.properties")) {
                if (input == null) {
                    throw new IllegalStateException("Missing jpimini version resource");
                }
                var properties = new Properties();
                properties.load(input);
                return properties.getProperty("version");
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }
    }
}
