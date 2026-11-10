package fs2.io.net

import cats.effect.Async
import cats.effect.FileDescriptorPollHandle
import cats.effect.FileDescriptorPoller
import cats.effect.IO
import cats.effect.LiftIO
import cats.effect.Resource
import cats.syntax.all.*
import com.comcast.ip4s.*
import fs2.io.internal.NativeUtil.*
import fs2.io.internal.SocketHelpers
import fs2.Chunk
import fs2.Pipe
import fs2.Stream
import java.net.UnknownHostException
import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.posix.sys.socket.{
  bind => sbind,
  connect => sconnect,
  recvfrom => srecvfrom,
  send => ssend,
  sendto => ssendto,
  *
}
import scala.scalanative.posix.sys.socketOps.*
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

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
        val domain = if (socketAddress.host.isInstanceOf[Ipv4Address]) AF_INET else AF_INET6
        for
          poller <- Resource.eval(FileDescriptorPoller.get.to[F])
          fd <- SocketHelpers.openNonBlocking[F](domain, SOCK_DGRAM)
          _ <- Resource.eval(options.traverse(so => SocketHelpers.setOption(fd, so.key, so.value)))
          _ <- Resource.eval(F.delay(SocketHelpers.toSockaddr(socketAddress) { (addr, len) =>
            guard_(sbind(fd, addr, len))
          }))
          handle <- poller.registerFileDescriptor(fd, true, true).mapK(LiftIO.liftK)
        yield new FdPollingDatagramSocket[F](fd, handle, domain, SocketHelpers.getAddress(fd, domain))
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
}

private final class FdPollingDatagramSocket[F[_]: LiftIO](
  fd: CInt,
  handle: FileDescriptorPollHandle,
  domain: CInt,
  val address: GenSocketAddress
)(implicit F: Async[F])
    extends DatagramSocket[F] {

  private val MaxDatagramSize = 65535

  def supportedOptions: F[Set[SocketOption.Key[?]]] =
    SocketHelpers.supportedOptions

  def getOption[A](key: SocketOption.Key[A]): F[Option[A]] =
    SocketHelpers.getOption[F, A](fd, key)

  def setOption[A](key: SocketOption.Key[A], value: A): F[Unit] =
    SocketHelpers.setOption(fd, key, value)

  def read: F[Datagram] =
    handle
      .pollReadRec(()) { _ =>
        IO {
          val buffer = new Array[Byte](MaxDatagramSize)
          SocketHelpers.allocateSockaddr(domain) { (addr, addrLen) =>
            val received = guardSSize(srecvfrom(fd, buffer.atUnsafe(0), MaxDatagramSize.toUSize, 0, addr, addrLen))
            if (received >= 0) {
              val remote = SocketHelpers.toSocketAddress(addr, domain).asInstanceOf[SocketAddress[IpAddress]]
              Right(Datagram(remote, Chunk.array(buffer, 0, received.toInt)))
            } else Left(())
          }
        }
      }
      .to

  def readGen: F[GenDatagram] =
    read.map(_.toGenDatagram)

  def reads: Stream[F, Datagram] =
    Stream.repeatEval(read)

  def connect(address: GenSocketAddress): F[Unit] =
    F.delay(SocketHelpers.toSockaddr(address.asIpUnsafe) { (addr, len) =>
      guard_(sconnect(fd, addr, len))
    })

  def disconnect: F[Unit] =
    F.delay {
      val addr = stackalloc[sockaddr]()
      addr.sa_family = AF_UNSPEC.toUShort
      guard_(sconnect(fd, addr, sizeof[sockaddr].toUInt))
    }

  def write(bytes: Chunk[Byte]): F[Unit] =
    handle
      .pollWriteRec(()) { _ =>
        IO {
          val buffer = bytes.toArray
          val flags = if (LinktimeInfo.isLinux) MSG_NOSIGNAL else 0
          val sent = guardSSize(ssend(fd, buffer.atUnsafe(0), buffer.length.toUSize, flags))
          if (sent >= 0) Right(()) else Left(())
        }
      }
      .to

  def write(bytes: Chunk[Byte], address: GenSocketAddress): F[Unit] =
    handle
      .pollWriteRec(()) { _ =>
        IO {
          val buffer = bytes.toArray
          val flags = if (LinktimeInfo.isLinux) MSG_NOSIGNAL else 0
          SocketHelpers.toSockaddr(address.asIpUnsafe) { (addr, len) =>
            val sent = guardSSize(ssendto(fd, buffer.atUnsafe(0), buffer.length.toUSize, flags, addr, len))
            if (sent >= 0) Right(()) else Left(())
          }
        }
      }
      .to

  def writes: Pipe[F, Datagram, Nothing] =
    _.evalMap(datagram => write(datagram)).drain

  @deprecated(
    "Use address instead, which returns GenSocketAddress instead of F[SocketAddress[IpAddress]]",
    "3.13.0"
  )
  def localAddress: F[SocketAddress[IpAddress]] =
    F.pure(address.asIpUnsafe)

  def join(join: MulticastJoin[IpAddress], interface: NetworkInterface): F[GroupMembership] =
    F.raiseError(new UnsupportedOperationException("Multicast is not supported on Native"))

  @deprecated("Use overload that takes a com.comcast.ip4s.NetworkInterface", "3.13.0")
  def join(join: MulticastJoin[IpAddress], interface: DatagramSocket.NetworkInterface): F[GroupMembership] =
    F.raiseError(new UnsupportedOperationException("Multicast is not supported on Native"))
}
