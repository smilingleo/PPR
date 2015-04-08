# Fully Recoverable Cluster
This is version #4, in this version, the cluster is fully recoverable.

`PaymentRunManager` now extends `PersistentActor`, it maintains a list of processed payments, when its hosting node was restarted, it can recover from previous failure and continue the work.
