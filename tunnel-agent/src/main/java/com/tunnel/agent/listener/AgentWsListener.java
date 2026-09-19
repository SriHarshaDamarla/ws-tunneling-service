package com.tunnel.agent.listener;

import com.tunnel.agent.model.*;
import lombok.RequiredArgsConstructor;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@RequiredArgsConstructor
public class AgentWsListener extends WebSocketListener {

    private final String agentId;
    private final Runnable onConnected;
    private final Runnable onDisconnected;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<Integer, Socket> sockets = new ConcurrentHashMap<>();
    private final Map<String, ServerSocket> agentListeners = new ConcurrentHashMap<>();
    private final AtomicInteger agentConnIds = new AtomicInteger(0);
    private final AtomicBoolean dropped =  new AtomicBoolean(false);

    @Override
    public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
        System.out.println("Connected to server");

        String json = objectMapper.writeValueAsString(new Register("register", agentId));
        webSocket.send(json);
        onConnected.run();
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
                    socket.setTcpNoDelay(true);
                    socket.setSendBufferSize(1024 * 1024);
                    socket.setReceiveBufferSize(1024 * 1024);
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
        handleDrop();
    }

    @Override
    public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
        handleDrop();
    }

    private void pumpSocketToServer(int connId, Socket socket, WebSocket webSocket) {
        try (InputStream in = socket.getInputStream()) {
            byte[] buffer = new byte[16 * 1024];
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
                trySocketClose(socket);
            }
            sendTcpClose(connId, webSocket);
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

    private void handleDrop() {
        if (!dropped.compareAndSet(false, true)) return;
        cleanUp();
        onDisconnected.run();
    }

    private void cleanUp() {
        agentListeners.values().forEach(this::trySocketClose);
        agentListeners.clear();
        sockets.values().forEach(this::trySocketClose);
        sockets.clear();
    }

    private String parseHost(byte[] head) {
        for (String line: new String(head, StandardCharsets.US_ASCII).split("\r\n")) {
            if (line.regionMatches(true, 0, "host:", 0, 5)) {
                String v = line.substring(5).trim();
                int c = v.indexOf(':');
                return c >= 0 ? v.substring(0, c) : v;
            }
        }
        return null;
    }

    private byte[] readHttpHead(InputStream in, int cap) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(cap);
        int b, n = 0;
        while ((b = in.read()) != -1) {
            buf.write(b);
            n++;
            if (n >= 4 && endsWithCRLFCRLF(buf.toByteArray())) break;
            if (n >= cap) break;
        }
        return buf.toByteArray();
    }

    private boolean endsWithCRLFCRLF(byte[] data) {
        return new String(data).endsWith("\r\n\r\n");
    }


    private byte[] readN(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r == -1) return null;
            off += r;
        }
        return buf;
    }

    // Section taken from AI and, it just works STARTS
    // read the whole first TLS record (the ClientHello). returns the raw bytes to forward, or null.
    private byte[] readTlsClientHello(InputStream in) throws IOException {
        byte[] header = readN(in, 5);
        if (header == null || (header[0] & 0xFF) != 0x16) return null;   // not a TLS handshake
        int recordLen = ((header[3] & 0xFF) << 8) | (header[4] & 0xFF);
        if (recordLen <= 0 || recordLen > 16384) return null;           // TLS record max
        byte[] body = readN(in, recordLen);
        if (body == null) return null;
        byte[] full = new byte[5 + recordLen];
        System.arraycopy(header, 0, full, 0, 5);
        System.arraycopy(body, 0, full, 5, recordLen);
        return full;                                                     // parse AND forward these
    }

    private int u16(byte[] b, int i) { return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF); }

    // walk the ClientHello to the SNI hostname. fail-safe: any malformed input → null.
    private String parseSni(byte[] rec) {
        try {
            int pos = 5;                                   // skip record header
            if ((rec[pos] & 0xFF) != 0x01) return null;    // must be ClientHello
            pos += 4;                                      // handshake type(1)+len(3)
            pos += 2 + 32;                                 // client_version + random
            pos += 1 + (rec[pos] & 0xFF);                  // session_id
            pos += 2 + u16(rec, pos);                      // cipher_suites
            pos += 1 + (rec[pos] & 0xFF);                  // compression_methods
            int extEnd = pos + 2 + u16(rec, pos);          // extensions block end
            pos += 2;
            while (pos + 4 <= extEnd) {
                int type = u16(rec, pos);
                int len  = u16(rec, pos + 2);
                pos += 4;
                if (type == 0x0000) {                      // server_name extension
                    int p = pos + 2;                       // skip server_name_list length
                    int nameType = rec[p] & 0xFF; p += 1;
                    int nameLen  = u16(rec, p);   p += 2;
                    if (nameType == 0x00) return new String(rec, p, nameLen, StandardCharsets.US_ASCII);
                }
                pos += len;
            }
        } catch (Exception ignored) { }                    // any bounds issue → treat as no SNI
        return null;
    }
    // Section taken from AI and, it just works ENDS

    private void acceptLoop(OpenListener ol, ServerSocket server, WebSocket webSocket) {
        try{
            while (true) {
                Socket client = server.accept();
                client.setTcpNoDelay(true);
                client.setSendBufferSize(1024 * 1024);
                client.setReceiveBufferSize(1024 * 1024);
                byte[] peeked = null;
                String host = switch (ol.getMode()) {
                    case HTTP -> {
                        peeked = readHttpHead(client.getInputStream(), 8192);
                        yield parseHost(peeked);
                    }
                    case TLS -> {
                        peeked = readTlsClientHello(client.getInputStream());
                        yield (peeked == null) ? null : parseSni(peeked);
                    }
                    default -> ol.getTargetHost();
                };
                if (ol.getMode() != ForwardMode.TCP && host == null) {
                    trySocketClose(client);
                    continue;
                }

                int connId = -agentConnIds.incrementAndGet();
                sockets.put(connId, client);

                TcpOpen open = new TcpOpen();
                open.setConnId(connId);
                open.setHost(host);
                open.setPort(ol.getTargetPort());

                webSocket.send(objectMapper.writeValueAsString(open));
                if (peeked != null) sendFrame(connId, peeked, webSocket);
                Thread.ofVirtual().start(() -> pumpSocketToServer(connId, client, webSocket));
            }
        } catch (IOException e) {
            System.out.println("Socket closed: " + e.getMessage());
        }
    }

    private void sendFrame(int connId, byte[] data, WebSocket webSocket) {
        ByteBuffer buf = ByteBuffer.allocate(4 + data.length);
        buf.putInt(connId);
        buf.put(data);
        buf.flip();
        webSocket.send(ByteString.of(buf.array()));
    }
}
