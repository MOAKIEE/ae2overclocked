package moakiee.ae2oc.compat.ae2;

/** Ownership follows the inventory instance, without a global map or GC-dependent registration. */
public interface ManagedGenericInventory {
    boolean ae2oc$isManaged();
    void ae2oc$setManaged(boolean managed);
}
