package com.tunnel.agent;

import com.tunnel.agent.listener.AgentWsListener;
import okhttp3.OkHttpClient;
import okhttp3.Request;

public class TunnelAgent {
    static void main(String[] args) throws InterruptedException {
        String agentId = null;
        String wsUrl = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--agent-id") && args.length > i + 1) {
                agentId = args[++i];
            }
            if (args[i].equals("--ws-url") && args.length > i + 1) {
                wsUrl = args[++i];
            }
        }
        if (agentId == null || wsUrl == null) {
            System.err.println("Error: Missing arguments --agent-id --ws-url");
            System.exit(1);
        }
        OkHttpClient client = new OkHttpClient();

        Request request = new Request.Builder()
                .url(wsUrl)
                .build();

        client.newWebSocket(request, new AgentWsListener(agentId));

        Thread.currentThread().join();
    }
}
