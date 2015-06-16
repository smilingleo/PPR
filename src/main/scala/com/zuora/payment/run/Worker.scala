package com.zuora.payment.run

import com.typesafe.config.ConfigFactory
import Messages.CheckStatus
import Messages.CreatePaymentRun
import Messages.CreateWorker
import Messages.Job
import Messages.NoWorkToBeDone
import Messages.RunCompletion
import Messages.Running
import Messages.Start
import Messages.WorkIsDone
import Messages.WorkIsReady
import Messages.WorkerCreated
import Messages.WorkerRequestsWork
import Models.Invoice
import Models.Payment
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSelection.toScala
import akka.actor.ActorSystem
import akka.actor.PoisonPill
import akka.actor.Props
import akka.actor.actorRef2Scala
import akka.actor.DeadLetter


class Worker(prActor: ActorRef) extends Actor with ActorLogging {
  override def preStart = {
    log.info("notifying {} I'm created.", prActor)
    prActor ! WorkerCreated(self)
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
	  Thread.sleep(5000)
	  Payment(inv.id, inv.balance)
  }
}

/**
 * Each worker node will have one nodeManager.
 */
class NodeManager extends Actor with ActorLogging {
  override def preStart = {
    context.system.eventStream.subscribe(self, classOf[DeadLetter])
  }
  
  def receive: Actor.Receive = {
    case CreateWorker(n, runKey) =>
      log.info("Creating {} workers for {}", n, runKey)
      // Worker created here are children of worker provider, not payment run manager.
      (1 to n).foreach { i => 
        context.actorOf(Props(classOf[Worker], sender), s"workerFor_${runKey}_${i}")
      }
      
    case CreatePaymentRun(job) =>
       val pmActor = context.actorOf(Props(classOf[PaymentRunManager], job.pmKey, job.invoices), name = "paymentRunManager_" + job.pmKey)
       log.info("created payment run {}", pmActor)
       pmActor ! Start
       // notify Server, but where is server ?
       context.actorSelection("/user/Server") ! Running(job.pmKey, pmActor)
       
    case CheckStatus =>
       val actorNames = context.children.map(ref => ref.path.name)
       sender() ! actorNames
    
    case RunCompletion(pmKey) =>
      log.info("payment run: {} is finished, killing its workers", pmKey)
      context.actorSelection(s"/user/nodeManager/workerFor_${pmKey}_*") ! PoisonPill
      
    case d: DeadLetter =>
      log.info("xxxxxxxxx dead letter found: {}", d)
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
    system.actorOf(Props[NodeManager], name = "nodeManager")
}