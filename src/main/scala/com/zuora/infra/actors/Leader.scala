package com.zuora.infra.actors

/**
 * @author leo
 */
import scala.concurrent.duration._
import scala.collection.mutable.{Map, Queue}
import scala.reflect.ClassTag
 
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorLogging
import akka.actor.Props
import akka.actor.Terminated
import akka.event.LoggingReceive
import akka.routing.FromConfig
import akka.util.Timeout
 
class Leader[U <: WorkUnit, N <: Node[_, J] : ClassTag, J <: JobMessage[J]](supervisor: ActorRef) extends Actor with ActorLogging {
 
  // Create the cluster-aware router for managing remote routees
  context.actorOf(Props[N].withRouter(FromConfig()),
    "nodeRouter")
 
  val workers = Map.empty[ActorRef, Option[(ActorRef, U)]]
 
  val workQueue = Queue.empty[(ActorRef, U)]
 
  // Notifies workers that there's work available, provided they're
  // not already working on something
  def notifyWorkers(): Unit = {
    if (!workQueue.isEmpty) {
      workers.foreach { 
        case (worker, m) if (m.isEmpty) => worker ! WorkIsReady
        case _ =>
      }
    }
  }
 
  override def preStart = {
    log.info("Starting leader at {}", self.path.toStringWithAddress(self.path.address))
  }
  
  def receive = LoggingReceive {
    case WorkerCreated(worker) =>
      log.info("Worker created: {}", worker)
      context.watch(worker)
      workers += (worker -> None)
      notifyWorkers()
    case WorkerRequestsWork(worker) =>
      log.info("Worker requests work: {}", worker)
      if (workers.contains(worker)) {
        if (workQueue.isEmpty)
          worker ! NoWorkToBeDone
        else if (workers(worker) == None) {
          val (workSender, work) = workQueue.dequeue()
          workers += (worker -> Some(workSender -> work))
          // Use the special form of 'tell' that lets us supply
          // the sender
          log.info("Sending work {} to {}", worker, work)
          worker.tell(WorkToBeDone(work), workSender)
        }
      }
    // Worker has completed its work and we can clear it out
    case WorkIsDone(msg, worker) =>
      workers.get(worker) match {
        case Some(Some((requester, _))) => 
          requester ! msg
          workers += (worker -> None)
        case Some(None) =>
          log.error("Duang! {} said it's done work but we didn't assign a job to him", worker)
        case None =>
          log.error("Blurgh! {} said it's done work but we didn't know about him", worker)
      }
      
    case Terminated(worker) =>
      if (workers.contains(worker) && workers(worker) != None) {
        log.error("Blurgh! {} died while processing {}", worker, workers(worker))
        // Send the work that it was doing back to ourselves for processing  
        val (workSender, work) = workers(worker).get
        self.tell(work, workSender)
      }
      workers -= worker
 
    case trigger: JobTrigger[J, JobAcknowledged[J], JobFailed[J], U] with ItemJobMessage[J] =>
      log.info("Queuing single item")
      trigger.toWorkUnits.foreach { work =>
//        workQueue.enqueue(sender -> work)
      }
      notifyWorkers()
      
    case work: U =>
      workQueue enqueue (sender -> work)
      notifyWorkers()
  }
}