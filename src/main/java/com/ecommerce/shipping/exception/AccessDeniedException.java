package com.ecommerce.shipping.exception;
public class AccessDeniedException extends RuntimeException {
    public AccessDeniedException() { super("Access denied"); }
}
