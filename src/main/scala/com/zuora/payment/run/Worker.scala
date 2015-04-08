package com.zuora.payment.run

import akka.actor.ActorLogging
import akka.actor.Actor
import Messages._
import Models._
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.MemberEvent
import akka.cluster.ClusterEvent.ReachabilityEvent
import akka.cluster.ClusterEvent.MemberUp
import akka.actor.RootActorPath
import akka.cluster.ClusterEvent.ReachableMember


class Worker extends Actor with ActorLogging {
  val cluster = Cluster(context.system)
  
  override def preStart = {
    cluster.subscribe(self, classOf[MemberEvent], classOf[ReachabilityEvent])
  }

  override def postStop(): Unit = {
    cluster.unsubscribe(self)
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
      
    case MemberUp(member) =>
      if (member.hasRole("frontend"))
        context.actorSelection(RootActorPath(member.address) / "user" / "paymentRunManager") ! WorkerCreated(self)
      
  }
  
  private def processInvoice(inv: Invoice): Payment = {
    // block current thread for 1 second to simulate the processing
	  Thread.sleep(5000)
	  Payment(inv.id, inv.balance)
  }
}