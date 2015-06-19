# Fully Recoverable Cluster
This is version #4, in this version, the cluster is fully recoverable.

`PaymentRunManager` now extends `PersistentActor`, it maintains a list of processed payments, when its hosting node was restarted, it can recover from previous failure and continue the work.


## Configuration
This version is using [akka-persistence-jdbc](https://github.com/dnvriend/akka-persistence-jdbc) for journal persistence, if you don't have a running database, you can use file system, by changing the `application.conf`

```
  persistence {
      journal.leveldb.dir = "target/example/journal"
      snapshot-store.local.dir = "target/example/snapshots"
  }
``` 

But if you use local leveldb and use multi-jvm on same server, you will see some errors, some payment runs might not be able to be processed, the root cause is the file exclusive lock preventing the second PaymentRunManager actor to persist its journal.

## Usage

To run the app, in the shell:

```bash
sbt "runMain com.zuora.cluster.WorkerNode 2551"
```

Open another shell:

```bash
sbt "runMain com.zuora.api.Server 2554"
```

To create a payment run:

```bash
curl -X POST -H "Content-Type:application/json" http://localhost:8080/payment-runs -d '{"pmKey": "pm001", "invoices": 100}'
```

To check cluster status:

```bash
curl -X GET http://localhost:8080/cluster-status
```

To see the visualized cluster status, open your browser and access `http://localhost:8080/`, you will see the cluster status changes accordingly by:

+ kicking off a payment run job
+ shutting down a node (any node, but you should not shut down all the seed nodes)

You can also see the run progress.

## Technologies Used

+ Akka (Cluster, Persistent Actor)
+ Spray for RESTful API
+ D3JS for visualization