package io.github.izakyl.jpi.mc;

@net.neoforged.fml.common.Mod("jpimini")
public final class NeoForgeEntry {

    public NeoForgeEntry() {
        System.getLogger("jpimini").log(System.Logger.Level.INFO, "jpimini loaded " + JpiMini.version());
    }
}
