package br.ufpa.icen.lib;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class ZooKeeperMultiLevelBarrierTest {

    private ServerCnxnFactory factory;
    private ZooKeeperServer zkServer;
    private ZooKeeper zk;

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

        zk = new ZooKeeper("localhost:2181", 3000, null);
    }

    @After
    public void tearDown() throws InterruptedException {
        // Encerra o servidor ZooKeeper embutido após cada teste
        if (factory != null) {
            factory.shutdown();
        }
        if (zkServer != null) {
            zkServer.shutdown();
        }
        if (zk != null) {
            zk.close();
        }
    }

    @Test
    public void testInitializeBarrier() throws KeeperException, InterruptedException, IOException {
        // Testa a inicialização da barreira
        try (ZooKeeperMultiLevelBarrier barrier = new ZooKeeperMultiLevelBarrier("localhost:2181", "/testBarrier", "/testInnerBarrier", 3)) {
            // Verifica se os nós da barreira externa e interna foram criados
            assertNotNull(zk.exists("/testBarrier", false));
            assertNotNull(zk.exists("/testInnerBarrier", false));
        }
    }

    @Test
    public void testWaitForBarrier() throws KeeperException, InterruptedException, IOException {
        // Testa a espera na barreira
        try (ZooKeeperMultiLevelBarrier barrier = new ZooKeeperMultiLevelBarrier("localhost:2181", "/testBarrier", "/testInnerBarrier", 3)) {
            barrier.waitForBarrier();
            // Verifica se o contador de participantes foi incrementado corretamente
            assertEquals(1, barrier.getParticipantCount("/testBarrier"));
            assertEquals(1, barrier.getParticipantCount("/testInnerBarrier"));
        }
    }

    @Test
    public void testPassBarrier() throws KeeperException, InterruptedException, IOException {
        // Testa a passagem pela barreira
        try (ZooKeeperMultiLevelBarrier barrier = new ZooKeeperMultiLevelBarrier("localhost:2181", "/testBarrier", "/testInnerBarrier", 3)) {
            barrier.passBarrier("/testBarrier");
            // Verifica se o contador de participantes foi incrementado corretamente
            assertEquals(1, barrier.getParticipantCount("/testBarrier"));
        }
    }

    @Test
    public void testIncrementParticipantCount() throws KeeperException, InterruptedException, IOException {
        // Testa o incremento do contador de participantes
        try (ZooKeeperMultiLevelBarrier barrier = new ZooKeeperMultiLevelBarrier("localhost:2181", "/testBarrier", "/testInnerBarrier", 3)) {
            barrier.incrementParticipantCount("/testBarrier");
            // Verifica se o contador de participantes foi incrementado corretamente
            assertEquals(1, barrier.getParticipantCount("/testBarrier"));
        }
    }

    @Test
    public void testResetBarrier() throws KeeperException, InterruptedException, IOException {
        // Testa o reset da barreira
        try (ZooKeeperMultiLevelBarrier barrier = new ZooKeeperMultiLevelBarrier("localhost:2181", "/testBarrier", "/testInnerBarrier", 3)) {
            barrier.incrementParticipantCount("/testBarrier");
            barrier.resetBarrier("/testBarrier");
            // Verifica se o contador de participantes foi resetado corretamente
            assertEquals(0, barrier.getParticipantCount("/testBarrier"));
        }
    }
}
