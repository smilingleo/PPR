package com.zuora.payment.run

import scala.collection.mutable.Map
import scala.collection.mutable.Queue
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

import scala.util.Random
import Messages._
import Models.Invoice
import Models.Payment
import akka.actor.ActorLogging
import akka.actor.ActorPath
import akka.actor.ActorSelection.toScala
import akka.actor.PoisonPill
import akka.actor.RootActorPath
import akka.actor.Terminated
import akka.actor.actorRef2Scala
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.InitialStateAsEvents
import akka.cluster.ClusterEvent.MemberEvent
import akka.cluster.ClusterEvent.MemberUp
import akka.cluster.ClusterEvent.ReachabilityEvent
import akka.cluster.Member
import akka.persistence.PersistentActor
import akka.persistence.Recover
import akka.actor.Props
import akka.actor.Deploy
import akka.remote.RemoteScope

/**
 * PaymentRunManager is responsible to query invoices to be collected, generate and dispatch jobs.
 * @author leo
 */
class PaymentRunManager(runNumber: String = "sample_payment_run", INV_AMOUNT: Int = 20) extends PersistentActor with ActorLogging{
  val cluster = Cluster(context.system)
  
  override def preStart = {
    cluster.subscribe(self, initialStateMode = InitialStateAsEvents, classOf[MemberEvent], classOf[ReachabilityEvent])
    self ! Recover()
  }

  override def postStop(): Unit = {
    cluster.unsubscribe(self)
  }

  //--------- States --------------------
  val workers = Map.empty[ActorPath, Option[Invoice]]
  
  var nodes = List.empty[Member]
  
  // current work
  val workQueue: Queue[Invoice] = Queue.empty[Invoice]
  
  var result = List.empty[Payment]
  //----------States End ----------------
  
  implicit val sysDispatcher = context.system.dispatcher
  
  context.watch(self)
  val heartBeating = context.system.scheduler.schedule(1 seconds, 1 seconds) {
    if (context != null)
      context.actorSelection("/user/nodeManager") ! RunProgress(runNumber, INV_AMOUNT, INV_AMOUNT - workQueue.size, 0)
  }
  
  
  private def loadWork = {
	  val invoices = (1 to INV_AMOUNT).map(i => Invoice(i.toString, Math.abs(Random.nextDouble()))).toSeq
    workQueue ++= invoices
  }
  
  
  def notifyWorkers(): Unit = {
    // zip with idle workers
    workQueue.zip(workers.filter(t => t._2.isEmpty)).foreach{
      case (inv, (idleWorker, _)) =>
        context.actorSelection(idleWorker) ! WorkIsReady
    }
  }
  
  override def persistenceId: String = runNumber

  override def receiveRecover: Receive = {
    case pmt: Payment => result = pmt :: result
    case env => log.info(">>>>>> recovering: {}, processed: {}", env, result)
  }

  override def receiveCommand: Receive = {
    case Start =>
      
      if (!result.isEmpty) {
        log.info("==========payment run manager is resumed, there are {} invoices processed", result.size)
        loadWork
        // remove the processed ones.
        workQueue.dequeueAll { inv => result.exists { pmt => pmt.id == inv.id } }
        log.info("==========there are still {} invoices to be processed", workQueue.size)
      } else {
    	  loadWork
    	  val total = workQueue.foldLeft(0.0)((acc, inv) => acc + inv.balance)
    	  log.info("==========generated {} invoices with total amount: {}", workQueue.size, total)
      }

            
    case WorkerCreated(worker) =>
      log.info("worker: {} is created.", worker)

      context.watch(worker)
      
      workers.get(worker.path) match {
        case Some(Some(inv)) =>
          log.info("node {} was restarted and it was processing invoice {}, will re-process the invoice. ", worker.path, inv)
          worker ! Job(inv)
        case _ =>
          workers += (worker.path -> None)
          if (!workQueue.isEmpty)
        	  worker ! WorkIsReady  // only notify this worker rather then all to avoid duplicate notification.          
      }
      
    case Terminated(worker) =>
      if (worker == self) {
        heartBeating.cancel()
      } else {
    	  workers.get(worker.path) match {
    	  case Some(Some(inv)) =>
    	  log.info("node {} is terminated and it was processing invoice {}, put the invoice back to queue. ", worker, inv)
    	  workers -= worker.path
    	  workQueue.enqueue(inv)
    	  case Some(None) =>
    	  log.info("node {} is terminated and it was idle ", worker)
    	  workers -= worker.path
    	  
    	  case None =>
    	  // do nothing.
    	  }
      } 
      
    case WorkerRequestsWork(worker) =>
      log.info("worker: {} is requesting work.", worker)
      if (workers.contains(worker.path)){
        if (workQueue.isEmpty){
          worker ! NoWorkToBeDone
        } else {
          val invoice = workQueue.dequeue()
    		  workers += (worker.path -> Some(invoice)) // mark current worker as working on this invoice

          log.info("Send job {} to worker: {}", invoice, worker)
          worker ! Job(invoice)
        }
      }
      checkStatus()
      
    case WorkIsDone(payment, worker) =>
      log.info("worker: {} has processed one payment: {}", worker, payment)
      // persist the state asynchronously
      persist(payment){ pmt => 
        result = pmt :: result
        // check status in callback of persist, since it's asynchronous call.
        checkStatus()
      } 
      workers += (worker.path -> None) // mark worker as idle
      

      if (!workQueue.isEmpty)
        notifyWorkers()
        
    case CheckProgress(pmKey) =>
      log.info("*************respond run progress for {}", runNumber)
  	  sender ! Some(RunProgress(runNumber, INV_AMOUNT, INV_AMOUNT - workQueue.size, 0))
        
    case MemberUp(member) =>
      if (member.hasRole("backend")){
        log.info("found one worker provider: {}", member)
        nodes = member :: nodes
        // tell nodeManager to create worker for me.
        // only do this for new created payment run mamager, don't do it for recovered payment run. ???
        // ask nodeManager to create workers for this payment run
        val providerPath = (RootActorPath(member.address) / "user" / "nodeManager")
        log.info("asking {} to generate 2 workers.", providerPath)
        context.actorSelection(providerPath) ! CreateWorker(2, runNumber)
      }
        
  }
  
  private def checkStatus(): Unit = {
    val status = workers.map{
      case (worker, invOp) => worker + " --> " + (if (invOp.isEmpty) "idle" else invOp.get.id)
    }.mkString("\t")
//    println(status)
//    println("|||| state: " + result.map(inv => inv.id).mkString(","))

    if (result.size == INV_AMOUNT) { // no more work and all workers are idle
    	log.info("All invoices have been processed. There are {} payments in total.", result.size)
    	result = Nil  // clear the state.
      
      nodes.foreach(member => context.actorSelection(RootActorPath(member.address) / "user" / "nodeManager") ! RunCompletion(runNumber))
    	self ! PoisonPill
    }
  }
  
}