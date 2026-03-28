<a href="https://typelevel.org/cats/"><img src="https://typelevel.org/cats/img/cats-badge.svg" height="40px" align="right" alt="Cats friendly" /></a>

http4s client with cache and proxy using Caffeine
=========

Require Scala 3 and Java >= 11
-------

```scala
val httpClientTimeout: FiniteDuration = 60.seconds
val useProxy: Option[Uri]             = None // Some(uri"proxy1.com:8080")
val cacheData: CacheData = CacheData(
  defaultTTL = Option(2.seconds),
  maximumSize = Some(1000),
  GCinterval = Some(LocalTime.of(3, 30)),
  minToGC = 1000
)

given Retry = Retry(maxRetries = 1, delay = 1.second)

val url = UriAndOpt(
  uri = uri"https://httpbin.org/delay/5",
  cache = Some(CacheUri(ttl = Some(1.second), ok = List(org.http4s.Status.Ok)))
)
val resHttpClient = HttpClientProvider
  .httpClientsResource[Json, Json](httpClientTimeout, useProxy, cacheData)
  .onFinalize(IO.println("Drop HttpClient"))

val res: Resource[IO, IO[Unit]] = resHttpClient.map { httpClient =>
  def get(count: Int) = for {
    _     <- IO.println(s"calling:\t${url.uri}...")
    start <- IO.monotonic
    res <- httpClient.call(
      url,
      GET,
      None,
      useCache = true
    )(e => IO.println(s"err ${e.getMessage}") *> IO.raiseError(e))
    end <- IO.monotonic
    _   <- IO.println(s"http status:\t${res._2}")
    _   <- IO.println(s"payload size:\t${res._1.spaces2.length}")
    _   <- IO.println(s"from cache:\t${res._3}")
    diff = end - start
    _ <- IO(if (count == 1) assert(!res._3) else assert(res._3))
    _ <- IO(if (count == 1) assert(diff > 5.second) else assert(diff < 5.second))
    _ <- IO.println(s"elapsed: ${diff.toMillis}ms <===========================")
  } yield ()
  get(1) *> get(2)
}
res.use(identity)
```

```
calling:	https://httpbin.org/delay/5...
http status:	200 OK
payload size:	382
from cache:	false
elapsed: 5993ms <===========================

calling:	https://httpbin.org/delay/5...
http status:	200 OK
payload size:	382
from cache:	true
elapsed: 1ms <===========================

```