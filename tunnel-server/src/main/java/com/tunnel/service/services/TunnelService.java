package com.tunnel.service.services;

import com.tunnel.service.model.AgentConnection;
import com.tunnel.service.model.CloseMessage;
import com.tunnel.service.model.OpenMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class TunnelService {

    private final Map<String, Sinks.Many<DataBuffer>> streams = new ConcurrentHashMap<>();
    private final Map<String, ServerHttpResponse> responses = new ConcurrentHashMap<>();
    private final Map<String, AgentConnection> agents = new ConcurrentHashMap<>();
    private final Map<String, String> sessionAgentMap = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void registerAgent(String agentId, WebSocketSession session, Set<String> targets) {
        AgentConnection agent = new AgentConnection();
        agent.setAgentId(agentId);
        agent.setSession(session);
        agent.setAllowedTargets(targets);

        agents.put(agentId, agent);
        sessionAgentMap.put(session.getId(), agentId);

        log.debug("Registered Agent: {}", agentId);

    }

    public void removeAgent(WebSocketSession session) {
        String agentId = sessionAgentMap.remove(session.getId());

        if (agentId != null) {
            agents.remove(agentId);
            log.debug("Removed Agent: {}", agentId);
        }
    }

    public boolean isTargetAllowed(String agentId, String host, int port) {
        AgentConnection agent = agents.get(agentId);

        if (agent == null) {
            return false;
        }

        String target = host + ":" + port;

        return agent.getAllowedTargets().contains(target);
    }

    public Mono<Void> handle(
            String agentId,
            String host,
            int port,
            ServerHttpRequest request,
            ServerHttpResponse response
    ) {
        AgentConnection agent = agents.get(agentId);

        if (agent == null) {
            response.setStatusCode(HttpStatus.BAD_GATEWAY);
            return response.setComplete();
        }

        if (!isTargetAllowed(agentId, host, port)) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return response.setComplete();
        }

        WebSocketSession agentSession = agent.getSession();

        String fullPath = request.getURI().getRawPath();
        String query = request.getURI().getRawQuery();

        String fullUrlPath = query != null ? fullPath + "?" + query : fullPath;

        String prefix = "/tunnel/" + agentId + "/" + host + "/" + port;

        String actualPath = fullUrlPath.substring(prefix.length());

        if (actualPath.isEmpty()) {
            actualPath = "/";
        }

        String id = UUID.randomUUID().toString();

        Map<String, String> headers = new HashMap<>();
        request.getHeaders().forEach((k, v) -> headers.put(k, v.getFirst()));
        headers.put("Host", host);

        Sinks.Many<DataBuffer> sink = Sinks.many().unicast().onBackpressureBuffer();

        streams.put(id, sink);
        responses.put(id, response);

        OpenMessage open = new OpenMessage();
        open.setId(id);
        open.setMethod(request.getMethod().name());
        open.setPath(actualPath);
        open.setHost(host);
        open.setPort(port);
        open.setHeaders(headers);

        log.debug("request path: {}", open.getPath());

        String json = objectMapper.writeValueAsString(open);

        agentSession.send(Mono.just(agentSession.textMessage(json))).subscribe();

        request.getBody()
                .doOnNext(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);

                    byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);

                    byte[] frame = new byte[idBytes.length + bytes.length];

                    System.arraycopy(idBytes, 0, frame, 0, idBytes.length);
                    System.arraycopy(bytes, 0, frame, idBytes.length, bytes.length);

                    agentSession.send(Mono.just(agentSession.binaryMessage(factory -> factory.wrap(frame))))
                            .subscribe();
                })
                .doOnComplete(() -> {
                    CloseMessage close = new CloseMessage();
                    close.setId(id);

                    String closeJson = objectMapper.writeValueAsString(close);
                    agentSession.send(Mono.just(agentSession.textMessage(closeJson))).subscribe();
                })
                .subscribe();

        return response.writeWith(sink.asFlux());
    }

    public void handleHeaders(String id, int status, Map<String, String> headers) {

        ServerHttpResponse response = responses.get(id);

        response.setStatusCode(HttpStatus.valueOf(status));

        headers.forEach(response.getHeaders()::add);
    }

    public void handleData(String id, byte[] bytes) {
        Sinks.Many<DataBuffer> sink = streams.get(id);

        if (sink != null) {
            DataBuffer buffer = new DefaultDataBufferFactory().wrap(bytes);
            sink.tryEmitNext(buffer);
        }
    }

    public void handleClose(String id) {
        Sinks.Many<DataBuffer> sink = streams.remove(id);
        responses.remove(id);

        if (sink != null) {
            sink.tryEmitComplete();
        }
    }
}
