package com.tunnel.service.model;

import lombok.Data;

@Data
public class OpenListener {
    private String type = "open-listener";
    private String forwardId;
    private int listenPort;
    private String targetHost;
    private int targetPort;
    private ForwardMode mode;
}
