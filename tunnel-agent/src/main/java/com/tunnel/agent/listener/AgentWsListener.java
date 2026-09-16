package com.tunnel.agent.listener;

import com.tunnel.agent.model.*;
import lombok.RequiredArgsConstructor;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@RequiredArgsConstructor
public class AgentWsListener extends WebSocketListener {

    private final String agentId;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<Integer, Socket> sockets = new ConcurrentHashMap<>();
    private final Map<String, ServerSocket> agentListeners = new ConcurrentHashMap<>();
    private final AtomicInteger agentConnIds = new AtomicInteger(0);

    @Override
    public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
        System.out.println("Connected to server");

        String json = objectMapper.writeValueAsString(new Register("register", agentId));
        webSocket.send(json);
    }

    @Override
    public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
        JsonNode node = objectMapper.readTree(text);
        switch(node.get("type").asString()) {
            case "tcp-open" -> {
                TcpOpen open = objectMapper.readValue(text, TcpOpen.class);
                int connId = open.getConnId();
                try {
                    Socket socket = new Socket(open.getHost(), open.getPort());
                    sockets.put(connId, socket);
                    Thread.ofVirtual().start(() -> pumpSocketToServer(connId, socket, webSocket));
                } catch (IOException e) {
                    sendTcpClose(connId, webSocket);
                }
            }
            case "tcp-close" -> {
                TcpClose close = objectMapper.readValue(text, TcpClose.class);
                Socket socket = sockets.remove(close.getConnId());
                if (socket != null) {
                    trySocketClose(socket);
                }
            }
            case "open-listener" -> {
                OpenListener ol = objectMapper.readValue(text, OpenListener.class);
                if (agentListeners.containsKey(ol.getForwardId())) break;
                try {
                    ServerSocket server = new ServerSocket(ol.getListenPort());
                    agentListeners.put(ol.getForwardId(), server);
                    Thread.ofVirtual().start(() -> acceptLoop(ol, server, webSocket));
                } catch (IOException e) {
                    System.out.println("Cannot bind " + ol.getListenPort() + ": " + e.getMessage());
                }
            }
            case "close-listener" -> {
                CloseListener cl = objectMapper.readValue(text, CloseListener.class);
                ServerSocket s = agentListeners.remove(cl.getForwardId());
                if (s != null) {
                    trySocketClose(s);
                }
            }
            default -> System.out.println("unknown: " + text);
        }
    }

    @Override
    public void onMessage(@NotNull WebSocket webSocket, @NotNull ByteString bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes.toByteArray());
        int connId = buf.getInt();
        byte[] payload = new byte[buf.remaining()];
        buf.get(payload);
        Socket socket = sockets.get(connId);
        if (socket != null) {
            try {
                socket.getOutputStream().write(payload);
                socket.getOutputStream().flush();
            } catch (IOException e) {
                Socket s = sockets.remove(connId);
                if (s != null) {
                    trySocketClose(socket);
                }
            }
        }
    }

    @Override
    public void onClosing(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
        webSocket.close(1000, null);
    }

    @Override
    public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, @Nullable Response response) {
        t.printStackTrace();
    }

    private void pumpSocketToServer(int connId, Socket socket, WebSocket webSocket) {
        try (InputStream in = socket.getInputStream()) {
            byte[] buffer = new byte[1024];
            int len;
            while ((len = in.read(buffer)) != -1) {
                ByteBuffer buf = ByteBuffer.allocate(4 + len);
                buf.putInt(connId);
                buf.put(buffer, 0, len);
                buf.flip();
                webSocket.send(ByteString.of(buf));
            }
        } catch (IOException e) {

        } finally {
             if (sockets.remove(connId) != null) {
                 sendTcpClose(connId, webSocket);
             }
             trySocketClose(socket);
        }
    }

    private void sendTcpClose(int connId, WebSocket webSocket) {
        TcpClose close = new TcpClose();
        close.setConnId(connId);
        webSocket.send(objectMapper.writeValueAsString(close));
    }

    private void trySocketClose(Closeable socket) {
        try {
            socket.close();
        } catch (IOException e) {
            System.out.println("Ignoring exception on closing socket");
        }
    }

    private void acceptLoop(OpenListener ol, ServerSocket server, WebSocket webSocket) {
        try{
            while (true) {
                Socket client = server.accept();
                int connId = -agentConnIds.incrementAndGet();
                sockets.put(connId, client);
                TcpOpen open = new TcpOpen();
                open.setConnId(connId);
                open.setHost(ol.getTargetHost());
                open.setPort(ol.getTargetPort());
                webSocket.send(objectMapper.writeValueAsString(open));
                Thread.ofVirtual().start(() -> pumpSocketToServer(connId, client, webSocket));
            }
        } catch (IOException e) {
            System.out.println("Socket closed: " + e.getMessage());
        }
    }
}
