package com.tunnel.service.model;

import lombok.Data;

@Data
public class Forward {
    private String id;
    private Direction direction;
    private String agentId;
    private int listenPort;
    private String targetHost;
    private int targetPort;
    private boolean enabled;
}
