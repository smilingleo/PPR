package com.zuora.api

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.util.Random

import org.json4s.JsonDSL.boolean2jvalue
import org.json4s.JsonDSL.pair2Assoc
import org.json4s.JsonDSL.string2jvalue

import com.zuora.payment.run.Messages.CheckProgress
import com.zuora.payment.run.Messages.CheckStatus
import com.zuora.payment.run.Messages.CreatePaymentRun
import com.zuora.payment.run.Messages.Error
import com.zuora.payment.run.Messages.Node
import com.zuora.payment.run.Messages.RunProgress
import com.zuora.payment.run.Messages.Success

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSelection.toScala
import akka.actor.RootActorPath
import akka.actor.actorRef2Scala
import akka.cluster.Member
import akka.pattern.ask
import akka.util.Timeout
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

    case CheckStatus =>
      val children = nodes.zipWithIndex.map { t =>
        val member = t._1
        val providerPath = (RootActorPath(member.address) / "user" / "nodeManager")
        val children = Await.result(ask(context.actorSelection(providerPath), CheckStatus).mapTo[Iterable[String]], 5 seconds)
        val workers = children.map{ name =>
          if (name.startsWith("paymentRunManager"))
            Node(name, "paymentRunManager", Seq.empty[Node])
          else
            Node(name, "worker", Seq.empty[Node])
        }
        Node("Node" + t._2, "node", Node("nodeManager", "nodeManager", workers.toSeq) :: Nil) 
      }
      val cluster = Node("Akka Cluster", "cluster", children)
      log.info(s"currently the cluster is: $cluster")
      sender() ! Success(cluster)

    case CreatePaymentRun(job) =>
      if (nodes.length > 0) {
        // Randomly select a node
        val member = nodes(Random.nextInt(nodes.size))

        log.info("creating payment run actor on {}", member)
        context.actorSelection((RootActorPath(member.address) / "user" / "nodeManager")) ! CreatePaymentRun(job)
        
        sender() ! Success(("success" -> true) ~ ("node" -> s"${member.address}"))
        
      } else {
        sender() ! Error("No WorkerNode found, please start at least one worker node, or I'm not ready yet.")
      }

  }
}