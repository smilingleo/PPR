package com.zuora.api

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import org.json4s.DefaultFormats
import com.typesafe.config.ConfigFactory
import com.zuora.message.Messages._
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.InitialStateAsEvents
import akka.cluster.ClusterEvent.MemberEvent
import akka.cluster.ClusterEvent.MemberUp
import akka.cluster.ClusterEvent.UnreachableMember
import akka.cluster.Member
import akka.io.IO
import akka.pattern.ask
import akka.util.Timeout
import spray.can.Http
import spray.httpx.Json4sSupport
import spray.routing.Directive.pimpApply
import spray.routing.HttpService
import spray.routing.Route
import akka.cluster.ClusterEvent.MemberRemoved

class Server extends Actor with ServerService with ActorLogging {
  implicit val timeout = Timeout(5 seconds)

  def actorRefFactory = context

  val json4sFormats = DefaultFormats
  val cluster = Cluster(context.system)
  override var nodes = List.empty[Member]
  override var db = Map.empty[String, ActorRef] // used to cache which node we use to run payment run

  override def preStart = {
    // have to add the initialStateMode, otherwise, the nodes list woundn't be initialized properly.
    cluster.subscribe(self, initialStateMode = InitialStateAsEvents, classOf[MemberEvent], classOf[UnreachableMember])
  }

  override def postStop(): Unit = {
    cluster.unsubscribe(self)
  }

  def receive: Actor.Receive = membering.orElse(runRoute(serverRoute))

  val membering: Actor.Receive = {
    case MemberUp(member) =>
      if (member.hasRole("backend")) {
        log.info("found one worker node: {}", member)
        nodes = member :: nodes
      }
      
    case UnreachableMember(member) =>
      log.info("unreachable member found: {}", member)
      nodes = nodes.dropWhile { m => m == member }
      log.info("removed unreachable member, node list: {}", nodes)
      
    case MemberRemoved(member, previousStatus) =>
      log.info("member {} was removed", member)
      nodes = nodes.dropWhile { m => m == member }
      log.info("removed unreachable member, node list: {}", nodes)
      
    case Running(pmKey, actor) =>
      db += (pmKey -> actor)
      log.info(s"added new payment run job, and current running pm: $db")
      
    case RunCompletion(pmKey) =>
      db -= pmKey
  }
}

trait ServerService extends HttpService with PerRequestCreator with Json4sSupport {
  implicit def executionContext = actorRefFactory.dispatcher
  var db: Map[String, ActorRef]
  var nodes: List[Member]
  val serverRoute =
    pathPrefix("progress" / """pm\d+""".r) { pmKey =>
      pathEnd {
        get {
          handlePerRequest {
            CheckProgress(pmKey)
          }
        }
      }
    } ~
      path("cluster-status") {
        get {
          handlePerRequest {
            CheckStatus
          }
        }
      } ~
      path("payment-runs") {
        post {
          entity(as[NewJob]) { job =>
            handlePerRequest {
              CreatePaymentRun(job)
            }
          }
        }
      } ~
      path("") {
        get {
          compressResponse() {
            getFromResource("d3/cluster.html")
          }
        }
      }

  def handlePerRequest(message: RequestMessage): Route =
    // To share immutable `db` and `nodes` state with other actors by message.
    ctx => perRequest(actorRefFactory, ctx, Props(classOf[ServerManager], db, nodes)
        .withDispatcher("akka.actor.status-checker-dispatcher"), message)
}

/**
 * a server to provide REST api.
 * <pre>
 * GET    /progress/<payment run key>
 * POST   /payment-runs/<payment-run-key>
 * </pre>
 */
object Server extends App { self =>
  import scala.concurrent.ExecutionContext.Implicits.global
  val port = if (args.isEmpty) "0" else args(0)
  val config = ConfigFactory.parseString(s"akka.remote.netty.tcp.port=$port").
    withFallback(ConfigFactory.parseString("akka.cluster.roles = [server]")).
    withFallback(ConfigFactory.load())

  implicit val system = ActorSystem("ClusterPPR", config)
  implicit val timeout = Timeout(5 seconds)
  val serverActor = system.actorOf(Props[Server], name = "Server")

  IO(Http) ? Http.Bind(serverActor, interface = "0.0.0.0", port = 8080)
}