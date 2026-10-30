package com.ecommerce.shipping.security;

import com.ecommerce.shipping.dto.CreateShipmentRequest;
import com.ecommerce.shipping.dto.ShipmentDTO;
import com.ecommerce.shipping.exception.InvalidShipmentStateException;
import com.ecommerce.shipping.messaging.ShipmentEventProducer;
import com.ecommerce.shipping.model.Shipment;
import com.ecommerce.shipping.model.ShipmentStatus;
import com.ecommerce.shipping.repository.ShipmentEventRepository;
import com.ecommerce.shipping.repository.ShipmentRepository;
import com.ecommerce.shipping.service.ShipmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Shipment state machine and tracking-number generation, with the repositories mocked. A refused
 * update must leave the shipment untouched and publish nothing.
 */
@Tag("security")
@ExtendWith(MockitoExtension.class)
class ShipmentServiceSecurityTest {

    @Mock private ShipmentRepository shipmentRepository;
    @Mock private ShipmentEventRepository shipmentEventRepository;
    @Mock private ShipmentEventProducer eventProducer;

    private ShipmentService service;

    @BeforeEach
    void setUp() {
        service = new ShipmentService(shipmentRepository, shipmentEventRepository, eventProducer);
        lenient().when(shipmentRepository.save(any(Shipment.class))).thenAnswer(i -> {
            Shipment s = i.getArgument(0);
            if (s.getId() == null) {
                s.setId(1L);
            }
            return s;
        });
    }

    private Shipment shipment(ShipmentStatus status) {
        Shipment shipment = Shipment.builder().id(5L).orderId(100L).customerId("alice").trackingNumber("TRACK-AAAAAAAA-A")
                .status(status).originAddress("Warehouse").destinationAddress("Home").build();
        lenient().when(shipmentRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(shipment));
        return shipment;
    }

    // ---- transitions -------------------------------------------------------------------------------------

    static Stream<Arguments> legalTransitions() {
        return Stream.of(
                Arguments.of(ShipmentStatus.PENDING, ShipmentStatus.PROCESSING),
                Arguments.of(ShipmentStatus.PROCESSING, ShipmentStatus.SHIPPED),
                Arguments.of(ShipmentStatus.SHIPPED, ShipmentStatus.OUT_FOR_DELIVERY),
                Arguments.of(ShipmentStatus.OUT_FOR_DELIVERY, ShipmentStatus.DELIVERED));
    }

    static Stream<Arguments> illegalTransitions() {
        return Stream.of(
                Arguments.of(ShipmentStatus.PENDING, ShipmentStatus.DELIVERED),
                Arguments.of(ShipmentStatus.PENDING, ShipmentStatus.OUT_FOR_DELIVERY),
                Arguments.of(ShipmentStatus.PENDING, ShipmentStatus.RETURNED),
                Arguments.of(ShipmentStatus.PROCESSING, ShipmentStatus.DELIVERED),
                Arguments.of(ShipmentStatus.SHIPPED, ShipmentStatus.PENDING),
                Arguments.of(ShipmentStatus.SHIPPED, ShipmentStatus.PROCESSING),
                Arguments.of(ShipmentStatus.OUT_FOR_DELIVERY, ShipmentStatus.SHIPPED),
                Arguments.of(ShipmentStatus.OUT_FOR_DELIVERY, ShipmentStatus.PENDING));
    }

    @ParameterizedTest
    @MethodSource("legalTransitions")
    void legalForwardTransition_isAccepted_andRecorded(ShipmentStatus from, ShipmentStatus to) {
        Shipment shipment = shipment(from);

        ShipmentDTO dto = service.updateShipmentStatus(5L, to.name(), "Hub", "ok");

        assertThat(dto.getStatus()).isEqualTo(to.name());
        assertThat(shipment.getEvents()).hasSize(1);
        verify(eventProducer).publishShipmentUpdated(any());
    }

    @ParameterizedTest
    @MethodSource("illegalTransitions")
    void illegalTransition_isRejected_withNoHistoryAndNoEvent(ShipmentStatus from, ShipmentStatus to) {
        Shipment shipment = shipment(from);

        assertThatThrownBy(() -> service.updateShipmentStatus(5L, to.name(), null, null)).isInstanceOf(InvalidShipmentStateException.class);

        assertThat(shipment.getStatus()).isEqualTo(from);
        assertThat(shipment.getEvents()).isEmpty();
        verifyNoInteractions(eventProducer);
    }

