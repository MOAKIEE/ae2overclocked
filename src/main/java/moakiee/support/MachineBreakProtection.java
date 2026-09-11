package moakiee.support;

import moakiee.ae2oc.compat.ae2.MachineItemContents;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class MachineBreakProtection {
    private MachineBreakProtection() {}

    public static boolean isProtectedMachine(BlockEntity blockEntity) {
        return blockEntity instanceof MachineItemContents.Owner owner && owner.ae2oc$itemContents().isManaged();
    }

    public static long getInternalItemTotalCount(BlockEntity blockEntity) {
        return blockEntity instanceof MachineItemContents.Owner owner ? owner.ae2oc$itemContents().total() : 0;
    }
}
