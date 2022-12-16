package org.tessellation.dag.l1.modules

import cats.effect.Async
import cats.effect.std.Random

import org.tessellation.dag.domain.block.Block
import org.tessellation.dag.l1.domain.snapshot.programs.SnapshotProcessor
import org.tessellation.dag.l1.http.p2p.P2PClient
import org.tessellation.dag.snapshot.Snapshot
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.transaction.Transaction
import org.tessellation.sdk.domain.cluster.programs.{Joining, L0PeerDiscovery, PeerDiscovery}
import org.tessellation.sdk.modules.SdkPrograms
import org.tessellation.security.SecurityProvider

object Programs {

  def make[F[_]: Async: KryoSerializer: SecurityProvider: Random, A <: Transaction, B <: Block[A], C <: Snapshot[B]](
    sdkPrograms: SdkPrograms[F],
    p2pClient: P2PClient[F, A, B],
    storages: Storages[F, A, B],
    snapshotProcessor: SnapshotProcessor[F, A, B, C]
  ): Programs[F, A, B, C] = {
    val l0PeerDiscovery = L0PeerDiscovery.make(p2pClient.l0Cluster, storages.l0Cluster)
//    val snapshotProcessor = DAGSnapshotProcessor
//      .make(storages.address, storages.block, storages.lastGlobalSnapshotStorage, storages.transaction)

    new Programs[F, A, B, C](sdkPrograms.peerDiscovery, l0PeerDiscovery, sdkPrograms.joining, snapshotProcessor) {}
  }
}

sealed abstract class Programs[F[_], A <: Transaction, B <: Block[A], C <: Snapshot[B]] private (
  val peerDiscovery: PeerDiscovery[F],
  val l0PeerDiscovery: L0PeerDiscovery[F],
  val joining: Joining[F],
  val snapshotProcessor: SnapshotProcessor[F, A, B, C]
)
