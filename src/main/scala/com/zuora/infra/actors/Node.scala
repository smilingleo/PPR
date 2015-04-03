package com.zuora.infra.actors

/**
 * @author leo
 */
import scala.reflect.ClassTag
import scala.reflect.classTag
 
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.Props
 
abstract class Node[Processor <: Actor : ClassTag, M <: JobMessage[M]](serviceName: String) extends Actor with ActorLogging {
 
  val facade = context.actorSelection("/user/facade")
 
  def leaderMsg(msg: Any) = ??? //NotifyLeader(serviceName, msg)
 
  def props: Props = Props(classTag[Processor].runtimeClass, self)
 
  override def preStart = {
    facade ! leaderMsg(WorkerCreated(self))
  }
 
  def working(work: Any): Receive = {
    case WorkIsReady => // do nothing
    case NoWorkToBeDone => // do nothing
    case WorkToBeDone =>
      log.error("Node busy")
    case sa: JobAcknowledged[M] =>
      facade ! leaderMsg(sa)
    case ca: JobCompleted[M] =>
      facade ! leaderMsg(WorkIsDone(ca, self))
      facade ! leaderMsg(WorkerRequestsWork(self))
      context.stop(sender)
      context.become(idle)
    case fa: JobFailed[M] =>
      facade ! leaderMsg(WorkIsDone(fa, self))
      facade ! leaderMsg(WorkerRequestsWork(self))
      context.stop(sender)
      context.become(idle)
  }
  
  def idle: Receive = {
    case WorkIsReady =>
      log.info("Requesting work")
      facade ! leaderMsg(WorkerRequestsWork(self))
    case WorkToBeDone(work) =>
      log.info("Got work {}", work)
      val processor = context.actorOf(props)
      processor ! work
      context.become(working(work))
    case NoWorkToBeDone => // do nothing
  }
 
  def receive = idle
 
}