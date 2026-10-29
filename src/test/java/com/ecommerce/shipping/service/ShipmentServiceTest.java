package com.ecommerce.shipping.service;

import com.ecommerce.shipping.dto.CreateShipmentRequest;
import com.ecommerce.shipping.dto.PublicTrackingDTO;
import com.ecommerce.shipping.dto.ShipmentDTO;
import com.ecommerce.shipping.dto.ShipmentEventDTO;
import com.ecommerce.shipping.event.ShipmentUpdatedEvent;
import com.ecommerce.shipping.exception.*;
import com.ecommerce.shipping.messaging.ShipmentEventProducer;
import com.ecommerce.shipping.model.*;
import com.ecommerce.shipping.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShipmentServiceTest {

    @Mock ShipmentRepository shipmentRepository;
    @Mock ShipmentEventRepository shipmentEventRepository;
    @Mock ShipmentEventProducer eventProducer;
    @InjectMocks ShipmentService shipmentService;

    private Shipment testShipment;

    @BeforeEach
    void setUp() {
        testShipment = Shipment.builder().id(1L).orderId(10L).customerId("user1")
                .trackingNumber("TRACK-ABC").status(ShipmentStatus.PENDING)
                .originAddress("A").destinationAddress("B").events(new ArrayList<>()).build();
    }

    @Test
    void createShipment_success() {
        CreateShipmentRequest req = CreateShipmentRequest.builder()
                .orderId(10L).customerId("user1").originAddress("A").destinationAddress("B").build();
        when(shipmentRepository.findByOrderId(10L)).thenReturn(Optional.empty());
        when(shipmentRepository.save(any())).thenAnswer(inv -> { Shipment s = inv.getArgument(0); s.setId(1L); return s; });

        ShipmentDTO result = shipmentService.createShipment(req);
        assertThat(result.getOrderId()).isEqualTo(10L);
        assertThat(result.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void createShipment_duplicate_throws() {
        when(shipmentRepository.findByOrderId(10L)).thenReturn(Optional.of(testShipment));
        assertThatThrownBy(() -> shipmentService.createShipment(
                CreateShipmentRequest.builder().orderId(10L).customerId("u").originAddress("A").destinationAddress("B").build()))
                .isInstanceOf(DuplicateShipmentException.class);
    }

    @Test
    void getShipmentByTrackingNumber_notFound_throws() {
        when(shipmentRepository.findByTrackingNumber("UNKNOWN")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> shipmentService.getShipmentByTrackingNumber("UNKNOWN"))
                .isInstanceOf(ShipmentNotFoundException.class);
    }

    @Test
    void getShipmentByTrackingNumber_withControlCharacters_isNotFound_withoutTouchingTheDatabase() {
        assertThatThrownBy(() -> shipmentService.getShipmentByTrackingNumber("TRACK-" + (char) 0 + "ABC"))
                .isInstanceOf(ShipmentNotFoundException.class);
        verifyNoInteractions(shipmentRepository);
    }

    @Test
    void updateStatus_alreadyDelivered_throws() {
        testShipment.setStatus(ShipmentStatus.DELIVERED);
        when(shipmentRepository.findById(1L)).thenReturn(Optional.of(testShipment));
        assertThatThrownBy(() -> shipmentService.updateShipmentStatus(1L, "SHIPPED", null, null))
                .isInstanceOf(InvalidShipmentStateException.class);
    }

    // ───────────────────────────── createShipment ─────────────────────────────

    @Test
    void createShipment_mapsRequest_defaultsCarrier_ignoresRequestedDelivery_andPublishesNothing() {
        CreateShipmentRequest req = CreateShipmentRequest.builder()
                .orderId(10L).customerId("user1").originAddress("A").destinationAddress("B")
                .estimatedDelivery("2030-01-01T00:00:00").build();
        when(shipmentRepository.findByOrderId(10L)).thenReturn(Optional.empty());
        when(shipmentRepository.save(any())).thenAnswer(inv -> { Shipment s = inv.getArgument(0); s.setId(5L); return s; });
        LocalDateTime before = LocalDateTime.now();

        ShipmentDTO result = shipmentService.createShipment(req);

        assertThat(result.getId()).isEqualTo(5L);
        assertThat(result.getCustomerId()).isEqualTo("user1");
        assertThat(result.getOriginAddress()).isEqualTo("A");
        assertThat(result.getDestinationAddress()).isEqualTo("B");
        assertThat(result.getCarrier()).isEqualTo("DHL");
        assertThat(result.getTrackingNumber()).matches("TRACK-[0-9A-F]{8}-[0-9A-F]{8}");
        assertThat(result.getActualDelivery()).isNull();
        assertThat(result.getCreatedAt()).isNotNull();
        // Documented: the requested estimatedDelivery is ignored, it is always now + 5 days.
        assertThat(result.getEstimatedDelivery())
                .isBetween(before.plusDays(5), LocalDateTime.now().plusDays(5));
        // Documented: the manual create path publishes no shipment.created and writes no history.
        verifyNoInteractions(eventProducer, shipmentEventRepository);
    }

    @Test
    void createShipment_keepsRequestedCarrier() {
        when(shipmentRepository.findByOrderId(10L)).thenReturn(Optional.empty());
        when(shipmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ShipmentDTO result = shipmentService.createShipment(CreateShipmentRequest.builder()
                .orderId(10L).customerId("u").originAddress("A").destinationAddress("B").carrier("UPS").build());

        assertThat(result.getCarrier()).isEqualTo("UPS");
    }

    @Test
    void createShipment_generatesADifferentTrackingNumberEachTime() {
        when(shipmentRepository.findByOrderId(anyLong())).thenReturn(Optional.empty());
        when(shipmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String first = shipmentService.createShipment(CreateShipmentRequest.builder()
                .orderId(1L).customerId("u").originAddress("A").destinationAddress("B").build()).getTrackingNumber();
        String second = shipmentService.createShipment(CreateShipmentRequest.builder()
                .orderId(2L).customerId("u").originAddress("A").destinationAddress("B").build()).getTrackingNumber();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void createShipment_duplicate_savesNothing() {
        when(shipmentRepository.findByOrderId(10L)).thenReturn(Optional.of(testShipment));

        assertThatThrownBy(() -> shipmentService.createShipment(
                CreateShipmentRequest.builder().orderId(10L).customerId("u").originAddress("A").destinationAddress("B").build()))
                .hasMessage("Shipment already exists for order: 10");
        verify(shipmentRepository, never()).save(any());
    }

    // ───────────────────────────── lookups ─────────────────────────────

    @Test
    void getShipmentByOrderId_found_mapsEveryField() {
        LocalDateTime eta = LocalDateTime.of(2026, 11, 3, 14, 30);
        LocalDateTime delivered = LocalDateTime.of(2026, 11, 2, 9, 0);
        testShipment.setCarrier("DHL");
        testShipment.setEstimatedDelivery(eta);
        testShipment.setActualDelivery(delivered);
        testShipment.setStatus(ShipmentStatus.DELIVERED);
        when(shipmentRepository.findByOrderId(10L)).thenReturn(Optional.of(testShipment));

        ShipmentDTO dto = shipmentService.getShipmentByOrderId(10L);

        assertThat(dto.getId()).isEqualTo(1L);
        assertThat(dto.getOrderId()).isEqualTo(10L);
        assertThat(dto.getTrackingNumber()).isEqualTo("TRACK-ABC");
        assertThat(dto.getStatus()).isEqualTo("DELIVERED");
        assertThat(dto.getCarrier()).isEqualTo("DHL");
        assertThat(dto.getEstimatedDelivery()).isEqualTo(eta);
        assertThat(dto.getActualDelivery()).isEqualTo(delivered);
        assertThat(dto.getCreatedAt()).isEqualTo(testShipment.getCreatedAt());
    }

    @Test
    void getShipmentByOrderId_notFound_throws() {
        when(shipmentRepository.findByOrderId(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> shipmentService.getShipmentByOrderId(99L))
                .isInstanceOf(ShipmentNotFoundException.class)
                .hasMessage("Shipment not found for order: 99");
    }

    @Test
    void getShipmentByTrackingNumber_found() {
        when(shipmentRepository.findByTrackingNumber("TRACK-ABC")).thenReturn(Optional.of(testShipment));
        testShipment.setEstimatedDelivery(LocalDateTime.of(2026, 11, 3, 14, 30));
        when(shipmentEventRepository.findByShipmentIdOrderByEventTimestampAsc(1L)).thenReturn(List.of(
                ShipmentEvent.builder().newStatus(ShipmentStatus.PROCESSING).location("Hub").eventDescription("Packed")
                        .eventTimestamp(LocalDateTime.of(2026, 10, 29, 10, 0)).build()));

        PublicTrackingDTO dto = shipmentService.getShipmentByTrackingNumber("TRACK-ABC");

        assertThat(dto.getTrackingNumber()).isEqualTo("TRACK-ABC");
        assertThat(dto.getStatus()).isEqualTo("PENDING");
        assertThat(dto.getEstimatedDelivery()).isEqualTo(LocalDateTime.of(2026, 11, 3, 14, 30));
        assertThat(dto.getEvents()).singleElement().satisfies(e -> {
            assertThat(e.getStatus()).isEqualTo("PROCESSING");
            assertThat(e.getLocation()).isEqualTo("Hub");
            assertThat(e.getDescription()).isEqualTo("Packed");
            assertThat(e.getTimestamp()).isEqualTo(LocalDateTime.of(2026, 10, 29, 10, 0));
        });
    }

    // ───────────────────────────── status state machine ─────────────────────────────

    private static final EnumSet<ShipmentStatus> TERMINAL = EnumSet.of(ShipmentStatus.DELIVERED, ShipmentStatus.RETURNED);

    /** Every (terminal from, any to) pair: the only transitions the service defines as illegal. */
    static Stream<Arguments> transitionsOutOfATerminalState() {
        return TERMINAL.stream().flatMap(from -> Arrays.stream(ShipmentStatus.values()).map(to -> Arguments.of(from, to)));
    }

    /** Every legal (from, to) pair of the transition table (S-82). */
    static Stream<Arguments> transitionsOutOfANonTerminalState() {
        return Arrays.stream(ShipmentStatus.values())
                .flatMap(from -> from.next().stream().map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1} is rejected")
    @MethodSource("transitionsOutOfATerminalState")
    void updateStatus_outOfTerminalState_isRejected_andChangesNothing(ShipmentStatus from, ShipmentStatus to) {
        testShipment.setStatus(from);
        when(shipmentRepository.findById(1L)).thenReturn(Optional.of(testShipment));

        assertThatThrownBy(() -> shipmentService.updateShipmentStatus(1L, to.name(), "Hub", "desc"))
                .isInstanceOf(InvalidShipmentStateException.class)
                .hasMessage("Cannot update status of a " + from + " shipment");

        assertThat(testShipment.getStatus()).isEqualTo(from);
        assertThat(testShipment.getEvents()).isEmpty();
        assertThat(testShipment.getUpdatedAt()).isNull();
        verify(shipmentRepository, never()).save(any());
        verifyNoInteractions(eventProducer);
    }

    @ParameterizedTest(name = "{0} -> {1} is applied")
    @MethodSource("transitionsOutOfANonTerminalState")
    void updateStatus_outOfNonTerminalState_isApplied(ShipmentStatus from, ShipmentStatus to) {
        testShipment.setStatus(from);
        when(shipmentRepository.findById(1L)).thenReturn(Optional.of(testShipment));
        when(shipmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ShipmentDTO dto = shipmentService.updateShipmentStatus(1L, to.name(), null, null);

        assertThat(dto.getStatus()).isEqualTo(to.name());
        assertThat(testShipment.getEvents()).singleElement()
                .extracting(ShipmentEvent::getNewStatus).isEqualTo(to);
        verify(eventProducer).publishShipmentUpdated(any());
    }

    @ParameterizedTest
    @EnumSource(value = ShipmentStatus.class, names = {"PROCESSING", "SHIPPED", "OUT_FOR_DELIVERY", "RETURNED"})
    void updateStatus_onlyDeliveredSetsActualDelivery(ShipmentStatus to) {
        testShipment.setStatus(switch (to) {
            case PROCESSING -> ShipmentStatus.PENDING;
            case SHIPPED -> ShipmentStatus.PROCESSING;
            case OUT_FOR_DELIVERY, RETURNED -> ShipmentStatus.SHIPPED;
            default -> throw new IllegalArgumentException();
        });
        when(shipmentRepository.findById(1L)).thenReturn(Optional.of(testShipment));
        when(shipmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(shipmentService.updateShipmentStatus(1L, to.name(), null, null).getActualDelivery()).isNull();
    }

    @Test
    void updateStatus_toDelivered_setsActualDelivery() {
        testShipment.setStatus(ShipmentStatus.OUT_FOR_DELIVERY);
        when(shipmentRepository.findById(1L)).thenReturn(Optional.of(testShipment));
        when(shipmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        LocalDateTime before = LocalDateTime.now();

        ShipmentDTO dto = shipmentService.updateShipmentStatus(1L, "DELIVERED", "Door", null);

        assertThat(dto.getStatus()).isEqualTo("DELIVERED");
        assertThat(dto.getActualDelivery()).isBetween(before, LocalDateTime.now());
        assertThat(testShipment.getUpdatedAt()).isBetween(before, LocalDateTime.now());
    }

    @Test
    void updateStatus_appendsHistoryEvent_withGivenLocationAndDescription() {
        testShipment.setStatus(ShipmentStatus.PROCESSING);
        when(shipmentRepository.findById(1L)).thenReturn(Optional.of(testShipment));
        when(shipmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        LocalDateTime before = LocalDateTime.now();

        shipmentService.updateShipmentStatus(1L, "SHIPPED", "Istanbul hub", "Left the warehouse");

        assertThat(testShipment.getEvents()).singleElement().satisfies(e -> {
            assertThat(e.getShipment()).isSameAs(testShipment);
            assertThat(e.getNewStatus()).isEqualTo(ShipmentStatus.SHIPPED);
            assertThat(e.getLocation()).isEqualTo("Istanbul hub");
            assertThat(e.getEventDescription()).isEqualTo("Left the warehouse");
            assertThat(e.getEventTimestamp()).isBetween(before, LocalDateTime.now());
        });
        // History rows are written through the Shipment's cascade, not the event repository.
        verify(shipmentRepository).save(testShipment);
        verifyNoInteractions(shipmentEventRepository);
    }

    @Test
    void updateStatus_withoutDescription_usesDefaultHistoryText() {
        when(shipmentRepository.findById(1L)).thenReturn(Optional.of(testShipment));
        when(shipmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        shipmentService.updateShipmentStatus(1L, "PROCESSING", null, null);

        assertThat(testShipment.getEvents()).singleElement().satisfies(e -> {
            assertThat(e.getEventDescription()).isEqualTo("Status updated to PROCESSING");
            assertThat(e.getLocation()).isNull();
        });
    }

    @Test
    void updateStatus_successiveUpdates_accumulateHistoryInOrder() {
        when(shipmentRepository.findById(1L)).thenReturn(Optional.of(testShipment));
        when(shipmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        shipmentService.updateShipmentStatus(1L, "PROCESSING", null, null);
        shipmentService.updateShipmentStatus(1L, "SHIPPED", null, null);
        shipmentService.updateShipmentStatus(1L, "OUT_FOR_DELIVERY", null, null);
        shipmentService.updateShipmentStatus(1L, "DELIVERED", null, null);

        assertThat(testShipment.getEvents()).extracting(ShipmentEvent::getNewStatus)
                .containsExactly(ShipmentStatus.PROCESSING, ShipmentStatus.SHIPPED, ShipmentStatus.OUT_FOR_DELIVERY, ShipmentStatus.DELIVERED);
        verify(eventProducer, times(4)).publishShipmentUpdated(any());
    }

    @Test
    void updateStatus_publishesShipmentUpdated() {
        testShipment.setStatus(ShipmentStatus.SHIPPED);
        when(shipmentRepository.findById(1L)).thenReturn(Optional.of(testShipment));
        when(shipmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        LocalDateTime before = LocalDateTime.now();

        shipmentService.updateShipmentStatus(1L, "OUT_FOR_DELIVERY", "Kadikoy", null);

        ArgumentCaptor<ShipmentUpdatedEvent> captor = ArgumentCaptor.forClass(ShipmentUpdatedEvent.class);
        verify(eventProducer).publishShipmentUpdated(captor.capture());
        ShipmentUpdatedEvent e = captor.getValue();
        assertThat(e.getShipmentId()).isEqualTo(1L);
        assertThat(e.getOrderId()).isEqualTo(10L);
        assertThat(e.getTrackingNumber()).isEqualTo("TRACK-ABC");
        assertThat(e.getNewStatus()).isEqualTo("OUT_FOR_DELIVERY");
        assertThat(e.getLocation()).isEqualTo("Kadikoy");
        assertThat(e.getUpdatedAt()).isBetween(before, LocalDateTime.now());
    }

    @Test
    void updateStatus_notFound_throws_andPublishesNothing() {
        when(shipmentRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> shipmentService.updateShipmentStatus(404L, "SHIPPED", null, null))
                .isInstanceOf(ShipmentNotFoundException.class)
                .hasMessage("Shipment not found: 404");
        verify(shipmentRepository, never()).save(any());
        verifyNoInteractions(eventProducer);
    }

    @Test
    void updateStatus_unknownOrLowerCaseStatus_isRefused_andChangesNothing() {
        when(shipmentRepository.findById(1L)).thenReturn(Optional.of(testShipment));

        assertThatThrownBy(() -> shipmentService.updateShipmentStatus(1L, "FLYING", null, null));
        assertThatThrownBy(() -> shipmentService.updateShipmentStatus(1L, "shipped", null, null));

        assertThat(testShipment.getStatus()).isEqualTo(ShipmentStatus.PENDING);
        assertThat(testShipment.getEvents()).isEmpty();
        verify(shipmentRepository, never()).save(any());
        verifyNoInteractions(eventProducer);
    }

    // ───────────────────────────── history ─────────────────────────────

    @Test
    void getShipmentHistory_mapsEventsInRepositoryOrder() {
        LocalDateTime t1 = LocalDateTime.of(2026, 10, 29, 10, 0);
        LocalDateTime t2 = t1.plusHours(3);
        ShipmentEvent first = ShipmentEvent.builder().id(100L).shipment(testShipment).newStatus(ShipmentStatus.SHIPPED)
                .eventDescription("Left").location("Hub").eventTimestamp(t1).build();
        ShipmentEvent second = ShipmentEvent.builder().id(101L).shipment(testShipment).newStatus(null)
                .eventDescription("Note").eventTimestamp(t2).build();
        when(shipmentRepository.findById(1L)).thenReturn(Optional.of(testShipment));
        when(shipmentEventRepository.findByShipmentIdOrderByEventTimestampAsc(1L)).thenReturn(List.of(first, second));

        List<ShipmentEventDTO> history = shipmentService.getShipmentHistory(1L);

        assertThat(history).hasSize(2);
        assertThat(history.get(0)).isEqualTo(ShipmentEventDTO.builder().id(100L).shipmentId(1L)
                .eventDescription("Left").newStatus("SHIPPED").location("Hub").eventTimestamp(t1).build());
        assertThat(history.get(1).getId()).isEqualTo(101L);
        assertThat(history.get(1).getNewStatus()).isNull();
        assertThat(history.get(1).getLocation()).isNull();
    }

    @Test
    void getShipmentHistory_noEvents_returnsEmptyList() {
        when(shipmentRepository.findById(1L)).thenReturn(Optional.of(testShipment));
        when(shipmentEventRepository.findByShipmentIdOrderByEventTimestampAsc(1L)).thenReturn(List.of());

        assertThat(shipmentService.getShipmentHistory(1L)).isEmpty();
    }

    @Test
    void getShipmentHistory_unknownShipment_throws_withoutQueryingEvents() {
        when(shipmentRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> shipmentService.getShipmentHistory(7L))
                .isInstanceOf(ShipmentNotFoundException.class)
                .hasMessage("Shipment not found: 7");
        verifyNoInteractions(shipmentEventRepository);
    }
}
