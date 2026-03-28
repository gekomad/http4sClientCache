package com.github.gekomad.http4sclientcache

import cats.*
import cats.effect.IO
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import retry.*
import retry.ResultHandler.retryOnAllErrors
import retry.RetryDetails.NextStep.*
import retry.RetryPolicies.*

import scala.concurrent.duration.*

final case class Retry(maxRetries: Int, delay: FiniteDuration) {

  private val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

  private def logError(err: Throwable, details: RetryDetails): IO[Unit] =
    details.nextStepIfUnsuccessful match {
      case DelayAndRetry(nextDelay: FiniteDuration) =>
        logger.warn(s"Failed. So far we have retried ${details.retriesSoFar} $err times. $nextDelay")
      case GiveUp =>
        logger.error(s"Giving up after ${details.retriesSoFar} retries $err")
    }

  private val retryPolicy = limitRetries[IO](maxRetries = maxRetries) join constantDelay[IO](delay)
  def retry[A](action: => IO[A]): IO[A] =
    retryingOnErrors(action)(
      policy = retryPolicy,
      errorHandler = retryOnAllErrors(logError)
    )

}
