package com.distcache.network;

import com.distcache.cluster.ClusterManager;
import com.distcache.cluster.ConsistentHashRouter;
import com.distcache.cluster.Node;
import com.distcache.core.SegmentedLruCache;
import com.distcache.protocol.BinaryProtocolFrame;
import com.distcache.protocol.OpCode;
import com.distcache.protocol.StatusCode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

public class DocsAndHttpEndpointTest {
    private static final int PORT = 18090;
    private static NettyCacheServer server;
    private static ClusterManager clusterManager;
    private static NettyMultiplexedClient binaryClient;
    private static HttpClient httpClient;

    @BeforeAll
    public static void setUp() throws Exception {
        Node localNode = new Node("test-node", "127.0.0.1", PORT, PORT + 1000);
        SegmentedLruCache cache = new SegmentedLruCache(1000, 4, 0.25f);
        ConsistentHashRouter router = new ConsistentHashRouter(64);
        binaryClient = new NettyMultiplexedClient();

        clusterManager = new ClusterManager(localNode, cache, null, router, binaryClient);
        server = new NettyCacheServer("127.0.0.1", PORT, clusterManager);
        server.start();
        clusterManager.start(Collections.emptyList());

        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @AfterAll
    public static void tearDown() {
        if (binaryClient != null) binaryClient.close();
        if (server != null) server.close();
        if (clusterManager != null) {
            try {
                clusterManager.close();
            } catch (Exception ignored) {}
        }
    }

    @Test
    public void testHttpDocsHtml() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + PORT + "/docs"))
                .header("Accept", "text/html")
                .GET()
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        assertTrue(resp.headers().firstValue("Content-Type").orElse("").contains("text/html"));
        assertTrue(resp.body().contains("<!DOCTYPE html>"));
        assertTrue(resp.body().contains("AirCache Distributed"));
        assertTrue(resp.body().contains("Architecture & API Reference"));
        assertTrue(resp.body().contains("Segmented LRU"));
        assertTrue(resp.body().contains("Data Recovery"));
    }

    @Test
    public void testHttpDocsMarkdown() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + PORT + "/docs"))
                .header("Accept", "text/markdown")
                .GET()
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        assertTrue(resp.headers().firstValue("Content-Type").orElse("").contains("text/markdown"));
        assertTrue(resp.body().contains("AirCache Distributed — Architecture & Operational Documentation"));
        assertTrue(resp.body().contains("1. SYSTEM ARCHITECTURE"));
        assertTrue(resp.body().contains("4. DATA RECOVERY"));
    }

    @Test
    public void testHttpDocsJson() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + PORT + "/api/docs"))
                .GET()
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        assertTrue(resp.headers().firstValue("Content-Type").orElse("").contains("application/json"));
        assertTrue(resp.body().contains("\"system\": \"AirCache Distributed\""));
        assertTrue(resp.body().contains("\"architecture\""));
        assertTrue(resp.body().contains("\"recovery\""));
    }

    @Test
    public void testHttpRestCacheCrudOperations() throws Exception {
        String key = "test:user:999";
        String value = "Enterprise Payload 42";

        // 1. PUT
        HttpRequest putReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + PORT + "/cache/" + key + "?ttl=60000"))
                .PUT(HttpRequest.BodyPublishers.ofString(value))
                .build();
        HttpResponse<String> putResp = httpClient.send(putReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, putResp.statusCode());
        assertTrue(putResp.body().contains("\"status\":\"OK\""));

        // 2. GET
        HttpRequest getReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + PORT + "/cache/" + key))
                .GET()
                .build();
        HttpResponse<String> getResp = httpClient.send(getReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, getResp.statusCode());
        assertEquals(value, getResp.body());

        // 3. DELETE
        HttpRequest delReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + PORT + "/cache/" + key))
                .DELETE()
                .build();
        HttpResponse<String> delResp = httpClient.send(delReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, delResp.statusCode());
        assertTrue(delResp.body().contains("\"status\":\"DELETED\""));

        // 4. GET after DELETE -> 404
        HttpResponse<String> getAfterDelResp = httpClient.send(getReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(404, getAfterDelResp.statusCode());
    }

    @Test
    public void testHttpClusterInfoAndHealth() throws Exception {
        // Test /cluster/info
        HttpRequest infoReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + PORT + "/cluster/info"))
                .GET()
                .build();
        HttpResponse<String> infoResp = httpClient.send(infoReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, infoResp.statusCode());
        assertTrue(infoResp.body().contains("\"localNode\": \"test-node\""));
        assertTrue(infoResp.body().contains("\"nodes\""));

        // Test /health
        HttpRequest healthReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + PORT + "/health"))
                .GET()
                .build();
        HttpResponse<String> healthResp = httpClient.send(healthReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, healthResp.statusCode());
        assertTrue(healthResp.body().contains("\"status\": \"UP\""));
        assertTrue(healthResp.body().contains("\"uptimeMs\""));
    }

    @Test
    public void testBinaryProtocolDocs() throws Exception {
        BinaryProtocolFrame req = BinaryProtocolFrame.createRequest(
                OpCode.DOCS,
                binaryClient.nextCorrelationId(),
                null,
                null,
                0L
        );

        BinaryProtocolFrame resp = binaryClient.sendAsync("127.0.0.1", PORT, req).get();
        assertNotNull(resp);
        assertEquals(StatusCode.SUCCESS, resp.getStatus());
        assertNotNull(resp.getValue());
        String docsText = new String(resp.getValue(), StandardCharsets.UTF_8);
        assertTrue(docsText.contains("AirCache Distributed — Architecture & Operational Documentation"));
        assertTrue(docsText.contains("1. SYSTEM ARCHITECTURE"));
    }
}
