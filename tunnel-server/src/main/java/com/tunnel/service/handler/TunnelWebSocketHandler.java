package com.tunnel.service.handler;

import com.tunnel.service.model.CloseMessage;
import com.tunnel.service.model.HeaderMessage;
import com.tunnel.service.services.TunnelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeSet;

@Component
@Slf4j
@RequiredArgsConstructor
public class TunnelWebSocketHandler implements WebSocketHandler {

    private final TunnelService tunnelService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        log.info("Agent Connected: {}", session.getId());

        return session.receive()
                .flatMap(msg -> {

                    if (msg.getType() == WebSocketMessage.Type.TEXT) {
                        String text = msg.getPayloadAsText();

                        if (text.contains("\"type\":\"register\"")) {
                            JsonNode node = objectMapper.readTree(text);

                            String agentId = node.get("agentId").asString();
                            TreeSet<String> targets = objectMapper.treeToValue(node.get("targets"), new TypeReference<>() {});

                            tunnelService.registerAgent(agentId, session, targets);
                        }

                        if (text.contains("\"type\":\"headers\"")) {
                            HeaderMessage headerMessage = objectMapper.readValue(text, HeaderMessage.class);
                            String id = headerMessage.getId();
                            int status = headerMessage.getStatus();
                            Map<String, String> headers = headerMessage.getHeaders();

                            tunnelService.handleHeaders(id, status, headers);
                        }

                        if (text.contains("\"type\":\"close\"")) {
                            CloseMessage close = objectMapper.readValue(text, CloseMessage.class);
                            tunnelService.handleClose(close.getId());
                        }
                    }

                    if (msg.getType() == WebSocketMessage.Type.BINARY) {

                        DataBuffer payload = msg.getPayload();

                        byte[] idBytes = new byte[36];
                        payload.read(idBytes, 0, 36);

                        String id = new String(idBytes, StandardCharsets.UTF_8);

                        byte[] data = new byte[payload.readableByteCount()];
                        payload.read(data, 0, data.length);

                        tunnelService.handleData(id, data);
                    }

                    return Mono.empty();
                })
                .doOnError(err -> log.error("Error in WebSocket session {}: {}", session.getId(), err.getMessage(), err))
                .doOnComplete(() -> {
                    tunnelService.removeAgent(session);
                }).then();
    }
}
