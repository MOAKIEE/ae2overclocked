package moakiee.support;

import com.mojang.brigadier.CommandDispatcher;
import moakiee.ae2oc.core.observability.ProcessingMetrics;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** Read-only server command exposing aggregate processing counters. */
public final class ProcessingDiagnosticsCommand {
    private ProcessingDiagnosticsCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ae2oc")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("diagnostics").executes(context -> show(context.getSource()))));
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
}
