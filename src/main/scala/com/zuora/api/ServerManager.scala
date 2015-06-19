package com.zuora.api

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.util.Random
import com.zuora.message.Messages._
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
import akka.actor.RootActorPath
import akka.actor.Identify
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
        val nodeName = member.address.host.getOrElse("Node") + ":" + member.address.port.getOrElse(t._2)
        val providerPath = (RootActorPath(member.address) / "user" / "nodeManager")
        try{
        	val status = Await.result(ask(context.actorSelection(providerPath), CheckStatus).mapTo[Map[ActorRef, ActorStatus]], 2 seconds)
    			val actors = status.map{ t =>
            val name = t._1.path.name
            if (name.startsWith("paymentRunManager"))
      				Node(name, "jobManager", Seq.empty[Node], t._2)
    				else
    					Node(name, "worker", Seq.empty[Node], t._2)
        	}
        	Node(nodeName, "node", Node("nodeManager", "nodeManager", actors.toSeq, NodeStatus(true)) :: Nil, NodeStatus(true)) 
          
        }catch{
          case e: java.util.concurrent.TimeoutException =>
            Node(nodeName, "node", Nil, NodeStatus(false))
        }
      }
      
      val cluster = Node("Akka Cluster", "cluster", children, NodeStatus(true))
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