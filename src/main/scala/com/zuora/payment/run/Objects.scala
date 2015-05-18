package com.zuora.payment.run

import akka.actor.ActorRef

/**
 * @author leo
 */
object Messages {
  import Models._
 
  case object Start
  
  // Message from workers
  case class WorkerCreated(worker: ActorRef)
  case class WorkerRequestsWork(worker: ActorRef)
  case class WorkIsDone(payment: Payment, worker: ActorRef)
  
  // Message to workers
  case object WorkIsReady
  case object NoWorkToBeDone
  case class Job(invoice: Invoice)
  
  
  // Message for monitor
  case object CheckProgress
  case class RunProgress(total: Int, done: Int, failed: Int)
}

object Models {
  case class Invoice(id: String, balance: Double)
  case class Payment(id: String, amount: Double)
}