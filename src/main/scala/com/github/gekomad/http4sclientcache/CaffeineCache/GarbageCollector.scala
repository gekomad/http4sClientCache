package com.github.gekomad.http4sclientcache.CaffeineCache

import cats.effect.Temporal
import cats.syntax.all.*
import cats.syntax.flatMap.*
import java.time.LocalTime

import org.typelevel.log4cats.Logger
import scala.concurrent.duration.FiniteDuration

object GarbageCollector {

  def garbageCollector[F[_], K, V](
    gcTime: FiniteDuration | LocalTime,
    cache: CatsCaffeine[F, K, V],
    minToGC: Long
  )(using F: Temporal[F], logger: Logger[F]): F[Unit] = {
    import cats.effect.Temporal

    import java.time.{LocalTime, ZoneId, Duration as JDuration}
    import scala.concurrent.duration.{FiniteDuration, *}

    def missingTime(target: LocalTime): F[FiniteDuration] =
      for {
        instant <- Temporal[F].realTimeInstant
        now         = instant.atZone(ZoneId.systemDefault()).toLocalTime
        diff        = JDuration.between(now, target)
        resDuration = if (diff.isNegative || diff.isZero) diff.plusDays(1) else diff
      } yield resDuration.toMillis.milliseconds

    (for {
      _ <- gcTime match {
        case v: FiniteDuration => Temporal[F].sleep(v)
        case localTime: LocalTime =>
          missingTime(localTime).flatMap(v =>
            logger.info(
              s"next GC in ${v.toHours} hours and ${v.toMinutes % 60} minutes"
            ) *> Temporal[F]
              .sleep(v)
          )
      }
      _     <- logger.info(s"Garbage Collector start")
      size1 <- cache.size
      _ <-
        if (size1 > minToGC) cache.clean
        else
          logger.info(
            s"Garbage Collector not started, min size to start is $minToGC, current size: $size1"
          ) *> F.pure(())
      size2 <- cache.size
      _     <- logger.info(s"Garbage Collector finish. Initial size: $size1, final: $size2")
    } yield ()).foreverM
  }
}
