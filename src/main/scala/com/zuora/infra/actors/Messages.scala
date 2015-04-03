package com.zuora.infra.actors

import akka.actor.ActorRef
import java.util.UUID

/**
 * @author leo
 */
// Work Request Messages
 
// Messages from Workers
case class WorkerCreated(worker: ActorRef)
case class WorkerRequestsWork(worker: ActorRef)
case class WorkIsDone[M <: JobMessage[M]](msg: JobResponse[M], worker: ActorRef)
 
// Messages to Workers
case class WorkToBeDone(work: WorkUnit)
case object WorkIsReady
case object NoWorkToBeDone
 
trait WorkUnit {
  def failed(reason: String): JobFailed[_]
}
 
// Job messages
 
trait JobMessage[T <: JobMessage[T]] {
  val info: JobInfo
}
 
trait JobInfo {
  val role: String
  val command: String
  val jobId: Long
  val uuid: UUID
}
 
trait JobTrigger[M <: JobMessage[M], A <: JobAcknowledged[M], F <: JobFailed[M], U <: WorkUnit] extends JobMessage[M] {
  def failed(reason: String): F
  def acknowledged: A
}
 
trait JobResponse[M <: JobMessage[M]] extends JobMessage[M]
  
trait JobFailed[M <: JobMessage[M]] extends JobResponse[M]
trait JobAcknowledged[M <: JobMessage[M]] extends JobResponse[M]
trait JobCompleted[M <: JobMessage[M]] extends JobResponse[M]


trait Job[T] {
  def toWorkUnits: Seq[WorkUnit]
}

trait CollectionJobMessage[T] extends Job[T]
trait ItemJobMessage[T] extends Job[T]