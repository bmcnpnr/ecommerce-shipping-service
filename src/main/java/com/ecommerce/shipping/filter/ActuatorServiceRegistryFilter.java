package com.ecommerce.shipping.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * /actuator/serviceregistry can take the instance out of service, so it answers only to loopback callers
 * (the Kubernetes preStop hook calls it on localhost). Everyone else gets 403.
 */
@Component
public class ActuatorServiceRegistryFilter extends OncePerRequestFilter {

    private static final String PATH = "/actuator/serviceregistry";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        if ((path.equals(PATH) || path.startsWith(PATH + "/")) && !isLoopback(req.getRemoteAddr())) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            res.setContentType("application/json");
            res.getWriter().write("{\"status\":403,\"error\":\"Forbidden\",\"message\":\"Access denied\"}");
            return;
        }
        chain.doFilter(req, res);
    }

    static boolean isLoopback(String remoteAddr) {
        // literal addresses only: never trigger a DNS lookup
        if (remoteAddr == null || !remoteAddr.matches("[0-9a-fA-F:.]+")) return false;
        try {
            return InetAddress.getByName(remoteAddr).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
