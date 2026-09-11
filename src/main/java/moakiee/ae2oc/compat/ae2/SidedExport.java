package moakiee.ae2oc.compat.ae2;

import java.util.EnumSet;
import appeng.api.inventories.InternalInventory;
import appeng.api.stacks.AEItemKey;
import moakiee.ae2oc.api.ResourceAmount;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/**
 * Overflow-safe transport of one logical output slot into adjacent inventories.
 *
 * <p>The upstream inscriber implementations take a whole stack out of the output slot, hand it to the
 * adjacent inventory and reinsert whatever was rejected. When the logical amount exceeds the slot's
 * visible capacity that reinsertion fails and the rejected remainder is destroyed. This helper never
 * removes more than the destination accepted, so an over-capacity logical slot keeps its excess.
 */
public final class SidedExport {
    private SidedExport() {}

    /**
     * @param budget maximum units a single machine (or lane) may move in one call
     * @return whether any unit was transferred
     */
    public static boolean push(Level level, BlockPos pos, LocalResourceSlot slot,
                               boolean separateSides, Direction top, long budget, Runnable onChanged) {
        return push(level, pos, slot, separateSides, top, budget, onChanged, neighbour -> false);
    }

    /**
     * @param exclude neighbours that must never receive this slot, matching upstream eject predicates
     */
    public static boolean push(Level level, BlockPos pos, LocalResourceSlot slot,
                               boolean separateSides, Direction top, long budget, Runnable onChanged,
                               java.util.function.Predicate<net.minecraft.world.level.block.entity.BlockEntity> exclude) {
        if (level == null || slot == null) return false;
        var before = slot.read();
        if (before == null || !(before.key() instanceof AEItemKey key)) return false;
        long capped = Math.min(Math.min(before.amount(), key.getMaxStackSize()), budget);
        if (capped <= 0) return false;
        int offered = (int) capped;
        var sides = EnumSet.allOf(Direction.class);
        if (separateSides) {
            sides.remove(top);
            sides.remove(top.getOpposite());
        }
        for (var side : sides) {
            var neighbour = level.getBlockEntity(pos.relative(side));
            if (neighbour != null && exclude.test(neighbour)) continue;
            var target = InternalInventory.wrapExternal(level, pos.relative(side), side.getOpposite());
            if (target == null) continue;
            // As with processing ports, a throwing external transfer must have moved nothing.
            var remainder = target.addItems(key.toStack(offered));
            if (remainder.getCount() > offered || (!remainder.isEmpty() && !key.matches(remainder)))
                throw new IllegalStateException("Auto-export port returned an invalid remainder");
            int accepted = offered - remainder.getCount();
            if (accepted > 0) {
                slot.write(new ResourceAmount<>(key, before.amount() - accepted));
                onChanged.run();
                return true;
            }
        }
        return false;
    }
}
