package spinoco.fs2.kafka

import java.nio.channels.AsynchronousChannelGroup
import java.time.LocalDateTime
import java.util.Date

import cats.effect.Effect
import cats.kernel.Eq
import cats.syntax.all._
import fs2._
import scodec.bits.ByteVector
import shapeless.{Typeable, tag}
import shapeless.tag._
import spinoco.fs2.kafka.KafkaClient.impl.PartitionPublishConnection
import spinoco.fs2.kafka.failure._
import spinoco.fs2.kafka.network.{BrokerAddress, BrokerConnection}
import spinoco.protocol.kafka.Message.SingleMessage
import spinoco.protocol.kafka.Request._
import spinoco.protocol.kafka.{ProtocolVersion, Request, _}
import spinoco.protocol.kafka.Response._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
/**
  * Client that binds to kafka broker. Usually application need only one client.
  *
  * Client lives until the emitted process is interrupted, or fails.
  *
  */
sealed trait KafkaClient[F[_]] {


  /**
    * Subscribes to specified topic to receive messages published to that topic.
    *
    * Essentially this acts sort of unix `tail` command.
    *
    *
    * Note that user can fine-tune reads from topic by specifying `minChunkByteSize`, `maxChunkByteSize` and `maxWaitTime` parameters
    * to optimize chunking and flow control of reads from Kafka. Default values provide polling each 1 minute whenever at least one message is available.
    *
    * User can by fine-tuning the maxWaitTime and `leaderFailureMaxAttempts` recovery in case of leadership changes in kafka cluster.
    *
    * For example, when leader fails, the stream will stop for about `leaderFailureTimeout` and then tries to continue where the last fetch ended.
    * However wehn there are leaderFailureMaxAttempts successive failures, then the stream will fail.
    *
    * Setting `leaderFailureTimeout` to 0 and `leaderFailureMaxAttempts` to 0 will cause resulting stream to fail immediatelly when any failure occurs.
    *
    *
    * @param topicId          Name of the topic to subscribe to
    * @param partition        Partition to subscribe to
    * @param offset           Offset of the topic to start to read from. First received message may have offset larger
    *                         than supplied offset only if the oldest message has offset higher than supplied offset.
    *                         Otherwise this will always return first message with this offset. -1 specified start from tail (new message arriving to topic)
    * @param prefetch         When true, the implementation will prefetch next chunk of messages from kafka while processing last chunk of messages.
    * @param minChunkByteSize Min size of bytes to read from kafka in single attempt. That number of bytes must be available, in order for read to succeed.
    * @param maxChunkByteSize Max number of bytes to include in reply. Should be always > than max siz of single message including key.
    * @param maxWaitTime      Maximum time to wait before reply, even when `minChunkByteSize` is not satisfied.
    * @param leaderFailureTimeout When fetch from Kafka leader fails, this will try to recover connection every this period up to `leaderFailureMaxAttempts` attempt count is exhausted
    * @param leaderFailureMaxAttempts  Maximum attempts to recover from leader failure, then this will fail.
    * @return
    */
  def subscribe(
    topicId: String @@ TopicName
    , partition: Int @@ PartitionId
    , offset: Long @@ Offset
    , prefetch: Boolean = true
    , minChunkByteSize: Int = 1
    , maxChunkByteSize: Int = 1024 * 1024
    , maxWaitTime: FiniteDuration = 1.minute
    , leaderFailureTimeout: FiniteDuration = 3.seconds
    , leaderFailureMaxAttempts: Int = 20
  ): Stream[F, TopicMessage]


  /**
    * Queries offsets for given topic and partition.
    * Returns offset of first message kept (head) and offset of next message that will arrive to topic.
    * When numbers are equal, then the topic does not include any messages at all.
    *
    * @param topicId      Id of the topic
    * @param partition    Id of the partition
    * @return
    */
  def offsetRangeFor(
     topicId: String @@ TopicName
     , partition: Int @@ PartitionId
   ): F[(Long @@ Offset, Long @@ Offset)]

  /**
    * Publishes single message to the supplied topic.
    * Returns None, if the message was not published due topic/partition not existent or
    * Some(offset) of published message.
    *
    * When `F` finishes its evaluation, message is guaranteed to be seen by the ensemble.
    *
    * @param topicId            Topic to publish to
    * @param partition          Partition to publish to
    * @param key                Key of the message
    * @param message            Message itself
    * @param requireQuorum      If true, this requires quorum of ISR to commit message before leader will reply.
    *                           If false, only leader is required to confirm this publish request.
    * @param serverAckTimeout   Timeout server waits for replicas to ack the request. If the publish request won't be acked by
    *                           server in this time, then the request fails to be published.
    * @return
    */
  def publish1(
  topicId           : String @@ TopicName
  , partition       : Int @@ PartitionId
  , key             : ByteVector
  , message         : ByteVector
  , requireQuorum   : Boolean
  , serverAckTimeout: FiniteDuration
  ): F[Long] = publishN(topicId, partition, requireQuorum, serverAckTimeout, None)(Chunk.singleton((key, message)))

  /**
    * Like `publish` except this won't wait for the confirmation that message was published (fire'n forget).
    *
    * Note that this does not guarantee that message was even sent to server. It will get queued and will
    * be delivered to server within earliest opportunity (once server will be ready to accept it).
    *
    */
  def publishUnsafe1(
    topicId: String @@ TopicName
    , partition: Int @@ PartitionId
    , key: ByteVector
    , message: ByteVector
  ): F[Unit] = publishUnsafeN(topicId, partition, None)(Chunk.singleton((key, message)))

