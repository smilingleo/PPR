package com.zuora.payment.run

import akka.actor.ActorLogging
import akka.actor.Actor
import Messages._
import Models._


class Worker extends Actor with ActorLogging {
  override def preStart = {
    val prManager = context.actorSelection("/user/paymentRunManager")
    prManager ! WorkerCreated(self)
  }
  
  def receive: Actor.Receive = {
    case WorkIsReady =>
      log.info("Requesting work")
      sender ! WorkerRequestsWork(self)
      
    case Job(invoice) =>
      log.info("Got invoice: {}", invoice)
      val payment = processInvoice(invoice)
      sender ! WorkIsDone(payment, self)
      
    case NoWorkToBeDone =>
      // do nothing
  }
  
  private def processInvoice(inv: Invoice): Payment = {
    // block current thread for 1 second to simulate the processing
	  Thread.sleep(500)
	  Payment(inv.id, inv.balance)
  }
}