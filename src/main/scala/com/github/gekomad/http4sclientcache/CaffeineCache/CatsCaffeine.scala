package com.github.gekomad.http4sclientcache.CaffeineCache

import cats.effect.kernel.Temporal
import cats.syntax.all.*
import com.github.benmanes.caffeine.cache.{Cache, Caffeine, Expiry}

import org.typelevel.log4cats.Logger
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

case class ElementWithTTL[V](value: V, ttl: FiniteDuration)

class CatsCaffeine[F[_], @specialized(Int, Long) K, @specialized(Int, Long) V](
  val defaultTTL: Option[FiniteDuration],
  private val cache: Cache[K, ElementWithTTL[V]]
)(using F: Temporal[F], logger: Logger[F]) {

  def append[A](key: K, values: List[A], ttl: Option[FiniteDuration] = None)(using ev: V <:< List[A]): F[Unit] =
    for {
      current <- get(key)
      _ <- current match {
        case Some(value) => upSert(key, (value ++ values).distinct.asInstanceOf[V], ttl)
        case None        => upSert(key, values.asInstanceOf[V], ttl)
      }
    } yield ()
 
  def get(key: K): F[Option[V]] = {
    val a =
      F.pure(Option(cache.getIfPresent(key))).flatTap { opt => logger.debug(s"get $key found: ${opt.isDefined}") }
    a.map {
      _.fold(None: Option[V])(a => Some(a.value))
    }
  }

  def upSert(key: K, value: V): F[Unit] = upSert(key, value, None)

  def upSert(key: K, value: V, ttl: Option[FiniteDuration]): F[Unit] =
    logger.debug(s"upSert $key") *> F.pure(
      cache.put(
        key,
        ElementWithTTL(value, ttl.getOrElse(FiniteDuration(Long.MaxValue, NANOSECONDS)))
      )
    )

  def upSert(key: K, value: V, ttl: FiniteDuration): F[Unit] =
    logger.debug(s"upSert $key") *> F.pure(cache.put(key, ElementWithTTL(value, ttl)))

  def upSert(map: Map[K, V], ttl: Option[FiniteDuration] = None): F[Unit] =
    if (map.isEmpty) F.pure(())
    else {
      val a = map.map { a =>
        a._1 -> ElementWithTTL(a._2, ttl.getOrElse(FiniteDuration(Long.MaxValue, NANOSECONDS)))
      }
      logger.debug(s"upSert batch size=${map.size}") *> F.pure(cache.putAll(a.asJava))
    }

  def delete(key: K): F[Unit] = logger.debug(s"delete $key") *> F.pure(cache.invalidate(key))

  def delete(keys: Iterable[K]): F[Unit] = if (keys.isEmpty) F.pure(())
  else logger.debug(s"delete batch size=${keys.size}") *> F.pure(cache.invalidateAll(keys.asJava))

  def invalidate: F[Unit] = logger.debug(s"invalidate") *> F.pure(cache.invalidateAll())

  def clean: F[Unit] = logger.debug(s"manual clean") *> F.pure(cache.cleanUp())

  def size: F[Long] = F.pure(cache.estimatedSize())

  def estimatedSize: F[Long] = F.pure(cache.estimatedSize())

}

object CatsCaffeine {

  import scala.util.chaining.*

  def apply[F[_], K, V](
    defaultTTL: Option[FiniteDuration] = Option(2.hour),
    maximumSize: Option[Long] = Some(1000)
  )(using F: Temporal[F], logger: Logger[F]): CatsCaffeine[F, K, V] = {

    val expiryPolicy = new Expiry[K, ElementWithTTL[V]] {

      override def expireAfterCreate(key: K, item: ElementWithTTL[V], currentTime: Long): Long =
        item.ttl.toNanos

      override def expireAfterUpdate(
        key: K,
        item: ElementWithTTL[V],
        currentTime: Long,
        currentDuration: Long
      ): Long = item.ttl.toNanos

      override def expireAfterRead(
        key: K,
        item: ElementWithTTL[V],
        currentTime: Long,
        currentDuration: Long
      ): Long =
        currentDuration
    }

    val ref: Cache[K, ElementWithTTL[V]] =
      Caffeine
        .newBuilder()
        .tap(b => maximumSize.foreach(b.maximumSize))
        .tap(_.expireAfter(expiryPolicy))
        .build[K, ElementWithTTL[V]]
    new CatsCaffeine[F, K, V](defaultTTL = defaultTTL, cache = ref)
  }
}
