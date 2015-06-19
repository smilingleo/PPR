package com.zuora.payment.run
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import com.typesafe.config.ConfigFactory
import com.zuora.message.Messages._
import com.zuora.message.Models._
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSelection.toScala
import akka.actor.ActorSystem
import akka.actor.PoisonPill
import akka.actor.Props
import akka.actor.actorRef2Scala
import akka.actor.DeadLetter
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.UnreachableMember
import akka.cluster.ClusterEvent.InitialStateAsEvents
import akka.cluster.ClusterEvent.MemberEvent
import akka.cluster.ClusterEvent.MemberUp
import akka.cluster.Member
import akka.cluster.ClusterEvent.MemberRemoved
import akka.actor.RootActorPath
import akka.actor.Terminated


class Worker(prActor: ActorRef) extends Actor with ActorLogging {
  
  implicit val sysDispatcher = context.system.dispatcher
  
  var processingInv: Option[Invoice] = None
  
  context.watch(self)
  
  val heartBeating = context.system.scheduler.schedule(1 seconds, 1 seconds) {
    if (context != null)
      context.actorSelection("/user/nodeManager") ! WorkerStatus(self, processingInv)
  }
  
  override def preStart = {
    log.info("notifying {} I'm created.", prActor)
    prActor ! WorkerCreated(self)
  }

  def receive: Actor.Receive = {
    case WorkIsReady =>
      log.info("Requesting work")
      sender ! WorkerRequestsWork(self)
      
    case Job(invoice) =>
      processingInv = Some(invoice)
      log.info("Got invoice: {}", invoice)
      
      val pmActor = sender()
      val payment = Payment(invoice.id, invoice.balance)
      
      Thread.sleep(500)  // don't use scheduler since it's too fast.
      
      pmActor ! WorkIsDone(payment, self)
      processingInv = None
      
    case NoWorkToBeDone =>
      // do nothing
    
    case Terminated(actor) =>
      if (actor == self)
        heartBeating.cancel()
  }
}

