package org.tessellation.node.shared.infrastructure.consensus

import java.security.KeyPair

import cats.effect.Async
import cats.effect.std.{Random, Supervisor}
import cats.kernel.Next
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Eq, Order, Show}

import scala.reflect.runtime.universe.TypeTag

import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.config.types.ConsensusConfig
import org.tessellation.node.shared.domain.cluster.services.Session
import org.tessellation.node.shared.domain.cluster.storage.ClusterStorage
import org.tessellation.node.shared.domain.consensus.ConsensusFunctions
import org.tessellation.node.shared.domain.gossip.Gossip
import org.tessellation.node.shared.domain.node.NodeStorage
import org.tessellation.node.shared.domain.seedlist.SeedlistEntry
import org.tessellation.node.shared.infrastructure.gossip.RumorHandler
import org.tessellation.node.shared.infrastructure.metrics.Metrics
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.{Hasher, SecurityProvider}

import io.circe.{Decoder, Encoder}
import org.http4s.client.Client

object Consensus {

  def make[
    F[_]: Async: Supervisor: Random: KryoSerializer: SecurityProvider: Metrics: Hasher,
    Event: TypeTag: Decoder,
    Key: Show: Order: Next: TypeTag: Encoder: Decoder,
    Artifact <: AnyRef: Eq: TypeTag: Encoder: Decoder,
    Context <: AnyRef: Eq: TypeTag: Encoder: Decoder
  ](
    consensusFns: ConsensusFunctions[F, Event, Key, Artifact, Context],
    gossip: Gossip[F],
    selfId: PeerId,
    keyPair: KeyPair,
    consensusConfig: ConsensusConfig,
    seedlist: Option[Set[SeedlistEntry]],
    clusterStorage: ClusterStorage[F],
    nodeStorage: NodeStorage[F],
    client: Client[F],
    session: Session[F]
  ): F[Consensus[F, Event, Key, Artifact, Context]] =
    for {
      storage <- ConsensusStorage.make[F, Event, Key, Artifact, Context](consensusConfig)
      stateUpdater = ConsensusStateUpdater.make[F, Event, Key, Artifact, Context](
        consensusFns,
        storage,
        gossip,
        keyPair
      )
      stateCreator = ConsensusStateCreator.make[F, Event, Key, Artifact, Context](
        consensusFns,
        storage,
        gossip,
        selfId,
        seedlist
      )
      stateRemover = ConsensusStateRemover.make[F, Event, Key, Artifact, Context](
        storage,
        gossip
      )
      consClient = ConsensusClient.make[F, Key, Artifact, Context](client, session)
      manager <- ConsensusManager.make[F, Event, Key, Artifact, Context](
        consensusConfig,
        storage,
        stateCreator,
        stateUpdater,
        stateRemover,
        nodeStorage,
        clusterStorage,
        consClient,
        selfId
      )
      handler = ConsensusHandler.make[F, Event, Key, Artifact, Context](storage, manager, consensusFns)
      routes = new ConsensusRoutes[F, Key, Artifact, Context](storage)
    } yield new Consensus(handler, storage, manager, routes, consensusFns)
}

sealed class Consensus[F[_]: Async, Event, Key, Artifact, Context] private (
  val handler: RumorHandler[F],
  val storage: ConsensusStorage[F, Event, Key, Artifact, Context],
  val manager: ConsensusManager[F, Key, Artifact, Context],
  val routes: ConsensusRoutes[F, Key, Artifact, Context],
  val consensusFns: ConsensusFunctions[F, Event, Key, Artifact, Context]
) {}
