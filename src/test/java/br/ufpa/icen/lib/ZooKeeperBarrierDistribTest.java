package br.ufpa.icen.lib;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertTrue;

public class ZooKeeperBarrierDistribTest {

    private ServerCnxnFactory factory;
    private ZooKeeperServer zkServer;

    @Before
    public void setUp() throws IOException, InterruptedException {
        // Configura o servidor ZooKeeper embutido antes de cada teste
        File snapDir = new File("/tmp/zookeeper/snap");
        File logDir = new File("/tmp/zookeeper/log");
        snapDir.mkdirs();
        logDir.mkdirs();

        zkServer = new ZooKeeperServer();
        FileTxnSnapLog ftsl = new FileTxnSnapLog(snapDir, logDir);
        zkServer.setTxnLogFactory(ftsl);
        zkServer.setTickTime(2000);

        factory = ServerCnxnFactory.createFactory();
        factory.configure(new InetSocketAddress("localhost", 2181), 100);
        factory.startup(zkServer);
    }

    @After
    public void tearDown() {
        // Encerra o servidor ZooKeeper embutido após cada teste
        if (factory != null) {
            factory.shutdown();
        }
        if (zkServer != null) {
            zkServer.shutdown();
        }
    }

    @Test
    public void testBarrierSynchronization() throws IOException, InterruptedException, KeeperException {
        // Testa a sincronização da barreira com múltiplos clientes
        ExecutorService executorService = Executors.newFixedThreadPool(ZooKeeperBarrierDistrib.NUM_CLIENTS);
        for (int i = 0; i < ZooKeeperBarrierDistrib.NUM_CLIENTS; i++) {
            int clientId = i;
            executorService.execute(() -> {
                try {
                    ZooKeeperMultiLevelBarrier barrier = new ZooKeeperMultiLevelBarrier(
                            ZooKeeperBarrierDistrib.ZK_ADDRESS,
                            ZooKeeperBarrierDistrib.BARRIER_NODE,
                            ZooKeeperBarrierDistrib.INNER_BARRIER_NODE,
                            ZooKeeperBarrierDistrib.MAX_PARTICIPANTS
                    );
                    System.out.println("Client " + clientId + " esperando na barreira externa...");
                    barrier.waitForBarrier();
                    System.out.println("Client " + clientId + " passou pela barreira externa!");

                    System.out.println("Client " + clientId + " esperando na barreira interna...");
                    barrier.waitForBarrier();
                    System.out.println("Client " + clientId + " passou pela barreira interna!");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
        executorService.shutdown();
        assertTrue(executorService.isShutdown());
    }
}
