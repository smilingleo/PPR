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
  
}

object Models {
  case class Invoice(id: String, balance: Double)
  case class Payment(id: String, amount: Double)
}