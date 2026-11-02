package com.stellar.platform.processing.plan;

import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.domain.processing.plan.ObservedTimingMetadata;

import java.util.Objects;
import java.util.Optional;

/**
 * One measurement source as already known to the project (inventory + optional original timing).
 * Plan building never opens/reparses the file.
 */
public final class SourcePlanInput {

    private final String projectSourceId;
    private final int sourceOrderIndex;
    private final CanSourceInventory inventory;
    private final ObservedTimingMetadata observedTiming;

    public SourcePlanInput(
            String projectSourceId,
            int sourceOrderIndex,
            CanSourceInventory inventory,
            ObservedTimingMetadata observedTiming
    ) {
        if (projectSourceId == null || projectSourceId.isBlank()) {
            throw new IllegalArgumentException("projectSourceId must not be blank");
        }
        this.projectSourceId = projectSourceId.trim();
        this.sourceOrderIndex = sourceOrderIndex;
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.observedTiming = observedTiming == null ? null : observedTiming.copy();
    }

    public String projectSourceId() {
        return projectSourceId;
    }

    public int sourceOrderIndex() {
        return sourceOrderIndex;
    }

    public CanSourceInventory inventory() {
        return inventory;
    }

    public Optional<ObservedTimingMetadata> observedTiming() {
        return Optional.ofNullable(observedTiming);
    }
}
