package com.github.gekomad.http4sclientcache

import cats.effect.{IO, *}
import com.github.gekomad.http4sclientcache.CaffeineCache.CatsCaffeine
import com.github.gekomad.http4sclientcache.CaffeineCache.GarbageCollector.garbageCollector
import com.github.gekomad.http4sclientcache.HttpHeader.applicationJson
import fs2.Stream
import io.circe.Json
import org.http4s.*
import org.http4s.Method.*
import org.http4s.client.Client
import org.http4s.client.dsl.io.*
import org.http4s.headers.{Accept, `Content-Type`}
import org.http4s.jdkhttpclient.JdkHttpClient
import org.http4s.multipart.{Boundary, Multipart, Part}
import org.typelevel.ci.CIString
import org.typelevel.log4cats.*
import org.typelevel.log4cats.slf4j.Slf4jFactory

import java.net.http.HttpClient
import java.net.{InetSocketAddress, ProxySelector}
import java.time.{LocalDateTime, LocalTime}
import scala.concurrent.duration.*

final case class CacheUri(ttl: Option[FiniteDuration], ok: List[org.http4s.Status])
final case class UriAndOpt(
  uri: Uri,
  cache: Option[CacheUri],
  basicToken: Option[String] = None,
  headerFields: Option[List[Header.Raw]] = None
)

case class CacheData(
  defaultTTL: Option[FiniteDuration],
  maximumSize: Option[Long],
  GCinterval: Option[FiniteDuration | LocalTime],
  minToGC: Long
)

private object HttpHeader {
  val acceptApplicationJson = Accept(MediaType.application.json)
  val applicationNdJson     = `Content-Type`(MediaType.parse("application/x-ndjson").getOrElse(???))
  val applicationJson       = `Content-Type`(MediaType.application.json)
  val textPlain             = `Content-Type`(MediaType.text.plain)
  val textXml               = `Content-Type`(MediaType.text.xml)

  def nameValue(name: String, value: String): Header.Raw = Header.Raw.apply(CIString(name), value)
  def basicAuth(b: String): Header.Raw                   = Header.Raw.apply(CIString("Authorization"), s"Basic $b")
}

object HttpClientProvider {
  private given LoggerFactory[IO]                   = Slf4jFactory.create[IO]
  private val logger: SelfAwareStructuredLogger[IO] = LoggerFactory[IO].getLogger

  def httpClientsResource[I, O](
    timeout: FiniteDuration,
    useProxy: Option[Uri],
    cacheData: CacheData
  ): Resource[IO, Http4sClient[I, O]] = {
    def proxy(p: Uri): (Uri, Int) = {
      val a = ("""^(.+?):(.+?)$""".r).findFirstMatchIn(p.toString).map(a => (a.group(1), a.group(2))) flatMap {
        case (host, port) =>
          for {
            p <- port.toIntOption
            u <- Uri.fromString(host).toOption
          } yield (u, p)
      }
      a.getOrElse(???)
    }

    val baseClient = HttpClient
      .newBuilder()
      .version(HttpClient.Version.HTTP_2)
      .connectTimeout(java.time.Duration.ofSeconds(timeout.toSeconds))
    val client0: IO[Client[IO]] = useProxy match {
      case Some(p) =>
        val (host, port) = proxy(p)
        IO(baseClient.proxy(ProxySelector.of(new InetSocketAddress(host.toString, port))).build())
          .map(JdkHttpClient(_))
      case None => IO(baseClient.build()).map(JdkHttpClient(_))
    }

    val c = client0
      .map { httpClient =>
        Http4sClient[I, O](httpClient, timeout, cacheData)
      }
    Resource
      .eval(c)
      .onFinalize(logger.info("HttpClient terminated."))
  }
}

