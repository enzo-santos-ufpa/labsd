package br.ufpa.icen.lib;

import org.apache.curator.test.TestingServer;
import org.apache.zookeeper.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ZooKeeperBarrierTest {
    private static final String BARRIER_NODE_PATH = "/barrier";
    private static TestingServer testingServer;
    private ZooKeeper zk;
    private ZooKeeperBarrier barrier;

    @BeforeEach
    public void setUp() throws Exception {
        // Cria um servidor em memória para cada teste executado
        testingServer = new TestingServer();
        barrier = new ZooKeeperBarrier(testingServer.getConnectString(), BARRIER_NODE_PATH) {
            @Override
            protected ZooKeeper createZooKeeperConnection(String connectString, Watcher watcher) throws IOException {
                final ZooKeeper zk = super.createZooKeeperConnection(connectString, watcher);
                ZooKeeperBarrierTest.this.zk = zk;
                return zk;
            }
        };
    }

    @Test
    public void testWaitForBarrier_QuandoNoExiste_DeveAguardarPorRemocao() throws Exception {
        // Cenário onde outro cliente já está ocupando a barreira
        Assertions.assertNull(zk.exists(BARRIER_NODE_PATH, false));
        zk.create(BARRIER_NODE_PATH, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        Assertions.assertNotNull(zk.exists(BARRIER_NODE_PATH, false));

        final Future<Void> future = CompletableFuture.runAsync(() -> {
            try {
                barrier.waitForBarrier();
            } catch (KeeperException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        });

        // Como o nó de barreira já existe, o cliente ainda está aguardando na barreira
        Assertions.assertThrows(TimeoutException.class, () -> future.get(5L, TimeUnit.SECONDS));

        // Simula a remoção do nó da barreira por outro cliente
        zk.delete(BARRIER_NODE_PATH, -1);

        // Como o nó de barreira não existe mais, o cliente termina de esperar na barreira
        Assertions.assertNull(zk.exists(BARRIER_NODE_PATH, false));
        Assertions.assertDoesNotThrow(() -> future.get(5L, TimeUnit.SECONDS));
        Assertions.assertTrue(future.isDone());
    }

    @Test
    public void testWaitForBarrier_QuandoNoNaoExiste_DeveProsseguir() throws InterruptedException, KeeperException {
        Assertions.assertNull(zk.exists(BARRIER_NODE_PATH, false));

        // Como o nó de barreira não existe, o cliente não precisa esperar
        final Future<Void> future = CompletableFuture.runAsync(() -> {
            try {
                barrier.waitForBarrier();
            } catch (KeeperException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        Assertions.assertNull(zk.exists(BARRIER_NODE_PATH, false));
        Assertions.assertDoesNotThrow(() -> future.get(5L, TimeUnit.SECONDS));
        Assertions.assertTrue(future.isDone());
    }

    @Test
    public void testRemoveBarrier_QuandoNoExiste_DeveRemoverNo() throws Exception {
        Assertions.assertNull(zk.exists(BARRIER_NODE_PATH, false));
        // Cenário onde um cliente já está ocupando a barreira
        zk.create(BARRIER_NODE_PATH, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        Assertions.assertNotNull(zk.exists(BARRIER_NODE_PATH, false));

        // Chamar o metodo para remover o nó
        barrier.removeBarrier();
        Assertions.assertNull(zk.exists(BARRIER_NODE_PATH, false));
    }

    @Test
    public void testRemoveBarrier_QuandoNoNaoExiste_DeveFazerNada() throws Exception {
        // Cenário onde ninguém está ocupando a barreira
        Assertions.assertNull(zk.exists(BARRIER_NODE_PATH, false));

        // Chamar o metodo para remover o nó
        barrier.removeBarrier();
        Assertions.assertNull(zk.exists(BARRIER_NODE_PATH, false));
    }

    @AfterEach
    public void tearDown() throws InterruptedException {
        barrier.close();
    }
}