  /**
    * Publishes Chunk of messages to the ensemble. The messages are published as a whole batch, so when this
    * terminates, all messages are guaranteed to be processed by kafka server.
    *
    * Returns offset of very first message published.
    *
    * @param topicId            Id of the topic to publish to.
    * @param partition          Partition to publish to.
    * @param compress           When defined, messages will be compressed by supplied algorithm.
    * @param serverAckTimeout   Defines how long to wait for server to confirm that messages were published.
    *                           Note that this will fail the resulting task if timeout is exceeded, but that won't guarantee that
    *                           messages were not published.
    * @param messages           Chunk of messages to publish. First is id of the topic, second is partition, then key and message itself.
    *                           Additionally `A` may be passed to pair the offset of the message in resulting chunk.
    * @param requireQuorum      If true, this requires quorum of ISR to commit message before leader will reply.
    *                           If false, only leader is required to confirm this publish request.
    *
    * @return
    */
  def publishN(
    topicId: String @@ TopicName
    , partition: Int @@ PartitionId
    , requireQuorum: Boolean
    , serverAckTimeout: FiniteDuration
    , compress: Option[Compression.Value]
  )(messages: Chunk[(ByteVector, ByteVector)]): F[Long]

  /**
    * Like `publishN` except this won't await for messages to be confirmed to be published successfully.
    *
    * Note that this does not guarantee that message was even sent to server. It will get queued and will
    * be delivered to server within earliest opportunity (once server will be ready to accept it).
    *
    */
  def publishUnsafeN(
    topic: String @@ TopicName
    , partition: Int @@ PartitionId
    , compress: Option[Compression.Value]
  )(messages: Chunk[(ByteVector, ByteVector)]): F[Unit]


  /**
    * Creates discrete signal of leaders that is queried from periodical query of metadata from brokers.
    *
    * While this stream is consumed, this will keep connection with very first broker that have answered request for metadata succesfully.
    *
    * If there is no broker available to server metadata request (all brokers was failing recently w/o providing valid response), this will fail as NoBrokerAvailable.
    *
    * If the broker from which metadata are queried will fail, this will try next broker in supplied seed.
    *
    * @param delay        Delay to refresh new metadata from last known good broker
    */
  def leaders(delay: FiniteDuration): Stream[F, Map[(String @@ TopicName, Int @@ PartitionId), BrokerAddress]]

  /**
    * Like `leaders` but queries only for supplied topics
    */
  def leaderFor(delay: FiniteDuration)(topic: (String @@ TopicName), topics: (String @@ TopicName) *): Stream[F, Map[(String @@ TopicName, Int @@ PartitionId), BrokerAddress]]


}


object KafkaClient {

  /**
    *
    * @param ensemble                 Ensemble to connect to.  Must not be empty.
    * @param protocol                 Protocol that will be used for requests. This shall be lowest common protocol supported by all brokers.
    * @param clientName               Name of the client. Name is suffixed for different type of connections to broker:
    *                                   - initial-meta-rq : Initial connection to query all available brokers
    *                                   - control : Control connection where publish requests and metadata requests are sent to
    *                                   - fetch: Connection where fetch requests are sent to.
    * @param brokerWriteTimeout       Timeout to complete single write (tcp) operation to broker before failing it.
    * @param queryOffsetTimeout       Timeout to query any partition offset.
    * @param brokerReadMaxChunkSize   Max size of chunk that is read in single tcp operation from broker
    * @param getLeaderDelay           How often re-query for leader if the leader is not known. Applies only for publish conmections.
    * @param brokerControlQueueBound  Max number of unprocessed messages to keep for broker, before stopping accepting new messages for broker.
    *
    * @see [[spinoco.fs2.kafka.client]]
    */
  def apply[F[_]](
    ensemble: Set[BrokerAddress]
    , protocol: ProtocolVersion.Value
    , clientName: String
    , getNow: => LocalDateTime = LocalDateTime.now()
    , brokerWriteTimeout: Option[FiniteDuration] = Some(10.seconds)
    , queryOffsetTimeout: FiniteDuration = 10.seconds
    , brokerReadMaxChunkSize: Int = 256 * 1024
    , getLeaderDelay: FiniteDuration = 3.seconds
    , brokerControlQueueBound: Int = 10 * 1000
  )(implicit AG: AsynchronousChannelGroup, EC: ExecutionContext, F: Effect[F], S: Scheduler, Logger: Logger[F]): Stream[F,KafkaClient[F]] = {

    def brokerConnection(addr: BrokerAddress):Pipe[F,RequestMessage,ResponseMessage] = s =>
      Stream.eval(addr.toInetSocketAddress).flatMap { inetSocketAddress =>
        s through BrokerConnection(inetSocketAddress, brokerWriteTimeout, brokerReadMaxChunkSize)
      }

    val fetchMeta = impl.requestReplyBroker[F, Request.MetadataRequest, Response.MetadataResponse](brokerConnection, protocol, s"$clientName-meta-rq") _

    def publishConnection(topicId: String @@ TopicName, partitionId: Int @@ PartitionId): F[PartitionPublishConnection[F]] = {
      impl.publishLeaderConnection(
        connection = brokerConnection
        , protocol = protocol
        , clientId = s"$clientName-produce"
        , getLeaderFor = impl.leaderFor(fetchMeta, ensemble.toSeq)
        , getLeaderDelay = getLeaderDelay
        , topicId = topicId
        , partition = partitionId
      )
    }


    Stream.bracket(impl.mkClient(
      ensemble = ensemble
      , publishConnection = publishConnection
      , fetchMetadata = fetchMeta
      , fetchConnection = impl.fetchBrokerConnection(brokerConnection, protocol, s"$clientName-fetch")
      , offsetConnection =  impl.offsetConnection(brokerConnection, protocol, s"$clientName-offset")
      , metaRequestConnection = impl.metadataConnection(brokerConnection, protocol, s"$clientName-meta")
      , queryOffsetTimeout = queryOffsetTimeout
      , protocol = protocol
    ))(
      use = { case (client, _) => Stream.emit(client) }
      , release = { case (_, shutdown) => shutdown }
    )
  }



