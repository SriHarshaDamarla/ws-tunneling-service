package com.tunnel.service.model;

import lombok.Data;
import org.springframework.web.reactive.socket.WebSocketSession;

import java.util.Set;

@Data
public class AgentConnection {
    private String agentId;
    private WebSocketSession session;
    private Set<String> allowedTargets;
}
