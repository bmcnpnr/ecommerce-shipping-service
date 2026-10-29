package com.ecommerce.shipping.exception;
/** A shipment already exists for the order: 409. */
public class DuplicateShipmentException extends InvalidShipmentStateException {
    public DuplicateShipmentException(String msg) { super(msg); }
}
