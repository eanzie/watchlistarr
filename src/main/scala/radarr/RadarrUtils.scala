package radarr

import cats.data.EitherT
import cats.effect.IO
import configuration.RadarrConfiguration
import http.HttpClient
import io.circe.{Decoder, Json}
import io.circe.generic.auto._
import io.circe.syntax.EncoderOps
import model.{ArrPage, Item}
import org.http4s.{Method, Uri}
import org.slf4j.LoggerFactory

trait RadarrUtils extends RadarrConversions {

  private val logger = LoggerFactory.getLogger(getClass)

  private val pagedPageSize = 1000

  protected def fetchMovies(
      client: HttpClient
  )(apiKey: String, baseUrl: Uri, bypass: Boolean): EitherT[IO, Throwable, Set[Item]] =
    for {
      movies <- getToArr[List[RadarrMovie]](client)(baseUrl, apiKey, "movie")
      exclusions <-
        if (bypass) {
          EitherT.pure[IO, Throwable](List.empty[RadarrMovieExclusion])
        } else {
          getPagedToArr[RadarrMovieExclusion](client)(baseUrl, apiKey, "exclusions")
        }
    } yield (movies.map(toItem) ++ exclusions.map(toItem)).toSet

  protected def addToRadarr(client: HttpClient)(config: RadarrConfiguration)(item: Item): IO[Unit] = {
    val movie = RadarrPost(
      item.title,
      item.getTmdbId.getOrElse(0L),
      config.radarrQualityProfileId,
      config.radarrRootFolder,
      minimumAvailability = config.radarrAvailability,
      tags = config.radarrTagIds.toList
    )

    val result = postToArr[Unit](client)(config.radarrBaseUrl, config.radarrApiKey, "movie")(movie.asJson)
      .fold(
        err => logger.debug(s"Received warning for sending ${item.title} to Radarr: $err"),
        r => r
      )

    result.map { r =>
      logger.info(s"Sent ${item.title} to Radarr")
      r
    }
  }

  /** Walks a `.../paged` endpoint until every record has been collected.
    *
    * Radarr marked the unpaged collection GET `[Obsolete]`, which is what produces "API call made to deprecated
    * endpoint" in its log. The paged replacement defaults to a pageSize of 10, so it has to be given an explicit size
    * and followed to the end - otherwise exclusions past the first page silently look as though they do not exist.
    */
  private def getPagedToArr[T: Decoder](
      client: HttpClient
  )(baseUrl: Uri, apiKey: String, endpoint: String, page: Int = 1): EitherT[IO, Throwable, List[T]] = {
    val url = (baseUrl / "api" / "v3" / endpoint / "paged")
      .withQueryParam("page", page)
      .withQueryParam("pageSize", pagedPageSize)

    for {
      response     <- EitherT(client.httpRequest(Method.GET, url, Some(apiKey)))
      maybeDecoded <- EitherT.pure[IO, Throwable](response.as[ArrPage[T]])
      decoded <- EitherT.fromOption[IO](
        maybeDecoded.toOption,
        new Throwable("Unable to decode paged response from Radarr")
      )
      remaining <-
        if (decoded.records.nonEmpty && page * pagedPageSize < decoded.totalRecords)
          getPagedToArr[T](client)(baseUrl, apiKey, endpoint, page + 1)
        else
          EitherT.pure[IO, Throwable](List.empty[T])
    } yield decoded.records ++ remaining
  }

  private def getToArr[T: Decoder](
      client: HttpClient
  )(baseUrl: Uri, apiKey: String, endpoint: String): EitherT[IO, Throwable, T] =
    for {
      response     <- EitherT(client.httpRequest(Method.GET, baseUrl / "api" / "v3" / endpoint, Some(apiKey)))
      maybeDecoded <- EitherT.pure[IO, Throwable](response.as[T])
      decoded <- EitherT.fromOption[IO](maybeDecoded.toOption, new Throwable("Unable to decode response from Radarr"))
    } yield decoded

  private def postToArr[T: Decoder](
      client: HttpClient
  )(baseUrl: Uri, apiKey: String, endpoint: String)(payload: Json): EitherT[IO, Throwable, T] = {
    logger.info(s"Sending JSON to Radarr: $payload")
    for {
      response <- EitherT(
        client.httpRequest(Method.POST, baseUrl / "api" / "v3" / endpoint, Some(apiKey), Some(payload))
      )
      maybeDecoded <- EitherT.pure[IO, Throwable](response.as[T])
      decoded <- EitherT.fromOption[IO](maybeDecoded.toOption, new Throwable("Unable to decode response from Radarr"))
    } yield decoded
  }
}
