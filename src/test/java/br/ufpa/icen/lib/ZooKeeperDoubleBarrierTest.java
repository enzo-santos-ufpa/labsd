package br.ufpa.icen.lib;

import org.apache.curator.test.TestingServer;
import org.apache.zookeeper.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ZooKeeperDoubleBarrierTest {
    private static final String BARRIER_NODE_PATH = "/barrier";
    private static TestingServer testingServer;
    private ZooKeeper zk;
    private ZooKeeperDoubleBarrier barrier;

    @BeforeEach
    public void setUp() throws Exception {
        // Cria um servidor em memória para cada teste executado
        testingServer = new TestingServer();
        barrier = new ZooKeeperDoubleBarrier(testingServer.getConnectString(), BARRIER_NODE_PATH) {
            @Override
            protected ZooKeeper createZooKeeperConnection(String connectString, Watcher watcher) throws IOException {
                zk = super.createZooKeeperConnection(connectString, watcher);
                return zk;
            }
        };
    }

    @Test
    public void testEnterBarrier_QuandoPrimeiroNoNaBarreira_DeveCriarNoProprioEAguardarPorNoReady() throws Exception {
        Assertions.assertNull(zk.exists(BARRIER_NODE_PATH + "/ready", false));
        Assertions.assertNull(zk.exists(BARRIER_NODE_PATH + "/" + barrier.getId(), false));
        final Future<Void> future = CompletableFuture.runAsync(() -> {
            try {
                barrier.enterBarrier();
            } catch (KeeperException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        });

        // Como o nó "ready" não existe, o cliente precisa criar o seu nó e esperar na barreira
        Assertions.assertThrows(TimeoutException.class, () -> future.get(1L, TimeUnit.SECONDS));
        Assertions.assertNotNull(zk.exists(BARRIER_NODE_PATH + "/" + barrier.getId(), false));

        // Simula a entrada na barreira por outro cliente
        final String id = UUID.randomUUID().toString();
        zk.create(
                BARRIER_NODE_PATH + "/" + id,
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).getBytes(StandardCharsets.UTF_8),
                ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.EPHEMERAL
        );
        // O cliente ainda precisa aguardar na barreira
        Assertions.assertThrows(TimeoutException.class, () -> future.get(1L, TimeUnit.SECONDS));

        // Simula a entrada na barreira do último cliente, o nó "ready"
        zk.create(BARRIER_NODE_PATH + "/ready", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        // O cliente pode prosseguir
        Assertions.assertDoesNotThrow(() -> future.get(1L, TimeUnit.SECONDS));
    }

    @Test
    public void testEnterBarrier_QuandoUltimoNoNaBarreira_DeveCriarNoProprioENoReady() throws Exception {
        // Simula a criação de 2 nós
        for (int n = 1; n <= 2; n++) {
            final String id = UUID.randomUUID().toString();
            zk.create(
                    BARRIER_NODE_PATH + "/" + id,
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).getBytes(StandardCharsets.UTF_8),
                    ZooDefs.Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT
            );
        }
        Assertions.assertNull(zk.exists(BARRIER_NODE_PATH + "/ready", false));
        Assertions.assertNull(zk.exists(BARRIER_NODE_PATH + "/" + barrier.getId(), false));

        final Future<Void> future = CompletableFuture.runAsync(() -> {
            try {
                barrier.enterBarrier();
            } catch (KeeperException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        });

        // Como este é o terceiro e último nó na barreira, deve criar o próprio nó, nó "ready" e prosseguir
        Assertions.assertDoesNotThrow(() -> future.get(1L, TimeUnit.SECONDS));
        Assertions.assertNotNull(zk.exists(BARRIER_NODE_PATH + "/ready", false));
        Assertions.assertNotNull(zk.exists(BARRIER_NODE_PATH + "/" + barrier.getId(), false));
    }

    @Test
    public void testExitBarrier_QuandoBarreiraEstiverVazia_DeveProsseguir() {
        final Future<Void> future = CompletableFuture.runAsync(() -> {
            try {
                barrier.exitBarrier();
            } catch (KeeperException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        Assertions.assertDoesNotThrow(() -> future.get(1L, TimeUnit.SECONDS));
    }

    @Test
    public void testExitBarrier_QuandoUnicoNoNaBarreira_DeveRemoverNoEProsseguir() throws Exception {
        // Simula próprio nó já existente
        zk.create(
                BARRIER_NODE_PATH + "/" + barrier.getId(),
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).getBytes(StandardCharsets.UTF_8),
                ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.EPHEMERAL
        );
        Assertions.assertNotNull(zk.exists(BARRIER_NODE_PATH + "/" + barrier.getId(), false));

        final Future<Void> future = CompletableFuture.runAsync(() -> {
            try {
                barrier.exitBarrier();
            } catch (KeeperException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        Assertions.assertDoesNotThrow(() -> future.get(1L, TimeUnit.SECONDS));
        Assertions.assertNull(zk.exists(BARRIER_NODE_PATH + "/" + barrier.getId(), false));

    }

    @Test
    public void testExitBarrier_QuandoNoMaisAntigoNaBarreira_DeveAguardarNoMaisRecenteEProsseguir() throws Exception {
        final LocalDateTime now = LocalDateTime.now();
        // Simula próprio nó já existente, mais antigo
        zk.create(
                BARRIER_NODE_PATH + "/" + barrier.getId(),
                now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).getBytes(StandardCharsets.UTF_8),
                ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.EPHEMERAL
        );

        // Simula outro nó já existente, mais recente
        final String id = UUID.randomUUID().toString();
        zk.create(
                BARRIER_NODE_PATH + "/" + id,
                now.plusMinutes(5).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).getBytes(StandardCharsets.UTF_8),
                ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.EPHEMERAL
        );
        Assertions.assertNotNull(zk.exists(BARRIER_NODE_PATH + "/" + barrier.getId(), false));
        Assertions.assertNotNull(zk.exists(BARRIER_NODE_PATH + "/" + id, false));

        final Future<Void> future = CompletableFuture.runAsync(() -> {
            try {
                barrier.exitBarrier();
            } catch (KeeperException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        // Nó mais recente detectado, deve aguardar sua remoção
        Assertions.assertThrows(TimeoutException.class, () -> future.get(1L, TimeUnit.SECONDS));

        // Remove nó mais recente
        zk.delete(BARRIER_NODE_PATH + "/" + id, -1);
        Assertions.assertNotNull(zk.exists(BARRIER_NODE_PATH + "/" + barrier.getId(), false));
        Assertions.assertNull(zk.exists(BARRIER_NODE_PATH + "/" + id, false));

        // Nó mais recente removido, deve prosseguir e remover nó próprio
        Assertions.assertDoesNotThrow(() -> future.get(1L, TimeUnit.SECONDS));
        Assertions.assertNull(zk.exists(BARRIER_NODE_PATH + "/" + barrier.getId(), false));
        Assertions.assertNull(zk.exists(BARRIER_NODE_PATH + "/" + id, false));
    }

    @Test
    public void testExitBarrier_QuandoNoMaisRecenteNaBarreira_DeveAguardarNoMaisAntigoEProsseguir() throws Exception {
        final LocalDateTime now = LocalDateTime.now();
        // Simula próprio nó já existente, mais recente
        zk.create(
                BARRIER_NODE_PATH + "/" + barrier.getId(),
                now.plusMinutes(5).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).getBytes(StandardCharsets.UTF_8),
                ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.EPHEMERAL
        );

        // Simula outro nó já existente, mais antigo
        final String id = UUID.randomUUID().toString();
        zk.create(
                BARRIER_NODE_PATH + "/" + id,
                now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).getBytes(StandardCharsets.UTF_8),
                ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.EPHEMERAL
        );
        Assertions.assertNotNull(zk.exists(BARRIER_NODE_PATH + "/" + barrier.getId(), false));
        Assertions.assertNotNull(zk.exists(BARRIER_NODE_PATH + "/" + id, false));

        final Future<Void> future = CompletableFuture.runAsync(() -> {
            try {
                barrier.exitBarrier();
            } catch (KeeperException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        // Nó mais antigo detectado, deve remover nó próprio e aguardar
        Assertions.assertThrows(TimeoutException.class, () -> future.get(1L, TimeUnit.SECONDS));
        Assertions.assertNull(zk.exists(BARRIER_NODE_PATH + "/" + barrier.getId(), false));
        Assertions.assertNotNull(zk.exists(BARRIER_NODE_PATH + "/" + id, false));

        // Aguarda remoção de nó mais antigo
        Assertions.assertThrows(TimeoutException.class, () -> future.get(1L, TimeUnit.SECONDS));

        // Remove nó mais antigo
        zk.delete(BARRIER_NODE_PATH + "/" + id, -1);
        Assertions.assertNull(zk.exists(BARRIER_NODE_PATH + "/" + barrier.getId(), false));
        Assertions.assertNull(zk.exists(BARRIER_NODE_PATH + "/" + id, false));

        // Deve prosseguir
        Assertions.assertDoesNotThrow(() -> future.get(1L, TimeUnit.SECONDS));
    }

    @AfterEach
    public void tearDown() throws InterruptedException {
        barrier.close();
    }
}