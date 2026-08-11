import cats.effect._
import cats.implicits.catsSyntaxTuple4Parallel
import configuration.{Configuration, ConfigurationUtils, FileAndSystemPropertyReader}
import http.HttpClient
import org.slf4j.LoggerFactory

import java.nio.channels.ClosedChannelException
import scala.concurrent.duration.DurationInt

object Server extends IOApp {

  private val logger = LoggerFactory.getLogger(getClass)

  override protected def reportFailure(err: Throwable): IO[Unit] = err match {
    case _: ClosedChannelException => IO.pure(logger.debug("Suppressing ClosedChannelException error", err))
    case _                         => IO.pure(logger.error("Failure caught and handled by IOApp", err))
  }

  private val version = IO {
    val props  = new java.util.Properties()
    val stream = getClass.getClassLoader.getResourceAsStream("version.properties")
    if (stream != null) {
      try props.load(stream)
      finally stream.close()
    }
    props.getProperty("version", "unknown")
  }

  def run(args: List[String]): IO[ExitCode] = {
    val configReader = FileAndSystemPropertyReader

    // The client is acquired once and shared by every sync loop, so connections are pooled and
    // reused instead of being renegotiated on each request.
    HttpClient.resource.use { httpClient =>
      for {
        v      <- version
        _      <- IO(logger.info(s"watchlistarr v$v starting"))
        config <- ConfigurationUtils.create(configReader, httpClient)
        result <- (
          pingTokenSync(config, httpClient),
          plexRssSync(config, httpClient),
          plexTokenDeleteSync(config, httpClient),
          plexFullSync(config, httpClient)
        ).parTupled.as(ExitCode.Success)
      } yield result
    }
  }

  private def pingTokenSync(config: Configuration, httpClient: HttpClient): IO[Unit] =
    for {
      _ <- PingTokenSync.run(config, httpClient)
      _ <- IO.sleep(24.hours)
      _ <- pingTokenSync(config, httpClient)
    } yield ()

  private def plexRssSync(config: Configuration, httpClient: HttpClient): IO[Unit] =
    for {
      _ <- PlexTokenSync.run(config, httpClient, runFullSync = false)
      _ <- IO.sleep(config.refreshInterval)
      _ <- plexRssSync(config, httpClient)
    } yield ()

  private def plexFullSync(config: Configuration, httpClient: HttpClient): IO[Unit] =
    for {
      _ <- PlexTokenSync.run(config, httpClient, runFullSync = true)
      _ <- IO.sleep(19.minutes)
      _ <- plexFullSync(config, httpClient)
    } yield ()

  private def plexTokenDeleteSync(config: Configuration, httpClient: HttpClient): IO[Unit] =
    for {
      _ <- PlexTokenDeleteSync.run(config, httpClient)
      _ <- IO.sleep(config.deleteConfiguration.deleteInterval)
      _ <- plexTokenDeleteSync(config, httpClient)
    } yield ()
}
