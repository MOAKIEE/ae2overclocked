package moakiee.ae2oc.migration;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Read-only summary of AE2 Overclocked persistence formats found in one block entity snapshot. */
public record MigrationInspection(int logicalInventories, int processingBatches, int legacyCountFields,
        int currentDataVersions, int unknownDataVersions) {

    public static MigrationInspection inspect(CompoundTag root) {
        var counts = new int[5];
        visit(root, counts);
        return new MigrationInspection(counts[0], counts[1], counts[2], counts[3], counts[4]);
    }

    public boolean hasAe2OcData() {
        return logicalInventories > 0 || processingBatches > 0 || legacyCountFields > 0
                || currentDataVersions > 0 || unknownDataVersions > 0;
    }

    private static void visit(CompoundTag tag, int[] counts) {
        for (String key : tag.getAllKeys()) {
            Tag value = tag.get(key);
            if (key.equals("ae2ocLongSlots") && value instanceof ListTag) counts[0]++;
            if (key.equals("ae2ocProcessing") && value instanceof CompoundTag) counts[1]++;
            if (key.equals("ae2ocCount") || key.equals("ae2ocNetCount")
                    || key.equals("ae2ocAmount") && !tag.contains("ae2ocDataVersion")) counts[2]++;
            if (key.equals("dataVersion") || key.equals("ae2ocDataVersion")) {
                int version = tag.getInt(key);
                if (version == 1) counts[3]++;
                else counts[4]++;
            }
            if (value instanceof CompoundTag child) visit(child, counts);
            else if (value instanceof ListTag list) {
                for (Tag entry : list) if (entry instanceof CompoundTag child) visit(child, counts);
            }
        }
    }
}
