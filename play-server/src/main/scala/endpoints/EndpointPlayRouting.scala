package endpoints

import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.functional.InvariantFunctor
import play.api.libs.functional.syntax._
import play.api.libs.streams.Accumulator
import play.api.mvc._
import play.twirl.api.Html

import scala.language.higherKinds

/**
  * Interpreter for [[EndpointAlg]] that performs routing using Play framework.
  *
  * Consider the following endpoints definition:
  *
  * {{{
  *   trait MyEndpoints extends EndpointAlg with JsonEntityAlg {
  *     val inc = endpoint(get(path / "inc" ? qs[Int]("x")), jsonResponse[Int])
  *   }
  * }}}
  *
  * You can get a router for them as follows:
  *
  * {{{
  *   object MyRouter extends MyEndpoints with EndpointPlayRouting with JsonEntityPlayRoutingCirce {
  *
  *     val routes = routesFromEndpoints(
  *       inc.implementedBy(x => x + 1)
  *     )
  *
  *   }
  * }}}
  *
  * Then `MyRouter.routes` can be used to define a proper Play router as follows:
  *
  * {{{
  *   val router = play.api.routing.Router.from(MyRouter.routes)
  * }}}
  */
trait EndpointPlayRouting extends EndpointAlg with UrlPlayRouting {

  /**
    * An attempt to extract an `A` from a request headers.
    *
    * Models failure by returning a `Left(result)`. That makes it possible
    * to early return an HTTP response if a header is wrong (e.g. if
    * an authentication information is missing)
    */
  type RequestHeaders[A] = Headers => Either[Result, A]

  /** Always succeeds in extracting no information from the headers */
  lazy val emptyHeaders: RequestHeaders[Unit] = _ => Right(())

  /**
    * An HTTP request.
    *
    * Has an instance of `InvariantFunctor`.
    */
  trait Request[A] {
    /**
      * Extracts a `BodyParser[A]` from an incoming request. That is
      * a way to extract an `A` from an incoming request.
      */
    def decode: RequestExtractor[BodyParser[A]]

    /**
      * Reverse routing.
      * @param a Information carried by the request
      * @return The URL and HTTP verb matching the `a` value.
      */
    def encode(a: A): Call
  }

  implicit lazy val invariantFunctorRequest: InvariantFunctor[Request] =
    new InvariantFunctor[Request] {
      def inmap[A, B](m: Request[A], f1: A => B, f2: B => A): Request[B] =
        new Request[B] {
          def decode: RequestExtractor[BodyParser[B]] =
            functorRequestExtractor.fmap(m.decode, (bodyParser: BodyParser[A]) => bodyParser.map(f1))
          def encode(a: B): Call = m.encode(f2(a))
        }
    }

  /**
    * The URL and HTTP headers of a request.
    */
  trait UrlAndHeaders[A] { parent =>
    /**
      * Attempts to extract an `A` from an incoming request.
      *
      * Two kinds of failures can happen:
      * 1. The incoming request URL does not match `this` definition: nothing
      *    is extracted (the `RequestExtractor` returns `None`) ;
      * 2. The incoming request URL matches `this` definition but the headers
      *    are erroneous: the `RequestExtractor` returns a `Left(result)`.
      */
    def decode: RequestExtractor[Either[Result, A]]

    /**
      * Reverse routing.
      * @param a Information carried by the request URL and headers
      * @return The URL and HTTP verb matching the `a` value.
      */
    def encode(a: A): Call

    /**
      * Promotes `this` to a `Request[B]`.
      *
      * @param toB Function defining how to get a `BodyParser[B]` from the extracted `A`
      * @param toA Function defining how to get back an `A` from the `B`.
      */
    def toRequest[B](toB: A => BodyParser[B])(toA: B => A): Request[B] =
      new Request[B] {
        def decode: RequestExtractor[BodyParser[B]] =
          request =>
            parent.decode(request).map {
              case Left(result) => BodyParser(_ => Accumulator.done(Left(result)))
              case Right(a) => toB(a)
            }
        def encode(b: B): Call = parent.encode(toA(b))
      }
  }

  /** Decodes a request entity */
  type RequestEntity[A] = BodyParser[A]

  private def extractMethod(method: String): RequestExtractor[Unit] =
    request =>
      if (request.method == method) Some(())
      else None

