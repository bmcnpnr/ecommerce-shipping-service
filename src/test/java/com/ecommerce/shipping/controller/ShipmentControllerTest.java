package com.ecommerce.shipping.controller;

import com.ecommerce.shipping.dto.CreateShipmentRequest;
import com.ecommerce.shipping.dto.ShipmentDTO;
import com.ecommerce.shipping.dto.ShipmentEventDTO;
import com.ecommerce.shipping.exception.InvalidShipmentStateException;
import com.ecommerce.shipping.exception.ShipmentNotFoundException;
import com.ecommerce.shipping.service.ShipmentService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web slice for {@link ShipmentController}: request mapping, binding, JSON shape, and the
 * error mapping of {@code GlobalExceptionHandler} and {@code CorrelationIdFilter} as a client sees them.
 */
@WebMvcTest(ShipmentController.class)
class ShipmentControllerTest {

    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private ShipmentService shipmentService;

    private static ShipmentDTO dto(String status) {
        return ShipmentDTO.builder().id(31L).orderId(7L).customerId("alice").trackingNumber("TRACK-3F2A9C1B-4")
                .status(status).carrier("DHL").originAddress("Warehouse").destinationAddress("Home")
                .estimatedDelivery(LocalDateTime.of(2026, 11, 3, 14, 30, 15))
                .createdAt(LocalDateTime.of(2026, 10, 29, 14, 30, 15)).build();
    }

    // ───────────────────────────── happy paths ─────────────────────────────

