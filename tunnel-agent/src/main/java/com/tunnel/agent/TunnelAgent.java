package com.tunnel.agent;

import com.tunnel.agent.listener.AgentWsListener;
import okhttp3.OkHttpClient;
import okhttp3.Request;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TunnelAgent {
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .pingInterval(Duration.ofSeconds(20))
            .socketFactory(new NoDelaySocketFactory())   // TCP_NODELAY on the WS transport (kills Nagle on the LAN hop)
            .build();
    private static final AtomicInteger backOff = new AtomicInteger(1);
    private static String wsUrl, agentId;
    static void main(String[] args) throws InterruptedException {
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

        connect();
        Thread.currentThread().join();
    }

    private static void connect() {
        Request request = new Request.Builder()
                .url(wsUrl)
                .build();

        client.newWebSocket(request, new AgentWsListener(agentId,
                () -> backOff.set(1),
                TunnelAgent::scheduleReconnect));
    }

    private static void scheduleReconnect() {
        int delay = backOff.getAndUpdate(val -> Math.min(val * 2, 30));
        System.out.println("connection lost; reconnecting in " + delay + " s");
        scheduler.schedule(TunnelAgent::connect, delay, TimeUnit.SECONDS);
    }
}
