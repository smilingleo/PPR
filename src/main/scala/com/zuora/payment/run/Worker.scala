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
import com.typesafe.config.ConfigFactory
import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.ActorRef


class Worker(runKey: String) extends Actor with ActorLogging {
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
        context.actorSelection("akka.tcp://ClusterPPR@*/user/paymentRunManager/" + runKey) ! WorkerCreated(self)
      
  }
  
  private def processInvoice(inv: Invoice): Payment = {
    // block current thread for 1 second to simulate the processing
	  Thread.sleep(5000)
	  Payment(inv.id, inv.balance)
  }
}

/**
 * Each worker node will have one WorkerProvider.
 */
class WorkerProvider extends Actor with ActorLogging {
  def receive: Actor.Receive = {
    case CreateWorker(n, runKey) =>
      log.info("Creating {} workers for {}", n, runKey)
      (1 to n).foreach(i => context.actorOf(Props(classOf[Worker], runKey)))
  }
}

/**
 * A worker node to create workers for each payment run
 * @author leo
 */
object WorkerNode extends App {
    val port = if (args.isEmpty) "0" else args(0)
    val config = ConfigFactory.parseString(s"akka.remote.netty.tcp.port=$port").
      withFallback(ConfigFactory.parseString("akka.cluster.roles = [backend]")).
      withFallback(ConfigFactory.load())

    val system = ActorSystem("ClusterPPR", config)
    
    // create one worker provider per node
    system.actorOf(Props[WorkerProvider], name = "workerProvider")
}