    @ParameterizedTest
    @EnumSource(value = ShipmentStatus.class, names = {"DELIVERED", "RETURNED"})
    void terminalShipment_cannotBeChanged(ShipmentStatus terminal) {
        Shipment shipment = shipment(terminal);

        for (ShipmentStatus target : ShipmentStatus.values()) {
            assertThatThrownBy(() -> service.updateShipmentStatus(5L, target.name(), null, null)).isInstanceOf(InvalidShipmentStateException.class);
        }

        assertThat(shipment.getStatus()).isEqualTo(terminal);
        assertThat(shipment.getEvents()).isEmpty();
        verify(shipmentRepository, never()).save(any());
        verifyNoInteractions(eventProducer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"delivered", "Delivered", " DELIVERED", "DELIVERED ", "DELIVERED'; DROP TABLE shipments;--", "ALL", "", "0", "<script>alert(1)</script>", "null"})
    void unknownStatusValue_isRejected_withNoChange(String status) {
        Shipment shipment = shipment(ShipmentStatus.PENDING);

        assertThatThrownBy(() -> service.updateShipmentStatus(5L, status, null, null)).isInstanceOf(InvalidShipmentStateException.class);

        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.PENDING);
        assertThat(shipment.getEvents()).isEmpty();
        verify(shipmentRepository, never()).save(any());
        verifyNoInteractions(eventProducer);
    }

    // ---- creation -----------------------------------------------------------------------------------------------

    private static CreateShipmentRequest request(long orderId) {
        return CreateShipmentRequest.builder().orderId(orderId).customerId("alice").originAddress("Warehouse")
                .destinationAddress("Home").carrier("UPS").estimatedDelivery("1999-01-01").build();
    }

    @Test
    void create_forAnOrderThatAlreadyHasAShipment_isRejected() {
        Shipment existing = shipment(ShipmentStatus.PENDING);
        lenient().when(shipmentRepository.findByOrderId(100L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.createShipment(request(100L))).isInstanceOf(InvalidShipmentStateException.class);

        verify(shipmentRepository, never()).save(any());
    }

    @Test
    void create_ignoresTheClientEstimate_andStartsPending() {
        LocalDateTime before = LocalDateTime.now();

        ShipmentDTO dto = service.createShipment(request(200L));

        assertThat(dto.getStatus()).isEqualTo("PENDING");
        assertThat(dto.getActualDelivery()).isNull();
        assertThat(dto.getEstimatedDelivery()).isAfter(before.plusDays(4));
    }

    // ---- tracking numbers -----------------------------------------------------------------------------------------

    private String[] generate(int n) {
        String[] numbers = new String[n];
        for (int i = 0; i < n; i++) {
            numbers[i] = service.createShipment(request(1000L + i)).getTrackingNumber();
        }
        return numbers;
    }

    @Test
    void trackingNumbers_areUnique_wellFormed_andNotSequential() {
        String[] numbers = generate(2000);

        Set<String> distinct = new HashSet<>(Set.of(numbers));
        assertThat(distinct).hasSize(numbers.length);
        assertThat(numbers).allSatisfy(n -> assertThat(n).matches("TRACK-[0-9A-F]{8}-[0-9A-F]{8}"));

        // sequential ids would be (almost) always ascending or always descending; random ones go up about half the time
        int ascending = 0;
        for (int i = 1; i < numbers.length; i++) {
            if (Long.parseLong(numbers[i].substring(6, 14), 16) > Long.parseLong(numbers[i - 1].substring(6, 14), 16)) {
                ascending++;
            }
        }
        assertThat(ascending).as("ascending steps out of %d", numbers.length - 1).isBetween(850, 1150);
    }

    @Test
    void trackingNumbers_carryAtLeast64BitsOfRandomness() {
        String number = generate(1)[0];

        String randomPart = number.substring("TRACK-".length()).replace("-", "");
        assertThat(randomPart.length() * 4).as("bits of randomness in %s", number).isGreaterThanOrEqualTo(64);
    }
}
