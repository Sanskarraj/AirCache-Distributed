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

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class EndToEndClusterTest {

    private static NettyCacheServer serverA;
    private static NettyCacheServer serverB;
    private static ClusterManager clusterManagerA;
    private static ClusterManager clusterManagerB;
    private static NettyMultiplexedClient client;

    private static final int PORT_A = 18021;
    private static final int PORT_B = 18022;

    @BeforeAll
    public static void setUp() throws Exception {
        Node nodeA = new Node("nodeA", "127.0.0.1", PORT_A, PORT_A + 1000);
        Node nodeB = new Node("nodeB", "127.0.0.1", PORT_B, PORT_B + 1000);

        SegmentedLruCache cacheA = new SegmentedLruCache(10000, 8, 0.25f);
        SegmentedLruCache cacheB = new SegmentedLruCache(10000, 8, 0.25f);

        ConsistentHashRouter routerA = new ConsistentHashRouter(128);
        ConsistentHashRouter routerB = new ConsistentHashRouter(128);

        NettyMultiplexedClient peerClientA = new NettyMultiplexedClient();
        NettyMultiplexedClient peerClientB = new NettyMultiplexedClient();

        clusterManagerA = new ClusterManager(nodeA, cacheA, null, routerA, peerClientA);
        clusterManagerB = new ClusterManager(nodeB, cacheB, null, routerB, peerClientB);

        serverA = new NettyCacheServer("127.0.0.1", PORT_A, clusterManagerA);
        serverB = new NettyCacheServer("127.0.0.1", PORT_B, clusterManagerB);

        serverA.start();
        serverB.start();

        clusterManagerA.start(List.of(nodeB));
        clusterManagerB.start(List.of(nodeA));

        client = new NettyMultiplexedClient();
    }

    @AfterAll
    public static void tearDown() throws Exception {
        if (client != null) client.close();
        if (serverA != null) serverA.close();
        if (serverB != null) serverB.close();
        if (clusterManagerA != null) clusterManagerA.close();
        if (clusterManagerB != null) clusterManagerB.close();
    }

    @Test
    public void testPing() throws Exception {
        BinaryProtocolFrame pingReq = BinaryProtocolFrame.createRequest(
                OpCode.PING, client.nextCorrelationId(), "ping".getBytes(), null, 0L);
        BinaryProtocolFrame resp = client.sendSync("127.0.0.1", PORT_A, pingReq, 3000);

        assertEquals(StatusCode.SUCCESS, resp.getStatus());
        assertEquals("PONG", new String(resp.getValue(), StandardCharsets.UTF_8));
    }

    @Test
    public void testClusterTransparentForwarding() throws Exception {
        // Find a key that hashes to Node B
        byte[] keyOwnedByB = null;
        for (int i = 0; i < 1000; i++) {
            byte[] candidate = ("test-forward-key-" + i).getBytes(StandardCharsets.UTF_8);
            if ("nodeB".equals(clusterManagerA.getRouter().route(candidate).getNodeId())) {
                keyOwnedByB = candidate;
                break;
            }
        }
        assertNotNull(keyOwnedByB, "Must find a key owned by Node B");

        byte[] val = "distributed-value-content".getBytes(StandardCharsets.UTF_8);

        // Send PUT to Node A for a key owned by Node B
        BinaryProtocolFrame putReq = BinaryProtocolFrame.createRequest(
                OpCode.PUT, client.nextCorrelationId(), keyOwnedByB, val, 0L);
        BinaryProtocolFrame putResp = client.sendSync("127.0.0.1", PORT_A, putReq, 3000);
        assertEquals(StatusCode.SUCCESS, putResp.getStatus());

        // Send GET to Node A -> Node A forwards to Node B and returns value
        BinaryProtocolFrame getReq = BinaryProtocolFrame.createRequest(
                OpCode.GET, client.nextCorrelationId(), keyOwnedByB, null, 0L);
        BinaryProtocolFrame getResp = client.sendSync("127.0.0.1", PORT_A, getReq, 3000);
        assertEquals(StatusCode.SUCCESS, getResp.getStatus());
        assertArrayEquals(val, getResp.getValue());

        // Directly query Node B to prove that Node B is indeed the physical owner storing the data
        byte[] directFromCacheB = clusterManagerB.getCacheEngine().get(keyOwnedByB);
        assertNotNull(directFromCacheB, "Data must be physically stored on Node B");
        assertArrayEquals(val, directFromCacheB);

        // Node A's local cache should NOT store Node B's key (Shared-Nothing principle)
        assertNull(clusterManagerA.getCacheEngine().get(keyOwnedByB),
                "Shared-nothing: Node A should not store Node B's keys in its local cache!");
    }
}
