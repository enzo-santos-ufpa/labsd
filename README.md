# Laboratório de Sistemas Distribuídos

<!-- TOC start (generated with https://github.com/derlin/bitdowntoc) -->

- [Laboratório de Sistemas Distribuídos](#laboratório-de-sistemas-distribuídos)
   * [Configuração](#configuração)
   * [Implementação](#implementação)
      + [Barreira simples](#barreira-simples)
      + [Barreira dupla](#barreira-dupla)
         - [Primeira barreira](#primeira-barreira)
         - [Segunda barreira](#segunda-barreira)
   * [Testes](#testes)
      + [Arquitetura](#arquitetura)
         - [Teste de código bloqueante](#teste-de-código-bloqueante)
         - [Teste da estrutura do ZooKeeper](#teste-da-estrutura-do-zookeeper)
      + [Barreira simples](#barreira-simples-1)
      + [Barreira dupla](#barreira-dupla-1)
   * [Uso](#uso)
      + [Servidor](#servidor)
      + [Cliente](#cliente)

<!-- TOC end -->

<!-- TOC --><a name="laboratório-de-sistemas-distribuídos"></a>
# Laboratório de Sistemas Distribuídos

<!-- TOC --><a name="configuração"></a>
## Configuração

Os requisitos do projeto são:

- ZooKeeper (versão 3.9.3), acessível via [Apache](https://dlcdn.apache.org/zookeeper/zookeeper-3.9.3/apache-zookeeper-3.9.3-bin.tar.gz)
- Maven (versão 3.9.9), acessível via [Apache](https://dlcdn.apache.org/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz)
- Java (versão 11), acessível via [Oracle](https://www.java.com/pt-BR/download/manual.jsp)

Passos:

1. Descompacte o arquivo _apache-zookeeper-3.9.3-bin.tar.gz_ e adicione o diretório extraído
   _apache-zookeeper-3.9.3-bin/bin_ na variável de
   ambiente PATH. Desta forma, o comando `zkServer` ficará acessível para executar a aplicação servidor.

2. Descompacte o arquivo _apache-maven-3.9.9-bin.tar.gz_ e adicione o diretório extraído _apache-maven-3.9.9/bin_ na
   variável de ambiente PATH. Desta
   forma, o comando `mvn` ficará acessível para executar a aplicação cliente.

3. Verifique se a configuração está válida executando os seguintes comandos:

    ```shell
    $ echo %JAVA_HOME%
    C:\Users\...\Java\jdk-11.0.22
    
    $ %JAVA_HOME%\bin\java -version
    java version "11.0.22" 2024-01-16 LTS
    Java(TM) SE Runtime Environment 18.9 (build 11.0.22+9-LTS-219)
    Java HotSpot(TM) 64-Bit Server VM 18.9 (build 11.0.22+9-LTS-219, mixed mode)
    
    $ mvn --version
    Apache Maven 3.9.9 (8e8579a9e76f7d015ee5ec7bfcdc97d260186937)
    Maven home: C:\Users\...\apache-maven-3.9.9-bin\apache-maven-3.9.9
    Java version: 11.0.22, vendor: Oracle Corporation, runtime: C:\Users\...\Java\jdk-11.0.22
    Default locale: pt_BR, platform encoding: Cp1252
    OS name: "windows 11", version: "10.0", arch: "amd64", family: "windows"
    ```

<!-- TOC --><a name="implementação"></a>
## Implementação

<!-- TOC --><a name="barreira-simples"></a>
### Barreira simples

A classe `br.ufpa.icen.lib.ZooKeeperBarrier` possui um método disponível: `waitForBarrier`, utilizado pelo cliente
para entrar na barreira e possivelmente aguardar até ela ser removida.

Existem apenas duas situações para esse tipo de barreira:

1. O cliente tenta acessar a barreira e ela **não existe**: ele prossegue com sucesso.
2. O cliente tenta acessar a barreira e ela **existe**: ele aguarda até que a barreira seja removida.

A barreira aqui é implementada simplesmente com um nó no ZooKeeper. Caso o cliente realize a leitura desse
nó e ele existe, ele aguarda até que o ZooKeeper envia um evento de remoção para esse nó, prosseguindo com sua execução.

```java
public void waitForBarrier() throws KeeperException, InterruptedException {
    while (true) {
        Stat stat = zk.exists(barrierNode, true);
        if (stat == null) {
            return; // A barreira foi removida, pode prosseguir
        }
        latch.await(); // Aguarda até que o nó seja excluído
    }
}
```

onde o `latch` é definido com um contador 1 e atualizado no construtor da classe:

```java
this.zk = createZooKeeperConnection(connectString, event -> {
    if (event.getType() == Watcher.Event.EventType.NodeDeleted && event.getPath().equals(this.barrierNode)) {
        latch.countDown();
    }
});
```

O `latch` aqui é um objeto do tipo [`java.util.concurrent.CountDownLatch`](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/CountDownLatch.html), que aje como um semáforo. Ao chamar seu método `countDown`, seu contador interno é decrementado para 0 e toda chamada que depende do seu método `await` é liberada, deixando a execução do programa prosseguir.

<!-- TOC --><a name="barreira-dupla"></a>
### Barreira dupla

A classe `br.ufpa.icen.lib.ZooKeeperDoubleBarrier` possui dois métodos disponíveis:
`enterBarrier`, utilizado pelo cliente para entrar na barreira e possivelmente aguardar outros clientes iniciarem o
processamento, e `exitBarrier`, utilizado pelo cliente para sair da barreira e possivelmente aguardar outros clientes 
terminarem o processamento.

Note que, apesar do nome, existe apenas um nó que realiza essa implementação no ZooKeeper. A parte "dupla" vem do fato que existem duas sincronizações entre todos os clientes que estão neste nó raiz: uma ao entrar na barreira e outra ao sair. Isso é perceptível ao notar que na implementação existem dois _latches_:

```java
this.zk = createZooKeeperConnection(connectString, event -> {
    if (event.getType() == Watcher.Event.EventType.NodeCreated) {
        if (event.getPath().equals(barrierNode + "/ready")) {
            enterLatch.countDown();
        }
    } else if (event.getType() == Watcher.Event.EventType.NodeDeleted) {
        if (exitLatch != null) {
            exitLatch.countDown();
        }
    }
});
```

Supondo que cada cliente possui uma tarefa a ser feita (como extrair informações de uma planilha), existem quatro etapas de vida para cada um:

1. **execução**: o cliente aguarda os demais clientes entrarem na primeira barreira; ocorre quando o processo do cliente iniciou com sucesso e já está na primeira barreira
2. **processamento**: o cliente realiza sua tarefa; ocorre quando todos os clientes já estão na primeira barreira
3. **finalização**: o cliente aguarda os demais clientes entrarem na segunda barreira; ocorre quando o cliente termina de realizar sua tarefa
4. **encerramento**: o cliente é encerrado; ocorre quando todos os clientes já estão na segunda barreira

As etapas de sincronização são **execução** e **finalização**: todos os clientes entram em **processamento** e em **encerramento** ao mesmo tempo.

Supondo que três clientes precisem realizar uma operação em conjunto com base em um nó `/barreira` que implementa barreiras duplas, o seguinte ocorre:

<!-- TOC --><a name="primeira-barreira"></a>
#### Primeira barreira

- O cliente C1 entra em **execução**, cria um nó `/barreira/C1`, verifica quantos nós já existem na barreira (apenas 1) e aguarda o nó `/ready` ser criado
- O cliente C2 entra em **execução**, cria um nó `/barreira/C2`, verifica quantos nós já existem na barreira (2) e aguarda o nó `/ready` ser criado
- O cliente C3 entra em **execução**, cria um nó `/barreira/C3`, verifica quantos nós já existem na barreira (3), e, por validar que já atingiu a quantidade de nós esperada, cria o nó `/ready`
- O ZooKeeper notifica todos os clientes da criação do nó `/ready` e os clientes entram em **processamento** assim que recebem esta notificação

![image](https://github.com/user-attachments/assets/ffa34670-146d-413d-a747-8f98c5e21e8c)

```java
public void enterBarrier() throws KeeperException, InterruptedException {
    // 1. Create a name n = b+"/"+p
    final String n = barrierNode + "/" + id;

    // 2. Set watch: exists(b + "/ready", true)
    zk.exists(barrierNode + "/ready", true);

    // 3. Create child: create( n, EPHEMERAL)
    zk.create(n,
        // Guarda a data de criação deste nó para consulta em `exitBarrier`
        LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).getBytes(StandardCharsets.UTF_8),
        ZooDefs.Ids.OPEN_ACL_UNSAFE,
        CreateMode.EPHEMERAL
    );

    // 4. L = getChildren(b, false)
    final List<String> children = zk.getChildren(barrierNode, false);
    if (children.size() < 3) {
        // 5. if fewer children in L than x, wait for watch event
        enterLatch.await();
    } else {
        // 6. else create(b + "/ready", REGULAR)
        zk.create(barrierNode + "/ready", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
    }
}
```

<!-- TOC --><a name="segunda-barreira"></a>
#### Segunda barreira

Supondo que os clientes terminem suas execuções em tempos diferentes,

- O cliente C2 finaliza por primeiro sua tarefa, entra em **finalização**, consulta a lista de nós de `/barreira`
  (`/C1`, `/C2` e `/C3`), consulta o nó mais antigo (o que entrou primeiro, `/C1`), verifica se este nó corresponde
  ao seu próprio nó, e, por validar que são nós diferentes, ele remove o seu próprio nó de `/barreira` e aguarda a
  remoção do nó mais antigo
- O cliente C3 finaliza por segundo sua tarefa, entra em **finalização**, consulta a lista de nós de `/barreira`
  (`/C1` e `/C3`), consulta o nó mais antigo (`/C1`), verifica se este nó corresponde ao seu próprio nó, e, por
  validar que são nós diferentes (conforme passo 5 do pseudocódigo), ele remove o seu próprio nó de `/barreira`
  e aguarda a remoção do nó mais antigo 
- O cliente C1 finaliza por terceiro sua tarefa, entra em **finalização**, consulta a lista de nós de `/barreira`
  (somente `/C1`), e, por validar que está sozinho (conforme passo 3 do pseudocódigo), ele remove o seu próprio
  nó sem validações adicionais e entra em **encerramento**
- O ZooKeeper notifica C2 e C3 da remoção do nó `/C1` e estes entram em **encerramento** assim que recebem
  esta notificação

![image](https://github.com/user-attachments/assets/1376d5d3-41f0-439f-8695-527b92ee9d6e)


Em outro exemplo, onde o cliente mais antigo finaliza por segundo:

- O cliente C2 finaliza por primeiro sua tarefa, entra em **finalização**, consulta a lista de nós de `/barreira`
  (`/C1`, `/C2` e `/C3`), consulta o nó mais antigo (o que entrou primeiro, `/C1`), verifica se este nó corresponde
  ao seu próprio nó, e, por validar que são nós diferentes (conforme passo 5 do pseudocódigo), ele remove o seu
  próprio nó de `/barreira` e aguarda a remoção do nó mais antigo
- O cliente C1 finaliza por segundo sua tarefa, entra em **finalização**, consulta a lista de nós de `/barreira`
  (`/C1` e `/C3`), consulta o nó mais antigo (`/C1`), verifica se este nó corresponde ao seu próprio nó, e, por
  validar que são nós iguais (conforme passo 4 do pseudocódigo), ele mantém seu nó e aguarda a remoção do nó mais
  antigo (o que entrou por último, `/C3`)
- O cliente C3 finaliza por terceiro sua tarefa, entra em **finalização**, consulta a lista de nós de `/barreira`
  (`/C1` e `/C3`), consulta o nó mais antigo (`/C1`), verifica se este nó corresponde ao seu próprio nó, e, por
  validar que são nós diferentes (conforme passo 5 do pseudocódigo), ele remove o seu próprio nó de `/barreira`
  e aguarda a remoção do nó mais antigo
- O ZooKeeper notifica C1 da remoção do nó `/C3`, que consulta novamente a lista de nós de `/barreira` (`/C1`),
  e, por validar que está sozinho (conforme passo 3 do pseudocódigo), ele remove o seu próprio nó sem validações
  adicionais e entra em **encerramento**
- O ZooKeeper notifica C2 e C3 da remoção do nó `/C1` e estes entram em **encerramento** assim que recebem esta
  notificação

![image](https://github.com/user-attachments/assets/e02dd13a-bb03-4ca2-9434-b7365e260270)

```java
public void exitBarrier() throws KeeperException, InterruptedException {
    for (; ; ) {
        // 1. L = getChildren(b, false)
        final List<Map.Entry<String, LocalDateTime>> children = zk.getChildren(barrierNode, false)
            .stream().collect(Collectors.toMap(id -> id, id -> {
                final byte[] creationDateData;
                try {
                    creationDateData = zk.getData(barrierNode + "/" + id, false, null);
                } catch (KeeperException.NoNodeException e) {
                    // Se nó não existe, adiciona valor padrão que removeremos posteriormente
                    return LocalDateTime.MIN;
                } catch (KeeperException | InterruptedException e) {
                    throw new RuntimeException(e);
                }
                return LocalDateTime.parse(new String(creationDateData, StandardCharsets.UTF_8), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            }))
            .entrySet().stream()
            .filter(entry -> entry.getValue() != LocalDateTime.MIN)
            .sorted(Map.Entry.comparingByValue())
            .collect(Collectors.toList());

        // 2. if no children, exit
        if (children.isEmpty()) {
            return;
        }
        // 3. if p is only process node in L, delete(n) and exit
        if (children.size() == 1 && children.get(0).getKey().equals(id)) {
            zk.delete(barrierNode + "/" + id, -1);
            return;
        }
        exitLatch = new CountDownLatch(1);
        if (children.get(0).getKey().equals(id)) {
            // 4. if p is the lowest process node in L, wait on highest process node in L
            zk.exists(barrierNode + "/" + children.get(children.size() - 1), true);
        } else {
            // 5. else delete(n) if still exists and wait on lowest process node in L
            try {
                zk.delete(barrierNode + "/" + id, -1);
            } catch (KeeperException.NoNodeException ignored) {
            }
            zk.exists(barrierNode + "/" + children.get(0), true);
        }
        exitLatch.countDown();
    }
}
```

<!-- TOC --><a name="testes"></a>
## Testes

Para construir os testes automatizados, foram utilizadas as bibliotecas

- JUnit (versão 5.9.2), acessível via [Maven](https://mvnrepository.com/artifact/org.junit.jupiter/junit-jupiter-engine)
- Curator Testing (versão 5.4.0), acessível
  via [Maven](https://mvnrepository.com/artifact/org.apache.curator/curator-test)

O Curator Testing foi utilizado para simular um servidor ZooKeeper em memória, onde ao invés de definir

```java
final ZooKeeper zk = new ZooKeeper("localhost:3181", 3000, null);
```

se cria uma instância de `TestingServer` e se acessa seu método `getConnectionString`:

```java
final TestingServer server = new TestingServer();
final ZooKeeper zk = new ZooKeeper(server.getConnectionString(), 3000, null);
```

sem precisar ter o _overhead_ de executar o `zkServer` toda vez que um teste for iniciado.

Os testes estão disponíveis no diretório _src/test_.

<!-- TOC --><a name="arquitetura"></a>
### Arquitetura

<!-- TOC --><a name="teste-de-código-bloqueante"></a>
#### Teste de código bloqueante

No código principal, existem algumas execuções que são bloqueantes (isto é, que bloqueiam a _thread_
principal do cliente até que a execução seja finalizada). Um exemplo delas é o método `enterBarrier`
da classe `ZooKeeperDoubleBarrier`, que pode bloquear o código do cliente enquanto os outros clientes
não entrarem na barreira desejada. Como para testes esse bloqueio é indesejado, essas execuções precisam
ser realizadas de forma assíncrona, e neste caso, foi escolhida a implementação assíncrona por meio de
objetos do tipo [`java.util.concurrent.Future<?>`](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/Future.html):

```java
final Future<Void> future = CompletableFuture.runAsync(() -> {
    try {
        barrier.enterBarrier();
    } catch (KeeperException | InterruptedException e) {
        throw new RuntimeException(e);
    }
});
```

Para testar se um `Future` está bloqueado, se utiliza a asserção

```java
Assertions.assertThrows(TimeoutException.class, () -> future.get(1L, TimeUnit.SECONDS));
```

Em outras palavras, se tenta obter o resultado do _future_ em um determinado período de tempo (neste caso, 1 segundo)
por meio do seu método `get`. Caso o método lance a exceção `TimeoutException` neste intervalo, significa que a _thread_
deste _future_ está bloqueada. Apesar dessa estratégia de esperar 1 segundo não ser a ideal para testes em geral (visto
que a execução do _future_ pode durar mais que 1 segundo e o teste acabar sendo um falso positivo), para esse tipo de 
testes é o suficiente.

Para testar se o cliente está com a sua execução normal, se verifica se o `Future` não lança nenhuma 
exceção após o mesmo limite de tempo:

```java
Assertions.assertDoesNotThrow(() -> future.get(1L, TimeUnit.SECONDS));
```

<!-- TOC --><a name="teste-da-estrutura-do-zookeeper"></a>
#### Teste da estrutura do ZooKeeper

Tanto o `ZooKeeperBarrier` quanto `ZooKeeperDoubleBarrier` possuem um atributo privado `zk`, que armazena uma 
instância do ZooKeeper. Para evitar criar uma nova instância do ZooKeeper para os testes e reutilizar esse 
atributo, foi criado um método protegido chamado `createZooKeeperConnection`:

```java
protected ZooKeeper createZooKeeperConnection(String connectString, Watcher watcher) throws IOException {
    return new ZooKeeper(connectString, 3000, watcher);
}
```

Desta forma, se refatorou o código do cliente para criar a instância do ZooKeeper com base neste método:

```java
this.zk = createZooKeeperConnection(connectString, event -> { /* ... */ });
```

E para acessar esta instância nos testes, se sobrescreve este método para capturar a criação desta instância e
armazená-la na classe de execução dos testes:

```java
public class ZooKeeperBarrierTest {
    private ZooKeeper zk;

    @BeforeEach
    public void setUp() throws Exception {
        testingServer = new TestingServer();
        barrier = new ZooKeeperBarrier(testingServer.getConnectString(), BARRIER_NODE_PATH) {
            @Override
            protected ZooKeeper createZooKeeperConnection(String connectString, Watcher watcher) throws IOException {
                zk = super.createZooKeeperConnection(connectString, watcher);
                return zk;
            }
        };
    }
}
```

Para simular as ações de outro cliente, como criação ou remoção de nós, se utiliza os métodos direto na variável `zk` capturada:

```java
zk.create(BARRIER_NODE_PATH, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
zk.delete(BARRIER_NODE_PATH, -1);
``` 

Para testar se os nós foram criados no ZooKeeper após a chamada de algum método da barreira, se utiliza as asserções no estilo

```java
Assertions.assertNotNull(zk.exists(BARRIER_NODE_PATH + "/ready", false));
Assertions.assertNotNull(zk.exists(BARRIER_NODE_PATH + "/" + barrier.getId(), false));
```

<!-- TOC --><a name="barreira-simples-1"></a>
### Barreira simples

Para a classe `ZooKeeperBarrier`, foi testado seu método principal, `waitForBarrier`.

Os testes são:

1. `testWaitForBarrier_QuandoNoExiste_DeveAguardarPorRemocao`

Testa a entrada na barreira com um nó que já existe.

- _Pré-condição:_ nó `/barreira` criado
- _Ação:_ **chamada do método `waitForBarrier`**, simulando o cliente entrar na barreira
- _Verificação:_ o cliente deve estar bloqueado
- _Ação:_ nó `/barreira` é removido
- _Verificação:_ cliente não deve mais estar bloqueado


1. `testWaitForBarrier_QuandoNoNaoExiste_DeveProsseguir`

Testa a entrada na barreira com um nó que não existe.

- _Pré-condição:_ nó `/barreira` não criado
- _Ação:_ **chamada do método `waitForBarrier`**, simulando o cliente entrar na barreira
- _Verificação:_ o cliente deve não estar bloqueado

<!-- TOC --><a name="barreira-dupla-1"></a>
### Barreira dupla

Para a classe `ZooKeeperDoubleBarrier`, foram testados seus dois métodos principais: `enterBarrier` e `exitBarrier`.

Os testes são:

1. `testEnterBarrier_QuandoPrimeiroNoNaBarreira_DeveCriarNoProprioEAguardarPorNoReady`

Testa a entrada de um nó na barreira que aguarda pelos demais até atingir o limite de 3 nós.

- _Pré-condição:_ nó `/ready` não criado; nó do cliente não criado
- _Ação:_ **chamada do método `enterBarrier`**, simulando o cliente entrar na barreira
- _Verificação:_ o cliente deve estar bloqueado; o nó do cliente deve ter sido foi criado
- _Ação:_ outro cliente cria um nó na barreira
- _Verificação:_ cliente ainda deve estar bloqueado
- _Ação:_ cria o nó `/ready`
- _Verificação:_ cliente não está mais bloqueado

2. `testEnterBarrier_QuandoUltimoNoNaBarreira_DeveCriarNoProprioENoReady`

Testa a entrada de um nó na barreira já com 2 nós esperando, atingindo o limite de 3 nós com sua entrada.

- _Pré-condição:_ nó `/ready` não criado; nó do cliente não criado; nó de dois outros clientes já criados
- _Ação:_ **chamada do método `enterBarrier`**, simulando o cliente entrar na barreira
- _Verificação:_ cliente não deve estar bloqueado; nó do cliente deve ter sido criado; nó `/ready` deve ter sido criado

3. `testExitBarrier_QuandoBarreiraEstiverVazia_DeveProsseguir`

Testa a saída de um nó da barreira sem nós restantes.

- _Pré-condição:_ sem nós criados
- _Ação:_ **chamada do método `exitBarrier`**, simulando o cliente sair na barreira
- _Verificação:_ cliente não deve estar bloqueado; nenhum nó deve ter sido criado

4. `testExitBarrier_QuandoUnicoNoNaBarreira_DeveRemoverNoEProsseguir`

Testa a saída de um nó da barreira com seu único nó restante.

- _Pré-condição:_ nó do cliente já criado
- _Ação:_ **chamada do método `exitBarrier`**, simulando o cliente sair na barreira
- _Verificação:_ cliente não deve estar bloqueado; nó do cliente deve ter sido removido

5. `testExitBarrier_QuandoNoMaisAntigoNaBarreira_DeveAguardarNoMaisRecenteEProsseguir`

Testa a saída de um nó da barreira, com um outro nó de um cliente mais antigo.

- _Pré-condição:_ nó do cliente já criado; nó de outro cliente já criado, mais recente
- _Ação:_ **chamada do método `exitBarrier`**, simulando o cliente sair na barreira
- _Verificação:_ cliente deve estar bloqueado; nó do cliente não deve ter sido removido; nó do outro cliente não deve ter sido removido
- _Ação:_ outro cliente remove o seu nó respectivo da barreira
- _Verificação:_ cliente não deve estar bloqueado; nó do cliente deve ter sido removido

6. `testExitBarrier_QuandoNoMaisRecenteNaBarreira_DeveAguardarNoMaisAntigoEProsseguir`

Testa a saída de um nó da barreira, com um outro nó de um cliente mais recente.

- _Pré-condição:_ nó do cliente já criado; nó de outro cliente já criado, mais recente
- _Ação:_ **chamada do método `exitBarrier`**, simulando o cliente sair na barreira
- _Verificação:_ cliente deve estar bloqueado; nó do cliente deve ter sido removido; nó do outro cliente não deve ter sido removido
- _Ação:_ outro cliente remove o seu nó respectivo da barreira
- _Verificação:_ cliente não deve estar bloqueado

<!-- TOC --><a name="uso"></a>
## Uso (Atividade 1)

A aplicação implementa um _lobby_ de jogadores simples, onde cada cliente acompanha o _status_ do _hub_: quando um
jogador
entra,
quando um jogador sai, e quantos jogadores faltam para começar a partida.

Os comandos dessa seção assumem que os seguintes comandos foram executados:

```shell
$ git clone https://github.com/enzo-santos-ufpa/labsd
$ cd labsd
```

<!-- TOC --><a name="servidor"></a>
### Servidor

Na primeira execução do servidor, adicione um arquivo chamado _zoo.cfg_ (cujo conteúdo está presente na raiz deste
repositório) no diretório _apache-zookeeper-3.9.3-bin/conf_, extraído no passo 1 da seção Configuração.

Rode o seguinte comando em um _prompt_ de comando separado:

```shell
$ zkServer
```

Este comando irá iniciar o servidor, conforme imagem abaixo.

![img.png](assets/img_servidor_1.png)

Não feche este _prompt_ de comando enquanto a aplicação for executada.

<!-- TOC --><a name="cliente"></a>
### Cliente

Um _prompt_ de comando deve ser executado para cada cliente.

Rode o seguinte comando em um novo _prompt_ de comando (**PC1**):

```shell
$ mvn compile
$ mvn exec:java
```

Este comando irá iniciar uma aplicação cliente, conforme imagem abaixo.

![img_cliente1_1.png](assets/img_cliente1_1.png)

Insira o nome de um jogador a entrar no _hub_ e aperte Enter:

![img_cliente1_2.png](assets/img_cliente1_2.png)

Rode o mesmo comando em um novo _prompt_ de comando (**PC2**) e insira o nome de outro jogador:

![img_cliente2_1.png](assets/img_cliente2_1.png)

Note que o nome do primeiro jogador adicionado anteriormente já está no _hub_.

Volte no **PC1** e note que o nome do jogador adicionado posteriormente foi identificado em tempo real:

![img_cliente1_3.png](assets/img_cliente1_3.png)

Feche o **PC2** e note que o nome do último jogador adicionado terá sido identificado como ausente no **PC1**:

![img_cliente1_4.png](assets/img_cliente1_4.png)

Abra novamente o **PC2** e adicione novamente o seu jogador respectivo.

Rode o mesmo comando em um novo _prompt_ de comando (**PC3**) e insira o nome do último jogador:

![img_cliente3_1.png](assets/img_cliente3_1.png)

Note que o jogo foi iniciado com sucesso, visto que já existem 3 jogadores.
