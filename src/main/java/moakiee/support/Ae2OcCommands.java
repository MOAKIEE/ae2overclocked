package moakiee.support;

import com.mojang.brigadier.CommandDispatcher;
import moakiee.ae2oc.core.observability.ProcessingMetrics;
import moakiee.ae2oc.migration.MigrationInspection;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraftforge.registries.ForgeRegistries;

/** Read-only server command exposing aggregate processing counters. */
public final class Ae2OcCommands {
    private Ae2OcCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ae2oc")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("diagnostics").executes(context -> show(context.getSource())))
                .then(Commands.literal("migration")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(context -> inspectMigration(context.getSource(),
                                        BlockPosArgument.getLoadedBlockPos(context, "pos"))))));
    }

    private static int show(CommandSourceStack source) {
        var value = ProcessingMetrics.snapshot();
        long uptimeSeconds = Math.max(0, (System.currentTimeMillis() - value.startedAtMillis()) / 1_000);
        source.sendSuccess(() -> Component.literal("AE2OC processing diagnostics (uptime " + uptimeSeconds + "s)"), false);
        source.sendSuccess(() -> Component.literal("ticks=" + value.tickCalls()
                + ", backoffSkips=" + value.backoffSkips() + ", blockedAttempts=" + value.blockedAttempts()), false);
        source.sendSuccess(() -> Component.literal("batches=" + value.batchesReserved()
                + "/" + value.batchesCompleted() + ", operations=" + value.operationsReserved()
                + ", processTicks=" + value.processTicks()), false);
        source.sendSuccess(() -> Component.literal("energyPaidAE=" + value.energyPaid()
                + ", outputUnits=" + value.outputUnitsDrained()), false);
        return 1;
    }

    private static int inspectMigration(CommandSourceStack source, net.minecraft.core.BlockPos pos) {
        var blockEntity = source.getLevel().getBlockEntity(pos);
        if (blockEntity == null) {
            source.sendFailure(Component.literal("No loaded block entity at " + pos.toShortString()));
            return 0;
        }
        var report = MigrationInspection.inspect(blockEntity.saveWithoutMetadata());
        var type = ForgeRegistries.BLOCK_ENTITY_TYPES.getKey(blockEntity.getType());
        source.sendSuccess(() -> Component.literal("AE2OC migration inspection for " + type + " at "
                + pos.toShortString()), false);
        source.sendSuccess(() -> Component.literal("logicalInventories=" + report.logicalInventories()
                + ", processingBatches=" + report.processingBatches()
                + ", legacyCountFields=" + report.legacyCountFields()), false);
        source.sendSuccess(() -> Component.literal("currentDataVersions=" + report.currentDataVersions()
                + ", unknownDataVersions=" + report.unknownDataVersions()
                + ", ae2ocData=" + report.hasAe2OcData()), false);
        return report.unknownDataVersions() == 0 ? 1 : 2;
    }
}
