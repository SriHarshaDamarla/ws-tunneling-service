package com.tunnel.service.dto;

import com.tunnel.service.model.Direction;

public record CreateForwardRequest(Direction direction, String agentId, int listenPort, String targetHost,int targetPort, boolean enabled) {
}
