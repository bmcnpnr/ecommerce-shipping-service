package com.ecommerce.shipping.model;

import java.util.Set;

public enum ShipmentStatus {
    PENDING, PROCESSING, SHIPPED, OUT_FOR_DELIVERY, DELIVERED, RETURNED;

    /**
     * Legal next statuses: PENDING to PROCESSING to SHIPPED to OUT_FOR_DELIVERY to DELIVERED, and a
     * shipment that has left the warehouse (SHIPPED or OUT_FOR_DELIVERY) may be RETURNED.
     * DELIVERED and RETURNED are terminal.
     */
    public Set<ShipmentStatus> next() {
        return switch (this) {
            case PENDING -> Set.of(PROCESSING);
            case PROCESSING -> Set.of(SHIPPED);
            case SHIPPED -> Set.of(OUT_FOR_DELIVERY, RETURNED);
            case OUT_FOR_DELIVERY -> Set.of(DELIVERED, RETURNED);
            case DELIVERED, RETURNED -> Set.of();
        };
    }

    public boolean canMoveTo(ShipmentStatus target) {
        return next().contains(target);
    }
}
