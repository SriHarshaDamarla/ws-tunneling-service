package com.tunnel.service.tcp;

import com.tunnel.service.dto.CreateForwardRequest;
import com.tunnel.service.model.*;
import com.tunnel.service.registry.AgentRegistry;
import com.tunnel.service.registry.ConnectionRegistry;
import com.tunnel.service.repository.ForwardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Slf4j
@RequiredArgsConstructor
public class TcpForwardService {
    private final AgentRegistry registry;
    private final ConnectionRegistry connectionRegistry;
    private final ForwardRepository repository;
    private final ObjectMapper objectMapper;

    private final AtomicInteger connIds = new AtomicInteger(0);
    private final Map<String, Forward> forwards = new ConcurrentHashMap<>();
    private final Map<String, ServerSocket> listeners = new ConcurrentHashMap<>();

    @EventListener(ApplicationReadyEvent.class)
    public void reloadOnBoot() {
        for (Forward f : repository.findAll()) {
            forwards.put(f.getId(), f);
            if (f.isEnabled()) {
                try {
                    applyEnabled(f);
                } catch (IOException e) {
                    log.error("could not re-establish forward {} on port {}: {}",
                            f.getId(), f.getListenPort(), e.getMessage());
                }
            }
        }
        log.info("reloaded {} forwards from db", forwards.size());
    }

    public void openTargetConnection(TcpOpen tcpOpen, String agentId) {
        try {
            Socket target = new Socket(tcpOpen.getHost(), tcpOpen.getPort());
            connectionRegistry.register(tcpOpen.getConnId(), target);
            Thread.ofVirtual().start(() -> pumpSocketToAgent(tcpOpen.getConnId(), target, agentId));
        } catch (IOException e) {
            sendTcpClose(tcpOpen.getConnId(), agentId);
        }
    }

    public void openServerListen(Forward f) throws IOException {
        ServerSocket server = new ServerSocket(f.getListenPort());
        listeners.put(f.getId(), server);
        Thread.ofVirtual().start(() -> acceptLoop(f, server));
    }

    public void closeServerListen(String id) {
        ServerSocket server = listeners.remove(id);
        if (server != null) trySocketClose(server);
    }

    public void onAgentRegistered(String agentId) {
        forwards.values().stream()
                .filter(f -> f.getDirection() == Direction.AGENT_LISTEN && f.isEnabled() && agentId.equals(f.getAgentId()))
                .forEach(this::sendOpenListener);
    }

    private void acceptLoop(Forward f, ServerSocket server) {
        try {
            while (true) {
                Socket client = server.accept();
                Optional<WebSocketSession> sessionOpt = registry.get(f.getAgentId());
                if (sessionOpt.isEmpty()) {
                    trySocketClose(client);
                    continue;
                }
                WebSocketSession session = sessionOpt.get();
                int connId = connIds.incrementAndGet();
                connectionRegistry.register(connId, client);
                TcpOpen open = new TcpOpen();
                open.setConnId(connId);
                open.setHost(f.getTargetHost());
                open.setPort(f.getTargetPort());
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(open)));
                Thread.ofVirtual().start(() -> pumpSocketToAgent(connId, client, f.getAgentId()));
            }

        } catch (IOException e) {
            log.info("accept loop on port {} stopped", f.getListenPort());
        }
    }

    private void pumpSocketToAgent(int connId, Socket client, String agentId) {
        try(InputStream in = client.getInputStream()) {
            byte[] buffer = new byte[1024];
            int len;
            WebSocketSession session = registry.get(agentId).orElseThrow();
            while ((len = in.read(buffer)) != -1) {
                ByteBuffer buf = ByteBuffer.allocate(4 + len);
                buf.putInt(connId);
                buf.put(buffer, 0, len);
                buf.flip();
                session.sendMessage(new BinaryMessage(buf));
            }
            sendTcpClose(connId, agentId);

        } catch (IOException e) {

        } finally {
            if (connectionRegistry.remove(connId) != null) {
                sendTcpClose(connId, agentId);
            }
           trySocketClose(client);
        }

    }

    public Forward createForward(CreateForwardRequest request) throws IOException {
        Forward f = new Forward();
        f.setId(UUID.randomUUID().toString());
        f.setDirection(request.direction());
        f.setAgentId(request.agentId());
        f.setListenPort(request.listenPort());
        f.setTargetHost(request.targetHost());
        f.setTargetPort(request.targetPort());
        f.setEnabled(request.enabled());
        f.setMode(request.mode());
        forwards.put(f.getId(), f);
        repository.save(f);
        if (request.enabled()) applyEnabled(f);
        return f;
    }

    public void setEnabled (String id, boolean enabled) throws IOException {
        Forward f = forwards.get(id);
        if (f == null) return;
        f.setEnabled(enabled);
        repository.save(f);
        if (enabled) applyEnabled(f);
        else applyDisabled(f);
    }

    public void deleteForward(String id) {
        Forward f = forwards.get(id);
        if (f == null) return;
        applyDisabled(f);
        forwards.remove(id);
        repository.deleteById(id);
    }

    public Collection<Forward> getForwards() {
        return forwards.values();
    }

    private void applyEnabled(Forward f) throws IOException {
        if (f.getDirection() == Direction.SERVER_LISTEN) {
            if (!listeners.containsKey(f.getId())) {
                openServerListen(f);
            }
        } else {
            sendOpenListener(f);
        }
    }

    private void applyDisabled(Forward f) {
        if (f.getDirection() == Direction.SERVER_LISTEN) closeServerListen(f.getId());
        else sendCloseListener(f);
    }

    public void sendTcpClose(int connId, String agentId) {
        if (registry.get(agentId).isPresent()) {
            try {
                TcpClose close = new TcpClose();
                close.setConnId(connId);
                WebSocketSession session = registry.get(agentId).get();
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(close)));
            } catch (IOException e) {

            }

        }
    }

    private void sendOpenListener(Forward f) {
        registry.get(f.getAgentId()).ifPresent(s -> {
            OpenListener ol = new OpenListener();
            ol.setForwardId(f.getId());
            ol.setListenPort(f.getListenPort());
            ol.setTargetHost(f.getTargetHost());
            ol.setTargetPort(f.getTargetPort());
            ol.setMode(f.getMode());
            trySend(s, ol);
        });
    }

    private void sendCloseListener(Forward f) {
        registry.get(f.getAgentId()).ifPresent(s -> {
            CloseListener cl = new CloseListener();
            cl.setForwardId(f.getId());
            trySend(s, cl);
        });
    }

    private void trySend(WebSocketSession webSocket, Object message) {
        try {
            webSocket.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
        } catch (IOException e) {
            log.info("ignored websocket send message: {}", e.getMessage());
        }
    }

    private void trySocketClose(Closeable socket) {
        try {
            socket.close();
        } catch (IOException e) {
            log.info("ignored socket close exception: {}", e.getMessage());
        }
    }
}
