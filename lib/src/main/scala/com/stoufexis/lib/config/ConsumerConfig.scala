package com.stoufexis.lib.config

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

case class ConsumerConfig[F[_]: Async, K, V](
  bootstrapServers:  String,
  groupId:           Option[String],
  offsetReset:       AutoOffsetReset,
  keyDeserializer:   Deserializer[F, K],
  valueDeserializer: Deserializer[F, V],
) {
  def makeConsumer(
    topic: String,
    seek:  Seek
  ): Stream[F, KafkaConsumer[F, K, V]] =
    makeConsumer(Left(topic), seek)

  def makeConsumer(
    topicPartition: TopicPartition,
    seek:           Seek
  ): Stream[F, KafkaConsumer[F, K, V]] =
    makeConsumer(Right(NonEmptySet.one(topicPartition)), seek)

  def makeConsumer(
    subscribeTo: Either[String, NonEmptySet[TopicPartition]],
    seek:        Seek
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
            .withAutoOffsetReset(offsetReset)
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
