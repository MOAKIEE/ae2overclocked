package moakiee.ae2oc.platform.forge;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.blockentity.AEBaseBlockEntity;
import moakiee.ae2oc.api.ResourceAmount;
import moakiee.ae2oc.compat.ae2.ProcessingCodec;
import moakiee.ae2oc.core.execution.ProcessingState;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;

/** Saved batches exercise display state independently of recipe lookup and live grid scheduling. */
final class MachineProgressVerification {
    static void run(GameTestHelper helper, AEBaseBlockEntity host, String childTag,
            IntSupplier progress, int maximum, BooleanSupplier working, InternalInventory output, int outputSlot) {
        var ticker = (IGridTickable) host;
        var saved = host.saveWithFullMetadata();
        var batchOwner = childTag == null ? saved : saved.getCompound(childTag);
        var partial = new ProcessingState<AEKey>("test:progress",
                List.of(new ResourceAmount<>(AEItemKey.of(Items.IRON_INGOT), 1)),
                List.of(new ResourceAmount<>(AEItemKey.of(Items.GOLD_INGOT), 1)), 100, 25, 3);
        batchOwner.put("ae2ocProcessing", ProcessingCodec.write(partial));
        if (childTag != null) saved.put(childTag, batchOwner);
        host.load(saved);
        ticker.tickingRequest(null, 1);
        helper.assertTrue(progress.getAsInt() == maximum / 4, "Partial batch did not update upstream progress");
        if (working != null) helper.assertTrue(working.getAsBoolean(), "Owned batch did not set working state");
        helper.assertTrue(output.getStackInSlot(outputSlot).isEmpty(), "Unpaid batch produced items");
        var unpaid = host.saveWithFullMetadata();
        if (childTag != null) unpaid = unpaid.getCompound(childTag);
        helper.assertTrue(unpaid.getCompound("ae2ocProcessing").equals(ProcessingCodec.write(partial)),
                "Display sync changed unpaid batch ownership or energy");

        batchOwner.put("ae2ocProcessing", ProcessingCodec.write(new ProcessingState<>(partial.recipe(),
                partial.inputs(), partial.outputs(), 100, 100, 3)));
        host.load(saved);
        ticker.tickingRequest(null, 1);
        helper.assertTrue(progress.getAsInt() == maximum, "Paid batch did not display full progress");
        ticker.tickingRequest(null, 1);
        ticker.tickingRequest(null, 1);
        helper.assertTrue(progress.getAsInt() == 0, "Completed batch retained stale progress");
        if (working != null) helper.assertTrue(!working.getAsBoolean(), "Completed batch retained working state");
        var result = output.getStackInSlot(outputSlot);
        helper.assertTrue(result.is(Items.GOLD_INGOT) && result.getCount() == 1, "Display sync changed output conservation");
        var complete = host.saveWithFullMetadata();
        if (childTag != null) complete = complete.getCompound(childTag);
        helper.assertTrue(!complete.contains("ae2ocProcessing"), "Completed display test retained batch");
        // With no cards or batch left, the next calls return to upstream without a second settlement.
        for (int i = 0; i < 20; i++) ticker.tickingRequest(null, 1);
        helper.assertTrue(output.getStackInSlot(outputSlot).getCount() == 1, "Upstream handoff repeated output");
        helper.succeed();
    }
}
