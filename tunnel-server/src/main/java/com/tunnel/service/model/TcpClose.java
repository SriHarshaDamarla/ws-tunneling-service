package com.tunnel.service.model;

import lombok.Data;

@Data
public class TcpClose {
    private String type = "tcp-close";
    private int connId;
}
