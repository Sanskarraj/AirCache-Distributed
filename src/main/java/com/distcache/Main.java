package com.distcache;

import com.distcache.cluster.ClusterManager;
import com.distcache.cluster.ConsistentHashRouter;
import com.distcache.cluster.Node;
import com.distcache.config.CacheConfig;
import com.distcache.core.SegmentedLruCache;
import com.distcache.network.NettyCacheServer;
import com.distcache.network.NettyMultiplexedClient;
import com.distcache.persistence.AppendOnlyLogPersistence;
import com.distcache.persistence.WriteBehindBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Main application entrypoint for bootstrapping a distributed cache node.
 */
public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        logger.info("=========================================================");
        logger.info("  Starting Distributed In-Memory Cache (Multi-Threaded)  ");
        logger.info("=========================================================");

        CacheConfig config = CacheConfig.fromArgs(args);

        Node localNode = new Node(
                config.getNodeId(),
                config.getHost().equals("0.0.0.0") ? "127.0.0.1" : config.getHost(),
                config.getCachePort(),
                config.getRaftPort()
        );

        // 1. Initialize Segmented LRU Cache
        SegmentedLruCache cache = new SegmentedLruCache(
                config.getCapacity(),
                32, // 32 striped partitions for fine-grained locking
                config.getProbationaryRatio()
        );

        // 2. Initialize Persistence & Replay WAL
        Path walPath = config.getDataDir().resolve(config.getNodeId() + "-wal.aof");
        AppendOnlyLogPersistence persistence;
        try {
            persistence = new AppendOnlyLogPersistence(walPath);
            Map<byte[], byte[]> recovered = persistence.recover();
            for (Map.Entry<byte[], byte[]> entry : recovered.entrySet()) {
                cache.put(entry.getKey(), entry.getValue());
            }
            logger.info("Replayed {} keys from persistent WAL", recovered.size());
        } catch (IOException e) {
            logger.error("Failed to initialize persistence engine", e);
            System.exit(1);
            return;
        }

        // 3. Initialize Write-Behind Buffer
        WriteBehindBuffer writeBehind = new WriteBehindBuffer(persistence);

        // 4. Initialize Consistent Hashing & Multiplexed Peer Client
        ConsistentHashRouter router = new ConsistentHashRouter(config.getVirtualNodes());
        NettyMultiplexedClient peerClient = new NettyMultiplexedClient();

        // 5. Initialize Cluster Manager
        ClusterManager clusterManager = new ClusterManager(
                localNode,
                cache,
                writeBehind,
                router,
                peerClient
        );

        // 6. Initialize and Start Netty Server
        NettyCacheServer server = new NettyCacheServer(config.getHost(), config.getCachePort(), clusterManager);

        try {
            server.start();
            clusterManager.start(config.getPeers());
            logger.info("Node {} successfully started and joined cluster!", config.getNodeId());
        } catch (Exception e) {
            logger.error("Failed to start Netty server", e);
            System.exit(1);
        }

        // 7. Register Clean Shutdown Hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Initiating graceful shutdown for node {}...", config.getNodeId());
            server.close();
            try {
                clusterManager.close();
            } catch (IOException e) {
                logger.error("Error shutting down cluster manager", e);
            }
            logger.info("Node {} shutdown complete.", config.getNodeId());
        }, "shutdown-hook"));
    }
}
