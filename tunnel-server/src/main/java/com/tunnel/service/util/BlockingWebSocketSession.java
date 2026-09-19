package com.tunnel.service.util;

import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketSessionDecorator;

import java.io.IOException;

public class BlockingWebSocketSession extends WebSocketSessionDecorator {

    public BlockingWebSocketSession(WebSocketSession session) {
        super(session);
    }

    @Override
    public synchronized void sendMessage(WebSocketMessage<?> message) throws IOException {
        getDelegate().sendMessage(message);
    }
}
