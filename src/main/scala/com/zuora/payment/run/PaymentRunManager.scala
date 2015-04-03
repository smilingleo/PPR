package com.zuora.payment.run

import scala.collection.mutable.Map
import scala.collection.mutable.Queue
import scala.language.postfixOps
import scala.util.Random
import com.typesafe.config.ConfigFactory
import Messages.Job
import Messages.NoWorkToBeDone
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
import akka.actor.ActorSystem
import akka.actor.PoisonPill
import akka.actor.Props
import akka.actor.actorRef2Scala
import akka.cluster.Cluster
/**
 * PaymentRunManager is responsible to query invoices to be collected, generate and dispatch jobs.
 * @author leo
 */
class PaymentRunManager extends Actor with ActorLogging{
  val cluster = Cluster(context.system)
  
  val INV_AMOUNT = 20
  
  val workers = Map.empty[ActorRef, Option[Invoice]]
  
  // current work
  val workQueue: Queue[Invoice] = Queue.empty[Invoice]
  
  val result = Queue.empty[Payment]
  
  private def loadWork = {
	  val invoices = (1 to INV_AMOUNT).map(i => Invoice(i.toString, Math.abs(Random.nextDouble()))).toSeq
    workQueue ++= invoices
  }
  
  
  def notifyWorkers: Unit = {
    // zip with idle workers
    workQueue.zip(workers.filter(t => t._2.isEmpty)).foreach{
      case (inv, (idleWorker, _)) =>
        idleWorker ! WorkIsReady
    }
  }
  
  def receive: Actor.Receive = {
    case Start =>
      loadWork
      val total = workQueue.foldLeft(0.0)((acc, inv) => acc + inv.balance)
      log.info("generated {} invoices with total amount: {}", workQueue.size, total)
      
      // create 10 workers
      (1 to 3).foreach{ i =>
        val worker = context.actorOf(Props[Worker], name = "worker" + i)
      }
      
    case WorkerCreated(worker) =>
      log.info("worker: {} is created.", worker)
      workers += (worker -> None)
      context.watch(worker)
      if (!workQueue.isEmpty)
        worker ! WorkIsReady  // only notify this worker rather then all to avoid duplicate notification.
      
    case WorkerRequestsWork(worker) =>
      log.info("worker: {} is requesting work.", worker)
      if (workers.contains(worker)){
        if (workQueue.isEmpty){
          worker ! NoWorkToBeDone
        } else {
          val invoice = workQueue.dequeue()
          log.info("Send job {} to worker: {}", invoice, worker)
          worker ! Job(invoice)
          workers += (worker -> Some(invoice)) // mark current worker as working on this invoice
        }
      }
      showWorkerStatus
      
    case WorkIsDone(payment, worker) =>
      log.info("worker: {} has processed one payment: {}", worker, payment)
      result.enqueue(payment)
      workers += (worker -> None) // mark worker as idle
      showWorkerStatus

      if (!workQueue.isEmpty)
        notifyWorkers
      
      if (result.size == INV_AMOUNT) { // no more work and all workers are idle
        log.info("All invoices have been processed. There are {} payments in total.", result.size)
        self ! PoisonPill
        System.exit(0)
      }
      
  }
  
  private def showWorkerStatus: Unit = {
    val status = workers.map{
      case (worker, invOp) => worker.path.name + " --> " + (if (invOp.isEmpty) "idle" else invOp.get.id)
    }.mkString("\t")
    println(status)
  }
  
}

object PaymentRunApp extends App {
    val port = if (args.isEmpty) "0" else args(0)
    val config = ConfigFactory.parseString(s"akka.remote.netty.tcp.port=$port").
      withFallback(ConfigFactory.parseString("akka.cluster.roles = [backend]")).
      withFallback(ConfigFactory.load())

    val system = ActorSystem("ClusterSystem", config)
    
    // create one actor for payment run
    val prManager = system.actorOf(Props[PaymentRunManager], name = "paymentRunManager")
    
    // kick off the run
    prManager ! Start  
}