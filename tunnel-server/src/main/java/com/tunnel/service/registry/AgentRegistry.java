package com.tunnel.service.registry;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AgentRegistry {

    private final Map<String, WebSocketSession> agents = new ConcurrentHashMap<>();

    public void register(String agentId, WebSocketSession session) {
        agents.put(agentId, session);
    }

    public void remove(String agentId) {
        agents.remove(agentId);
    }

    public Optional<WebSocketSession> get(String agentId) {
        return Optional.ofNullable(agents.get(agentId));
    }

    public Set<String> getAgentIds() {
        return agents.keySet();
    }
}
