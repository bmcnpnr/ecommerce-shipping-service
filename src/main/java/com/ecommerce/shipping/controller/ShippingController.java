package com.ecommerce.shipping.controller;

import com.ecommerce.shipping.service.ShippingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ShippingController {

    private final ShippingService shippingService;

    @Autowired
    public ShippingController(final ShippingService shippingService) {
        this.shippingService = shippingService;
    }

    @GetMapping("/hello")
    public String sayHello() {
        return shippingService.getHelloMessage();
    }
}
