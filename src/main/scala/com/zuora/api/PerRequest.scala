package com.zuora.api

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

import org.json4s.DefaultFormats
import org.json4s.JsonDSL.boolean2jvalue
import org.json4s.JsonDSL.pair2Assoc
import org.json4s.JsonDSL.string2jvalue

import com.zuora.payment.run.Messages.Error
import com.zuora.payment.run.Messages.RequestMessage

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorRefFactory
import akka.actor.OneForOneStrategy
import akka.actor.Props
import akka.actor.ReceiveTimeout
import akka.actor.SupervisorStrategy.Stop
import akka.actor.actorRef2Scala
import spray.http.HttpHeader
import spray.http.StatusCode
import spray.http.StatusCodes.BadRequest
import spray.http.StatusCodes.GatewayTimeout
import spray.http.StatusCodes.InternalServerError
import spray.http.StatusCodes.OK
import spray.httpx.Json4sSupport
import spray.routing.RequestContext

/**
 * Per request pattern, taken from https://github.com/NET-A-PORTER/spray-actor-per-request
 *
 * Created by rguderlei on 25.02.14.
 */
trait PerRequest extends Actor with Json4sSupport{
    def r: RequestContext
    def target: ActorRef
    def message: RequestMessage
    import context._
    
    val json4sFormats = DefaultFormats

    setReceiveTimeout(2 seconds)

    target ! message

    def receive = {
      case com.zuora.payment.run.Messages.Success(payload) =>
        complete(OK, payload)
      
      case Error(message) => 
        complete(BadRequest, ("success" -> false) ~ ("errorMsg" -> message))
      
      case ReceiveTimeout => 
        complete(GatewayTimeout, "Request timeout")
    }

    def complete[T](status: StatusCode, obj: T, headers: List[HttpHeader] = List()): Unit = {
      r.withHttpResponseHeadersMapped(oldheaders => oldheaders:::headers).complete(status, obj)
      stop(self)
    }

    override val supervisorStrategy =
      OneForOneStrategy() {
        case e => {
          complete(InternalServerError, Error(e.getMessage))
          Stop
        }
      }
}

object PerRequest {
  case class WithProps(r: RequestContext, props: Props, message: RequestMessage) extends PerRequest {
    lazy val target = context.actorOf(props)
  }
}

trait PerRequestCreator {
  import PerRequest._
  def perRequest(actorRefFactory: ActorRefFactory, r: RequestContext, props: Props, message: RequestMessage) =
    actorRefFactory.actorOf(Props(new WithProps(r, props, message)))
}