package com.tunnel.service.handler;

import com.tunnel.service.model.TcpClose;
import com.tunnel.service.model.TcpOpen;
import com.tunnel.service.registry.AgentRegistry;
import com.tunnel.service.registry.ConnectionRegistry;
import com.tunnel.service.tcp.TcpForwardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;

@Component
@Slf4j
@RequiredArgsConstructor
public class AgentWsHandler extends AbstractWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final AgentRegistry registry;
    private final TcpForwardService tcpForwardService;
    private final ConnectionRegistry connectionRegistry;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("Agent socket opened: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode node = objectMapper.readTree(message.getPayload());
        String type = node.get("type").asString();
        switch (type) {
            case "register" -> {
                String agentId = node.get("agentId").asString();
                WebSocketSession concurrent = new ConcurrentWebSocketSessionDecorator(session, 10_000, 512 * 1024);
                registry.register(agentId, concurrent);
                session.getAttributes().put("agentId", agentId);
                tcpForwardService.onAgentRegistered(agentId);
                log.info("Registered agent: {}", agentId);
            }
            case "tcp-close" -> {
                TcpClose close = objectMapper.readValue(message.getPayload(), TcpClose.class);
                Socket client = connectionRegistry.remove(close.getConnId());
                trySocketClose(client);
            }
            case "tcp-open" -> {
                String agentId = (String) session.getAttributes().get("agentId");
                TcpOpen open = objectMapper.readValue(message.getPayload(), TcpOpen.class);
                tcpForwardService.openTargetConnection(open, agentId);
            }
            default -> log.warn("Unknown message type: {}", type);
        }

    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        ByteBuffer buf = message.getPayload();
        int connId = buf.getInt();
        byte[] payload =  new byte[buf.remaining()];
        buf.get(payload);

        Socket client = connectionRegistry.get(connId);
        if (client != null) {
            try {
                client.getOutputStream().write(payload);
                client.getOutputStream().flush();
            } catch (IOException e) {
                Socket s = connectionRegistry.remove(connId);
                trySocketClose(s);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String agentId = (String) session.getAttributes().get("agentId");
        log.info("Agent socket closed: {} ({}) - {}", session.getId(), status, agentId);
        if (agentId != null) registry.remove(agentId);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("Transport error on {}", session.getId(), exception);
    }

    private void trySocketClose(Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException e) {
            log.info("ignored socket close exception: {}", e.getMessage());
        }
    }
}
