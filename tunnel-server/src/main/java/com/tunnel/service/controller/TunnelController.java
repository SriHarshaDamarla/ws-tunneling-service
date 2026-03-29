package com.tunnel.service.controller;

import com.tunnel.service.services.TunnelService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequiredArgsConstructor
public class TunnelController {
    private final TunnelService tunnelService;

    @RequestMapping("/tunnel/{agentId}/{host}/{port}/**")
    public Mono<Void> tunnel(
            @PathVariable String agentId,
            @PathVariable String host,
            @PathVariable int port,
            ServerHttpRequest request,
            ServerHttpResponse response
    ) {
        return tunnelService.handle(agentId, host, port, request, response);
    }
}
