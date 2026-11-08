package com.github.torrentdam.bittorrent.net

import cats.effect.IO
import cats.syntax.all.*
import com.comcast.ip4s.*
import fs2.Chunk
import fs2.Stream
import fs2.io.net.Datagram
import fs2.io.net.DatagramSocket
import fs2.io.net.SocketOption
import scala.concurrent.duration.DurationInt

class UdpSpec extends munit.CatsEffectSuite {

  private val network = Networks.custom[IO]

  private val concurrentBindOptions =
    List(SocketOption.reuseAddress(true), SocketOption.reusePort(true))

  private def reuse(option: SocketOption) = {
    val options = List(option)

    network.bindDatagramSocket(options = options).use { socket1 =>
      network.bindDatagramSocket(socket1.address, options).use_
    }
  }

  private def sendAndReceive(socket: DatagramSocket[IO], toSend: Datagram): IO[Datagram] =
    socket
      .write(toSend) >> socket.read.timeoutTo(1.second, IO.defer(sendAndReceive(socket, toSend)))

  private def sendAndReceiveBytes(socket: DatagramSocket[IO], bytes: Chunk[Byte]): IO[Datagram] =
    socket
      .write(bytes) >> socket.read.timeoutTo(1.second, IO.defer(sendAndReceiveBytes(socket, bytes)))

  test("echo one") {
    val msg = Chunk.array("Hello, world!".getBytes)
    Stream
      .resource(network.bindDatagramSocket(SocketAddress.Wildcard))
      .flatMap { serverSocket =>
        val serverAddress = SocketAddress(ip"127.0.0.1", serverSocket.address.asIpUnsafe.port)
        val server = serverSocket.reads.foreach(packet => serverSocket.write(packet))
        val client =
          Stream.resource(network.bindDatagramSocket(SocketAddress.Wildcard)).evalMap {
            clientSocket =>
              sendAndReceive(clientSocket, Datagram(serverAddress, msg))
          }
        client.concurrently(server)
      }
      .compile
      .lastOrError
      .map(_.bytes)
      .assertEquals(msg)
  }

  test("echo one over IPv6 loopback") {
    val msg = Chunk.array("Hello, IPv6!".getBytes)
    Stream
      .resource(network.bindDatagramSocket(SocketAddress(ip"::1", Port.Wildcard)))
      .flatMap { serverSocket =>
        val serverAddress = SocketAddress(ip"::1", serverSocket.address.asIpUnsafe.port)
        val server = serverSocket.reads.foreach(packet => serverSocket.write(packet))
        val client =
          Stream.resource(network.bindDatagramSocket(SocketAddress(ip"::1", Port.Wildcard))).evalMap {
            clientSocket =>
              sendAndReceive(clientSocket, Datagram(serverAddress, msg))
          }
        client.concurrently(server)
      }
      .compile
      .lastOrError
      .map(_.bytes)
      .assertEquals(msg)
  }

  test("echo lots") {
    val msgs = (1 to 20).toVector.map(n => Chunk.array(("Hello, world! " + n).getBytes))
    val numClients = 50
    val numParallelClients = 10
    val expected = Vector
      .fill(numClients)(msgs.map(b => new String(b.toArray)))
      .flatten
      .sorted

    Stream
      .resource(network.bindDatagramSocket())
      .flatMap { serverSocket =>
        val serverAddress = SocketAddress(ip"127.0.0.1", serverSocket.address.asIpUnsafe.port)
        val server = serverSocket.reads.foreach(packet => serverSocket.write(packet))
        val client = Stream.resource(network.bindDatagramSocket()).flatMap { clientSocket =>
          Stream
            .emits(msgs.map(msg => Datagram(serverAddress, msg)))
            .evalMap(msg => sendAndReceive(clientSocket, msg))
        }
        val clients = Stream
          .constant(client)
          .take(numClients.toLong)
          .parJoin(numParallelClients)
        clients.concurrently(server)
      }
      .compile
      .toVector
      .map(_.map(p => new String(p.bytes.toArray)).sorted)
      .assertEquals(expected)
  }

  test("echo connected") {
    val msg = Chunk.array("Hello, world!".getBytes)
    Stream
      .resource(network.bindDatagramSocket())
      .flatMap { serverSocket =>
        val serverAddress = serverSocket.address.asIpUnsafe
        val server = serverSocket.reads.foreach(packet => serverSocket.write(packet))
        val client = Stream.resource(network.bindDatagramSocket()).evalMap { clientSocket =>
          clientSocket.connect(serverAddress) >> sendAndReceiveBytes(clientSocket, msg)
        }
        client.concurrently(server)
      }
      .compile
      .lastOrError
      .map(_.bytes)
      .assertEquals(msg)
  }

  test("options allow concurrent use of ports") {
    concurrentBindOptions.traverse(reuse(_))
  }

  test("network allows reuse of port immediately") {
    network
      .bindDatagramSocket(options = List(SocketOption.reuseAddress(true)))
      .use { socket1 =>
        socket1.read.void.timeoutTo(10.millis, IO.unit) *> IO.pure(socket1.address)
      }
      .flatMap { socket2Address =>
        network
          .bindDatagramSocket(socket2Address, List(SocketOption.reuseAddress(true)))
          .use(_.read.void.timeoutTo(1.milli, IO.unit))
      }
  }
}
