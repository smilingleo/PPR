package com.zuora.cluster

import akka.actor.RootActorPath
import akka.cluster.ClusterEvent.MemberRemoved
import akka.actor.Props
import com.zuora.payment.run.PaymentRunManager
import akka.actor.ActorLogging
import com.typesafe.config.ConfigFactory
import akka.cluster.ClusterEvent.UnreachableMember
import akka.actor.ActorSystem
import akka.actor.DeadLetter
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.MemberUp
import akka.actor.Actor
import akka.cluster.Member
import akka.actor.ActorRef
import com.zuora.message.Messages._
import akka.cluster.ClusterEvent.MemberEvent
import akka.cluster.ClusterEvent.InitialStateAsEvents
import com.zuora.payment.run.Worker
import akka.actor.PoisonPill
/**
 * Each worker node will have one nodeManager.
 */
class NodeManager extends Actor with ActorLogging {
  val cluster = Cluster(context.system)
  var serverMember: Option[Member] = None
  
  var status: Map[ActorRef, ActorStatus] = Map.empty[ActorRef, ActorStatus]
  
  override def preStart = {
    cluster.subscribe(self, initialStateMode = InitialStateAsEvents, classOf[MemberEvent], classOf[UnreachableMember])
    context.system.eventStream.subscribe(self, classOf[DeadLetter])
  }
  
  override def postStop(): Unit = {
    cluster.unsubscribe(self)
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
       if (serverMember.isDefined){
         val serverPath = RootActorPath(serverMember.get.address) / "user" / "Server"
    		 context.system.actorSelection(serverPath) ! Running(job.pmKey, pmActor)
       }
       
    case CheckStatus =>
       sender() ! status
    
    case RunProgress(pmKey, total, done, failed) =>  
       status += (sender() -> RunProgress(pmKey, total, done, failed))
       
    case WorkerStatus(af, invOps) =>
       status += (sender() -> WorkerStatus(af, invOps))
       
    case RunCompletion(pmKey) =>
      log.info("payment run: {} is finished, killing its workers", pmKey)
      context.actorSelection(s"/user/nodeManager/workerFor_${pmKey}_*") ! PoisonPill
      val pmActor = sender()
      status -= pmActor
      status = status.dropWhile{ p =>
        val actor = p._1
        p._2 match {
          case WorkerStatus(af, invOps) =>
            status.contains(af)
          case _ =>
            false
        }
      }
      
    case d: DeadLetter =>
      log.info("xxxxxxxxx dead letter found: {}", d)
      
    case MemberUp(member) =>
      if (member.hasRole("server")) {
        log.info("discovered server node on: {}", member)
        serverMember = Some(member)
      }
    case UnreachableMember(member) =>
      if (member.hasRole("server")) {
        serverMember = None
      }
    case MemberRemoved(member, previousStatus) =>
      if (member.hasRole("server")) {
        serverMember = None
      }
      
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