    @Test
    void createShipment_returns201_withBody() throws Exception {
        given(shipmentService.createShipment(any())).willReturn(dto("PENDING"));

        mockMvc.perform(post("/api/v1/shipments").contentType(MediaType.APPLICATION_JSON).content("""
                        {"orderId":7,"customerId":"alice","originAddress":"Warehouse","destinationAddress":"Home","carrier":"UPS"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(31))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.trackingNumber").value("TRACK-3F2A9C1B-4"))
                // REST uses ISO-8601 strings for LocalDateTime (only Kafka uses the int-array form).
                .andExpect(jsonPath("$.estimatedDelivery").value("2026-11-03T14:30:15"))
                .andExpect(jsonPath("$.actualDelivery").value(nullValue()));

        ArgumentCaptor<CreateShipmentRequest> captor = ArgumentCaptor.forClass(CreateShipmentRequest.class);
        verify(shipmentService).createShipment(captor.capture());
        assertThat(captor.getValue()).isEqualTo(CreateShipmentRequest.builder().orderId(7L).customerId("alice")
                .originAddress("Warehouse").destinationAddress("Home").carrier("UPS").build());
    }

    @Test
    void getByOrderId_returns200() throws Exception {
        given(shipmentService.getShipmentByOrderId(7L)).willReturn(dto("SHIPPED"));

        mockMvc.perform(get("/api/v1/shipments/order/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(7))
                .andExpect(jsonPath("$.status").value("SHIPPED"));
    }

    @Test
    void trackShipment_returns200() throws Exception {
        given(shipmentService.getShipmentByTrackingNumber("TRACK-3F2A9C1B-4")).willReturn(dto("SHIPPED"));

        mockMvc.perform(get("/api/v1/shipments/tracking/TRACK-3F2A9C1B-4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trackingNumber").value("TRACK-3F2A9C1B-4"));
    }

    @Test
    void updateStatus_returns200_andPassesEveryField() throws Exception {
        given(shipmentService.updateShipmentStatus(31L, "SHIPPED", "Istanbul hub", "Left the warehouse"))
                .willReturn(dto("SHIPPED"));

        mockMvc.perform(patch("/api/v1/shipments/31/status").contentType(MediaType.APPLICATION_JSON).content("""
                        {"status":"SHIPPED","location":"Istanbul hub","description":"Left the warehouse"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"));
    }

    @Test
    void updateStatus_optionalFieldsMayBeOmitted() throws Exception {
        given(shipmentService.updateShipmentStatus(31L, "PROCESSING", null, null)).willReturn(dto("PROCESSING"));

        mockMvc.perform(patch("/api/v1/shipments/31/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PROCESSING\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void getHistory_returnsArray() throws Exception {
        given(shipmentService.getShipmentHistory(31L)).willReturn(List.of(
                ShipmentEventDTO.builder().id(1L).shipmentId(31L).newStatus("PROCESSING").eventDescription("a")
                        .eventTimestamp(LocalDateTime.of(2026, 10, 29, 10, 0)).build(),
                ShipmentEventDTO.builder().id(2L).shipmentId(31L).newStatus("SHIPPED").eventDescription("b").location("Hub")
                        .eventTimestamp(LocalDateTime.of(2026, 10, 29, 11, 0)).build()));

        mockMvc.perform(get("/api/v1/shipments/31/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].newStatus").value("PROCESSING"))
                .andExpect(jsonPath("$[1].location").value("Hub"))
                .andExpect(jsonPath("$[1].eventTimestamp").value("2026-10-29T11:00:00"));
    }

    // ───────────────────────────── error paths ─────────────────────────────

    @Test
    void notFound_is404_withErrorBody_andEchoedCorrelationId() throws Exception {
        given(shipmentService.getShipmentByOrderId(99L)).willThrow(new ShipmentNotFoundException("Shipment not found for order: 99"));

        mockMvc.perform(get("/api/v1/shipments/order/99").header("X-Correlation-Id", "cid-42"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Correlation-Id", "cid-42"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Shipment not found for order: 99"))
                .andExpect(jsonPath("$.path").value("/api/v1/shipments/order/99"))
                .andExpect(jsonPath("$.correlationId").value("cid-42"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void trackingNotFound_is404_withGeneratedCorrelationId() throws Exception {
        given(shipmentService.getShipmentByTrackingNumber("NOPE")).willThrow(new ShipmentNotFoundException("Shipment not found: NOPE"));

        mockMvc.perform(get("/api/v1/shipments/tracking/NOPE"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Correlation-Id", matchesPattern(UUID_PATTERN)))
                .andExpect(jsonPath("$.correlationId", matchesPattern(UUID_PATTERN)));
    }

    @Test
    void historyOfUnknownShipment_is404() throws Exception {
        given(shipmentService.getShipmentHistory(5L)).willThrow(new ShipmentNotFoundException("Shipment not found: 5"));

        mockMvc.perform(get("/api/v1/shipments/5/history"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Shipment not found: 5"));
    }

    @Test
    void updateOfTerminalShipment_is400() throws Exception {
        given(shipmentService.updateShipmentStatus(31L, "SHIPPED", null, null))
                .willThrow(new InvalidShipmentStateException("Cannot update status of a DELIVERED shipment"));

        mockMvc.perform(patch("/api/v1/shipments/31/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SHIPPED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Cannot update status of a DELIVERED shipment"));
    }

    @Test
    void duplicateCreate_is400() throws Exception {
        given(shipmentService.createShipment(any())).willThrow(new InvalidShipmentStateException("Shipment already exists for order: 7"));

        mockMvc.perform(post("/api/v1/shipments").contentType(MediaType.APPLICATION_JSON).content("""
                        {"orderId":7,"customerId":"alice","originAddress":"W","destinationAddress":"H"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Shipment already exists for order: 7"));
    }

    @Test
    void nonNumericId_is400() throws Exception {
        mockMvc.perform(get("/api/v1/shipments/abc/history"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"));
        verifyNoInteractions(shipmentService);
    }

    @Test
    void malformedJson_is400() throws Exception {
        mockMvc.perform(patch("/api/v1/shipments/31/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(shipmentService);
    }

    @Test
    void missingBody_is400() throws Exception {
        mockMvc.perform(post("/api/v1/shipments").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(shipmentService);
    }

    @Test
    void wrongMethodOnTracking_is405() throws Exception {
        mockMvc.perform(post("/api/v1/shipments/tracking/TRACK-1"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.error").value("Method Not Allowed"));
    }

    @Test
    void unexpectedServiceFailure_is500_withGenericMessage() throws Exception {
        given(shipmentService.getShipmentByOrderId(anyLong())).willThrow(new IllegalStateException("connection refused"));

        mockMvc.perform(get("/api/v1/shipments/order/1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }

    @Test
    void createShipment_missingRequiredFields_is400_withFieldErrors() throws Exception {
        mockMvc.perform(post("/api/v1/shipments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\" \",\"originAddress\":\"W\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors.length()").value(3))
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'orderId')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'customerId')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'destinationAddress')]").exists());
        verifyNoInteractions(shipmentService);
    }

    @Test
    void updateStatus_blankStatus_is400() throws Exception {
        mockMvc.perform(patch("/api/v1/shipments/31/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("status"));
        verify(shipmentService, org.mockito.Mockito.never()).updateShipmentStatus(anyLong(), anyString(), any(), any());
    }
}
