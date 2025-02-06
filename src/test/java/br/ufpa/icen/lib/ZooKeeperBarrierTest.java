
package br.ufpa.icen.lib;

import org.apache.curator.test.TestingServer;
import org.apache.zookeeper.*;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.util.concurrent.*;

public class ZooKeeperBarrierTest {
    private ZooKeeper zk;

    private static TestingServer testingServer;
    private ZooKeeperBarrier barrier;
    private static final String BARRIER_NODE_PATH = "/barrier";

    @BeforeEach
    public void setUp() throws Exception {
        // Cria um servidor em memória para cada teste executado
        testingServer = new TestingServer();
        barrier = new ZooKeeperBarrier(testingServer.getConnectString(), BARRIER_NODE_PATH) {
            @Override
            protected ZooKeeper createZooKeeperConnection(String connectString, Watcher watcher) throws IOException {
                zk = super.createZooKeeperConnection(connectString, watcher);
                return zk;
            }
        };
    }

    @Test
    public void testWaitForBarrier_QuandoNoExiste_DeveAguardarPorRemocao() throws Exception {
        // Cenário onde outro cliente já está ocupando a barreira
        zk.create(BARRIER_NODE_PATH, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

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
        Assertions.assertDoesNotThrow(() -> future.get(5L, TimeUnit.SECONDS));
        Assertions.assertTrue(future.isDone());
    }

    @Test
    public void testWaitForBarrier_QuandoNoNaoExiste_DeveProsseguir() {
        // Como o nó de barreira não existe, o cliente não precisa esperar
        final Future<Void> future = CompletableFuture.runAsync(() -> {
            try {
                barrier.waitForBarrier();
            } catch (KeeperException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        Assertions.assertDoesNotThrow(() -> future.get(5L, TimeUnit.SECONDS));
        Assertions.assertTrue(future.isDone());
    }

    @Test
    public void testRemoveBarrier_QuandoNoExiste_DeveRemoverNo() throws Exception {
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
