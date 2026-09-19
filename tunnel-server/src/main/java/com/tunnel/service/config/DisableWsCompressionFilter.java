package com.tunnel.service.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Strips the Sec-WebSocket-Extensions request header on the WS handshake so Tomcat never
 * negotiates permessage-deflate. Compression over already-incompressible tunnel traffic
 * (TLS/binary/files) just burns CPU (~35MB/s ceiling) for zero size benefit.
 */
@Component
@Order(0)
public class DisableWsCompressionFilter extends OncePerRequestFilter {

    private static final String EXT = "Sec-WebSocket-Extensions";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpServletRequestWrapper wrapper = new HttpServletRequestWrapper(request) {
            @Override public String getHeader(String name) {
                return EXT.equalsIgnoreCase(name) ? null : super.getHeader(name);
            }
            @Override public Enumeration<String> getHeaders(String name) {
                return EXT.equalsIgnoreCase(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
            }
            @Override public Enumeration<String> getHeaderNames() {
                List<String> names = Collections.list(super.getHeaderNames());
                names.removeIf(EXT::equalsIgnoreCase);
                return Collections.enumeration(names);
            }
        };
        chain.doFilter(wrapper, response);
    }
}
