package fs2.io.net

import cats.effect.Async
import cats.effect.LiftIO
import cats.effect.Resource
import com.comcast.ip4s.*
import fs2.Chunk
import fs2.Pipe
import fs2.Stream
import java.net.DatagramPacket
import java.net.DatagramSocket as JDatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress as JSocketAddress
import java.net.UnknownHostException

class NativeDatagramNetwork[F[_]](using F: Async[F], liftIO: LiftIO[F]) extends Network.AsyncNetwork[F] {

  private val delegate: Network[F] = Network.forLiftIO[F]

  override def connect(address: GenSocketAddress, options: List[SocketOption]): Resource[F, Socket[F]] =
    delegate.connect(address, options)

  override def bind(address: GenSocketAddress, options: List[SocketOption]): Resource[F, ServerSocket[F]] =
    delegate.bind(address, options)

  override def bindDatagramSocket(
      address: GenSocketAddress,
      options: List[SocketOption]
  ): Resource[F, DatagramSocket[F]] =
    Resource
      .eval(resolveIp(address))
      .flatMap { socketAddress =>
        Resource
          .make(F.delay(new JDatagramSocket(null.asInstanceOf[JSocketAddress])))(socket => F.delay(socket.close()))
          .evalTap { socket =>
            F.delay {
              options.foreach(applyOption(socket, _))
              socket.bind(toJava(socketAddress))
            }
          }
          .map(socket => new JavaDatagramSocket[F](socket))
      }

  private def resolveIp(address: GenSocketAddress): F[SocketAddress[IpAddress]] =
    address match
      case SocketAddress(ip: IpAddress, port) => F.pure(SocketAddress(ip, port))
      case SocketAddress(hostname: Hostname, port) =>
        delegate.dns.resolveOption(hostname).flatMap {
          case Some(ip) => F.pure(SocketAddress(ip, port))
          case None     => F.raiseError(new UnknownHostException(hostname.toString))
        }
      case other => F.raiseError(new UnsupportedOperationException(s"Unsupported address: $other"))

  private def toJava(address: SocketAddress[IpAddress]): InetSocketAddress =
    new InetSocketAddress(InetAddress.getByAddress(address.host.toBytes), address.port.value)

  private def applyOption(socket: JDatagramSocket, option: SocketOption): Unit =
    option.key match
      case SocketOption.ReuseAddress => socket.setReuseAddress(option.value.asInstanceOf[Boolean])
      case SocketOption.Broadcast    => socket.setBroadcast(option.value.asInstanceOf[Boolean])
      case _                         => throw new UnsupportedOperationException(s"Unsupported socket option: ${option.key}")
}

private[net] final class JavaDatagramSocket[F[_]](socket: JDatagramSocket)(using F: Async[F]) extends DatagramSocket[F] {

  private val MaxDatagramSize = 65535

  def address: GenSocketAddress =
    socket.getLocalSocketAddress match
      case isa: InetSocketAddress => fromJava(isa)
      case _ =>
        SocketAddress(
          IpAddress.fromBytes(socket.getLocalAddress.getAddress).get,
          Port.fromInt(socket.getLocalPort).getOrElse(Port.Wildcard)
        )

  def supportedOptions: F[Set[SocketOption.Key[?]]] =
    F.pure(Set(SocketOption.ReuseAddress, SocketOption.Broadcast))

  def getOption[A](key: SocketOption.Key[A]): F[Option[A]] =
    F.delay {
      key match
        case SocketOption.ReuseAddress => Some(socket.getReuseAddress).asInstanceOf[Option[A]]
        case SocketOption.Broadcast    => Some(socket.getBroadcast).asInstanceOf[Option[A]]
        case _                         => None
    }

  def setOption[A](key: SocketOption.Key[A], value: A): F[Unit] =
    F.delay {
      key match
        case SocketOption.ReuseAddress => socket.setReuseAddress(value.asInstanceOf[Boolean])
        case SocketOption.Broadcast    => socket.setBroadcast(value.asInstanceOf[Boolean])
        case _                         => throw new UnsupportedOperationException(s"Unsupported socket option: $key")
    }

  def read: F[Datagram] =
    F.blocking {
      val buffer = new Array[Byte](MaxDatagramSize)
      val packet = new DatagramPacket(buffer, buffer.length)
      socket.receive(packet)
      val remote = fromJava(packet.getSocketAddress.asInstanceOf[InetSocketAddress])
      val bytes =
        Chunk.array(java.util.Arrays.copyOfRange(packet.getData, packet.getOffset, packet.getOffset + packet.getLength))
      Datagram(remote, bytes)
    }

  def readGen: F[GenDatagram] =
    read.map(_.toGenDatagram)

  def reads: Stream[F, Datagram] =
    Stream.repeatEval(read)

  def connect(address: GenSocketAddress): F[Unit] =
    F.blocking(socket.connect(toJava(address)))

  def disconnect: F[Unit] =
    F.blocking(socket.disconnect())

  def write(bytes: Chunk[Byte]): F[Unit] =
    F.blocking(socket.send(new DatagramPacket(bytes.toArray, bytes.size)))

  def write(bytes: Chunk[Byte], address: GenSocketAddress): F[Unit] =
    F.blocking {
      val isa = toJava(address)
      socket.send(new DatagramPacket(bytes.toArray, bytes.size, isa.getAddress, isa.getPort))
    }

  def writes: Pipe[F, Datagram, Nothing] =
    _.evalMap(write).drain

  @deprecated(
    "Use address instead, which returns GenSocketAddress instead of F[SocketAddress[IpAddress]]",
    "3.13.0"
  )
  def localAddress: F[SocketAddress[IpAddress]] =
    F.blocking(fromJava(socket.getLocalSocketAddress.asInstanceOf[InetSocketAddress]))

  def join(join: MulticastJoin[IpAddress], interface: NetworkInterface): F[GroupMembership] =
    F.raiseError(new UnsupportedOperationException("Multicast is not supported on Native"))

  @deprecated("Use overload that takes a com.comcast.ip4s.NetworkInterface", "3.13.0")
  def join(join: MulticastJoin[IpAddress], interface: DatagramSocket.NetworkInterface): F[GroupMembership] =
    F.raiseError(new UnsupportedOperationException("Multicast is not supported on Native"))

  private def toJava(address: GenSocketAddress): InetSocketAddress =
    address match
      case SocketAddress(ip: IpAddress, port) =>
        new InetSocketAddress(InetAddress.getByAddress(ip.toBytes), port.value)
      case SocketAddress(hostname: Hostname, port) =>
        new InetSocketAddress(hostname.toString, port.value)
      case other =>
        throw new UnsupportedOperationException(s"Unsupported address: $other")

  private def fromJava(address: InetSocketAddress): SocketAddress[IpAddress] =
    SocketAddress(
      IpAddress.fromBytes(address.getAddress.getAddress).get,
      Port.fromInt(address.getPort).get
    )
}
