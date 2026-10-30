package com.ecommerce.shipping.repository;

import com.ecommerce.shipping.model.Shipment;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface ShipmentRepository extends JpaRepository<Shipment, Long> {
    Optional<Shipment> findByOrderId(Long orderId);
    Optional<Shipment> findByTrackingNumber(String trackingNumber);
    List<Shipment> findByCustomerId(String customerId);

    /**
     * The shipment, locked for the rest of the transaction. Status changes read the current status and decide
     * from it, so two admins (or a retried request) changing one shipment at the same time would both pass the
     * transition check, write two history rows, announce two events, and let the later write undo a terminal
     * state. The second caller now waits here, then sees the new status and is refused (409). The wait is
     * bounded: past 5 s the request fails as 503 instead of holding a thread and a connection.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select s from Shipment s where s.id = :id")
    Optional<Shipment> findByIdForUpdate(@Param("id") Long id);
}
