package com.ecommerce.shipping.exception;
public class AuthenticationRequiredException extends RuntimeException {
    public AuthenticationRequiredException() { super("Authentication required"); }
}
