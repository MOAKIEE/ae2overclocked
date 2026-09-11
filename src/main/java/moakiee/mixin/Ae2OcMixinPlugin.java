package moakiee.mixin;

import java.util.List;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;
import net.minecraftforge.fml.loading.LoadingModList;
import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;
import org.objectweb.asm.tree.ClassNode;

/** Resolves optional compatibility once at startup, without loading optional Java classes. */
public final class Ae2OcMixinPlugin implements IMixinConfigPlugin {
    private static final Map<String, Boolean> ENABLED = new HashMap<>();
    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public List<String> getMixins() { return null; }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) {}
    @Override public void preApply(String name, ClassNode target, String mixin, IMixinInfo info) {}
    @Override public void postApply(String name, ClassNode target, String mixin, IMixinInfo info) {}
    @Override public boolean shouldApplyMixin(String target, String mixin) {
        String id = target.startsWith("com.glodblock.github.extendedae.") ? "expatternprovider"
                : target.startsWith("net.pedroksl.advanced_ae.") ? "advanced_ae"
                : target.startsWith("io.github.lounode.ae2cs.") ? "ae2cs" : null;
        if (id == null) return true;
        return ENABLED.computeIfAbsent(id, Ae2OcMixinPlugin::validate);
    }
    private static boolean validate(String id) {
        var logger = LogManager.getLogger("ae2_overclocked/compat");
        var list = LoadingModList.get();
        var file = list == null ? null : list.getModFileById(id);
        if (file == null) { logger.info("Compatibility {} disabled: mod absent", id); return false; }
        String[] targets = switch (id) {
            case "expatternprovider" -> new String[]{
                    "com.glodblock.github.extendedae.common.me.InscriberThread#tick#()Lappeng/api/networking/ticking/TickRateModulation;",
                    "com.glodblock.github.extendedae.common.tileentities.TileCircuitCutter#getInput#()Lappeng/util/inv/AppEngInternalInventory;"};
            case "advanced_ae" -> new String[]{"net.pedroksl.advanced_ae.common.entities.ReactionChamberEntity#getTask#()Lnet/pedroksl/advanced_ae/recipes/ReactionChamberRecipe;"};
            case "ae2cs" -> new String[]{
                    "io.github.lounode.ae2cs.common.block.entity.CircuitEtcherBlockEntity#serverTick#()V",
                    "io.github.lounode.ae2cs.common.block.entity.CrystalAggregatorBlockEntity#serverTick#()V",
                    "io.github.lounode.ae2cs.common.block.entity.CrystalPulverizerBlockEntity#serverTick#()V",
                    "io.github.lounode.ae2cs.common.block.entity.EntropyVariationReactionChamberBlockEntity#serverTick#()V"};
            default -> new String[0];
        };
        try {
            for (String signature : targets) {
                String[] parts = signature.split("#");
                var node = MixinService.getService().getBytecodeProvider().getClassNode(parts[0]);
                if (node.methods.stream().noneMatch(method -> method.name.equals(parts[1]) && method.desc.equals(parts[2])))
                    throw new IllegalStateException("Missing signature: " + signature);
            }
            logger.info("Compatibility {} enabled; versions {}", id, file.getMods().stream().map(mod -> mod.getVersion().toString()).toList());
            return true;
        } catch (Exception failure) {
            logger.error("Compatibility {} disabled: {}", id, failure.getMessage());
            return false;
        }
    }
}
