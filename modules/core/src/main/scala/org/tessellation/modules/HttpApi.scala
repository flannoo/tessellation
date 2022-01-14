package org.tessellation.modules

import java.security.PrivateKey

import cats.effect.Async
import cats.syntax.functor._
import cats.syntax.semigroupk._

import org.tessellation.http.routes
import org.tessellation.http.routes._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.config.AppEnvironment
import org.tessellation.sdk.config.AppEnvironment.{Dev, Testnet}
import org.tessellation.sdk.http.p2p.middleware.PeerAuthMiddleware
import org.tessellation.security.SecurityProvider

import com.comcast.ip4s.Host
import org.http4s.implicits.http4sKleisliResponseSyntaxOptionT
import org.http4s.server.middleware.{RequestLogger, ResponseLogger}
import org.http4s.{HttpApp, HttpRoutes}

object HttpApi {

  def make[F[_]: Async: SecurityProvider: KryoSerializer](
    storages: Storages[F],
    queues: Queues[F],
    services: Services[F],
    programs: Programs[F],
    privateKey: PrivateKey,
    environment: AppEnvironment
  ): HttpApi[F] =
    new HttpApi[F](storages, queues, services, programs, privateKey, environment) {}
}

sealed abstract class HttpApi[F[_]: Async: SecurityProvider: KryoSerializer] private (
  storages: Storages[F],
  queues: Queues[F],
  services: Services[F],
  programs: Programs[F],
  privateKey: PrivateKey,
  environment: AppEnvironment
) {

  private val healthRoutes = HealthRoutes[F](services.healthcheck).routes
  private val clusterRoutes =
    ClusterRoutes[F](programs.joining, programs.peerDiscovery, programs.trustPush, storages.cluster, storages.trust)
  private val registrationRoutes = RegistrationRoutes[F](services.cluster)
  private val gossipRoutes = routes.GossipRoutes[F](storages.rumor, queues.rumor, services.gossip)
  private val trustRoutes = routes.TrustRoutes[F](storages.trust)
  private val stateChannelRoutes = routes.StateChannelRoutes[F](services.stateChannelRunner)

  private val debugRoutes = DebugRoutes[F](storages, services).routes

  private val metricRoutes = MetricRoutes[F](services).routes

  private val openRoutes: HttpRoutes[F] =
    (if (environment == Testnet || environment == Dev) debugRoutes else HttpRoutes.empty) <+>
      healthRoutes <+> metricRoutes <+> stateChannelRoutes.publicRoutes

  private val getKnownPeersId: Host => F[Set[PeerId]] = { (host: Host) =>
    storages.cluster.getPeers(host).map(_.map(_.id))
  }

  private val p2pRoutes: HttpRoutes[F] =
    PeerAuthMiddleware.responseSignerMiddleware(privateKey, storages.session)(
      registrationRoutes.p2pPublicRoutes <+>
        clusterRoutes.p2pPublicRoutes <+>
        PeerAuthMiddleware.requestVerifierMiddleware(getKnownPeersId)(
          PeerAuthMiddleware.requestTokenVerifierMiddleware(services.session)(
            healthRoutes <+>
              clusterRoutes.p2pRoutes <+>
              gossipRoutes.p2pRoutes <+>
              trustRoutes.p2pRoutes
          )
        )
    )

  private val cliRoutes: HttpRoutes[F] =
    healthRoutes <+>
      clusterRoutes.cliRoutes

  private val loggers: HttpApp[F] => HttpApp[F] = {
    { http: HttpApp[F] =>
      RequestLogger.httpApp(logHeaders = true, logBody = false)(http)
    }.andThen { http: HttpApp[F] =>
      ResponseLogger.httpApp(logHeaders = true, logBody = false)(http)
    }
  }

  val publicApp: HttpApp[F] = loggers(openRoutes.orNotFound)
  val p2pApp: HttpApp[F] = loggers(p2pRoutes.orNotFound)
  val cliApp: HttpApp[F] = loggers(cliRoutes.orNotFound)

}