  protected[kafka] object impl {

    sealed trait PartitionPublishConnection[F[_]] {
      def run: F[Unit]
      def shutdown: F[Unit]
      def publish(data: Vector[Message], timeout: FiniteDuration, acks: RequiredAcks.Value): F[Option[(Long @@ Offset, Option[Date])]]
    }

    sealed trait Publisher[F[_]] {
      def shutdown: F[Unit]
      def publish(topic: String @@ TopicName, partition: Int @@ PartitionId, data: Vector[Message], timeout: FiniteDuration, acks: RequiredAcks.Value): F[Option[(Long @@ Offset, Option[Date])]]
    }


    /**
      * Creates a client and F that cleans up lients resources.
      * @param ensemble       Initial kafka clients to connect to
      * @param fetchMetadata  A function fo fetch metadata from client specified provided address and signal of state.
      * @return
      */
    def mkClient[F[_]](
      ensemble: Set[BrokerAddress]
      , publishConnection: (String @@ TopicName, Int @@ PartitionId) => F[PartitionPublishConnection[F]]
      , fetchMetadata: (BrokerAddress, MetadataRequest) => F[MetadataResponse]
      , fetchConnection : BrokerAddress => Pipe[F, FetchRequest, (FetchRequest, FetchResponse)]
      , offsetConnection : BrokerAddress => Pipe[F, OffsetsRequest, OffsetResponse]
      , metaRequestConnection: BrokerAddress => Pipe[F, MetadataRequest, MetadataResponse]
      , queryOffsetTimeout: FiniteDuration
      , protocol: ProtocolVersion.Value
    )(implicit F: Effect[F], L: Logger[F], S: Scheduler, ec: ExecutionContext): F[(KafkaClient[F], F[Unit])] =  {
      mkPublishers(publishConnection) map { publisher =>

        val queryOffsetRange = impl.queryOffsetRange(impl.leaderFor(fetchMetadata, ensemble.toSeq), offsetConnection, queryOffsetTimeout) _

        def preparePublishMessages(messages: Chunk[(ByteVector, ByteVector)], compress: Option[Compression.Value]) = {
          val singleMessages =  messages.map { case (k, v) => Message.SingleMessage(0, MessageVersion.V0, None, k , v) }
          compress match {
            case None => singleMessages.toVector
            case Some(compression) => Vector(Message.CompressedMessages(0, MessageVersion.V0, compression, None, singleMessages.toVector))
          }
        }

        val NoResponseTimeout = 10.seconds

        val client = new KafkaClient[F] {

          def subscribe(
             topicId: @@[String, TopicName]
             , partition: @@[Int, PartitionId]
             , offset: @@[Long, Offset]
             , prefetch: Boolean
             , minChunkByteSize: Int
             , maxChunkByteSize: Int
             , maxWaitTime: FiniteDuration
             , leaderFailureTimeout: FiniteDuration
             , leaderFailureMaxAttempts: Int
           ): Stream[F, TopicMessage] =
            subscribePartition[F](
              topicId = topicId
              , partition = partition
              , firstOffset = offset
              , prefetch = prefetch
              , minChunkByteSize = minChunkByteSize
              , maxChunkByteSize = maxChunkByteSize
              , maxWaitTime = maxWaitTime
              , protocol = protocol
              , fetchConnection = fetchConnection
              , getLeader = impl.leaderFor(fetchMetadata, ensemble.toSeq)
              , queryOffsetRange = queryOffsetRange
              , leaderFailureTimeout = leaderFailureTimeout
              , leaderFailureMaxAttempts = leaderFailureMaxAttempts
            )

          def offsetRangeFor(
            topicId: @@[String, TopicName]
            , partition: @@[Int, PartitionId]
          ): F[(Long @@ Offset, Long @@ Offset)] =
            queryOffsetRange(topicId, partition)

          def publishN(
            topicId: String @@ TopicName
            , partition: Int @@ PartitionId
            , requireQuorum: Boolean
            , serverAckTimeout: FiniteDuration
            , compress: Option[Compression.Value]
          )(messages: Chunk[(ByteVector, ByteVector)]): F[Long] = {

            val toPublish = preparePublishMessages(messages, compress)
            val requiredAcks = if (requireQuorum) RequiredAcks.Quorum else RequiredAcks.LocalOnly
            publisher.publish(topicId, partition, toPublish, serverAckTimeout, requiredAcks) flatMap {
              case None => F.raiseError(new Throwable(s"Successfully published to $topicId, $partition, but no result available?"))
              case Some((o, _)) => F.pure(o)
            }
          }

          def publishUnsafeN(
            topicId: @@[String, TopicName]
            , partition: @@[Int, PartitionId]
            , compress: Option[Compression.Value]
          )(messages: Chunk[(ByteVector, ByteVector)]): F[Unit] = {
            val toPublish = preparePublishMessages(messages, compress)
            publisher.publish(topicId, partition, toPublish, NoResponseTimeout, RequiredAcks.NoResponse) as (())
          }

          def leaders(delay: FiniteDuration): Stream[F, Map[(@@[String, TopicName], @@[Int, PartitionId]), BrokerAddress]] =
            impl.leadersDiscrete(
              metaRequestConnection = metaRequestConnection
              , seed = ensemble.toSeq
              , delay = delay
              , topics = Vector.empty
            )


          def leaderFor(delay: FiniteDuration)(topic: @@[String, TopicName], topics: @@[String, TopicName]*): Stream[F, Map[(@@[String, TopicName], @@[Int, PartitionId]), BrokerAddress]] =
            impl.leadersDiscrete(
              metaRequestConnection = metaRequestConnection
              , seed = ensemble.toSeq
              , delay = delay
              , topics = Vector(topic) ++ topics
            )

        }

        client -> publisher.shutdown
      }
    }




    /**
      * Queries all supplied seeds for first leader and then returns that leader. Returns None if no seed replied with leader for that partition
      * @param requestMeta     A function that requests signle metadata
      * @param seed            A seed of brokers
      * @param topicId         Id of topic
      * @param partition       Id of partition
      * @tparam F
      * @return
      */
    def leaderFor[F[_]](
      requestMeta: (BrokerAddress, MetadataRequest) => F[MetadataResponse]
      , seed: Seq[BrokerAddress]
    )(topicId: String @@ TopicName, partition: Int @@ PartitionId)(implicit F: Effect[F]) :F[Option[BrokerAddress]] = {
      Stream.emits(seed)
      .evalMap { address => requestMeta(address, MetadataRequest(Vector(topicId))).attempt  }
      .collect { case Right(response) => response }
      .map { resp =>
        resp.topics.find(_.name == topicId) flatMap { _.partitions.find( _.id == partition)} flatMap {
          _.leader flatMap { leaderId => resp.brokers.find { _.nodeId == leaderId } map { b => BrokerAddress(b.host, b.port) } }
        }
      }
      .collectFirst { case Some(broker) => broker }
      .compile
      .last
    }






    val consumerBrokerId = tag[Broker](-1)


    /**
      * Augments connection to broker to FetchRequest/FetchResponse pattern.
      *
      * Apart of supplying fetch fith proper details, this echoes original request with every fetch
      *
      * @param brokerConnection  Connection to broker
      * @param version           protocol version
      * @param clientId          Id of client
      * @param address           Address of broker.
      */
    def fetchBrokerConnection[F[_]](
     brokerConnection : BrokerAddress => Pipe[F, RequestMessage, ResponseMessage]
     , version: ProtocolVersion.Value
     , clientId: String
    )(address: BrokerAddress)(implicit F: Effect[F], ec: ExecutionContext): Pipe[F, FetchRequest, (FetchRequest, FetchResponse)] = { s =>
      Stream.eval(async.signalOf(Map[Int, FetchRequest]())) flatMap { openRequestSignal =>
        (s.zip(indexer) evalMap { case (request, idx) =>
          openRequestSignal.modify(_ + (idx -> request)) as RequestMessage(version, idx, clientId, request)
        } through brokerConnection(address)) evalMap[(FetchRequest, FetchResponse)] { resp => resp.response match {
          case fetch: FetchResponse =>
            openRequestSignal.get map { _.get(resp.correlationId) } flatMap {
              case Some(req) => openRequestSignal.modify(_ - resp.correlationId) as ((req, fetch))
              case None => F.raiseError(new Throwable(s"Invalid response to fetch request, request not available: $resp"))
            }
          case _ =>
            F.raiseError(new Throwable(s"Invalid response to fetch request: $resp"))
        }}
      }
    }

    private def indexer[F[_]]: Stream[F, Int] = Stream.range(0, Int.MaxValue).covary[F].repeat


    /**
      * Creates connection that allows to submit offset Requests.
      */
    def offsetConnection[F[_]](
      brokerConnection : BrokerAddress => Pipe[F, RequestMessage, ResponseMessage]
      , version: ProtocolVersion.Value
      , clientId: String
    )(address: BrokerAddress)(implicit F: Effect[F]): Pipe[F, OffsetsRequest, OffsetResponse] = { s =>
      (s.zip(indexer) map { case (request, idx) =>
        RequestMessage(version, idx, clientId, request)
      } through brokerConnection(address)) flatMap { resp => resp.response match {
        case offset: OffsetResponse => Stream.emit(offset)
        case _ => Stream.raiseError(UnexpectedResponse(address, resp))
      }}
    }


    /**
      * Creates connection that allows to submit offset Requests.
      */
    def metadataConnection[F[_]](
      brokerConnection : BrokerAddress => Pipe[F, RequestMessage, ResponseMessage]
      , version: ProtocolVersion.Value
      , clientId: String
    )(address: BrokerAddress)(implicit F: Effect[F], ec: ExecutionContext): Pipe[F, MetadataRequest, MetadataResponse] = { s =>
      Stream.eval(async.refOf[F, Option[MetadataRequest]](None)) flatMap { requestRef =>
      (s.evalMap(mrq => requestRef.modify(_ => Some(mrq)) as mrq).zip(indexer) map { case (request, idx) =>
        RequestMessage(version, idx, clientId, request)
      } through brokerConnection(address)) flatMap { resp => resp.response match {
        case meta: MetadataResponse => Stream.emit(meta)
        case other =>
          Stream.eval(requestRef.get) flatMap {
            case Some(request) => Stream.raiseError(InvalidBrokerResponse(address, "MetadataResponse", request, Some(other)))
            case None => Stream.raiseError(UnexpectedResponse(address, resp))
          }
      }}}
    }


    /**
      * Subscribes to given partition and topic starting offet supplied.
      * Each subscription creates single connection to isr.
      *
      *
      * @param topicId        Id of the topic
      * @param partition      Partition id
      * @param firstOffset    Offset from where to start (including this one). -1 designated start with very first message published (tail)
      * @param getLeader      Function to query for available leader
      * @param queryOffsetRange Queries range of offset kept for given topic. First is head (oldest message offset) second is tail (offset of the message not yet in topic)
      * @return
      */
    def subscribePartition[F[_]](
      topicId           : String @@ TopicName
      , partition       : Int @@ PartitionId
      , firstOffset     : Long @@ Offset
      , prefetch        : Boolean
      , minChunkByteSize: Int
      , maxChunkByteSize: Int
      , maxWaitTime     : FiniteDuration
      , protocol        : ProtocolVersion.Value
      , fetchConnection : BrokerAddress => Pipe[F, FetchRequest, (FetchRequest, FetchResponse)]
      , getLeader       : (String @@ TopicName, Int @@ PartitionId) => F[Option[BrokerAddress]]
      , queryOffsetRange : (String @@ TopicName, Int @@ PartitionId) => F[(Long @@ Offset, Long @@ Offset)]
      , leaderFailureTimeout: FiniteDuration
      , leaderFailureMaxAttempts: Int
    )(implicit F: Effect[F], S: Scheduler, L: Logger[F], ec: ExecutionContext): Stream[F, TopicMessage] = {

      Stream.eval(async.refOf((firstOffset, 0))) flatMap { startFromRef =>
        def fetchFromBroker(broker: BrokerAddress): Stream[F, TopicMessage] = {
          def tryRecover(rsn: Throwable): Stream[F, TopicMessage] = {
            L.error2(s"Leader $broker failed fetch $topicId[$partition]", rsn ) >>
            Stream.eval(startFromRef.get map { _._2 }) flatMap { failures =>
              if (failures >= leaderFailureMaxAttempts) Stream.raiseError(rsn)
              else {
                Stream.eval(startFromRef.modify { case (start, failures) => (start, failures + 1) }) >>
                S.sleep(leaderFailureTimeout) >>
                Stream.eval(getLeader(topicId, partition)) flatMap {
                  case None => tryRecover(LeaderNotAvailable(topicId, partition))
                  case Some(leader) => fetchFromBroker(leader)
                }

              }
            }
          }

          Stream.eval(async.unboundedQueue[F, FetchRequest]) flatMap { requestQueue =>
            def requestNextChunk: F[Long @@ Offset] = {
              startFromRef.get map { _._1 } flatMap { startFrom =>
                requestQueue.enqueue1(
                  FetchRequest(consumerBrokerId, maxWaitTime, minChunkByteSize, None, Vector((topicId, Vector((partition, startFrom, maxChunkByteSize)))))
                ) as startFrom
              }
            }

            (Stream.eval(requestNextChunk) flatMap { thisChunkStart =>
            (requestQueue.dequeue through fetchConnection (broker)) flatMap { case (request, fetch) =>
              fetch.data.find(_._1 == topicId).flatMap(_._2.find(_.partitionId == partition)) match {
                case None =>
                  Stream.raiseError(InvalidBrokerResponse(broker, "FetchResponse", request, Some(fetch)))

                case Some(result) =>
                  result.error match {
                    case Some(error) =>
                      Stream.raiseError(BrokerReportedFailure(broker, request, error))

                    case None =>
                      val messages = messagesFromResult(protocol, result)

                      val updateLastKnown = messages.lastOption.map(m => m.offset) match {
                        case None => Stream.empty.covary[F] // No messages emitted, just go on
                        case Some(lastOffset) => Stream.eval_(startFromRef.modify { _ => (offset(lastOffset + 1), 0) })
                      }

                      val removeHead = messages.dropWhile(_.offset < thisChunkStart)

                      updateLastKnown ++ {
                        if (prefetch) Stream.eval_(requestNextChunk) ++ Stream.emits(removeHead)
                        else Stream.emits(removeHead) ++ Stream.eval_(requestNextChunk)
                      }
                  }
              }
            }}) ++ {
              // in normal situations this append shall never be consulted. But the broker may close connection from its side
              // and in that case we need to start querying from the last unfinished request or eventually continue from the
              // as such we fail there and OnError shall handle failure of early termination from broker
              Stream.raiseError(new Throwable(s"Leader closed connection early: $broker ($topicId, $partition)"))
            }

          } handleErrorWith  {
            case err: LeaderNotAvailable => tryRecover(err)

            case err: BrokerReportedFailure => err.failure match {
              case ErrorType.OFFSET_OUT_OF_RANGE =>
                Stream.eval(queryOffsetRange(topicId, partition)).attempt flatMap {
                  case Right((min, max)) =>
                    Stream.eval(startFromRef.get) flatMap { case (startFrom, _) =>
                      if (startFrom < min) Stream.eval(startFromRef.modify(_ => (min, 0))) >> fetchFromBroker(broker)
                      else if (startFrom > max) Stream.eval(startFromRef.modify(_ => (max, 0))) >> fetchFromBroker(broker)
                      else Stream.raiseError(new Throwable(s"Offset supplied is in acceptable range, but still not valid: $startFrom ($min, $max)", err))
                    }

                  case Left(err) => tryRecover(err)
                }

              case other => tryRecover(err)
            }

            case other => tryRecover(other)
          }
        }

        def start: Stream[F, TopicMessage] =
          Stream.eval(getLeader(topicId, partition)) flatMap {
            case Some(broker) => fetchFromBroker(broker)
            case None =>
              // leader unavailable
              Stream.eval(startFromRef.modify { case (o, fail) => (o, fail + 1 ) }) flatMap { c =>
                if (c.now._2 > leaderFailureMaxAttempts) Stream.raiseError(NoBrokerAvailable)
                else S.sleep(leaderFailureTimeout) >> start
              }
          }

        start
      }

    }


    /**
      * Because result of fetch can retrieve messages in compressed and nested forms,
      * This decomposes result to simple vector by traversing through the nested message results.
      *
      * @param result  Result from teh fetch
      * @return
      */
    def messagesFromResult(protocol: ProtocolVersion.Value, result: Response.PartitionFetchResult): Vector[TopicMessage] = {

      // Extract compressed messages. No nested compressed messages support
      def extractCompressed(m: Vector[Message], lastOffset: Long): Vector[SingleMessage] = {
        protocol match {
          case ProtocolVersion.Kafka_0_8 |
               ProtocolVersion.Kafka_0_9 =>
            m.collect { case sm: SingleMessage => sm }

          case ProtocolVersion.Kafka_0_10 |
               ProtocolVersion.Kafka_0_10_1 |
               ProtocolVersion.Kafka_0_10_2 =>
            val first = lastOffset - m.size + 1
            m.collect { case sm: SingleMessage => sm.copy(offset = offset(sm.offset + first)) }
        }

      }

      def toTopicMessage(message: SingleMessage): TopicMessage =
        TopicMessage(offset(message.offset), message.key, message.value, result.highWMOffset)

      result.messages flatMap {
        case message: Message.SingleMessage => Vector(toTopicMessage(message))
        case messages: Message.CompressedMessages =>  extractCompressed(messages.messages, messages.offset) map toTopicMessage
      }

    }




    /**
      * Queries offsets for given topic and partition.
      * Returns offset of first message kept (head) and offset of next message that will arrive to topic.
      * When numbers are equal, then the topic does not include any messages at all.
      * @param topicId              Id of the topic
      * @param partition            Id of the partition
      * @param getLeader            Queries leader for the partition supplied
      * @param brokerOffsetConnection     A function to create connection to broker to send // receive OffsetRequests
      * @tparam F
      */
    def queryOffsetRange[F[_]](
     getLeader: (String @@ TopicName, Int @@ PartitionId) => F[Option[BrokerAddress]]
      , brokerOffsetConnection : BrokerAddress => Pipe[F, OffsetsRequest, OffsetResponse]
      , maxTimeForQuery: FiniteDuration
    )(
      topicId: String @@ TopicName
      , partition: Int @@ PartitionId
    )(implicit F: Effect[F], S: Scheduler, ec: ExecutionContext): F[(Long @@ Offset, Long @@ Offset)] = {
      getLeader(topicId, partition) flatMap {
        case None => F.raiseError(LeaderNotAvailable(topicId, partition))
        case Some(broker) =>
          val requestOffsetDataMin = OffsetsRequest(consumerBrokerId, Vector((topicId, Vector((partition, new Date(-1), Some(Int.MaxValue))))))
          val requestOffsetDataMax = OffsetsRequest(consumerBrokerId, Vector((topicId, Vector((partition, new Date(-2), Some(Int.MaxValue))))))
          (((Stream(requestOffsetDataMin, requestOffsetDataMax) ++ S.sleep_(maxTimeForQuery)) through brokerOffsetConnection(broker)).take(2).compile.toVector) flatMap { responses =>
            val results = responses.flatMap(_.data.filter(_._1 == topicId).flatMap(_._2.find(_.partitionId == partition)))
            results.collectFirst(Function.unlift(_.error)) match {
              case Some(err) => F.raiseError(BrokerReportedFailure(broker, requestOffsetDataMin, err))
              case None =>
                val offsets = results.flatMap { _.offsets } map { o => (o: Long) }
                if (offsets.isEmpty) F.raiseError(new Throwable(s"Invalid response. No offsets available: $responses, min: $requestOffsetDataMin, max: $requestOffsetDataMax"))
                else F.pure ((offset(offsets.min), offset(offsets.max)))
            }
          }
      }
    }


    /**
      * Request // reply communication to broker. This sends one message `I` and expect one result `O`
      */
    def requestReplyBroker[F[_], I <: Request, O <: Response](
      f: BrokerAddress => Pipe[F, RequestMessage, ResponseMessage]
      , protocol:  ProtocolVersion.Value
      , clientId: String
    )(address: BrokerAddress, input: I)(implicit F: Effect[F], ec: ExecutionContext, T: Typeable[O]): F[O] = {
      async.promise[F, Either[Throwable, Option[ResponseMessage]]] flatMap { promise =>
       async.start(((Stream.emit(RequestMessage(protocol, 1, clientId, input)) ++ Stream.eval(promise.get).drain) through f(address) take 1).compile.last.attempt.flatMap { r => promise.complete(r) }) >>
        promise.get flatMap {
          case Right(Some(response)) => T.cast(response.response) match {
            case Some(o) => F.pure(o)
            case None => F.raiseError(InvalidBrokerResponse(address, T.describe, input, Some(response.response)))
          }
          case Right(None) =>  F.raiseError(InvalidBrokerResponse(address, T.describe, input, None))
          case Left(err) => F.raiseError(BrokerRequestFailure(address, input, err))
        }
      }
    }


    /**
      * With every leader for each topic and partition active this keeps connection open.
      * Connection is open once the topic and partition will get first produce request to serve.
      * @param connection     Function handling connection to Kafka Broker
      * @param topicId        Id of the topic
      * @param partition      Id of the partition
      * @param protocol       Protocol
      * @param clientId       Id of the client
      * @param getLeaderFor   Returns a leader for supplied topic and partition
      * @param getLeaderDelay Wait that much time to retry for new leader if leader is not known
      */
    def publishLeaderConnection[F[_]](
      connection: BrokerAddress => Pipe[F, RequestMessage, ResponseMessage]
      , protocol:  ProtocolVersion.Value
      , clientId: String
      , getLeaderFor: (String @@ TopicName, Int @@ PartitionId) => F[Option[BrokerAddress]]
      , getLeaderDelay: FiniteDuration
      , topicId: String @@ TopicName
      , partition: Int @@ PartitionId
    )(implicit F: Effect[F], S: Scheduler, L: Logger[F], ec: ExecutionContext) : F[PartitionPublishConnection[F]] = {
      type Response = Option[(Long @@ Offset, Option[Date])]
      async.signalOf[F, Boolean](false) flatMap { termSignal =>
      async.boundedQueue[F, (ProduceRequest, Either[Throwable, Response] => F[Unit])](1) flatMap { queue =>
      async.refOf[F, Map[Int, (ProduceRequest, Either[Throwable, Response] => F[Unit])]](Map.empty) map { ref =>

        def registerMessage(in: (ProduceRequest, Either[Throwable, Response] => F[Unit]), idx: Int): F[RequestMessage] = {
          val (produce, cb) = in

          val msg = RequestMessage(
            version = protocol
            , correlationId = idx
            , clientId = clientId
            , request = produce
          )

          produce.requiredAcks match {
            case RequiredAcks.NoResponse => cb(Right(None)) as msg
            case _ => ref.modify { _ + (idx -> ((produce, cb))) } as msg
          }
        }

        def getRequest(response: ResponseMessage): F[Option[(ProduceRequest, Either[Throwable, Response] => F[Unit])]] = {
          ref.modify2 { m => (m - response.correlationId, m.get(response.correlationId)) } map { _._2 }
        }


        def completeNotProcessed(failure: Throwable): F[Unit] = {
          import cats.instances.list._
          ref.modify(_ => Map.empty) map { _.previous.values } flatMap { toCancel =>
            val fail = Left(failure)
            toCancel.toList.traverse(_._2 (fail)) as (())
          }
        }


        // When leader is available this is run to publish any incoming messages to server for processing
        // Message is processed from queue, then added to map of open messages and then send to server
        // this may only finish when either broker closes connection or fails.
        def leaderAvailable(leader: BrokerAddress): Stream[F, Unit] = {
          L.info2(s"Leader available for publishing to $topicId[$partition] : $leader") >>
          (((queue.dequeue.zip(indexer) evalMap (registerMessage _ tupled)) through connection(leader)) flatMap { response =>
            Stream.eval(getRequest(response)) flatMap {
              case Some((req, cb)) =>
                response match {
                  case ResponseMessage(_, produceResp: ProduceResponse) =>
                    produceResp.data.find(_._1 == topicId).flatMap(_._2.find(_._1 == partition)) match {
                      case None => Stream.raiseError(UnexpectedResponse(leader, response))

                      case Some((_, result)) => result.error match {
                        case None => Stream.eval_(cb(Right(Some((result.offset, result.time)))))
                        case Some(err) => Stream.eval_(cb(Left(BrokerReportedFailure(leader, req, err))))
                      }
                    }

                  case _ => Stream.raiseError(UnexpectedResponse(leader, response))
                }

              case None =>
                Stream.raiseError(UnexpectedResponse(leader, response))
            }
          }) ++ Stream.raiseError(new Throwable("Broker terminated connection")) // the first part of the stream shall never terminate unless broker terminates connection, which we convert to failure
        }

        val getLeader: Stream[F, Option[BrokerAddress]] =
          Stream.eval { getLeaderFor(topicId, partition) }

        // when leader is not available this rejects all requests.
        // each `getLeaderDelay` this refreshes new known metadata and once leader is knwon for given topic/partition
        // this will terminate with leader address
        def leaderUnavailable: Stream[F, BrokerAddress] = {
          L.error2(s"Leader unavailable for publishing to $topicId[$partition]") >> {
            Stream.eval(async.signalOf[F, Option[BrokerAddress]](None)) flatMap { leaderSignal =>
              val cancelIncoming = queue.dequeue.evalMap { case (_, cb) => cb(Left(LeaderNotAvailable(topicId, partition))) } drain
              val queryLeader = ((S.awakeEvery(getLeaderDelay) >> getLeader) evalMap { r => leaderSignal.modify { _ => r } }) drain

              (cancelIncoming mergeHaltBoth queryLeader).interruptWhen(leaderSignal.map {
                _.nonEmpty
              }) ++
              (leaderSignal.discrete.take(1) flatMap {
                case None => leaderUnavailable // impossible
                case Some(leader) =>
                  L.debug2(s"Publisher got leader for $topicId[$partition]: $leader") >> Stream.emit(leader)
              })
            }
          }
        }



        // main runner
        // this never terminates
        def runner(lastFailed: Option[BrokerAddress], knownLeader: Stream[F, Option[BrokerAddress]]): Stream[F, Unit] = {
          knownLeader flatMap {
            case None =>
              leaderUnavailable flatMap { leader => runner(None, Stream(Some(leader))) }

            case Some(leader) =>
              lastFailed match {
                case Some(failedBrokerAddress) if leader == failedBrokerAddress =>
                  // this indicates that cluster sill thinks the leader is same as the one that failed us, for that reason
                  // we have to suspend execution for while and retry in FiniteDuration
                  L.warn2(s"New elected leader is same like the old one ($leader), awaiting next leader: $topicId[$partition], currently unavailable") >>
                  leaderUnavailable flatMap { leader => runner(None, Stream(Some(leader))) }

                case _ =>
                  // connection with leader will always fail with error.
                  // so when that happens, all open requests are completed and runner is rerun to switch likely to leaderUnavailable.
                  // as the last action runner is restarted
                  leaderAvailable(leader) handleErrorWith  { failure =>
                    L.error2(s"Failure of publishing connection to $topicId[$partition] at broker $leader", failure) >>
                    Stream.eval(completeNotProcessed(failure)) >>
                    runner(Some(leader), getLeader)
                  }
              }

          }
        }


        new PartitionPublishConnection[F] {

          def run: F[Unit] =
            L.info(s"Starting publish connection for $topicId[$partition]") *>
            (runner(None, getLeader) interruptWhen termSignal).compile.drain.attempt flatMap { r =>
              completeNotProcessed(r.left.toOption.getOrElse(ClientTerminated)) *>
              L.info(s"Publish connection for $topicId[$partition] terminated: $r")
            }

          def shutdown: F[Unit] =
            L.info(s"Shutting-down publish connection for $topicId[$partition]") *> termSignal.set(true)

          def publish(messages: Vector[Message], timeout: FiniteDuration, acks: RequiredAcks.Value): F[Option[(Long @@ Offset, Option[Date])]] = {
            async.promise[F, Either[Throwable, Response]] flatMap { promise =>
              val request = ProduceRequest(
                requiredAcks = acks
                , timeout = timeout
                , messages = Vector((topicId, Vector((partition, messages))))
              )

              queue.enqueue1((request, promise.complete)) >> promise.get flatMap {
                case Left(err) => F.raiseError(err)
                case Right(r) => F.pure(r)
              }

            }

          }
        }

      }}}
    }


    /**
      * Produces a publisher that for every publishes partition-topic will spawn `PartitionPublishConnection`.
      * That connection is handling then all publish requests for given partition.
      * Connections are cached are re-used on next publish.
      *
      * @param createPublisher    Function to create single publish connection to given partition.
      *
      */
    def mkPublishers[F[_]](
      createPublisher: (String @@ TopicName, Int @@ PartitionId) => F[PartitionPublishConnection[F]]
    )(implicit F: Effect[F], ec: ExecutionContext): F[Publisher[F]] = {
      case class PublisherState(shutdown: Boolean, connections: Map[TopicAndPartition, PartitionPublishConnection[F]])
      implicit val stateEq : Eq[PublisherState] = Eq.fromUniversalEquals

      async.refOf(PublisherState(false, Map.empty)) map { stateRef =>

        new Publisher[F] {
          import cats.instances.list._

          def shutdown: F[Unit] = {
            stateRef.modify { _.copy(shutdown = true) } flatMap { c =>
              c.previous.connections.values.toList.traverse(_.shutdown) as (())
            }
          }

          def publish(topic: String @@ TopicName, partition: Int @@ PartitionId, data: Vector[Message], timeout: FiniteDuration, acks: RequiredAcks.Value): F[Option[(Long @@ Offset, Option[Date])]] = {
            stateRef.get map { _.connections.get((topic, partition)) } flatMap {
              case Some(ppc) =>   ppc.publish(data, timeout, acks)
              case None =>
                // lets create a new connection and try to swap it in
                createPublisher(topic, partition) flatMap { ppc =>
                stateRef.modify { s =>
                  if (s.shutdown) s
                  else {
                    // add to connections only if there is no current connection yet
                    if (s.connections.isDefinedAt((topic, partition))) s
                    else s.copy(connections = s.connections + ((topic, partition) -> ppc))
                  }
                } flatMap { c =>
                  if (c.previous.shutdown) {
                    F.raiseError(ClientTerminated)
                  } else if (c.previous != c.now) {
                    // we have won the race, so we shall start the publisher and then publish
                    async.start(ppc.run) >> publish(topic, partition, data, timeout, acks)
                  } else  {
                    // someone else won the ppc, we shall publish to new publisher.
                    publish(topic, partition, data, timeout, acks)
                  }
                }}

            }
          }

        }

      }
    }


    /**
      * Creates discrete signal of leaders that is queried from periodical query of metadata from brokers.
      * This will query supplied seeds in order given and then with first seed that succeeds this will compile
      * map of metadata that is emitted.
      *
      * While this stream is consumed, this will keep connection with very first broker that have answered this.
      *
      * If there is no broker available to server metadata request, this will fail as NoBrokerAvailable
      *
      * If the broker from which metadata are queried will fail, this will try next broker in supplied seed.
      *
      * @param metaRequestConnection   connection to create against the given broker
      * @param seed         Seed of ensemble to use to query metadata from
      * @param delay        Delay to refresh new metadata from last known good broker
      * @param topics       If nonempty, filters topic for which the metadata are queried
      * @tparam F
      * @return
      */
    def leadersDiscrete[F[_]](
      metaRequestConnection: BrokerAddress => Pipe[F, MetadataRequest, MetadataResponse]
      , seed: Seq[BrokerAddress]
      , delay: FiniteDuration
      , topics: Vector[String @@ TopicName]
    )(implicit F: Effect[F], S: Scheduler, ec: ExecutionContext, L: Logger[F]): Stream[F, Map[(String @@ TopicName, Int @@ PartitionId), BrokerAddress]] = {
      val metaRq = MetadataRequest(topics)

      // build map of available leaders from response received.
      def buildMap(resp: MetadataResponse): Map[(String @@ TopicName, Int @@ PartitionId), BrokerAddress] = {
        val brokersById = (resp.brokers map { b => (b.nodeId, BrokerAddress(b.host, b.port)) }).toMap
        (resp.topics flatMap { tp => tp.partitions flatMap { p => p.leader flatMap { brokersById.get } map { ((tp.name, p.id), _) } } }).toMap
      }

      def go(remains: Seq[BrokerAddress], success: Boolean): Stream[F, Map[(String @@ TopicName, Int @@ PartitionId), BrokerAddress]] = {
        remains.headOption match {
          case None =>
            if (success) go(seed, success = false)
            else Stream.raiseError(NoBrokerAvailable)

          case Some(broker) =>
            Stream.eval(async.refOf[F, Boolean](success)) flatMap { successRef =>
              ((Stream.eval(async.boundedQueue[F, MetadataRequest](1)) flatMap { requestQ =>
              Stream.eval(requestQ.enqueue1(metaRq)) >>
                (requestQ.dequeue through metaRequestConnection(broker)) flatMap { response =>
                  // we will here process the brokers and topics, and schedule next request after a given timeout
                  Stream.eval_(successRef.modify(_ => true)) ++
                  Stream.emit(buildMap(response)) ++
                  S.sleep_(delay) ++
                  Stream.eval_(requestQ.enqueue1(metaRq))
                }
              }) ++ Stream.raiseError(new Throwable(s"Broker Terminated connection early while monitoring for leader: $broker"))) handleErrorWith  { failure =>
                Stream.eval(successRef.get) flatMap { onceOk =>
                  L.error2(s"Broker terminated early while fetching metadata update (onceOk: $onceOk)", failure) >>
                  go(remains.tail, onceOk)
                }
              }
            }

        }
      }
      go(seed, success = false)
    }




  }


}





