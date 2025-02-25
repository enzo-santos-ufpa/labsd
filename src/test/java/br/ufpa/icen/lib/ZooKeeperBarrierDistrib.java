package br.ufpa.icen.lib;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
public class ZooKeeperBarrierDistrib {

    private static final String ZK_ADDRESS = "localhost:2181";
    private static final String BARRIER_NODE = "/outerBarrier";
    private static final String INNER_BARRIER_NODE = "/innerBarrier";
    private static final int MAX_PARTICIPANTS = 3;
    private static final int NUM_CLIENTS = 6;

    public static void main(String[] args) throws IOException, InterruptedException, KeeperException {
        startEmbeddedZooKeeper();

        ExecutorService executorService = Executors.newFixedThreadPool(NUM_CLIENTS);
        for (int i = 0; i < NUM_CLIENTS; i++) {
            int clientId = i;
            executorService.execute(() -> {
                try {
                    ZooKeeperMultiLevelBarrier barrier = new ZooKeeperMultiLevelBarrier(ZK_ADDRESS, BARRIER_NODE, INNER_BARRIER_NODE, MAX_PARTICIPANTS);
                    System.out.println("Client " + clientId + " waiting at outer barrier...");
                    barrier.waitForBarrier();
                    System.out.println("Client " + clientId + " passed outer barrier!");

                    System.out.println("Client " + clientId + " waiting at inner barrier...");
                    barrier.waitForBarrier();
                    System.out.println("Client " + clientId + " passed inner barrier!");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
        executorService.shutdown();
    }

    
    @Test
    private static void startEmbeddedZooKeeper() throws IOException, InterruptedException {
        File snapDir = new File("/tmp/zookeeper/snap");
        File logDir = new File("/tmp/zookeeper/log");
        snapDir.mkdirs();
        logDir.mkdirs();

        ZooKeeperServer zkServer = new ZooKeeperServer();
        FileTxnSnapLog ftsl = new FileTxnSnapLog(snapDir, logDir);
        zkServer.setTxnLogFactory(ftsl);
        zkServer.setTickTime(2000);

        ServerCnxnFactory factory = ServerCnxnFactory.createFactory();
        factory.configure(new InetSocketAddress("localhost", 2181), 100);
        factory.startup(zkServer);
    }
    
}
