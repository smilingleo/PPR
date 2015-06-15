package com.zuora.api

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.util.Random

import com.zuora.payment.run.Messages._
import com.zuora.payment.run.PaymentRunManager

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Deploy
import akka.actor.Props
import akka.actor.actorRef2Scala
import akka.cluster.Member
import akka.pattern.ask
import akka.remote.RemoteScope
import akka.util.Timeout
import org.json4s.jackson.JsonMethods._
import org.json4s.JsonDSL._
/**
 * @author leo
 */
class ServerManager(db: Map[String, ActorRef], nodes: List[Member]) extends Actor with ActorLogging {
  implicit val timeout = Timeout(5 seconds)

  def receive: Actor.Receive = {
    case CheckProgress(pmKey) =>
      log.info(s"checking progress for payment-run: $pmKey, db: $db")
      db.get(pmKey) match {
        case Some(pmActor) =>
          val progressF: Future[Option[RunProgress]] = ask(pmActor, CheckProgress(pmKey)).mapTo[Option[RunProgress]]
          val progress: Option[RunProgress] = Await.result(progressF, 5 seconds)
          log.info("received progress: {}", progress)
          sender() ! Success(progress.get)
        case None =>
          sender() ! Error("no payment run found")
      }

    case CheckClusterStatus =>
      val children = nodes.zipWithIndex.map { t => Node("Node" + t._2, "node", Seq.empty[Node]) }
      val cluster = Node("Akka Cluster", "cluster", children)
      log.info(s"currently the cluster is: $cluster")
      sender() ! Success(cluster)

    case CreatePaymentRun(job) =>
      if (nodes.length > 0) {
        // Randomly select a node
        val member = nodes(Random.nextInt(nodes.size))

        log.info("creating payment run actor on {}", member)
        // create a payment run actor remotely
        val pmActor = context.system.actorOf(Props(classOf[PaymentRunManager], job.pmKey, job.invoices).withDeploy(Deploy(scope = RemoteScope(member.address))),
          name = "paymentRunManager_" + job.pmKey)
        
        context.actorSelection("/user/Server") ! Running(job.pmKey, pmActor)

        pmActor ! Start
        
        sender() ! Success(("success" -> true) ~ ("node" -> s"${member.address}"))
        
      } else {
        sender() ! Error("No WorkerNode found, please start at least one worker node, or I'm not ready yet.")
      }

  }
}