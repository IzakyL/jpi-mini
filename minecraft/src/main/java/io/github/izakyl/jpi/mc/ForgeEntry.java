package io.github.izakyl.jpi.mc;

@net.minecraftforge.fml.common.Mod("jpimini")
public final class ForgeEntry {

    public ForgeEntry() {
        System.getLogger("jpimini").log(System.Logger.Level.INFO, "jpimini loaded " + JpiMini.version());
    }
}
