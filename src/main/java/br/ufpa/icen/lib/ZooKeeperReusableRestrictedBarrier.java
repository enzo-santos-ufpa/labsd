package br.ufpa.icen.lib;

import org.apache.zookeeper.*;
import org.apache.zookeeper.data.Stat;

import java.io.IOException;

/**
 * Uma barreira distribuída reutilizável e restrita usando o Apache ZooKeeper.
 */
public class ZooKeeperReusableRestrictedBarrier extends ZooKeeperBarrier {
    
	private final int maxParticipants;

    /**
     * Inicializa a barreira reutilizável e restrita do ZooKeeper.
     *
     * @param connectString   String de conexão com o ZooKeeper.
     * @param barrierNode     Caminho do nó da barreira.
     * @param maxParticipants Número máximo de participantes na barreira.
     * @throws IOException se a conexão falhar.
     */
    public ZooKeeperReusableRestrictedBarrier(String connectString, String barrierNode, int maxParticipants)
            throws IOException, InterruptedException, KeeperException {
        super(connectString, barrierNode);
        this.maxParticipants = maxParticipants;
        initializeBarrier();
    }

    /**
     * Inicializa a barreira criando o nó e o contador de participantes, se necessário.
     */
    private void initializeBarrier() throws KeeperException, InterruptedException {
        Stat stat = zk.exists(barrierNode, false);
        if (stat == null) {
            zk.create(barrierNode, "0".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }
    }

    @Override
    public void waitForBarrier() throws KeeperException, InterruptedException {
        while (true) {
            Stat stat = zk.exists(barrierNode, true);
            if (stat == null) {
                return; // A barreira foi removida, pode prosseguir
            }

            int count = getParticipantCount();
            if (count < maxParticipants) {
                incrementParticipantCount();
                if (count + 1 == maxParticipants) {
                    removeBarrier();
                    resetBarrier();
                }
                return;
            }
        }
    }

    /**
     * Obtém o número atual de participantes na barreira.
     */
    private int getParticipantCount() throws KeeperException, InterruptedException {
        byte[] data = zk.getData(barrierNode, false, null);
        return Integer.parseInt(new String(data));
    }

    /**
     * Incrementa o número de participantes na barreira.
     */
    private void incrementParticipantCount() throws KeeperException, InterruptedException {
        int count = getParticipantCount();
        zk.setData(barrierNode, String.valueOf(count + 1).getBytes(), -1);
    }

    /**
     * Reseta a barreira para ser reutilizada.
     */
    private void resetBarrier() throws KeeperException, InterruptedException {
        zk.create(barrierNode, "0".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
    }
}

