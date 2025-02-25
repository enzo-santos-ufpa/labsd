package br.ufpa.icen.lib;

import org.apache.zookeeper.*;
import org.apache.zookeeper.data.Stat;

import java.io.IOException;


public class ZooKeeperMultiLevelBarrier extends ZooKeeperBarrier {

    private final int maxParticipantes;
    private final String innerBarrierNode;

    /**
     * Inicializa a barreira reutilizável e restrita do ZooKeeper com suporte a barreiras duplas aninhadas.
     *
     * @param connectString   String de conexão com o ZooKeeper.
     * @param barrierNode     Caminho do nó da barreira externa.
     * @param innerBarrier    Caminho do nó da barreira interna (opcional).
     * @param maxParticipants Número máximo de participantes na barreira.
     * @throws IOException          se a conexão falhar.
     * @throws InterruptedException se a inicialização for interrompida.
     * @throws KeeperException      se houver erro na comunicação com o ZooKeeper.
     */
    public ZooKeeperMultiLevelBarrier(String connectString, String barrierNode, String innerBarrier, int maxParticipantes)
            throws IOException, InterruptedException, KeeperException {
        super(connectString, barrierNode);
        this.innerBarrierNode = innerBarrier;
        this.maxParticipantes = maxParticipantes;
        initializeBarrier(barrierNode);
        if (innerBarrierNode != null) {
            initializeBarrier(innerBarrierNode);
        }
    }

   
    private void initializeBarrier(String node) throws KeeperException, InterruptedException {
        Stat stat = zk.exists(node, false);
        if (stat == null) {
            zk.create(node, "0".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }
    }

    @Override
    public void waitForBarrier() throws KeeperException, InterruptedException {
        passBarrier(barrierNode);

       
        if (innerBarrierNode != null) {
            passBarrier(innerBarrierNode);
        }
    }

   
    private void passBarrier(String node) throws KeeperException, InterruptedException {
        while (true) {
            Stat stat = zk.exists(node, true);
            if (stat == null) {
                return; 
            }

            int count = getParticipantCount(node);
            if (count < maxParticipantes) {
                incrementParticipantCount(node);
                if (count + 1 == maxParticipantes) {
                    passBarrier(node);
                    resetBarrier(node);
                }
                return;
            }
        }
    }

    private int getParticipantCount(String node) throws KeeperException, InterruptedException {
        byte[] data = zk.getData(node, false, null);
        return Integer.parseInt(new String(data));
    }

   
    private void incrementParticipantCount(String node) throws KeeperException, InterruptedException {
        int count = getParticipantCount(node);
        zk.setData(node, String.valueOf(count + 1).getBytes(), -1);
    }

   
    private void resetBarrier(String node) throws KeeperException, InterruptedException {
        zk.create(node, "0".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
    }
}
