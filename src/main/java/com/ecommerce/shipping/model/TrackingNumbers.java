package com.ecommerce.shipping.model;

import java.security.SecureRandom;

/** Tracking numbers are a public lookup key, so they carry 64 bits from a SecureRandom: TRACK-XXXXXXXX-XXXXXXXX. */
public final class TrackingNumbers {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String HEX = "0123456789ABCDEF";

    private TrackingNumbers() { }

    public static String generate() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder("TRACK-");
        for (int i = 0; i < bytes.length; i++) {
            if (i == 4) sb.append("-");
            sb.append(HEX.charAt((bytes[i] >> 4) & 0xF)).append(HEX.charAt(bytes[i] & 0xF));
        }
        return sb.toString();
    }
}
