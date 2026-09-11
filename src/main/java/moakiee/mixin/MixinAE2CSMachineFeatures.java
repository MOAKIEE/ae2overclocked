package moakiee.mixin;

import io.github.lounode.ae2cs.common.machine.MachineComponentContainer;
import io.github.lounode.ae2cs.common.machine.component.EnergyComponent;
import moakiee.ae2oc.compat.ae2.MachineFeatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

@Pseudo
@Mixin(targets = "io.github.lounode.ae2cs.common.block.entity.AENetworkedComponentBlockEntity", remap = false)
public abstract class MixinAE2CSMachineFeatures implements MachineFeatures {
    @Shadow public abstract MachineComponentContainer getMachineComponents();

    @Override public void ae2oc$refreshEnergy() {
        var components = getMachineComponents();
        if (components != null && components.hasComponent(EnergyComponent.class)) {
            // The component refreshes and clamps its buffer before reporting capacity.
            components.get(EnergyComponent.class).getAEMaxPower();
        }
    }
}
