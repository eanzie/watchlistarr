package http

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.client.middleware.FollowRedirect
import org.http4s.headers.Location
import org.http4s.{HttpApp, Method, Response, Status, Uri}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.CIString

class HttpClientSpec extends AnyFlatSpec with Matchers {

  private val feed     = Uri.unsafeFromString("https://rss.example/feed")
  private val redirect = Uri.unsafeFromString("https://storage.example/object")

  // Plex redirects its RSS feed to a presigned S3 URL on another host, and S3 signs the Host
  // header. http4s derives Host per hop from the request Uri, but only when the request does not
  // already carry one, so setting Host by hand pins the original host and earns a 403.
  "HttpClient" should "send the redirect target's Host, not the original one" in {
    var hostAtTarget: Option[String] = None
    var reachedTarget                = false

    val app = HttpApp[IO] { request =>
      request.uri.host.map(_.value) match {
        case Some("rss.example") =>
          IO.pure(Response[IO](Status.Found).putHeaders(Location(redirect)))
        case Some("storage.example") =>
          reachedTarget = true
          hostAtTarget = request.headers.get(CIString("Host")).map(_.head.value)
          IO.pure(Response[IO](Status.Ok).withEntity(Json.obj("ok" -> Json.True)))
        case _ =>
          IO.pure(Response[IO](Status.NotFound))
      }
    }

    val client = new EmberHttpClient(FollowRedirect(5)(Client.fromHttpApp(app)))
    val result = client.httpRequest(Method.GET, feed).unsafeRunSync()

    result.isRight shouldBe true
    reachedTarget shouldBe true
    hostAtTarget shouldBe Some("storage.example")
  }

  it should "not leak credentials to a different host across a redirect" in {
    var apiKeyAtTarget: Option[String] = None
    var tokenAtTarget: Option[String]  = None

    val app = HttpApp[IO] { request =>
      request.uri.host.map(_.value) match {
        case Some("rss.example") =>
          IO.pure(Response[IO](Status.Found).putHeaders(Location(redirect)))
        case Some("storage.example") =>
          apiKeyAtTarget = request.headers.get(CIString("X-Api-Key")).map(_.head.value)
          tokenAtTarget = request.headers.get(CIString("X-Plex-Token")).map(_.head.value)
          IO.pure(Response[IO](Status.Ok).withEntity(Json.obj("ok" -> Json.True)))
        case _ =>
          IO.pure(Response[IO](Status.NotFound))
      }
    }

    val guarded = FollowRedirect(
      maxRedirects = 5,
      sensitiveHeaderFilter = Set(CIString("X-Plex-Token"), CIString("X-Api-Key"))
    )(Client.fromHttpApp(app))

    val result = new EmberHttpClient(guarded)
      .httpRequest(Method.GET, feed, apiKey = Some("super-secret"))
      .unsafeRunSync()

    result.isRight shouldBe true
    apiKeyAtTarget shouldBe None
    tokenAtTarget shouldBe None
  }

  it should "not serve a POST from cache" in {
    var calls = 0

    val app = HttpApp[IO] { _ =>
      calls += 1
      IO.pure(Response[IO](Status.Ok).withEntity(Json.obj("ok" -> Json.True)))
    }

    val client  = new EmberHttpClient(Client.fromHttpApp(app))
    val payload = Some(Json.obj("a" -> Json.fromInt(1)))

    client.httpRequest(Method.POST, feed, payload = payload).unsafeRunSync()
    client.httpRequest(Method.POST, feed, payload = payload).unsafeRunSync()

    calls shouldBe 2
  }

  it should "serve a repeated GET from cache" in {
    var calls = 0

    val app = HttpApp[IO] { _ =>
      calls += 1
      IO.pure(Response[IO](Status.Ok).withEntity(Json.obj("ok" -> Json.True)))
    }

    val client = new EmberHttpClient(Client.fromHttpApp(app))

    client.httpRequest(Method.GET, feed).unsafeRunSync()
    client.httpRequest(Method.GET, feed).unsafeRunSync()

    calls shouldBe 1
  }

  it should "not cache a failure" in {
    var calls = 0

    val app = HttpApp[IO] { _ =>
      calls += 1
      IO.pure(Response[IO](Status.InternalServerError))
    }

    val client = new EmberHttpClient(Client.fromHttpApp(app))

    client.httpRequest(Method.GET, feed).unsafeRunSync().isLeft shouldBe true
    client.httpRequest(Method.GET, feed).unsafeRunSync().isLeft shouldBe true

    calls shouldBe 2
  }
}