  private def extractMethodUrlAndHeaders[A, B](method: String, url: Url[A], headers: RequestHeaders[B]): UrlAndHeaders[(A, B)] =
    new UrlAndHeaders[(A, B)] {
      val decode: RequestExtractor[Either[Result, (A, B)]] =
        request =>
          extractMethod(method).andKeep(url.decodeUrl).apply(request).map { a =>
            headers(request.headers).right.map((a, _))
          }
      def encode(ab: (A, B)): Call = Call(method, url.encodeUrl(ab._1))
    }

  /**
    * Decodes a request that uses the GET HTTP verb.
    *
    * @param url Request URL
    * @param headers Request headers
    */
  def get[A, B](url: Url[A], headers: RequestHeaders[B])(implicit tupler: Tupler[A, B]): Request[tupler.Out] =
    extractMethodUrlAndHeaders("GET", url, headers)
      .toRequest { case (a, b) =>
        BodyParser(_ => Accumulator.done(Right(tupler.apply(a, b))))
      } { ab =>
        tupler.unapply(ab)
      }

  /**
    * Decodes a request that uses the POST HTTP verb.
    * @param url Request URL
    * @param entity Request entity
    * @param headers Request headers
    */
  def post[A, B, C, AB](url: Url[A], entity: RequestEntity[B], headers: RequestHeaders[C])(implicit tuplerAB: Tupler.Aux[A, B, AB], tuplerABC: Tupler[AB, C]): Request[tuplerABC.Out] =
    extractMethodUrlAndHeaders("POST", url, headers)
      .toRequest {
        case (a, c) => entity.map(b => tuplerABC.apply(tuplerAB.apply(a, b), c))
      } { abc =>
        val (ab, c) = tuplerABC.unapply(abc)
        val (a, _) = tuplerAB.unapply(ab)
        (a, c)
      }


  /**
    * Turns the `A` information into a proper Play `Result`
    */
  type Response[A] = A => Result

  /** A successful HTTP response (status code 200) with no entity */
  lazy val emptyResponse: Response[Unit] = _ => Results.Ok

  /** A successful HTTP response (status code 200) with an HTML entity */
  lazy val htmlResponse: Response[Html] = html => Results.Ok(html)


  /**
    * Concrete representation of an `Endpoint` for routing purpose.
    */
  case class Endpoint[A, B](request: Request[A], response: Response[B]) {
    /** Reverse routing */
    def call(a: A): Call = request.encode(a)

    /**
      * Provides an actual implementation to the endpoint definition, to turn it
      * into something effectively usable by the Play router.
      *
      * @param service Function that turns the information carried by the request into
      *                the information necessary to build the response
      */
    def implementedBy(service: A => B): EndpointWithHandler[A, B] = EndpointWithHandler(this, service andThen Future.successful)

    /**
      * Same as `implementedBy`, but with an async `service`.
      */
    def implementedByAsync(service: A => Future[B]): EndpointWithHandler[A, B] = EndpointWithHandler(this, service)
  }

  /**
    * An endpoint from which we can get a Play request handler.
    */
  case class EndpointWithHandler[A, B](endpoint: Endpoint[A, B], service: A => Future[B]) {
    /**
      * Builds a request `Handler` (a Play `Action`) if the incoming request headers matches
      * the `endpoint` definition.
      */
    def playHandler(header: RequestHeader): Option[Handler] =
      endpoint.request.decode(header)
        .map(a => Action.async(a){ request =>
          service(request.body).map { b =>
            endpoint.response(b)
          }
        })
  }

  def endpoint[A, B](request: Request[A], response: Response[B]): Endpoint[A, B] =
    Endpoint(request, response)


  /**
    * Builds a Play router out of endpoint definitions.
    *
    * {{{
    *   val routes = routesFromEndpoints(
    *     inc.implementedBy(x => x + 1)
    *   )
    * }}}
    */
  def routesFromEndpoints(endpoints: EndpointWithHandler[_, _]*): PartialFunction[RequestHeader, Handler] =
    Function.unlift { request : RequestHeader =>
      def loop(es: Seq[EndpointWithHandler[_, _]]): Option[Handler] =
        es match {
          case e +: es2 => e.playHandler(request).orElse(loop(es2))
          case Nil => None
        }
      loop(endpoints)
    }

}
