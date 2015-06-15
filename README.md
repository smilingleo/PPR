# Fully Recoverable Cluster
This is version #4, in this version, the cluster is fully recoverable.

`PaymentRunManager` now extends `PersistentActor`, it maintains a list of processed payments, when its hosting node was restarted, it can recover from previous failure and continue the work.

To run the app, in the shell:

```bash
sbt "runMain com.zuora.payment.run.WorkerNode 2551"
```

Open another shell:

```bash
sbt "runMain com.zuora.api.Server 2554"

To create a payment run:

```bash
curl -X POST http://localhost:8080/payment-runs/{run-key}
```

To check progress:

```bash
curl -X GET http://localhost:8080/progress/{run-key}
```