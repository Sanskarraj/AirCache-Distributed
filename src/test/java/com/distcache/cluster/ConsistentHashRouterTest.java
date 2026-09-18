package com.distcache.cluster;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class ConsistentHashRouterTest {

    @Test
    public void testRingVirtualNodeTokenCount() {
        ConsistentHashRouter router = new ConsistentHashRouter(128);
        Node n1 = new Node("node1", "127.0.0.1", 8001, 9001);
        Node n2 = new Node("node2", "127.0.0.1", 8002, 9002);

        router.addNode(n1);
        router.addNode(n2);

        assertEquals(2, router.getPhysicalNodeCount());
        assertEquals(256, router.getRingSize());

        router.removeNode("node1");
        assertEquals(1, router.getPhysicalNodeCount());
        assertEquals(128, router.getRingSize());
    }

    @Test
    public void testDeterministicRouting() {
        ConsistentHashRouter router = new ConsistentHashRouter(64);
        router.addNode(new Node("nodeA", "127.0.0.1", 8001, 9001));
        router.addNode(new Node("nodeB", "127.0.0.1", 8002, 9002));
        router.addNode(new Node("nodeC", "127.0.0.1", 8003, 9003));

        byte[] key = "session:user:45892".getBytes(StandardCharsets.UTF_8);

        Node firstLookup = router.route(key);
        assertNotNull(firstLookup);

        // Multiple queries should route to the exact same node
        for (int i = 0; i < 100; i++) {
            assertEquals(firstLookup.getNodeId(), router.route(key).getNodeId());
        }
    }
}
