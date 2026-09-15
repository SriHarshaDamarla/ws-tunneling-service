package com.tunnel.service.registry;

import org.springframework.stereotype.Component;

import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ConnectionRegistry {
    private final Map<Integer, Socket> conns = new ConcurrentHashMap<>();

    public void register(int connId, Socket socket) {
        conns.put(connId, socket);
    }

    public Socket get(int connId) {
        return conns.get(connId);
    }

    public Socket remove(int connId) {
        return conns.remove(connId);
    }
}
