package com.github.torrentdam.bittorrent

import cats.effect.IO
import cats.effect.Resource
import cats.effect.std.Random
import cats.implicits.*
import fs2.Chunk
import fs2.Stream
import fs2.io.file.Files
import fs2.io.file.Path
import munit.CatsEffectSuite
import scodec.bits.ByteVector

import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeoutException
import scala.concurrent.duration.*
import scala.sys.process.Process
import scala.sys.process.ProcessLogger

class TransmissionIntegrationSuite extends CatsEffectSuite:

  override def munitTimeout: Duration = 15.minutes
  override def munitIOTimeout: Duration = 15.minutes

  private val PayloadSizeBytes = 16 * 1024 * 1024

  test("CLI downloads and verifies a file seeded by transmission daemon") {
    val daemon = binary("transmission-daemon")
    val create = binary("transmission-create")
    val remote = binary("transmission-remote")
    assume(
      daemon.nonEmpty && create.nonEmpty,
      "transmission binaries not found; run 'nix develop' or export PATH with transmission"
    )
    run(daemon.get, create.get, remote)
  }

  private def run(daemon: String, create: String, remote: Option[String]): IO[Unit] =
    fixture(daemon, create, remote).use {
      case (cli, torrentPath, downloadDir, peerPort, logPath, payload) =>
        val scenario = for
          _ <- runCli(
            cli,
            Seq(
              "torrent", "download",
              "--torrent", torrentPath.toString,
              "--peer", s"127.0.0.1:$peerPort"
            ),
            cwd = downloadDir
          )
          downloaded <- Files[IO].readAll(downloadDir / "payload").compile.to(ByteVector)
          _ <- IO(assertEquals(downloaded, payload))
          _ <- runCli(
            cli,
            Seq("torrent", "verify", "--torrent", torrentPath.toString, "--target", downloadDir.toString),
            cwd = downloadDir
          )
        yield ()
        scenario.handleErrorWith { error =>
          readTail(logPath).flatMap { log =>
            IO.raiseError(RuntimeException(s"end-to-end test failed: $error\n--- transmission log ---\n$log"))
          }
        }
    }

  private def fixture(
      daemon: String,
      create: String,
      remote: Option[String]
  ): Resource[IO, (Path, Path, Path, Int, Path, ByteVector)] =
    for
      root <- Files[IO].tempDirectory(None, "torrentdam-e2e-", None)
      seedDir = root / "seed"
      watchDir = root / "watch"
      configDir = root / "config"
      downloadDir = root / "download"
      _ <- Resource.eval(
        List(seedDir, watchDir, configDir, downloadDir).traverse_(Files[IO].createDirectories)
      )
      cli <- Resource.eval(buildCli)
      payload <- Resource.eval(generatePayload)
      payloadPath = seedDir / "payload"
      _ <- Resource.eval {
        Files[IO].writeAll(payloadPath)(Stream.chunk(Chunk.byteVector(payload))).compile.drain
      }
      torrentPath = root / "test.torrent"
      _ <- Resource.eval(
        execExpectSuccess(Seq(create, "-o", torrentPath.toString, payloadPath.toString))
      )
      rpcPort <- Resource.eval(freePort)
      peerPort <- Resource.eval(freePort)
      logPath = root / "daemon.log"
      _ <- startDaemon(daemon, logPath, configDir, downloadDir, watchDir, peerPort, rpcPort)
      _ <- Resource.eval(waitForPort(rpcPort, 30.seconds))
      _ <- Resource.eval(Files[IO].copy(payloadPath, downloadDir / "payload").void)
      _ <- Resource.eval(Files[IO].copy(torrentPath, watchDir / "test.torrent").void)
      _ <- Resource.eval(waitForSeed(remote, rpcPort, 60.seconds))
    yield (cli, torrentPath, downloadDir, peerPort, logPath, payload)

  private def buildCli: IO[Path] =
    sys.env.get("TORRENT_TEST_CMD_BIN") match
      case Some(bin) => IO.pure(Path(new File(bin).getAbsolutePath))
      case None =>
        val repoRoot = findRepoRoot(Path(System.getProperty("user.dir")))
        val mill = repoRoot / "mill"
        val assembly = repoRoot / "out" / "cmd" / "jvm" / "assembly.dest" / "out.jar"
        val nativeBin = repoRoot / "out" / "cmd" / "native" / "nativeLink.dest" / "out"
        val target = sys.env.getOrElse("TORRENT_TEST_TARGET", "jvm")
        if target == "native" then
          execExpectSuccess(Seq(mill.toString, "show", "cmd.native.nativeLink"))
            .flatMap { _ =>
              IO.raiseUnless(new File(nativeBin.toString).canExecute)(
                RuntimeException(s"native binary not found: $nativeBin")
              ).as(Path(new File(nativeBin.toString).getAbsolutePath))
            }
        else
          execExpectSuccess(Seq(mill.toString, "show", "cmd.jvm.assembly"))
            .flatMap { _ =>
              IO.raiseUnless(new File(assembly.toString).isFile)(
                RuntimeException(s"assembly jar not found: $assembly")
              ).as(Path(new File(assembly.toString).getAbsolutePath))
            }

  private def findRepoRoot(start: Path): Path =
    var dir = Option(start)
    while dir.isDefined && !new File(dir.get.toString, "build.mill").isFile do
      dir = dir.get.parent
    dir.getOrElse(throw RuntimeException(s"repository root not found above $start"))

  private def runCli(bin: Path, args: Seq[String], cwd: Path): IO[Unit] =
    IO.blocking {
      val out = new StringBuilder
      val target = sys.env.getOrElse("TORRENT_TEST_TARGET", "jvm")
      val cmd =
        if target == "native" then Seq(bin.toString)
        else
          Seq(
            "java",
            "-Dcats.effect.tracing.mode=none",
            "--add-exports", "java.base/jdk.internal.vm=ALL-UNNAMED",
            "-jar", bin.toString
          )
      val cmdWithArgs = cmd ++ args
      val process = Process(cmdWithArgs, new File(cwd.toString)).run(ProcessLogger(s => out.append(s).append('\n')))
      val code = process.exitValue()
      (code, out.toString)
    }.flatMap { (code, out) =>
      val target = sys.env.getOrElse("TORRENT_TEST_TARGET", "jvm")
      val acceptable = code == 0 || (target == "native" && code == 134)
      IO.raiseWhen(!acceptable) {
        RuntimeException(s"CLI failed with exit code $code: ${out.take(4000)}")
      }.void
    }

  private def startDaemon(
      bin: String,
      logPath: Path,
      configDir: Path,
      downloadDir: Path,
      watchDir: Path,
      peerPort: Int,
      rpcPort: Int
  ): Resource[IO, Unit] =
    Resource
      .make(IO.blocking {
        val log = java.nio.file.Files.newBufferedWriter(java.nio.file.Paths.get(logPath.toString))
        val write = (line: String) => log.synchronized {
          log.write(line)
          log.newLine()
          log.flush()
        }
        val cmd = Seq(
          bin,
          "-f",
          "-T",
          "-g", configDir.toString,
          "-w", downloadDir.toString,
          "-c", watchDir.toString,
          "-P", peerPort.toString,
          "-p", rpcPort.toString,
          "-M",
          "--log-level", "info"
        )
        Process(cmd).run(ProcessLogger(write))
      })(proc =>
        IO.blocking(proc.destroy()) >>
          IO.blocking {
            proc.exitValue()
            ()
          }.attempt.void
      )
      .void

  private def generatePayload: IO[ByteVector] =
    Random.scalaUtilRandom[IO].flatMap { random =>
      val chunkSize = 256 * 1024
      val chunks = PayloadSizeBytes / chunkSize
      fs2.Stream
        .eval(random.nextBytes(chunkSize))
        .take(chunks.toLong)
        .map(ByteVector(_))
        .compile
        .fold(ByteVector.empty)(_ ++ _)
    }

  private def exec(cmd: Seq[String]): IO[(Int, String)] =
    IO.blocking {
      val out = new StringBuilder
      val proc = Process(cmd).run(ProcessLogger(s => out.append(s).append('\n')))
      (proc.exitValue(), out.toString)
    }

  private def execExpectSuccess(cmd: Seq[String]): IO[Unit] =
    exec(cmd).flatMap { (code, out) =>
      IO.raiseWhen(code != 0)(RuntimeException(s"command failed ($code): ${cmd.mkString(" ")}\n$out")).void
    }

  private def freePort: IO[Int] =
    IO.blocking {
      val socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress)
      val port = socket.getLocalPort
      socket.close()
      port
    }

  private def waitForPort(port: Int, timeout: FiniteDuration): IO[Unit] =
    poll(timeout) {
      IO.blocking {
        val socket = new Socket()
        socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress, port), 1000)
        socket.close()
      }.attempt.map(_.isRight)
    }

  private def waitForSeed(remote: Option[String], rpcPort: Int, timeout: FiniteDuration): IO[Unit] =
    remote match
      case Some(bin) =>
        poll(timeout) {
          exec(Seq(bin, s"127.0.0.1:$rpcPort", "-l")).flatMap { (listCode, listOut) =>
            val seeded = listCode == 0 && listOut.contains("100%")
            if seeded then IO.pure(true)
            else
              exec(Seq(bin, s"127.0.0.1:$rpcPort", "-t", "1", "--verify")).map { (code, _) =>
                code == 0
              }
          }
        }
      case None => IO.sleep(15.seconds)

  private def poll(timeout: FiniteDuration)(check: IO[Boolean]): IO[Unit] =
    IO.monotonic.flatMap { now =>
      val deadline = now + timeout
      def loop: IO[Unit] =
        check.flatMap { done =>
          if done then IO.unit
          else
            IO.monotonic.flatMap { current =>
              if current >= deadline
              then IO.raiseError(TimeoutException(s"condition not met within $timeout"))
              else IO.sleep(500.millis) >> loop
            }
        }
      loop
    }

  private def readTail(path: Path, maxLines: Int = 100): IO[String] =
    Files[IO]
      .readAll(path)
      .through(fs2.text.utf8.decode)
      .through(fs2.text.lines)
      .compile
      .toList
      .map(_.takeRight(maxLines).mkString("\n"))

  private def binary(name: String): Option[String] =
    sys.env.get(s"TORRENT_TEST_${name.toUpperCase.replace('-', '_')}").orElse {
      sys.env
        .get("PATH")
        .toList
        .flatMap(_.split(File.pathSeparator).toSeq)
        .map(dir => new File(dir, name))
        .find(file => file.isFile && file.canExecute)
        .map(_.getAbsolutePath)
    }
end TransmissionIntegrationSuite