private case class Http4sClient[I, O](
  client: Client[IO],
  timeout: FiniteDuration,
  cacheData: CacheData,
  starting: LocalDateTime = LocalDateTime.now()
) {
  private given LoggerFactory[IO]                            = Slf4jFactory.create[IO]
  private implicit val logger: SelfAwareStructuredLogger[IO] = LoggerFactory[IO].getLogger

  private val cache: CatsCaffeine[IO, String, (O, Status)] = {
    import cats.effect.unsafe.implicits.global
    val c = CatsCaffeine[IO, String, (O, Status)](cacheData.defaultTTL, cacheData.maximumSize)
    val _ = cacheData.GCinterval
      .fold(IO(()).start)(gc => garbageCollector(gc, c, cacheData.minToGC).start)
      .unsafeRunAndForget()
    c
  }
  def estimatedSize: IO[Long]   = cache.estimatedSize
  def invalidateCache: IO[Unit] = cache.invalidate

  private val digestLocal = ThreadLocal.withInitial(() => java.security.MessageDigest.getInstance("SHA-256"))

  private def sha256(s: String): String =
    digestLocal
      .get()
      .digest(s.getBytes("UTF-8"))
      .map("%02x".format(_))
      .mkString

  private given EntityEncoder[IO, I] = new EntityEncoder[IO, I] {
    override def toEntity(a: I): Entity[IO] = {
      val bytes = a match {
        case s: String => s.getBytes
        case j: Json   => j.spaces2.getBytes
      }
      Entity.stream(Stream(bytes.toIndexedSeq*), Some(bytes.length))
    }
    override def headers: Headers = Headers.empty
  }
  def call(
    uri: UriAndOpt,
    method: org.http4s.Method,
    body: Option[I] = None,
    cookies: Option[RequestCookie] = None,
    multipart: Option[String] = None,
    useCache: Boolean = false
  )(
    insKO: => Throwable => IO[(O, Status)]
  )(implicit retry: Retry, _ed: EntityDecoder[IO, O]): IO[(O, Status, Boolean)] = {
    def shaRequest: String = {
      val url        = uri.toString
      val method1    = method.toString
      val body1      = body.toString
      val cookies1   = cookies.toString
      val multipart1 = multipart.toString
      val source     = s"$url-$method1-$body1-$cookies1-$multipart1"
      val a          = sha256(source)
      a
    }

    if (!useCache)
      callNoCache(
        uri,
        method,
        body,
        cookies,
        multipart
      )(insKO).map(a => (a._1, a._2, false))
    else
      for {
        _ <- IO(())
        hash = shaRequest
        _         <- logger.debug(s"${uri.uri} probe cache hash: $hash")
        fromCache <- cache.get(hash)
        res <-
          fromCache match {
            case Some(value) =>
              IO(
                (value._1, value._2, true)
              )
            case None =>
              for {
                res <- callNoCache(
                  uri,
                  method,
                  body,
                  cookies,
                  multipart
                )(insKO)
                _ <- uri.cache match {
                  case Some(value) if value.ok.contains(res._2) => cache.upSert(hash, (res._1, res._2), value.ttl)
                  case _                                        => IO(())
                }
              } yield (res._1, res._2, false)
          }
      } yield res
  }

  private def callNoCache(
    uri: UriAndOpt,
    method: org.http4s.Method,
    body: Option[I],
    cookies: Option[RequestCookie],
    multipart: Option[String]
  )(
    insKO: => Throwable => IO[(O, Status)]
  )(implicit retry: Retry, _ed: EntityDecoder[IO, O]): IO[(O, Status)] = {
    val headers = uri.headerFields.map(b => Headers(b.map(a => Header.Raw(a.name, a.value))))
    def addBodyMultipart(name: String)(req: Request[IO]): Request[IO] =
      body match {
        case Some(value) =>
          val mlt =
            Multipart[IO](Vector(Part.formData(name, value.toString)), Boundary("----FormBoundaryEmABDsBKjG7QEqu"))
          req.withEntity(mlt).withHeaders(mlt.headers)
        case None => req
      }

    def addBody(req: Request[IO]): Request[IO] = body.fold(req) { b =>
      req.withEntity(b)
    }

    def addHeaders(req: Request[IO]): Request[IO] =
      headers.fold(req)(f = b => req.withHeaders(Headers(req.headers.headers ::: b.headers)))

    def addCookie(req: Request[IO]): Request[IO] = cookies.fold(req)(b => req.addCookie(b))

    def addBasicAuth(req: Request[IO]): Request[IO] =
      uri.basicToken.fold(req)(b => req.withHeaders(req.headers.put(HttpHeader.basicAuth(b))))

    def addPayloadHeader(req: Request[IO]): Request[IO] =
      if (headers.exists(_.headers.exists(_.name.toString.toLowerCase == "content-type"))) req
      else
        body match {
          case Some(value) =>
            value match {
              case _: Json => req.withHeaders(req.headers.put(applicationJson))
              case _       => req.withHeaders(req.headers.put(HttpHeader.textPlain))
            }
          case None => req.withHeaders(req.headers.put(HttpHeader.textPlain))
        }

    def decorate(req: Request[IO]): Request[IO] =
      multipart match {
        case Some(name) => ((addBodyMultipart(name)(_)) andThen addCookie andThen addBasicAuth)(req)
        case None =>
          (addBody andThen addHeaders andThen addCookie andThen addBasicAuth andThen addPayloadHeader)(req)
      }

    val doRequest: Request[IO] = method match {
      case POST    => decorate(POST(uri.uri))
      case PUT     => decorate(PUT(uri.uri))
      case PATCH   => decorate(PATCH(uri.uri))
      case DELETE  => decorate(DELETE(uri.uri))
      case OPTIONS => decorate(OPTIONS(uri.uri))
      case GET     => decorate(GET(uri.uri))
      case HEAD    => decorate(HEAD(uri.uri))
      case _       => throw new NotImplementedError(s"no method found: $method")
    }

    for {
      res <- retry
        .retry {
          logger.debug(s"call with $retry ($method) $uri headers: (${doRequest.headers.headers.mkString(",")})...") *>
            client.run(doRequest).use(response => response.as[O].map(i => (i, response.status)))
        }
        .handleErrorWith(insKO)
      _ <- logger.debug(s"call ($method) $uri ok")
    } yield (res._1, res._2)

  }
}
