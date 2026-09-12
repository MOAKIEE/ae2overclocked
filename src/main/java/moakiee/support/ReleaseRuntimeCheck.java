package moakiee.support;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.blockentity.misc.InscriberBlockEntity;
import appeng.core.definitions.AEBlocks;
import moakiee.Ae2Overclocked;
import moakiee.ae2oc.api.ResourceAmount;
import moakiee.ae2oc.compat.ae2.ManagedItemStorages;
import moakiee.ae2oc.compat.ae2.ProcessingCodec;
import moakiee.ae2oc.core.execution.ProcessingState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Items;
import net.minecraftforge.fml.ModList;

/** Production-launch proof that the exact release artifact loads and executes its processing mixins. */
public final class ReleaseRuntimeCheck {
    private ReleaseRuntimeCheck() {}

    public static Result verify(MinecraftServer server) throws Exception {
        Path loadedJar = ModList.get().getModFileById(Ae2Overclocked.MODID).getFile().getFilePath()
                .toAbsolutePath().normalize();
        String actualHash = sha256(loadedJar);
        String expectedName = requireProperty("ae2oc.releaseJarName");
        String expectedHash = requireProperty("ae2oc.releaseJarSha256");
        if (!loadedJar.getFileName().toString().equals(expectedName)) {
            throw new IllegalStateException("Loaded release jar name mismatch: " + loadedJar);
        }
        if (!actualHash.equalsIgnoreCase(expectedHash)) {
            throw new IllegalStateException("Loaded release jar hash mismatch: " + actualHash);
        }

        var level = server.overworld();
        var pos = level.getSharedSpawnPos().above(8);
        if (!level.setBlockAndUpdate(pos, AEBlocks.INSCRIBER.block().defaultBlockState())) {
            throw new IllegalStateException("Could not place representative release-check machine");
        }
        try {
            if (!(level.getBlockEntity(pos) instanceof InscriberBlockEntity machine)) {
                throw new IllegalStateException("Representative inscriber block entity missing");
            }
            var saved = machine.saveWithFullMetadata();
            saved.put("ae2ocProcessing", ProcessingCodec.write(new ProcessingState<AEKey>("ae2oc:release_check",
                    List.of(), List.of(new ResourceAmount<>(AEItemKey.of(Items.GOLD_INGOT), 2)), 0, 0, 0)));
            machine.load(saved);
            machine.tickingRequest(null, 1);
            var output = ManagedItemStorages.slots(machine.getInternalInventory()).get(3).read();
            if (output == null || !output.key().equals(AEItemKey.of(Items.GOLD_INGOT)) || output.amount() != 2
                    || machine.saveWithFullMetadata().contains("ae2ocProcessing")) {
                throw new IllegalStateException("Release jar processing mixin did not settle representative batch: " + output);
            }
            return new Result(loadedJar, actualHash, output.amount());
        } finally {
            level.removeBlock(pos, false);
        }
    }

    private static String requireProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing release-check property " + name);
        return value;
    }

    private static String sha256(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0;) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public record Result(Path loadedJar, String sha256, long representativeOutput) {}
}
