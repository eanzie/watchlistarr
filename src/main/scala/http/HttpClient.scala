package http

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Resource}
import com.github.blemale.scaffeine.{AsyncLoadingCache, Scaffeine}
import io.circe.Json
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.client.middleware.FollowRedirect
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.{Header, Headers, Method, Request, Uri}
import org.slf4j.LoggerFactory
import org.typelevel.ci.CIString

import scala.concurrent.duration._

trait HttpClient {
  def httpRequest(
      method: Method,
      url: Uri,
      apiKey: Option[String] = None,
      payload: Option[Json] = None
  ): IO[Either[Throwable, Json]]
}

object HttpClient {

  // http4s only strips Authorization/Cookie/Set-Cookie when a redirect crosses to another
  // host, which would let our Plex token and Arr API keys follow a redirect off-site.
  private val sensitiveHeaders: Set[CIString] =
    Headers.SensitiveHeaders ++ Set(CIString("X-Plex-Token"), CIString("X-Api-Key"))

  /** A single client, shared for the lifetime of the app.
    *
    * Building the client per request gives every call its own connection pool and therefore its own TLS handshake,
    * which burns the compute pool and triggers Cats Effect's starvation warnings during a full sync.
    */
  def resource: Resource[IO, HttpClient] =
    EmberClientBuilder
      .default[IO]
      .build
      .map(FollowRedirect(maxRedirects = 5, sensitiveHeaderFilter = sensitiveHeaders))
      .map(new EmberHttpClient(_))
}

private final class EmberHttpClient(client: Client[IO]) extends HttpClient {

  private val logger = LoggerFactory.getLogger(getClass)

  private val cacheTtl = 5.seconds

  private type CacheKey = (Method, Uri, Option[String], Option[Json])

  // The loader always completes successfully and carries the outcome as an Either. Failing the
  // future instead would let Caffeine relegate every transient HTTP error to a java.util.logging
  // stack trace, outside this app's log configuration.
  private val cache: AsyncLoadingCache[CacheKey, Either[Throwable, Json]] =
    Scaffeine()
      .expireAfterWrite(cacheTtl)
      .maximumSize(1000)
      .buildAsyncFuture { case (method, url, apiKey, payload) =>
        makeHttpRequest(method, url, apiKey, payload).unsafeToFuture()
      }

  private val cacheView = cache.synchronous()

  def httpRequest(
      method: Method,
      url: Uri,
      apiKey: Option[String] = None,
      payload: Option[Json] = None
  ): IO[Either[Throwable, Json]] =
    // Only GET is safe to serve from cache. Caching a POST or DELETE would silently swallow a
    // repeat of the same mutation within the TTL.
    if (method == Method.GET) {
      val key = (method, url, apiKey, payload)
      IO.fromFuture(IO(cache.get(key))).attempt.map(_.flatten).flatMap {
        // A failure must not be replayed for the rest of the TTL, so drop the entry as soon as
        // one is seen rather than relying on Caffeine's own asynchronous eviction, which can
        // land after the next request has already read the cache.
        case failure @ Left(_) => IO(cacheView.invalidate(key)).as(failure)
        case success           => IO.pure(success)
      }
    } else
      makeHttpRequest(method, url, apiKey, payload)

  private def makeHttpRequest(
      method: Method,
      url: Uri,
      apiKey: Option[String],
      payload: Option[Json]
  ): IO[Either[Throwable, Json]] = {
    // Deliberately no Host header. http4s fills one in per hop from the request Uri, but only
    // when the request does not already carry one, and FollowRedirect rewrites that Uri on each
    // hop. Setting Host by hand therefore pins the original host across a cross-host redirect,
    // which breaks the presigned S3 URLs that rss.plex.tv redirects to: S3 signs the Host header,
    // so a stale one comes back as 403 SignatureDoesNotMatch.
    val baseRequest = Request[IO](method = method, uri = url)
      .withHeaders(
        Header.Raw(CIString("Accept"), "application/json"),
        Header.Raw(CIString("Content-Type"), "application/json"),
        Header.Raw(CIString("User-Agent"), "watchlistarr/1.0")
      )
    val requestWithApiKey = apiKey.fold(baseRequest)(key =>
      baseRequest.withHeaders(
        Header.Raw(CIString("X-Api-Key"), key),
        Header.Raw(CIString("X-Plex-Token"), key),
        baseRequest.headers
      )
    )
    val requestWithPayload = payload.fold(requestWithApiKey)(p => requestWithApiKey.withEntity(p))

    logger.debug(s"HTTP Request: ${requestWithPayload.toString()}")

    client.expect[Json](requestWithPayload).attempt.map { response =>
      logger.debug(s"HTTP Response: $response")
      response
    }
  }
}
