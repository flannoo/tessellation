package org.tesselation.http.routes

import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.flatMap._

import org.tesselation.domain.cluster.{Cluster, ClusterStorage}
import org.tesselation.ext.http4s.refined._
import org.tesselation.schema.cluster._
import org.tesselation.schema.peer.JoinRequest

import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router
import org.typelevel.log4cats.slf4j.Slf4jLogger

final case class ClusterRoutes[F[_]: Async](
  clusterStorage: ClusterStorage[F],
  cluster: Cluster[F]
) extends Http4sDsl[F] {

  implicit val logger = Slf4jLogger.getLogger[F]

  private[routes] val prefixPath = "/cluster"

  private val cli: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "join" =>
      req.decodeR[PeerToJoin] { peerToJoin =>
        cluster
          .join(peerToJoin)
          .flatMap(_ => Ok())
          .recoverWith {
            case NodeStateDoesNotAllowForJoining(nodeState) =>
              Conflict(s"Node state=${nodeState} does not allow for joining the cluster.")
            case PeerIdInUse(id) => Conflict(s"Peer id=${id} already in use.")
            case PeerHostPortInUse(host, port) =>
              Conflict(s"Peer host=${host.toString} port=${port.value} already in use.")
            case SessionAlreadyExists =>
              Conflict(s"Session already exists.")
          }
      }
  }

  private val p2p: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "join" =>
      req.decodeR[JoinRequest] { joinRequest =>
        cluster
          .joinRequest(joinRequest)
          .flatMap(_ => Ok())
      }
  }

  val p2pRoutes: HttpRoutes[F] = Router(
    prefixPath -> p2p
  )

  val cliRoutes: HttpRoutes[F] = Router(
    prefixPath -> cli
  )
}
