package com.tunnel.service.config;

import com.tunnel.service.handler.ClientWsHandler;
import com.tunnel.service.handler.TunnelWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class WebSocketConfig {

    @Bean
    public HandlerMapping webSocketMapping(
            TunnelWebSocketHandler agentHandler,
            ClientWsHandler clientWsHandler
            ) {
        Map<String, WebSocketHandler> map = new HashMap<>();
        map.put("/agent", agentHandler);
        map.put("/ws/tunnel/**", clientWsHandler);

        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(map);
        mapping.setOrder(-1);
        return mapping;
    }

    @Bean
    public WebSocketHandlerAdapter  handlerAdapter() {
        return new WebSocketHandlerAdapter();
    }
}
