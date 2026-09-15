package com.tunnel.service.tcp;

import com.tunnel.service.model.TcpClose;
import com.tunnel.service.model.TcpOpen;
import com.tunnel.service.registry.AgentRegistry;
import com.tunnel.service.registry.ConnectionRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Slf4j
@RequiredArgsConstructor
public class TcpForwardService {
    private final AgentRegistry registry;
    private final ConnectionRegistry connectionRegistry;
    private final ObjectMapper objectMapper;
    private final AtomicInteger connIds = new AtomicInteger(0);

    @PostConstruct
    public void start() {
        Thread.ofVirtual().start(() -> acceptLoop(9090, "raspberrypi.local", 22, "local-agent"));
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

    private void acceptLoop(int listenPort, String targetHost, int targetPort, String agentId) {
        try (ServerSocket server = new ServerSocket(listenPort)) {
            while (!Thread.currentThread().isInterrupted()) {
                Socket client = server.accept();
                int connId = connIds.incrementAndGet();
                connectionRegistry.register(connId, client);
                TcpOpen open = new TcpOpen();
                open.setConnId(connId);
                open.setHost(targetHost);
                open.setPort(targetPort);
                WebSocketSession session = registry.get(agentId).orElseThrow();
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(open)));
                Thread.ofVirtual().start(() -> pumpSocketToAgent(connId, client, agentId));
            }

        } catch (IOException e) {
            log.error("accept loop died on {}", listenPort, e);
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

    private void trySocketClose(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            log.info("ignored socket close exception: {}", e.getMessage());
        }
    }
}
