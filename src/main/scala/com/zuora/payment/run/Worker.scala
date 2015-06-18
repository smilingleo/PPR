package com.zuora.payment.run
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
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
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.UnreachableMember
import akka.cluster.ClusterEvent.InitialStateAsEvents
import akka.cluster.ClusterEvent.MemberEvent
import akka.cluster.ClusterEvent.MemberUp
import akka.cluster.Member
import akka.cluster.ClusterEvent.MemberRemoved
import akka.actor.RootActorPath
import com.zuora.payment.run.Messages.RunProgress
import com.zuora.payment.run.Messages.WorkerStatus
import com.zuora.payment.run.Messages.ActorStatus
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