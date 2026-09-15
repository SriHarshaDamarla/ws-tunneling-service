package com.tunnel.service.tcp;

import com.tunnel.service.model.Forward;
import com.tunnel.service.model.TcpClose;
import com.tunnel.service.model.TcpOpen;
import com.tunnel.service.registry.AgentRegistry;
import com.tunnel.service.registry.ConnectionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Slf4j
@RequiredArgsConstructor
public class TcpForwardService {
    private final AgentRegistry registry;
    private final ConnectionRegistry connectionRegistry;
    private final ObjectMapper objectMapper;

    private final AtomicInteger connIds = new AtomicInteger(0);
    private final Map<String, Forward> forwards = new ConcurrentHashMap<>();
    private final Map<String, ServerSocket> listeners = new ConcurrentHashMap<>();

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

    private void trySocketClose(Closeable socket) {
        try {
            socket.close();
        } catch (IOException e) {
            log.info("ignored socket close exception: {}", e.getMessage());
        }
    }
}
