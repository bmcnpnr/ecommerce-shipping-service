package com.ecommerce.shipping.exception;
/** A well-formed status that is not a legal next step from the shipment's current status: 409. */
public class IllegalStatusTransitionException extends InvalidShipmentStateException {
    public IllegalStatusTransitionException(String msg) { super(msg); }
}
