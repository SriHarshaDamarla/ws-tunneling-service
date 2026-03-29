package com.tunnel.agent.listener;

import com.tunnel.agent.TunnelAgent;
import com.tunnel.agent.bean.RequestContext;
import com.tunnel.agent.model.CloseMessage;
import com.tunnel.agent.model.HeaderMessage;
import com.tunnel.agent.model.OpenMessage;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.BufferedSink;
import okio.ByteString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AgentWebSocketListener extends WebSocketListener {

    private String agentId;

    public AgentWebSocketListener(String agentId) {
        this.agentId = agentId;
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, RequestContext> requests = new ConcurrentHashMap<>();

    @Override
    public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
        System.out.println("Connected to tunnel server");
        Map<String, Object> register = new HashMap<>();
        register.put("type", "register");
        register.put("agentId", agentId);
        register.put("targets", TunnelAgent.getSupportedHosts());

        webSocket.send(objectMapper.writeValueAsString(register));
    }

    @Override
    public void onMessage(@NotNull WebSocket webSocket, @NotNull ByteString bytes) {
        byte[] message = bytes.toByteArray();
        byte[] idBytes = new byte[36];
        byte[] data = new byte[message.length - 36];
        System.arraycopy(message, 0, idBytes, 0, 36);
        System.arraycopy(message, 36, data, 0, message.length - 36);

        RequestContext ctx = requests.get(new String(idBytes, StandardCharsets.UTF_8));

        if (ctx != null) {
            try {
                ctx.getOutputStream().write(data);
                ctx.getOutputStream().flush();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
        System.out.println("Received Text: " + text);

        if (text.contains("\"type\":\"open\"")) {
            OpenMessage open = objectMapper.readValue(text, OpenMessage.class);

            RequestContext ctx = new RequestContext();
            ctx.setId(open.getId());
            ctx.setMethod(open.getMethod());
            ctx.setHost(open.getHost());
            ctx.setPath(open.getPath());
            ctx.setPort(open.getPort());
            ctx.setHeaders(open.getHeaders());

            ctx.setOutputStream(new PipedOutputStream());
            ctx.setInputStreamFromPipedOutputStream(ctx.getOutputStream());

            requests.put(ctx.getId(), ctx);

            new Thread(() -> executeRequest(ctx, webSocket)).start();
        }

        if (text.contains("\"type\":\"close\"")) {

            CloseMessage close = objectMapper.readValue(text, CloseMessage.class);

            RequestContext ctx = requests.get(close.getId());

            if (ctx != null) {
                try {
                    ctx.getOutputStream().close(); // signals end of request body
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    @Override
    public void onClosing(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
        System.out.println("WebSocket Closing: " + code + " - " + reason);
        webSocket.close(1000, null);
    }

    @Override
    public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, @Nullable Response response) {
        t.printStackTrace();
    }

    private void executeRequest(RequestContext ctx, WebSocket webSocket) {
        try {
            OkHttpClient client = new OkHttpClient();

            RequestBody requestBody = new RequestBody() {

                @Nullable
                @Override
                public MediaType contentType() {
                    return null;
                }

                @Override
                public void writeTo(@NotNull BufferedSink bufferedSink) throws IOException {
                    byte[] buffer = new byte[8192];
                    int bytesRead;

                    while ((bytesRead = ctx.getInputStream().read(buffer)) != -1) {
                        bufferedSink.write(buffer, 0, bytesRead);
                        bufferedSink.flush();
                    }
                }
            };

            String hostUrl = TunnelAgent.getHostUrl(ctx.getHost() + ":" + ctx.getPort()).orElse("");
            Request.Builder builder = new Request.Builder()
                    .url(hostUrl + ctx.getPath());

            builder.method(ctx.getMethod(),
                    ctx.getMethod().equalsIgnoreCase("GET") ? null : requestBody);

            ctx.getHeaders().forEach(builder::addHeader);

            Response response = client.newCall(builder.build()).execute();
            handleResponse(webSocket, response, ctx.getId());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleResponse(WebSocket webSocket, Response response, String id) {
        try {
            Headers headers = response.headers();

            HeaderMessage headerMessage = new HeaderMessage();
            Map<String, String> headerMap = new HashMap<>();
            headers.forEach(pair -> headerMap.put(pair.getFirst(), pair.getSecond()));
            headerMessage.setId(id);
            headerMessage.setHeaders(headerMap);
            headerMessage.setStatus(response.code());

            String headerJson = objectMapper.writeValueAsString(headerMessage);

            webSocket.send(headerJson);

            InputStream is = response.body().byteStream();

            byte[] buffer = new byte[8192];
            int bytesRead;

            while ((bytesRead = is.read(buffer)) != -1) {
                byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
                byte[] frame = new byte[bytesRead + idBytes.length];

                System.arraycopy(idBytes, 0, frame, 0, idBytes.length);
                System.arraycopy(buffer, 0, frame, idBytes.length, bytesRead);

                ByteString byteString = ByteString.of(frame, 0, frame.length);
                webSocket.send(byteString);
            }
            CloseMessage close = new CloseMessage();
            close.setId(id);
            webSocket.send(objectMapper.writeValueAsString(close));
            requests.remove(id);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
