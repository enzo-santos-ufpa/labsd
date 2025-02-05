package br.ufpa.icen.Barreira2;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;

public class Hub implements Watcher {

    private static final String ZOOKEEPER_ADDRESS = "localhost:2181";
    private static final int SESSION_TIMEOUT = 3000;
    private static final String HUB_PATH = "/gameHubBarrier";
    private static final String BARRIER1_PATH = HUB_PATH + "/barrier1";
    private static final String BARRIER2_PATH = HUB_PATH + "/barrier2";

    private final ZooKeeper zooKeeper;
    private final int numJogadores;
    private final Map<Integer, String> jogadores = new HashMap<>();
    private final CountDownLatch connectLatch = new CountDownLatch(1);
    private final CountDownLatch barrierLatch1;
    private final CountDownLatch barrierLatch2;


    public Hub(int numJogadores) throws IOException {
        this.numJogadores = numJogadores;
        zooKeeper = new ZooKeeper(ZOOKEEPER_ADDRESS, SESSION_TIMEOUT, this);
        barrierLatch1 = new CountDownLatch(numJogadores);
        barrierLatch2 = new CountDownLatch(numJogadores);
    }

    public void initialize() throws KeeperException, InterruptedException {
        connectLatch.await(); // Wait for ZooKeeper connection

        Stat stat = zooKeeper.exists(HUB_PATH, false);
        if (stat == null) {
            zooKeeper.create(HUB_PATH, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }

        createBarrier(BARRIER1_PATH);
        createBarrier(BARRIER2_PATH);
    }

    private void createBarrier(String barrierPath) throws KeeperException, InterruptedException {
        Stat stat = zooKeeper.exists(barrierPath, false);
        if (stat == null) {
            zooKeeper.create(barrierPath, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }
    }

    public void startGame() throws InterruptedException, KeeperException {
        ExecutorService executor = Executors.newFixedThreadPool(numJogadores);
        Scanner scanner = new Scanner(System.in);

        for (int i = 0; i < numJogadores; i++) {
            final int jogadorId = i + 1;
            System.out.println("Jogador " + jogadorId + ", insira seu nome:");
            String nomeJogador = scanner.nextLine();
            jogadores.put(jogadorId, nomeJogador);

            executor.submit(() -> {
                try {
                    System.out.println(jogadores.get(jogadorId) + " (Jogador " + jogadorId + ") está pronto e esperando na barreira 1.");
                    enterBarrier(1);
                    System.out.println(jogadores.get(jogadorId) + " (Jogador " + jogadorId + ") passou pela barreira 1. Caminhando...");
                    Thread.sleep(2000);

                    System.out.println(jogadores.get(jogadorId) + " (Jogador " + jogadorId + ") está pronto e esperando na barreira 2.");
                    enterBarrier(2);
                    System.out.println(jogadores.get(jogadorId) + " (Jogador " + jogadorId + ") passou pela barreira 2. Caminhando...");
                    Thread.sleep(2000);

                    System.out.println(jogadores.get(jogadorId) + " (Jogador " + jogadorId + ") completou todas as barreiras!");
                } catch (InterruptedException | KeeperException e) {
                    e.printStackTrace();
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS); // Wait for all threads to finish
        scanner.close();
    }

    public void enterBarrier(int barrierNumber) throws KeeperException, InterruptedException {
        String barrierPath = (barrierNumber == 1) ? BARRIER1_PATH : BARRIER2_PATH;
        zooKeeper.create(barrierPath + "/player_", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL);

        if (barrierNumber == 1) {
            barrierLatch1.countDown();
            barrierLatch1.await();
        } else {
            barrierLatch2.countDown();
            barrierLatch2.await();
        }
    }

    public void close() throws InterruptedException {
        zooKeeper.close();
    }

    @Override
    public void process(WatchedEvent event) {
        if (event.getType() == Event.EventType.None && event.getState() == Event.KeeperState.SyncConnected) {
            connectLatch.countDown();
        }
    }

    public static void main(String[] args) throws InterruptedException, KeeperException, IOException {
        Scanner scanner = new Scanner(System.in);
        System.out.println("Quantos jogadores vão participar do jogo?");
        int numJogadores = scanner.nextInt();
        scanner.nextLine(); // Consume newline

        Hub jogo = new Hub(numJogadores);
        jogo.initialize();
        jogo.startGame();
        jogo.close();
        scanner.close();
    }
}