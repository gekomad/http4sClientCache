import cats.effect.{IO, Resource}
import com.github.gekomad.http4sclientcache.*
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.Method.GET
import org.http4s.Uri
import org.http4s.circe.*
import org.http4s.implicits.uri

import scala.concurrent.duration.{DurationInt, FiniteDuration}

class TestEmptyBody extends CatsEffectSuite {

  test("Empty body") {

    val httpClientTimeout: FiniteDuration = 60.seconds
    val useProxy: Option[Uri]             = None
    val cacheData: CacheData = CacheData(
      defaultTTL = None,
      maximumSize = None,
      GCinterval = None,
      minToGC = 1000
    )

    given Retry = Retry(maxRetries = 1, delay = 1.second)

    val url = UriAndOpt(uri = uri"https://httpbin.org/status/204", cache = None)

    val resHttpClient = HttpClientProvider
      .httpClientsResource[Json, Json](httpClientTimeout, useProxy, cacheData)
      .onFinalize(IO.println("Drop HttpClient"))

    val res: Resource[IO, IO[Unit]] = resHttpClient.map { httpClient =>
      for {
        _ <- IO.println(s"calling:\t${url.uri}...")

        _ <- httpClient.call(
          url,
          GET,
          None,
          useCache = true
        )(e => IO.println(s"err ${e.getMessage}") *> IO.raiseError(e))

      } yield ()

    }
    res.use(identity)
  }
}
