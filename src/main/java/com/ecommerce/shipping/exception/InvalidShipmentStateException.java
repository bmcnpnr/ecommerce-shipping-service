package com.ecommerce.shipping.exception;
public class InvalidShipmentStateException extends RuntimeException {
    public InvalidShipmentStateException(String msg) { super(msg); }
}
