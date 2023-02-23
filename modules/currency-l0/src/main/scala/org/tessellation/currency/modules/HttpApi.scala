package org.tessellation.currency.modules

import cats.effect.Async
import cats.syntax.semigroupk._
import org.http4s.implicits.http4sKleisliResponseSyntaxOptionT
import org.http4s.server.Router
import org.http4s.server.middleware.{CORS, RequestLogger, ResponseLogger}
import org.http4s.{HttpApp, HttpRoutes}
import org.tessellation.currency.domain.cell.{L0Cell, L0CellInput}
import org.tessellation.currency.schema.currency.{CurrencyBlock, CurrencySnapshot, CurrencyTransaction}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.config.AppEnvironment
import org.tessellation.sdk.config.AppEnvironment.{Dev, Testnet}
import org.tessellation.sdk.config.types.HttpConfig
import org.tessellation.sdk.http.p2p.middleware.{PeerAuthMiddleware, `X-Id-Middleware`}
import org.tessellation.sdk.http.routes
import org.tessellation.sdk.http.routes._
import org.tessellation.sdk.infrastructure.healthcheck.ping.PingHealthCheckRoutes
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed

import java.security.PrivateKey

object HttpApi {

  def make[F[_]: Async: SecurityProvider: KryoSerializer: Metrics](
    storages: Storages[F],
    queues: Queues[F],
    services: Services[F],
    programs: Programs[F],
    healthchecks: HealthChecks[F],
    privateKey: PrivateKey,
    environment: AppEnvironment,
    selfId: PeerId,
    nodeVersion: String,
    httpCfg: HttpConfig
  ): HttpApi[F] =
    new HttpApi[F](
      storages,
      queues,
      services,
      programs,
      healthchecks,
      privateKey,
      environment,
      selfId,
      nodeVersion,
      httpCfg
    ) {}
}

sealed abstract class HttpApi[F[_]: Async: SecurityProvider: KryoSerializer: Metrics] private (
  storages: Storages[F],
  queues: Queues[F],
  services: Services[F],
  programs: Programs[F],
  healthchecks: HealthChecks[F],
  privateKey: PrivateKey,
  environment: AppEnvironment,
  selfId: PeerId,
  nodeVersion: String,
  httpCfg: HttpConfig
) {

  private val mkCell = (block: Signed[CurrencyBlock]) => L0Cell.mkL0Cell(queues.l1Output).apply(L0CellInput.HandleL1Block(block))

  private val snapshotRoutes = SnapshotRoutes[F, CurrencySnapshot](storages.snapshot, "/snapshots")
  private val clusterRoutes =
    ClusterRoutes[F](programs.joining, programs.peerDiscovery, storages.cluster, services.cluster, services.collateral)
  private val nodeRoutes = NodeRoutes[F](storages.node, storages.session, storages.cluster, nodeVersion, httpCfg, selfId)

  private val registrationRoutes = RegistrationRoutes[F](services.cluster)
  private val gossipRoutes = GossipRoutes[F](storages.rumor, services.gossip)
  private val currencyRoutes = routes.CurrencyRoutes[F, CurrencyTransaction, CurrencyBlock, CurrencySnapshot](services.address, mkCell)
  private val consensusInfoRoutes = new ConsensusInfoRoutes[F, SnapshotOrdinal](services.cluster, services.consensus.storage, selfId)
  private val consensusRoutes = services.consensus.routes.p2pRoutes

  private val healthcheckP2PRoutes = {
    val pingHealthcheckRoutes = PingHealthCheckRoutes[F](healthchecks.ping)

    Router("healthcheck" -> pingHealthcheckRoutes.p2pRoutes)
  }

  private val debugRoutes = DebugRoutes[F](
    storages.cluster,
    services.consensus,
    services.gossip,
    services.session
  ).routes

  private val metricRoutes = routes.MetricRoutes[F]().routes
  private val targetRoutes = routes.TargetRoutes[F](services.cluster).routes

  private val openRoutes: HttpRoutes[F] =
    CORS.policy.withAllowOriginAll.withAllowHeadersAll.withAllowCredentials(false).apply {
      PeerAuthMiddleware
        .responseSignerMiddleware(privateKey, storages.session, selfId) {
          `X-Id-Middleware`.responseMiddleware(selfId) {
            (if (environment == Testnet || environment == Dev) debugRoutes else HttpRoutes.empty) <+>
              metricRoutes <+>
              targetRoutes <+>
              snapshotRoutes.publicRoutes <+>
              clusterRoutes.publicRoutes <+>
              currencyRoutes.publicRoutes <+>
              nodeRoutes.publicRoutes <+>
              consensusInfoRoutes.publicRoutes
          }
        }
    }

  private val p2pRoutes: HttpRoutes[F] =
    PeerAuthMiddleware.responseSignerMiddleware(privateKey, storages.session, selfId)(
      registrationRoutes.p2pPublicRoutes <+>
        clusterRoutes.p2pPublicRoutes <+>
        PeerAuthMiddleware.requestVerifierMiddleware(
          PeerAuthMiddleware.requestTokenVerifierMiddleware(services.session)(
            PeerAuthMiddleware.requestCollateralVerifierMiddleware(services.collateral)(
              snapshotRoutes.p2pRoutes <+>
                clusterRoutes.p2pRoutes <+>
                nodeRoutes.p2pRoutes <+>
                gossipRoutes.p2pRoutes <+>
                healthcheckP2PRoutes <+>
                consensusRoutes
            )
          )
        )
    )

  private val cliRoutes: HttpRoutes[F] =
    clusterRoutes.cliRoutes
  private val loggers: HttpApp[F] => HttpApp[F] = { http: HttpApp[F] =>
    RequestLogger.httpApp(logHeaders = true, logBody = false)(http)
  }.andThen { http: HttpApp[F] =>
    ResponseLogger.httpApp(logHeaders = true, logBody = false)(http)
  }

  val publicApp: HttpApp[F] = loggers(openRoutes.orNotFound)
  val p2pApp: HttpApp[F] = loggers(p2pRoutes.orNotFound)
  val cliApp: HttpApp[F] = loggers(cliRoutes.orNotFound)

}
