package com.ecommerce.shipping.service;

import org.springframework.stereotype.Service;

@Service
public class ShippingService {

    public static final String HELLO_FROM_SHIPPING_SERVICE = "Hello from Shipping Service!";

    public String getHelloMessage() {
        return HELLO_FROM_SHIPPING_SERVICE;
    }
}
