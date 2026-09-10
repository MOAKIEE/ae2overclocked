package moakiee.ae2oc.compat.advancedae;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import moakiee.ae2oc.api.ResourceAmount;
import moakiee.ae2oc.compat.ae2.MachineProcessorAdapter;
import net.pedroksl.advanced_ae.common.entities.ReactionChamberEntity;

public final class ReactionAdapter extends MachineProcessorAdapter {
    private final ReactionChamberEntity host;
    public ReactionAdapter(ReactionChamberEntity host) {
        super(host, host, host.getOutput(), new ReactionRecipes(host), 1, 0);
        this.host = host;
    }
    @Override protected long insertLocalOutput(ResourceAmount<AEKey> resource) {
        if (!(resource.key() instanceof AEFluidKey)) return super.insertLocalOutput(resource);
        var tank = host.getTank();
        var current = tank.getStack(0);
        if (current != null && !current.what().equals(resource.key())) return 0;
        long amount = current == null ? 0 : current.amount();
        long room = Math.max(0, tank.getCapacity(resource.key().getType()) - amount);
        long accepted = Math.min(room, resource.amount());
        if (accepted > 0) tank.setStack(0, new GenericStack(resource.key(), amount + accepted));
        return accepted;
    }
}
