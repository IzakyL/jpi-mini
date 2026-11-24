package net.fabricmc.loader.api;

import java.nio.file.Path;

public interface FabricLoader {

    static FabricLoader getInstance() {
        throw new UnsupportedOperationException();
    }

    Path getGameDir();
}
