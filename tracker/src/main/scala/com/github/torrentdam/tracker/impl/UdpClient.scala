package com.github.torrentdam.tracker.impl

import cats.syntax.traverse.given
import cats.effect.{IO, Resource}
import cats.effect.kernel.{Deferred, Ref}
import cats.effect.std.{Random, Supervisor}
import com.github.lavrov.bittorrent.{InfoHash, PeerId, PeerInfo}
import com.github.torrentdam.tracker.Client
import fs2.Chunk
import fs2.io.net.{Datagram, DatagramSocket}
import com.comcast.ip4s.{Dns, IpAddress, Port, SocketAddress}
import org.http4s.Uri
import scodec.bits.{BitVector, ByteVector}

import java.net.InetSocketAddress
import scala.concurrent.TimeoutException
import scala.concurrent.duration.DurationInt
import scala.util.Try

trait UdpClient extends Client

object UdpClient:

  def impl(selfId: PeerId, socket: DatagramSocket[IO])(using dns: Dns[IO]): Resource[IO, UdpClient] =
    val send = Send(socket)
    val receive = Receive(socket)
    for
      callbackRegistry <- CallbackRegistry()
      _ <- handle(receive, callbackRegistry.complete).background
      randomTx <- Resource.eval(Random.scalaUtilRandom[IO].map(RandomTx(_)))
      makeRequest = new MakeRequest(callbackRegistry, send, randomTx)
      sendConnect = new SendConnect(makeRequest)
      sendAnnounce = new SendAnnounce(makeRequest)
    yield new:
      def get(announceUrl: Uri, infoHash: InfoHash): IO[Client.Response] =
        val hostPort =
          for
            host <- announceUrl.host
            host <- host match
              case Uri.Ipv4Address(address) => Some(Right(address))
              case Uri.Ipv6Address(_)       => None
              case name @ Uri.RegName(_)    => name.toHostname.map(Left(_))
            port <- announceUrl.port
            port <- Port.fromInt(port)
          yield (host, port)
        val address =
          for
            hostPort <- IO.fromOption(hostPort)(InvalidRemoteAddress(announceUrl))
            address <- hostPort._1 match
              case Right(address) => IO.pure(address)
              case Left(hostname) => dns.resolve(hostname)
          yield SocketAddress(address, hostPort._2)
        for
          address <- address
          response <- sendConnect(address, Request.Connect())
          response <- sendAnnounce(
            address,
            Request.Announce(
              response.connectionId,
              infoHash,
              selfId,
              ULong.Zero,
              ULong.Zero,
              ULong.Zero,
              0,
              0,
              0,
              -1,
              0
            )
          )
        yield Client.Response.Success(response.peers)

  case class CorrelatedRequest(transactionId: Long, request: Request)
  object CorrelatedRequest:
    import scodec.{Codec, Encoder}
    import scodec.codecs.*
    import scodec.bits.hex
    val connectEncoder: Encoder[Long] =
      (
        bytes(8).unit(hex"0000041727101980") ~>
        uint32.unit(0) ~>
        uint32
      )
    val ulong64: Codec[ULong] = bytes(8).xmap(ULong(_), _.bytes)
    val announceEncoder: Encoder[(Long, Request.Announce)] =
      (
        bytes(8) :: // 0       64-bit integer  connection_id
          uint32.unit(1) ~> // 8       32-bit integer  action          1 // announce
          uint32 :: // 12      32-bit integer  transaction_id
          bytes(20) :: // 16      20-byte string  info_hash
          bytes(20) :: // 36      20-byte string  peer_id
          ulong64 :: // 56      64-bit integer  downloaded
          ulong64 :: // 64      64-bit integer  left
          ulong64 :: // 72      64-bit integer  uploaded
          uint32 :: // 80      32-bit integer  event           0 // 0: none; 1: completed; 2: started; 3: stopped
          uint32 :: // 84      32-bit integer  IP address      0 // default
          uint32 :: // 88      32-bit integer  key
          int32 :: // 92      32-bit integer  num_want        -1 // default
          uint16 // 96      16-bit integer  port
      ).contramap[(Long, Request.Announce)]((tid, a) =>
        (
          a.connectionId.bytes,
          tid,
          a.infoHash.bytes,
          a.peerId.bytes,
          a.downloaded,
          a.left,
          a.uploaded,
          a.event,
          a.ipAddress,
          a.key,
          a.numWant,
          a.port
        )
      )

    def encode(request: CorrelatedRequest) =
      request.request match
        case Request.Connect()          => connectEncoder.encode(request.transactionId).require
        case announce: Request.Announce => announceEncoder.encode((request.transactionId, announce)).require

  case class ULong(bytes: ByteVector)
  object ULong:
    val Zero: ULong = ULong(ByteVector.fill(8)(0))

  case class ConnectionId(bytes: ByteVector)

  enum Request:
    case Connect()
    case Announce(
      connectionId: ConnectionId,
      infoHash: InfoHash,
      peerId: PeerId,
      downloaded: ULong,
      left: ULong,
      uploaded: ULong,
      event: Long,
      ipAddress: Long,
      key: Long,
      numWant: Int,
      port: Int
    )

  case class CorrelatedResponse(transactionId: Long, response: Response)
  object CorrelatedResponse:
    import scodec.{Codec, Decoder}
    import scodec.codecs.*
    private val connectDecoder: Decoder[CorrelatedResponse] =
      (
        uint32 ::
          bytes(8).xmap(ConnectionId.apply, _.bytes)
      ).map((transactionId, connectionId) => CorrelatedResponse(transactionId, Response.Connect(connectionId)))
    private val announceDecoder: Decoder[CorrelatedResponse] =
      (
        uint32 ::
          int32 ::
          int32 ::
          int32
      ).flatMap((transactionId, interval, nLeechers, nSeeders) =>
        list(inetSocketAddressCodec)
          .map(_.map(PeerInfo.apply))
          .map(peers =>
            CorrelatedResponse(
              transactionId,
              Response.Announce(
                interval,
                nLeechers,
                nSeeders,
                peers
              )
            )
          )
      )
    private val inetSocketAddressCodec: Codec[SocketAddress[IpAddress]] =
      (bytes(4) :: bytes(2)).xmap(
        (address, port) =>
          SocketAddress(
            IpAddress.fromBytes(address.toArray).get,
            Port.fromInt(port.toInt(signed = false)).get
          ),
        v => (ByteVector(v.host.toBytes), ByteVector.fromInt(v.port.value, 2))
      )
    private val responseDecoder: Decoder[CorrelatedResponse] =
      uint32.flatMap {
        case 0 => connectDecoder
        case 1 => announceDecoder
      }

    def decode(bytes: ByteVector): Try[CorrelatedResponse] =
      responseDecoder.decodeValue(bytes.toBitVector).toTry

  enum Response:
    case Connect(
      connectionId: ConnectionId
    )
    case Announce(
      interval: Long,
      leechers: Int,
      seeders: Int,
      peers: List[PeerInfo]
    )

  private class SendConnect(makeRequest: MakeRequest):
    def apply(remote: SocketAddress[IpAddress], request: Request.Connect): IO[Response.Connect] =
      makeRequest(remote, request).flatMap {
        case r: Response.Connect => IO.pure(r)
        case _                   => IO.raiseError(WrongResponse())
      }

  private class SendAnnounce(makeRequest: MakeRequest):
    def apply(remote: SocketAddress[IpAddress], request: Request.Announce): IO[Response.Announce] =
      makeRequest(remote, request).flatMap {
        case r: Response.Announce => IO.pure(r)
        case _                    => IO.raiseError(WrongResponse())
      }

  private class MakeRequest(registry: CallbackRegistry, send: Send, randomTx: RandomTx):
    def apply(remote: SocketAddress[IpAddress], request: Request): IO[Response] =
      for
        transactionId <- randomTx()
        callback <- Callback()
        _ <- registry.register(transactionId, callback)
        _ <- send(remote, CorrelatedRequest(transactionId, request))
        result <- callback.get
        result <- IO.fromEither(result)
      yield result

  private class RandomTx(random: Random[IO]):
    def apply(): IO[Long] = random.nextLongBounded(4294967295L).map(_.abs)

  private class Send(socket: DatagramSocket[IO]):
    def apply(remote: SocketAddress[IpAddress], request: CorrelatedRequest): IO[Unit] =
      val bytes = CorrelatedRequest.encode(request).bytes
      socket.write(Datagram(remote, Chunk.byteVector(bytes)))

  private class Receive(socket: DatagramSocket[IO]):
    def apply(): IO[CorrelatedResponse] =
      socket.read.flatMap(datagram =>
        val bytes = datagram.bytes.toByteVector
        IO.fromTry(CorrelatedResponse.decode(bytes))
      )

  private def handle(
    receive: Receive,
    completeCallback: (Long, Either[TimeoutException, Response]) => IO[Unit]
  ): IO[Nothing] =
    def step =
      for
        message <- receive()
        _ <- completeCallback(message.transactionId, Right(message.response))
      yield {}
    step.attempt.foreverM

  private type Callback = Deferred[IO, Either[TimeoutException, Response]]
  private object Callback:
    def apply(): IO[Callback] =
      Deferred[IO, Either[TimeoutException, Response]]

  private class CallbackRegistry(
    ref: Ref[IO, Map[Long, Callback]],
    supervisor: Supervisor[IO]
  ):
    def complete(transactionId: Long, result: Either[TimeoutException, Response]): IO[Unit] =
      for
        callback <- ref.modify(map => (map.removed(transactionId), map.get(transactionId)))
        _ <- callback.traverse(_.complete(result))
      yield {}
    def register(transactionId: Long, callback: Callback): IO[Unit] =
      for
        _ <- ref.update(map => map.updated(transactionId, callback))
        _ <- supervisor.supervise(
          IO.sleep(5.seconds) >> complete(transactionId, Left(new TimeoutException))
        )
      yield {}

  private object CallbackRegistry:
    def apply(): Resource[IO, CallbackRegistry] =
      for
        ref <- Resource.eval(Ref[IO].of(Map.empty[Long, Callback]))
        supervisor <- Supervisor[IO]
      yield new CallbackRegistry(ref, supervisor)

  class WrongResponse extends Throwable
  class InvalidRemoteAddress(uri: Uri) extends Throwable

end UdpClient
