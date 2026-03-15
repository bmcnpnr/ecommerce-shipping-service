package com.ecommerce.shipping.exception;
public class ShipmentNotFoundException extends RuntimeException {
    public ShipmentNotFoundException(String msg) { super(msg); }
}
