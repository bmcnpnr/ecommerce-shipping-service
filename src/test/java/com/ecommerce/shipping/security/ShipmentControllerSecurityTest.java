package com.ecommerce.shipping.security;

import com.ecommerce.shipping.controller.ShipmentController;
import com.ecommerce.shipping.service.ShipmentService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a hostile client can send to the shipment endpoints, with ShipmentService mocked: malformed
 * and oversized input must be refused before the service, and refusals must not leak internals.
 * Authorization and the public tracking response are covered in ShipmentSecurityIT.
 */
@Tag("security")
@WebMvcTest(ShipmentController.class)
class ShipmentControllerSecurityTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private ShipmentService shipmentService;

    private MvcResult create(String json) throws Exception {
        return mockMvc.perform(post("/api/v1/shipments").contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    private MvcResult updateStatus(String json) throws Exception {
        return mockMvc.perform(patch("/api/v1/shipments/1/status").contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    private static String createJson(String orderId, String customer, String origin, String destination, String carrier) {
        return """
                {"orderId":%s,"customerId":"%s","originAddress":"%s","destinationAddress":"%s","carrier":"%s"}"""
                .formatted(orderId, customer, origin, destination, carrier);
    }

    private static void assertNoInternals(String body) {
        for (String marker : new String[]{"com.ecommerce", "java.lang", "java.time", "org.springframework", "org.hibernate",
                "org.postgresql", "jakarta.", "tools.jackson", "com.fasterxml", "Exception", "\tat ", "select ", "SQLState"}) {
            assertThat(body).as("error body must not leak '%s'", marker).doesNotContain(marker);
        }
    }

    // ---- create ------------------------------------------------------------------------------------

    @Test
    void create_missingOrBlankRequiredFields_is400() throws Exception {
        for (String json : new String[]{
                createJson("null", "alice", "A", "B", "DHL"),
                createJson("1", "", "A", "B", "DHL"),
                createJson("1", "   ", "A", "B", "DHL"),
                createJson("1", "alice", "", "B", "DHL"),
                createJson("1", "alice", "A", "   ", "DHL"),
                "{}", "{\"orderId\":1}"}) {
            assertThat(create(json).getResponse().getStatus()).as(json).isEqualTo(400);
        }
        verifyNoInteractions(shipmentService);
    }

    @Test
    void create_unparseableBodies_are400() throws Exception {
        for (String json : new String[]{"{not json", "", "[]", "null", "\"x\"",
                createJson("\"1 OR 1=1\"", "alice", "A", "B", "DHL"),
                createJson("99999999999999999999", "alice", "A", "B", "DHL"),
                "{\"orderId\":1,\"customerId\":[\"a\"],\"originAddress\":\"A\",\"destinationAddress\":\"B\"}"}) {
            assertThat(create(json).getResponse().getStatus()).as(json).isEqualTo(400);
        }
        verifyNoInteractions(shipmentService);
    }

    @Test
    void create_andStatusUpdate_withValuesLongerThanTheirColumns_are400() throws Exception {
        assertThat(create(createJson("1", "a".repeat(256), "A", "B", "DHL")).getResponse().getStatus()).as("customerId").isEqualTo(400);
        assertThat(create(createJson("1", "alice", "A", "B", "C".repeat(51))).getResponse().getStatus()).as("carrier").isEqualTo(400);
        assertThat(updateStatus("{\"status\":\"SHIPPED\",\"location\":\"" + "L".repeat(256) + "\"}").getResponse().getStatus()).as("location").isEqualTo(400);
        assertThat(updateStatus("{\"status\":\"SHIPPED\",\"description\":\"" + "D".repeat(501) + "\"}").getResponse().getStatus()).as("description").isEqualTo(400);
        verifyNoInteractions(shipmentService);
    }

    // ---- status update ------------------------------------------------------------------------------

    @Test
    void updateStatus_missingBlankOrUnparseable_is400() throws Exception {
        for (String json : new String[]{"{}", "{\"status\":\"\"}", "{\"status\":\"  \"}", "{\"status\":null}", "{\"status\":[\"SHIPPED\"]}", "{bad", ""}) {
            assertThat(updateStatus(json).getResponse().getStatus()).as(json).isEqualTo(400);
        }
        verifyNoInteractions(shipmentService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1%20OR%201=1", "abc", "-", "9999999999999999999999"})
    void idPathVariable_thatIsNotALong_is400_andNeverReachesTheService(String id) throws Exception {
        mockMvc.perform(get("/api/v1/shipments/order/" + id)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/shipments/" + id + "/history")).andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/v1/shipments/" + id + "/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SHIPPED\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(shipmentService);
    }

    // ---- request shape ----------------------------------------------------------------------------------

    @Test
    void wrongContentType_is415() throws Exception {
        for (String type : new String[]{"text/plain", "application/xml", "application/x-www-form-urlencoded"}) {
            assertThat(mockMvc.perform(post("/api/v1/shipments").contentType(type).content("orderId=1")).andReturn().getResponse().getStatus())
                    .as(type).isEqualTo(415);
            assertThat(mockMvc.perform(patch("/api/v1/shipments/1/status").contentType(type).content("status=SHIPPED")).andReturn().getResponse().getStatus())
                    .as(type).isEqualTo(415);
        }
        verifyNoInteractions(shipmentService);
    }

    @Test
    void unsupportedMethods_are405_soShipmentsCannotBeEditedDeletedOrListed() throws Exception {
        mockMvc.perform(put("/api/v1/shipments/order/1")).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete("/api/v1/shipments/order/1")).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete("/api/v1/shipments/tracking/TRACK-1")).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(post("/api/v1/shipments/1/history")).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put("/api/v1/shipments/1/status")).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(post("/api/v1/shipments/tracking/TRACK-1")).andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(shipmentService);
    }

    @Test
    void unknownPath_is404() throws Exception {
        mockMvc.perform(get("/api/v1/shipments/1")).andExpect(status().isNotFound());
        // GET on the collection path exists only as POST (create): the correct answer is 405, not 404
        mockMvc.perform(get("/api/v1/shipments")).andExpect(status().isMethodNotAllowed());
    }

    // ---- error bodies ---------------------------------------------------------------------------------------

    @Test
    void unexpectedFailure_is500WithAGenericBody() throws Exception {
        given(shipmentService.getShipmentByOrderId(5L)).willThrow(new org.springframework.dao.DataIntegrityViolationException(
                "could not execute statement [ERROR: value too long for type character varying(255)] [insert into shipments ...]"));

        MvcResult result = mockMvc.perform(get("/api/v1/shipments/order/5").header("X-Username", "alice").header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isInternalServerError()).andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("An unexpected error occurred").doesNotContain("value too long").doesNotContain("insert into");
        assertNoInternals(body);
    }

    @Test
    void validationErrors_listFieldsAndMessagesOnly() throws Exception {
        String body = create("{}").getResponse().getContentAsString();

        assertThat(body).contains("Validation failed").contains("orderId");
        assertNoInternals(body);
    }

    @Test
    void malformedJson_errorBodyLeaksNoClassNames() throws Exception {
        MvcResult result = create(createJson("\"abc\"", "alice", "A", "B", "DHL"));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertNoInternals(result.getResponse().getContentAsString());
    }

    @Test
    void wrongTypedPathVariable_errorBodyLeaksNoClassNames() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/shipments/order/abc")).andExpect(status().isBadRequest()).andReturn();

        assertNoInternals(result.getResponse().getContentAsString());
    }
}
