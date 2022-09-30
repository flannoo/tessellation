package org.tessellation.dag.l1.rosetta

import cats.effect.unsafe.implicits.global
import cats.effect.{Async, IO}

import org.tessellation.dag.l1.rosetta.model.dag.Registry.rosettaKryoRegistrar
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.sdk.config.types.HttpServerConfig
import org.tessellation.sdk.resources.MkHttpServer
import org.tessellation.sdk.resources.MkHttpServer.ServerName
import org.tessellation.security.SecurityProvider
import org.tessellation.security.hex.Hex
import org.tessellation.shared.sharedKryoRegistrar

import com.comcast.ip4s.{Host, Port}
import org.http4s.HttpApp
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import weaver.SimpleIOSuite

object RosettaMainTest extends SimpleIOSuite {

  test("asdf") {
    for {
//      _ <- ignore("Comment to manually run test").unlessA(false)
      _ <- IO.pure("a")

    } yield {

      val res = serverResource()
      res.useForever
        .unsafeRunSync()
//      expect.all(true)
    }

  }

  private def serverResource() = {
    implicit val logger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]
    import org.tessellation.ext.kryo._
    val registrar = org.tessellation.dag.dagSharedKryoRegistrar.union(sharedKryoRegistrar).union(rosettaKryoRegistrar)
    val res = SecurityProvider
      .forAsync[IO]
      .flatMap { implicit sp =>
        KryoSerializer
          .forAsync[IO](registrar)
          .flatMap { implicit kryo =>
            println(
              "Example hex unsigned transaction: " + Hex
                .fromBytes(kryo.serialize(examples.transaction.value).toOption.get)
                .value
            )
            //            Future{
            //              while (true) {
            //                Thread.sleep(5000)
            //                println("Background future running")
            //                println(mockup.mkNewTestTransaction())
            //              }
            //            }(scala.concurrent.ExecutionContext.global)
            val value = MockData.mockup.genesis.hash.toOption.get
            MockData.mockup.genesisHash = value.value
            MockData.mockup.currentBlockHash = value
            MockData.mockup.blockToHash(MockData.mockup.genesis) = value
            val value1 = kryo.serialize(examples.transaction)
            value1.left.map(t => throw t)
            println(
              "Example hex signed transaction: " + Hex
                .fromBytes(value1.toOption.get)
                .value
            )
            val http = new RosettaRoutes[IO]("mainnet", new BlockIndexClient(), new L1Client())(Async[IO], kryo, sp)
            val publicApp: HttpApp[IO] = http.allRoutes.orNotFound
            //loggers(openRoutes.orNotFound)
            MkHttpServer[IO].newEmber(
              ServerName("public"),
              HttpServerConfig(Host.fromString("0.0.0.0").get, Port.fromInt(8080).get),
              publicApp
            )
          }
      }
    res
  }
}
