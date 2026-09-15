package com.tunnel.agent.model;

import lombok.Data;

@Data
public class TcpClose {
    private String type = "tcp-close";
    private int connId;
}
