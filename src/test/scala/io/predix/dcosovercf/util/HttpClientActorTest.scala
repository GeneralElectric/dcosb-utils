package io.predix.dcosb.util

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, Props}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.testkit.{CallingThreadDispatcher, TestActorRef}
import akka.util.Timeout
import io.predix.dcosb.util.actor.HttpClientActor
import spray.json.DefaultJsonProtocol

import scala.collection.immutable.HashMap
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class HttpClientActorTest extends ActorSuite {
  implicit val executionContext = system.dispatchers.lookup(CallingThreadDispatcher.Id)
  implicit val timeout = Timeout(FiniteDuration(5, TimeUnit.SECONDS))

  "An Actor mixing in the HttpClientActor trait" - {


    "should be able to use it's configured http client" - {

      val httpClientMock = mockFunction[HttpRequest, String, Future[(HttpResponse, String)]]
      val httpClientActor = TestActorRef(Props(new HttpClientActor {

        httpClient = Some(httpClientMock)

        override def receive: Receive = {
          case _ => // not a lot of behavior here
        }

      }))

      "and multipleHttpRequestsSending to send a sequence of HttpRequests and have a callback invoked on it's results - HttpResponse or Failure objects" in {

        val handler = mockFunction[Seq[Try[(HttpResponse, String)]], Unit]
        val underlyingActor = httpClientActor.underlyingActor.asInstanceOf[HttpClientActor]

        val httpRequests = List(
          (HttpRequest(method = HttpMethods.GET, uri = "/foo"), "foo"),
          (HttpRequest(method = HttpMethods.POST, uri = "/bar"), "bar"))

        for (httpRequest <- httpRequests) {
          httpClientMock expects(httpRequest._1, httpRequest._2) returning Future.successful((HttpResponse(), httpRequest._2)) once()
        }

        handler expects(List(Success((HttpResponse(), "foo")), Success((HttpResponse(), "bar")))) once()

        underlyingActor.multipleHttpRequestsSending(httpRequests, handler)

      }

      "and `sendRequest and unmarshall entity in response` to send an HttpRequest, pair up an unmarshaller and a handler with a boolean function on HttpResponse and " - {

        case class Foo(bar: String)
        case class FooNotAuthorized(baz: String)
        val req = HttpRequest(method = HttpMethods.GET, uri = "/foo")

        val underlyingActor = httpClientActor.underlyingActor.asInstanceOf[HttpClientActor]

        def send(fooHandler: (Foo) => _, fooNotAuthorizedHandler: (FooNotAuthorized) => _, failureHandler: (Throwable => _))(implicit executionContext: ExecutionContext) {

          import DefaultJsonProtocol._
          import SprayJsonSupport._

          underlyingActor.`sendRequest and unmarshall entity in response`(
            req,
            Seq(
              (((r:HttpResponse) => r.status == StatusCodes.OK), underlyingActor.UnmarshallAndHandle[Foo](jsonFormat1(Foo), fooHandler)),
              (((r:HttpResponse) => r.status == StatusCodes.Unauthorized), underlyingActor.UnmarshallAndHandle[FooNotAuthorized](jsonFormat1(FooNotAuthorized), fooNotAuthorizedHandler))
            ),
            failureHandler
          )

        }

        "given an HttpResponse that matches the first UnmarshallAndHandle in the method invocation, invokes the first UnmarshallAndHandle" in {

          import DefaultJsonProtocol._
          import SprayJsonSupport._

          implicit val fooFormatSupport = jsonFormat1(Foo)
          val entity = Await.result(Marshal(Foo("bar")).to[ResponseEntity], timeout.duration)
          httpClientMock expects(req, *) returning Future.successful((HttpResponse(status = StatusCodes.OK, entity = entity), ""))

          val fooHandler = mockFunction[Foo, Unit]
          fooHandler expects(Foo("bar")) once()

          send(fooHandler, stubFunction[FooNotAuthorized, Unit], stubFunction[Throwable, Unit])


        }

        "given an HttpResponse that matches the second UnmarshallAndHandle in the method invocation, invokes the second UnmarshallAndHandle" in {

          import DefaultJsonProtocol._
          import SprayJsonSupport._

          implicit val fooNotAuthorizedSupport = jsonFormat1(FooNotAuthorized)
          val entity = Await.result(Marshal(FooNotAuthorized("bar")).to[ResponseEntity], timeout.duration)
          httpClientMock expects(req, *) returning Future.successful((HttpResponse(status = StatusCodes.Unauthorized, entity = entity), ""))

          val fooNotAuthorizedHandler = mockFunction[FooNotAuthorized, Unit]
          fooNotAuthorizedHandler expects(FooNotAuthorized("bar")) once()

          send(stubFunction[Foo, Unit], fooNotAuthorizedHandler, stubFunction[Throwable, Unit])

        }

        "given an exception while sending the http request, invokes the failure handler" in {

          case class SadThrowable() extends Throwable

          httpClientMock expects(req, *) returning Future.failed(SadThrowable())

          val failureHandler = mockFunction[Throwable, Unit]
          failureHandler expects(SadThrowable()) once()

          send(stubFunction[Foo, Unit], stubFunction[FooNotAuthorized, Unit], failureHandler)


        }


      }


    }



  }

}
