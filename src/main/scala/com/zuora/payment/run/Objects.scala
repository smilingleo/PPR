package com.zuora.payment.run

import akka.actor.ActorRef

/**
 * @author leo
 */
object Messages {
  import Models._
 
  case object Start
  case class CreateWorker(n: Int, pmKey: String)
  
  // Message from workers
  case class WorkerCreated(worker: ActorRef)
  case class WorkerRequestsWork(worker: ActorRef)
  case class WorkIsDone(payment: Payment, worker: ActorRef)
  
  // Message to workers
  case object WorkIsReady
  case object NoWorkToBeDone
  case class Job(invoice: Invoice)
  
  
  // Message for monitor
  case class CheckProgress(pmKey: String)  // send to Server.
  case object CheckProgress  // send to PaymentRunManager
  case class RunProgress(pmKey: String, total: Int, done: Int, failed: Int)
  
  // Message for Server
  case class NewJob(pmKey: String)
}

object Models {
  case class Invoice(id: String, balance: Double)
  case class Payment(id: String, amount: Double)
}