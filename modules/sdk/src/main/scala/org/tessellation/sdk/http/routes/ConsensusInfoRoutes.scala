package org.tessellation.sdk.http.routes

import cats.Order
import cats.effect.Async
import cats.syntax.all._

import org.tessellation.http.routes.internal.{InternalUrlPrefix, PublicRoutes}
import org.tessellation.schema.peer.{PeerId, PeerInfo}
import org.tessellation.sdk.domain.cluster.services.Cluster
import org.tessellation.sdk.http.routes.ConsensusInfoRoutes.ConsensusInfo
import org.tessellation.sdk.infrastructure.consensus.{ConsensusOutcome, ConsensusStorage}

import derevo.circe.magnolia.encoder
import derevo.derive
import eu.timepit.refined.auto._
import io.circe.Encoder
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl

class ConsensusInfoRoutes[F[_]: Async, Key: Order: Encoder](
  cluster: Cluster[F],
  consensusStorage: ConsensusStorage[F, _, Key, _, _],
  selfId: PeerId
) extends Http4sDsl[F]
    with PublicRoutes[F] {

  protected val prefixPath: InternalUrlPrefix = "/consensus"

  protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "latest" / "peers" =>
      consensusStorage.getLastConsensusOutcome.flatMap {
        case Some(outcome) => Ok(makeConsensusInfo(outcome))
        case _             => NotFound()
      }
  }

  private def makeConsensusInfo(outcome: ConsensusOutcome[Key, _, _]): F[ConsensusInfo[Key]] =
    filterClusterPeers(outcome.facilitators.toSet.incl(selfId))
      .map(ConsensusInfo(outcome.key, _))

  private def filterClusterPeers(peers: Set[PeerId]): F[Set[PeerInfo]] =
    cluster.info.map(_.filter(peerInfo => peers.contains(peerInfo.id)))

}

object ConsensusInfoRoutes {
  @derive(encoder)
  case class ConsensusInfo[Key](
    key: Key,
    peers: Set[PeerInfo]
  )
}
