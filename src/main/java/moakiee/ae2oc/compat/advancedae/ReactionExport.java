package moakiee.ae2oc.compat.advancedae;

import java.util.function.Function;
import appeng.api.config.Actionable;
import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.MEStorage;
import moakiee.Ae2OcConfig;
import moakiee.ae2oc.api.ResourceAmount;
import moakiee.ae2oc.compat.ae2.ManagedItemStorages;
import net.minecraft.core.Direction;
import net.pedroksl.advanced_ae.common.entities.ReactionChamberEntity;

/**
 * Bounded auto-export for the AdvancedAE reaction chamber.
 *
 * <p>Upstream only calls {@code pushOutResult} from its own tick, which the shared processor cancels as
 * soon as a parallel or overclock card owns a batch, so an upgraded chamber never exported at all. Its
 * item path also reinserts whatever the target rejected, which destroys the remainder of an
 * over-capacity logical slot. This helper offers only what the destination accepted and honours the
 * configured output sides for items and fluids alike.
 */
public final class ReactionExport {
    private ReactionExport() {}

    public static boolean push(ReactionChamberEntity host, Function<Direction, MEStorage> target) {
        if (host.getConfigManager().getSetting(Settings.AUTO_EXPORT) != YesNo.YES) return false;
        long budget = Ae2OcConfig.getMaxTransferAmountPerMachineTick();
        var source = IActionSource.ofMachine(host);
        for (var side : host.getAllowedOutputs()) {
            var storage = target.apply(host.getOrientation().getSide(side));
            if (storage == null) continue;
            if (pushItems(host, storage, source, budget) | pushFluid(host, storage, source, budget)) return true;
        }
        return false;
    }

    private static boolean pushItems(ReactionChamberEntity host, MEStorage storage, IActionSource source, long budget) {
        var slot = ManagedItemStorages.slots(host.getOutput()).get(0);
        var before = slot.read();
        if (before == null || !(before.key() instanceof AEItemKey key)) return false;
        long offered = Math.min(Math.min(before.amount(), key.getMaxStackSize()), budget);
        if (offered <= 0) return false;
        long accepted = storage.insert(key, offered, Actionable.MODULATE, source);
        if (accepted <= 0) return false;
        slot.write(new ResourceAmount<>(key, before.amount() - accepted));
        host.saveChanges();
        return true;
    }

    private static boolean pushFluid(ReactionChamberEntity host, MEStorage storage, IActionSource source, long budget) {
        var tank = host.getTank();
        var before = tank.getStack(0);
        if (before == null) return false;
        long offered = Math.min(before.amount(), budget);
        if (offered <= 0) return false;
        long accepted = storage.insert(before.what(), offered, Actionable.MODULATE, source);
        if (accepted <= 0) return false;
        tank.setStack(0, accepted >= before.amount() ? null : new GenericStack(before.what(), before.amount() - accepted));
        host.saveChanges();
        return true;
    }
}
