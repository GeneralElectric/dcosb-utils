package io.predix.dcosb.util.actor

import akka.actor.{Actor, ActorLogging}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import io.predix.dcosb.util.AsyncUtils
import pureconfig.syntax._
import spray.json.{JsValue, RootJsonReader}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

trait HttpClientActor extends Actor with ActorLogging with SprayJsonSupport {

  var httpClient
    : Option[(HttpRequest, String) => Future[(HttpResponse, String)]] = None
  val tConfig = ConfigFactory.load()
  val timeout = Timeout(
    tConfig.getValue("http.client.default-timeout").toOrThrow[FiniteDuration])

  /**
    * @deprecated in favor of [[HttpClientActor.`sendRequest and handle response`()]]
    */
  def httpRequestSending(
      r: HttpRequest,
      f: (Try[(HttpResponse, String)] => _),
      c: String = "")(implicit executionContext: ExecutionContext) = {
    httpClient match {

      case Some(h: ((HttpRequest, String) => Future[(HttpResponse, String)])) =>
        h(r, c) onComplete {
          case Success((response: HttpResponse, context: String)) =>
            f(Success((response, context)))
          case Failure(e: Throwable) => f(Failure(e))
          case e =>
            f(
              Failure(new IllegalStateException(
                s"Received unexpected response from HTTP client: $e")))

        }

      case None =>
        f(Failure(new IllegalStateException(s"No HTTP client was configured")))

    }
  }

  /**
    * @deprecated in favor of [[HttpClientActor.`sendRequest and handle response`()]]
    */
  def multipleHttpRequestsSending(rqs: Seq[(HttpRequest, String)],
                                  f: (Seq[Try[(HttpResponse, String)]] => _))(
      implicit executionContext: ExecutionContext) = {
    log.debug(s"Sending requests $rqs")
    httpClient match {

      case Some(h: ((HttpRequest, String) => Future[(HttpResponse, String)])) =>
        AsyncUtils.waitAll[(HttpResponse, String)](rqs map (r => h(r._1, r._2))) map {
          responses =>
            f(responses)
        }

      case None =>
        f(List(
          Failure(new IllegalStateException(s"No HTTP client was configured"))))

    }

  }

  /**
    * @deprecated in favor of [[HttpClientActor.`sendRequest and handle response`()]]
    */
  def `sendRequest and unmarshall entity`[T](request: HttpRequest,
                                             f: ((Try[T]) => _),
                                             expectedStatus: StatusCode =
                                               StatusCodes.OK)(
      implicit um: Unmarshaller[akka.http.scaladsl.model.ResponseEntity, T],
      executionContext: ExecutionContext,
      ttag: ClassTag[T]): Unit = {
    log.debug(s"Sending request: $request")
    httpRequestSending(
      request,
      (response: Try[(HttpResponse, String)]) => {

        response match {
          case Success((r: HttpResponse, _: String))
              if r.status == expectedStatus =>
            log.debug(s"Received response: ${r}, preparing to unmarshall")
            // unmarshall
            implicit val executor = context.dispatcher
            implicit val materializer =
              ActorMaterializer(ActorMaterializerSettings(context.system))
            Unmarshal(r.entity.withContentType(ContentTypes.`application/json`))
              .to[T] onComplete {
              case Success(resp: T)      => f(Success(resp))
              case Failure(e: Throwable) => f(Failure(e))
            }

          case Success((r: HttpResponse, _: String)) =>
            f(Failure(
              new IllegalStateException(s"Received unexpected response: ${r}")))
          case Failure(e: Throwable) => f(Failure(e))
        }

      }
    )

  }


  case class UnmarshallAndHandle[T](
      reader: RootJsonReader[T],
      handler: (T) => _)(implicit ctag: ClassTag[T]) {
    def apply(js: JsValue) = {
      handler(reader.read(js))
    }
  }

  /**
    * @deprecated in favor of [[HttpClientActor.`sendRequest and handle response`()]]
    */
  def `sendRequest and unmarshall entity in response`(
      request: HttpRequest,
      handling: Seq[((HttpResponse) => Boolean, UnmarshallAndHandle[_])],
      failureHandling: (Throwable => _),
      c: String = "")(implicit executionContext: ExecutionContext): Unit = {

    httpRequestSending(
      request,
      (response: Try[(HttpResponse, String)]) => {

        response match {

          case Success((r: HttpResponse, c: String)) =>
            handling find { _._1(r) } match {
              case Some((_, h)) =>
                implicit val materializer =
                  ActorMaterializer(ActorMaterializerSettings(context.system))
                Unmarshal(r.entity).to[JsValue] onComplete {
                  case Success(js: JsValue)  => h(js)
                  case Failure(e: Throwable) => failureHandling(e)
                }
              case None =>
                log.warning(
                  s"$r was matched to no UnmarshallAndHandle object..")

            }

          case Failure(e: Throwable) => failureHandling(e)

        }

      }
    )

  }

  def `sendRequest and handle response`[T](request: HttpRequest,
                                           handling: Try[HttpResponse] => T)(
      implicit executionContext: ExecutionContext,
      materializer: ActorMaterializer) = {

    httpRequestSending(
      request,
      (response: Try[(HttpResponse, String)]) => {

        response match {
          case Success((r: HttpResponse, c: String)) => handling(Success(r))
          case Failure(e: Throwable)                 => handling(Failure(e))
        }

      }
    )
  }

}
