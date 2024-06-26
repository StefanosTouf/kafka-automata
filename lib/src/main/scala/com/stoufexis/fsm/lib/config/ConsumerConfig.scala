package com.stoufexis.fsm.lib.config

import ConsumerConfig.Seek
import cats.Applicative
import cats.data.NonEmptySet
import cats.effect._
import cats.implicits._
import fs2._
import fs2.kafka._
import fs2.kafka.instances._
import org.apache.kafka.common.TopicPartition

import java.util.UUID

case class ConsumerConfig(
  bootstrapServers: String
) {
  def makeConsumer[F[_]: Async, K: Deserializer[F, *], V: Deserializer[F, *]](
    topic:   String,
    groupId: Option[String],
    seek:    Seek
  ): Stream[F, KafkaConsumer[F, K, V]] =
    makeConsumer(Left(topic), groupId, seek)

  def makeConsumer[F[_]: Async, K: Deserializer[F, *], V: Deserializer[F, *]](
    topicPartition: TopicPartition,
    groupId:        Option[String],
    seek:           Seek
  ): Stream[F, KafkaConsumer[F, K, V]] =
    makeConsumer(Right(NonEmptySet.one(topicPartition)), groupId, seek)

  def makeConsumer[F[_]: Async, K, V](
    subscribeTo: Either[String, NonEmptySet[TopicPartition]],
    groupId:     Option[String],
    seek:        Seek
  )(implicit
    keyDeserializer:   Deserializer[F, K],
    valueDeserializer: Deserializer[F, V]
  ): Stream[F, KafkaConsumer[F, K, V]] =
    for {
      gid: String <-
        Stream.eval {
          groupId.fold {
            Async[F].delay(UUID.randomUUID).map(_.toString)
          } { value =>
            Async[F].pure(value)
          }
        }

      consumer: KafkaConsumer[F, K, V] <-
        KafkaConsumer.stream {
          ConsumerSettings(keyDeserializer, valueDeserializer)
            .withBootstrapServers(bootstrapServers)
            .withGroupId(gid)
            .withAutoOffsetReset(AutoOffsetReset.Earliest)
            .withIsolationLevel(IsolationLevel.ReadCommitted)
        }

      _ <- Stream.eval {
        subscribeTo match {
          case Left(topic)            => consumer.subscribeTo(topic)
          case Right(topicPartitions) => consumer.assign(topicPartitions)
        }
      }

      _ <-
        Stream.eval(Seek(seek, consumer))

    } yield consumer
}

object ConsumerConfig {
  sealed trait Seek
  object Seek {
    case object ToEnd       extends Seek
    case object ToBeginning extends Seek
    case object None        extends Seek

    def apply[F[_]: Applicative, K, V](seek: Seek, consumer: KafkaConsumer[F, K, V]) =
      seek match {
        case ToEnd       => consumer.seekToEnd
        case ToBeginning => consumer.seekToBeginning
        case None        => Applicative[F].unit
      }
  }
}
