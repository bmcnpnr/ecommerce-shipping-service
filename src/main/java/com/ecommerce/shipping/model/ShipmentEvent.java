package com.ecommerce.shipping.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity @Table(name = "shipment_events")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ShipmentEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shipment_id", nullable = false)
    private Shipment shipment;

    @Column(name = "event_description", length = 500)
    private String eventDescription;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", length = 30)
    private ShipmentStatus newStatus;

    @Column(name = "location", length = 255)
    private String location;

    @Column(name = "event_timestamp", nullable = false)
    @Builder.Default
    private LocalDateTime eventTimestamp = LocalDateTime.now();
}
