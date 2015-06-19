package com.zuora.message

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
  
  
  
  // Message for Server
  case class NewJob(pmKey: String, invoices: Int = 20)
  
  sealed trait RequestMessage
  // Message for monitor
  case class CheckProgress(pmKey: String) extends RequestMessage // send to Server.
  case object CheckStatus extends RequestMessage
  
  case class CreatePaymentRun(job: NewJob) extends RequestMessage
  case object CheckProgress  // send to PaymentRunManager
  
  case class Running(pmKey: String, actor: ActorRef)
  case class RunCompletion(pmKey: String)
  
  sealed trait ResultMessage
  case class Created(location: String) extends ResultMessage
  case class Success[T <: AnyRef](payload: T) extends ResultMessage
  case class Error(message: String) extends ResultMessage

  
  // cluster
  case class Node(name: String, role: String, children: Seq[Node], status: ActorStatus)

  sealed trait ActorStatus { 
    def timestamp: Long = System.currentTimeMillis()
  }
  case class NodeStatus(running: Boolean) extends ActorStatus
  case class WorkerStatus(actor: ActorRef, inv: Option[Invoice]) extends ActorStatus
  case class RunProgress(pmKey: String, total: Int, done: Int, failed: Int) extends ActorStatus
  
}


object Models {
  case class Invoice(id: String, balance: Double)
  case class Payment(id: String, amount: Double)
}