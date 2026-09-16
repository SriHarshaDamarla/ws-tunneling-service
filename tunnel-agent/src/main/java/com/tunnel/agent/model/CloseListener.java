package com.tunnel.agent.model;

import lombok.Data;

@Data
public class CloseListener {
    private String type = "close-listener";
    private String forwardId;
}
