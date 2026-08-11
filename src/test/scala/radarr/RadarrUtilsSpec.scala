package radarr

import cats.effect.IO
import http.HttpClient
import cats.effect.unsafe.implicits.global
import io.circe.parser._
import model.Item
import org.http4s.{Method, Uri}
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.io.Source

class RadarrUtilsSpec extends AnyFlatSpec with Matchers with RadarrUtils with MockFactory {

  "RadarrUtils" should "successfully fetch a list of movies and exclusions from Radarr" in {
    val movieJsonStr      = Source.fromResource("radarr.json").getLines().mkString("\n")
    val exclusionsJsonStr = Source.fromResource("exclusions.json").getLines().mkString("\n")
    val mockClient        = mock[HttpClient]
    (mockClient.httpRequest _)
      .expects(
        Method.GET,
        Uri.unsafeFromString("http://localhost:7878").withPath(Uri.Path.unsafeFromString("/api/v3/movie")),
        Some("radarr-api-key"),
        None
      )
      .returning(IO.pure(parse(movieJsonStr)))
      .once()
    (mockClient.httpRequest _)
      .expects(
        Method.GET,
        Uri
          .unsafeFromString("http://localhost:7878")
          .withPath(Uri.Path.unsafeFromString("/api/v3/exclusions/paged"))
          .withQueryParam("page", 1)
          .withQueryParam("pageSize", 1000),
        Some("radarr-api-key"),
        None
      )
      .returning(IO.pure(parse(exclusionsJsonStr)))
      .once()

    val eitherResult =
      fetchMovies(mockClient)("radarr-api-key", Uri.unsafeFromString("http://localhost:7878"), false).value
        .unsafeRunSync()

    eitherResult shouldBe a[Right[_, _]]
    val result = eitherResult.getOrElse(Set.empty)
    result.size shouldBe 157
    result.find(_.title == "Moonlight") shouldBe Some(
      Item("Moonlight", List("tt4975722", "tmdb://376867", "radarr://32"), "movie")
    )
    result.find(_.title == "Oculus") shouldBe Some(
      Item("Oculus", List("tt2388715", "tmdb://157547", "radarr://21"), "movie")
    )
    // Check that exclusions are added
    result.find(_.title == "Monty Python and the Holy Grail") shouldBe Some(
      Item("Monty Python and the Holy Grail", List("tmdb://762", "radarr://2"), "movie")
    )
  }

  it should "not fail when the list returned is empty" in {
    val mockClient = mock[HttpClient]
    (mockClient.httpRequest _)
      .expects(
        Method.GET,
        Uri.unsafeFromString("http://localhost:7878").withPath(Uri.Path.unsafeFromString("/api/v3/movie")),
        Some("radarr-api-key"),
        None
      )
      .returning(IO.pure(parse("[]")))
      .once()
    (mockClient.httpRequest _)
      .expects(
        Method.GET,
        Uri
          .unsafeFromString("http://localhost:7878")
          .withPath(Uri.Path.unsafeFromString("/api/v3/exclusions/paged"))
          .withQueryParam("page", 1)
          .withQueryParam("pageSize", 1000),
        Some("radarr-api-key"),
        None
      )
      .returning(IO.pure(parse("""{"page":1,"pageSize":1000,"totalRecords":0,"records":[]}""")))
      .once()

    val eitherResult =
      fetchMovies(mockClient)("radarr-api-key", Uri.unsafeFromString("http://localhost:7878"), false).value
        .unsafeRunSync()

    eitherResult shouldBe Right(Set.empty)
  }

  // The paged endpoint caps a response at pageSize records. If the pages are not followed to the
  // end, exclusions beyond the first page silently look as though they were never excluded.
  it should "follow every page of exclusions, not just the first" in {
    val pageSize   = 1000
    val totalCount = pageSize + 3
    val mockClient = mock[HttpClient]

    def exclusionsPage(pageNumber: Int, ids: Seq[Int]): String = {
      val records = ids.map(i => s"""{"tmdbId":$i,"movieTitle":"Movie $i","movieYear":2000,"id":$i}""").mkString(",")
      s"""{"page":$pageNumber,"pageSize":$pageSize,"totalRecords":$totalCount,"records":[$records]}"""
    }

    (mockClient.httpRequest _)
      .expects(
        Method.GET,
        Uri.unsafeFromString("http://localhost:7878").withPath(Uri.Path.unsafeFromString("/api/v3/movie")),
        Some("radarr-api-key"),
        None
      )
      .returning(IO.pure(parse("[]")))
      .once()

    (mockClient.httpRequest _)
      .expects(
        Method.GET,
        Uri
          .unsafeFromString("http://localhost:7878")
          .withPath(Uri.Path.unsafeFromString("/api/v3/exclusions/paged"))
          .withQueryParam("page", 1)
          .withQueryParam("pageSize", pageSize),
        Some("radarr-api-key"),
        None
      )
      .returning(IO.pure(parse(exclusionsPage(1, 1 to pageSize))))
      .once()

    (mockClient.httpRequest _)
      .expects(
        Method.GET,
        Uri
          .unsafeFromString("http://localhost:7878")
          .withPath(Uri.Path.unsafeFromString("/api/v3/exclusions/paged"))
          .withQueryParam("page", 2)
          .withQueryParam("pageSize", pageSize),
        Some("radarr-api-key"),
        None
      )
      .returning(IO.pure(parse(exclusionsPage(2, (pageSize + 1) to totalCount))))
      .once()

    val eitherResult =
      fetchMovies(mockClient)("radarr-api-key", Uri.unsafeFromString("http://localhost:7878"), false).value
        .unsafeRunSync()

    eitherResult shouldBe a[Right[_, _]]
    val result = eitherResult.getOrElse(Set.empty)

    result.size shouldBe totalCount
    // an item from the second page, which a single-page fetch would have missed
    result.find(_.title == s"Movie $totalCount") shouldBe Some(
      Item(s"Movie $totalCount", List(s"tmdb://$totalCount", s"radarr://$totalCount"), "movie", None)
    )
  }
}
