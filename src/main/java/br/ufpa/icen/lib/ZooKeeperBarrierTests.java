package br.ufpa.icen.lib;

import org.apache.zookeeper.KeeperException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

public class ZooKeeperBarrierTest {
    private ZooKeeperBarrier barrier;
    private final String connectString = "localhost:2181";
    private final String barrierNode = "/testBarrier";

    /**
     * Configuração inicial antes de cada teste.
     */
    @Before
    public void setUp() throws IOException, KeeperException, InterruptedException {
        barrier = new ZooKeeperBarrier(connectString, barrierNode);
        barrier.removeBarrier();  // Garante que não existe uma barreira anterior
    }

    /**
     * Limpeza após cada teste.
     */
    @After
    public void tearDown() throws InterruptedException, KeeperException {
        barrier.removeBarrier();  // Remove qualquer barreira remanescente
        barrier.close();          // Fecha a conexão com o ZooKeeper
    }

    /**
     * Teste 1: Verifica se a barreira permite a passagem quando removida.
     */
    @Test
    public void testBarrierRemoval() throws KeeperException, InterruptedException {
        barrier.removeBarrier();  // Remove a barreira (caso exista)
        barrier.waitForBarrier(); // Deve passar sem bloquear
        assertTrue(true);         // Se chegou até aqui, o teste passou
    }

    /**
     * Teste 2: Teste de bloqueio quando a barreira existe.
     * O teste falhará se o thread não ficar bloqueado como esperado.
     */
    @Test(timeout = 5000) // Evita loops infinitos
    public void testBarrierBlocking() throws KeeperException, InterruptedException {
        barrier.zk.create(barrierNode, "block".getBytes(), null, org.apache.zookeeper.CreateMode.PERSISTENT);

        Thread waitingThread = new Thread(() -> {
            try {
                barrier.waitForBarrier();
                fail("A barreira não bloqueou como esperado.");
            } catch (Exception e) {
                assertNotNull(e); // Se ocorrer exceção, validar
            }
        });

        waitingThread.start();
        Thread.sleep(1000); // Garante que o thread está bloqueado
        assertTrue(waitingThread.isAlive()); // O thread deve estar bloqueado
    }

    /**
     * Teste 3: Simula falha do servidor ZooKeeper.
     * O teste deve falhar devido à perda de conexão.
     */
    @Test
    public void testServerFailure() throws InterruptedException, KeeperException {
        try {
            System.out.println("Pare o ZooKeeper agora para simular falha (aguardando 10 segundos)...");
            Thread.sleep(10000); // Tempo para parar o servidor manualmente
            barrier.waitForBarrier();
            fail("O teste deveria falhar devido à perda de conexão com o ZooKeeper.");
        } catch (IOException | KeeperException e) {
            assertTrue(e instanceof KeeperException.ConnectionLossException);
        }
    }

    /**
     * Teste 4: Teste de concorrência (múltiplos clientes esperando a barreira ser removida).
     */
    @Test
    public void testConcurrentBarrier() throws KeeperException, InterruptedException {
        barrier.zk.create(barrierNode, "lock".getBytes(), null, org.apache.zookeeper.CreateMode.PERSISTENT);

        Runnable client = () -> {
            try {
                barrier.waitForBarrier();
                System.out.println("Cliente passou pela barreira.");
            } catch (Exception e) {
                fail("Erro inesperado: " + e.getMessage());
            }
        };

        Thread client1 = new Thread(client);
        Thread client2 = new Thread(client);

        client1.start();
        client2.start();

        Thread.sleep(2000); // Garante que ambos estão aguardando

        barrier.removeBarrier(); // Remove a barreira

        client1.join();
        client2.join();

        assertTrue(true); // Se ambos os clientes passaram, o teste está OK
    }

    /**
     * Teste 5: Resiliência após reconexão (desconecta e reconecta o ZooKeeper).
     */
    @Test
    public void testReconnection() throws IOException, KeeperException, InterruptedException {
        barrier.zk.close(); // Simula desconexão
        barrier = new ZooKeeperBarrier(connectString, barrierNode); // Reconecta

        barrier.removeBarrier();
        barrier.waitForBarrier();

        assertTrue(true); // Se reconectou e passou pela barreira, está OK
    }
}
