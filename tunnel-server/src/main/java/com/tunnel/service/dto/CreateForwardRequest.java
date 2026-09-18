package com.tunnel.service.dto;

import com.tunnel.service.model.Direction;
import com.tunnel.service.model.ForwardMode;

public record CreateForwardRequest(Direction direction, String agentId, int listenPort, String targetHost, int targetPort, boolean enabled, ForwardMode mode) {
}
