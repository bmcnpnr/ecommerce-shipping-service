package com.ecommerce.shipping.repository;

import com.ecommerce.shipping.model.ShipmentEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ShipmentEventRepository extends JpaRepository<ShipmentEvent, Long> {
    List<ShipmentEvent> findByShipmentIdOrderByEventTimestampAsc(Long shipmentId);
}
