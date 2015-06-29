# Parallel Processing on Akka Cluster
![Akka Cluster](https://github.com/smilingleo/PPR/blob/master/src/main/resources/d3/akka-cluster.png)

This is a sample app which demostrates how to process a business run on an Akka cluster.

The sample use case here is to process a payment run, basically to process each invoice with an actor. There are 3 types of actors:

+ PaymentRunManager
  + the *orange* node with a blue progress edge ring.
  + a PersistentActor, it maintains a list of processed payments, when its hosting node was restarted, it can recover from previous failure and continue the work.
+ Worker
  + the *blue* spot
  + a slave actor responsible to process invoices, it's using a pull pattern to prevent message overflow.
+ NodeManager
  + the *green* node on the big ring.
  + singleton actor for each node, responsible to create actor on each node, so it's parent of all above actors.
  + can also collect the status of each actor.


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
