package com.tunnel.service.handler;

import com.tunnel.service.services.TunnelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

@Component
@Slf4j
@RequiredArgsConstructor
public class ClientWsHandler implements WebSocketHandler {

    private final TunnelService tunnelService;

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        String path = session.getHandshakeInfo().getUri().getPath();

        String[] parts = path.split("/");

        String agentId = parts[3];
        String host = parts[4];
        int port = Integer.parseInt(parts[5]);

        return null;
    }
}
