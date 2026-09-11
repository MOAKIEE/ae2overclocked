package moakiee.ae2oc.compat.ae2;

/** Ownership follows the inventory instance, without a global map or GC-dependent registration. */
public interface ManagedGenericInventory {
    boolean ae2oc$isManaged();
    /** Permanent for this instance: capacity removal must preserve overflow ownership. */
    void ae2oc$markManaged();
}
