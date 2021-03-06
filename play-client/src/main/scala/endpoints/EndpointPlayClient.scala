package endpoints

import play.api.libs.ws.{WSClient, WSRequest, WSResponse}

import scala.concurrent.{ExecutionContext, Future}

/**
  * An interpreter for [[EndpointAlg]] that builds a client issuing requests using
  * Play’s [[WSClient]] HTTP client.
  * @param wsClient The underlying client to use
  */
class EndpointPlayClient(wsClient: WSClient)(implicit ec: ExecutionContext) extends EndpointAlg with UrlClient {

  /**
    * A function that, given an `A` and a request model, returns an updated request
    * containing additional headers
    */
  type RequestHeaders[A] = (A, WSRequest) => WSRequest

  /** Does not modify the request */
  lazy val emptyHeaders: RequestHeaders[Unit] = (_, wsRequest) => wsRequest

  /**
    * A function that takes an `A` information and eventually returns a `WSResponse`
    */
  type Request[A] = A => Future[WSResponse]

  /**
    * A function that, given an `A` information and a `WSRequest`, eventually returns a `WSResponse`
    */
  type RequestEntity[A] = (A, WSRequest) => Future[WSResponse]

  def get[A, B](url: Url[A], headers: RequestHeaders[B])(implicit tupler: Tupler[A, B]): Request[tupler.Out] =
    (ab: tupler.Out) => {
      val (a, b) = tupler.unapply(ab)
      val wsRequest = wsClient.url(url.encode(a))
      headers(b, wsRequest).get()
    }

  def post[A, B, C, AB](url: Url[A], entity: RequestEntity[B], headers: RequestHeaders[C])(implicit tuplerAB: Tupler.Aux[A, B, AB], tuplerABC: Tupler[AB, C]): Request[tuplerABC.Out] =
    (abc: tuplerABC.Out) => {
      val (ab, c) = tuplerABC.unapply(abc)
      val (a, b) = tuplerAB.unapply(ab)
      val wsRequest = wsClient.url(url.encode(a))
      entity(b, headers(c, wsRequest))
    }

  /**
    * Attempts to decode an `A` from a `WSResponse`.
    */
  type Response[A] = WSResponse => Either[Throwable, A]

  /** Successfully decodes no information from a response */
  val emptyResponse: Response[Unit] = _ => Right(())

  /**
    * A function that, given an `A`, eventually attempts to decode the response.
    *
    * Communication failures are represented by a `Future.failed`, while protocol
    * failures are represented by successful Future containing a `Left(throwable)`.
    */
  type Endpoint[A, B] = A => Future[Either[Throwable, B]]

  def endpoint[A, B](request: Request[A], response: Response[B]) =
    a => request(a).map(response)

}
