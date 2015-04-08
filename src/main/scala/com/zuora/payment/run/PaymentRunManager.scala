package com.zuora.payment.run

import scala.collection.mutable.Map
import scala.collection.mutable.Queue
import scala.language.postfixOps
import scala.util.Random
import com.typesafe.config.ConfigFactory
import Messages._
import Models.Invoice
import Models.Payment
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.PoisonPill
import akka.actor.Props
import akka.actor.actorRef2Scala
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.MemberEvent
import akka.cluster.ClusterEvent.ReachabilityEvent
import akka.cluster.ClusterEvent.MemberUp
import akka.cluster.ClusterEvent.UnreachableMember
import akka.cluster.ClusterEvent.ReachableMember
import akka.actor.RootActorPath
import akka.actor.ActorPath
import akka.actor.Terminated
import akka.persistence.PersistentActor
import akka.persistence.Recover
/**
 * PaymentRunManager is responsible to query invoices to be collected, generate and dispatch jobs.
 * @author leo
 */
class PaymentRunManager extends PersistentActor with ActorLogging{
  val cluster = Cluster(context.system)
  
  override def preStart = {
    cluster.subscribe(self, classOf[MemberEvent], classOf[ReachabilityEvent])
    self ! Recover()
  }

  override def postStop(): Unit = {
    cluster.unsubscribe(self)
  }

  
  val INV_AMOUNT = 20
  
  //--------- States --------------------
  val workers = Map.empty[ActorPath, Option[Invoice]]
  
  // current work
  val workQueue: Queue[Invoice] = Queue.empty[Invoice]
  
  var result = List.empty[Payment]
  //----------States End ----------------
  
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
  
  override def persistenceId: String = "sample_payment_run"

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
      showWorkerStatus()
      
    case WorkIsDone(payment, worker) =>
      log.info("worker: {} has processed one payment: {}", worker, payment)
      // persist the state asynchronously
      persist(payment){ pmt => 
        result = pmt :: result
        // check status in callback of persist, since it's asynchronous call.
        showWorkerStatus()
      } 
      workers += (worker.path -> None) // mark worker as idle
      

      if (!workQueue.isEmpty)
        notifyWorkers()
  }
  
  private def showWorkerStatus(): Unit = {
    val status = workers.map{
      case (worker, invOp) => worker + " --> " + (if (invOp.isEmpty) "idle" else invOp.get.id)
    }.mkString("\t")
    println(status)
    println("|||| state: " + result.map(inv => inv.id).mkString(","))

    if (result.size == INV_AMOUNT) { // no more work and all workers are idle
    	log.info("All invoices have been processed. There are {} payments in total.", result.size)
    	result = Nil  // clear the state.
    	self ! PoisonPill
    	System.exit(0)
    }
  }
  
}

object PaymentRunApp extends App {
    val port = if (args.isEmpty) "0" else args(0)
    val config = ConfigFactory.parseString(s"akka.remote.netty.tcp.port=$port").
      withFallback(ConfigFactory.parseString("akka.cluster.roles = [frontend]")).
      withFallback(ConfigFactory.load())

    val system = ActorSystem("ClusterSystem", config)
    
    // create one actor for payment run
    val prManager = system.actorOf(Props[PaymentRunManager], name = "paymentRunManager")
    
    // kick off the run
    prManager ! Start  
}

object WorkerApp extends App {
    val port = if (args.isEmpty) "0" else args(0)
    val config = ConfigFactory.parseString(s"akka.remote.netty.tcp.port=$port").
      withFallback(ConfigFactory.parseString("akka.cluster.roles = [backend]")).
      withFallback(ConfigFactory.load())

    val system = ActorSystem("ClusterSystem", config)
    
    // create one worker per node
    system.actorOf(Props[Worker], name = "worker")
  
}