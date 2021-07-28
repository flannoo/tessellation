package org.tessellation.http

import cats.effect._
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.circe.generic.auto._
import io.circe.syntax._
import io.prometheus.client.exporter.common.TextFormat
import org.http4s._
import org.http4s.circe._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.server.Router
import org.http4s.server.blaze.BlazeServerBuilder
import org.tessellation.consensus.L1ConsensusStep.{BroadcastProposalPayload, BroadcastProposalResponse}
import org.tessellation.consensus.{L1Edge, ProposalResponse}
import org.tessellation.metrics.Metrics
import org.tessellation.node.{Node, Peer}
import org.tessellation.schema.CellError

import java.io.{StringWriter, Writer}
import scala.concurrent.ExecutionContext.Implicits.global

class HttpServer(node: Node, httpClient: HttpClient, metrics: Metrics) {
  implicit val cs: ContextShift[IO] = IO.contextShift(global)
  implicit val timer: Timer[IO] = IO.timer(global)
  private val logger = Slf4jLogger.getLogger[IO]

  private val service = HttpRoutes
    .of[IO] {
      case GET -> Root / "debug" / "peers" =>
        for {
          peers <- node.getPeers
          res <- Ok(peers.asJson)
        } yield res
      case req @ POST -> Root / "join" =>
        implicit val decoder = jsonOf[IO, Peer]
        for {
          joiningPeer <- req.as[Peer]
          _ <- node.updatePeers(joiningPeer)
          selfPeer = Peer(node.ip, node.port, node.id)
          _ <- logger.info(s"$joiningPeer joined to $selfPeer")
          res <- Ok(selfPeer.asJson)
        } yield res
      case req @ POST -> Root / "proposal" =>
        implicit val decoder = jsonOf[IO, BroadcastProposalPayload]
        for {
          request <- req.as[BroadcastProposalPayload]
          _ <- logger.info(
            s"Received proposal: ${request.proposal} for round ${request.roundId} and facilitators ${request.facilitators}"
          )

          consensus <- node.participateInL1Consensus(
            request.roundId,
            request.senderId,
            request.consensusOwnerId,
            L1Edge(request.proposal),
            request.facilitators,
            httpClient
          )
          res <- consensus match {
            case Right(ProposalResponse(txs)) =>
              Ok(BroadcastProposalResponse(request.roundId, request.proposal, txs).asJson)
            case Left(CellError(reason)) => InternalServerError(reason)
            case _                       => InternalServerError()
          }
        } yield res
      // TODO: Implement after turning off random tx generator
      //      case req @ POST -> Root / "transaction" => {
      //        tx <- req.as[L1Transaction]
      //      } yield ()
      case _ => NotFound()
    }

  private val metricsService = HttpRoutes.of[IO] {
    case GET -> Root / "micrometer-metrics" =>
      IO.delay {
        val writer: Writer = new StringWriter()
        TextFormat.write004(writer, metrics.collectorRegistry.metricFamilySamples())
        writer.toString
      }.flatMap(Ok(_))
  }

  private val publicAPI = BlazeServerBuilder[IO](global)
    .bindHttp(9001, "0.0.0.0")
    .withHttpApp(Router("/" -> service).orNotFound)

  private val metricsAPI = BlazeServerBuilder[IO](global)
    .bindHttp(9000, "0.0.0.0")
    .withHttpApp(Router("/" -> metricsService).orNotFound)

  def run() = publicAPI.serve.merge(metricsAPI.serve)
}

object HttpServer {

  implicit val contextShift = IO.contextShift(scala.concurrent.ExecutionContext.global)

  def apply(node: Node, httpClient: HttpClient, metrics: Metrics): HttpServer =
    new HttpServer(node, httpClient, metrics)
}
