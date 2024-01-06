package com.ecommerce.shipping.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.ecommerce.shipping.service.ShippingService.HELLO_FROM_SHIPPING_SERVICE;
import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
public class ShippingServiceTest {

    @InjectMocks
    private ShippingService shippingService;

    @Test
    void testGetHelloMessage() {
        // when
        var result = shippingService.getHelloMessage();

        // then
        assertEquals(HELLO_FROM_SHIPPING_SERVICE, result);
    }
}
