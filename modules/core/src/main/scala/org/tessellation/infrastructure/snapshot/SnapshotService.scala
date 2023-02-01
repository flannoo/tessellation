package org.tessellation.infrastructure.snapshot

import cats.data.NonEmptyList

import scala.collection.immutable.{SortedMap, SortedSet}

import org.tessellation.dag.domain.block.DAGBlockAsActiveTip
import org.tessellation.dag.snapshot.GlobalSnapshotInfo
import org.tessellation.schema.SnapshotTips
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.transaction.{RewardTransaction, TransactionReference}
import org.tessellation.sdk.infrastructure.consensus.trigger.ConsensusTrigger
import org.tessellation.security.signature.Signed
import org.tessellation.statechannel.StateChannelSnapshotBinary

trait SnapshotService[F[_], SnapshotArtifact] {

  def storeSnapshot(signedSnapshot: Signed[SnapshotArtifact]): F[Unit]

  def getBalances(snapshot: SnapshotArtifact, address: Address): F[Option[Balance]]

  def getGlobalSnapshotInfo(snapshot: SnapshotArtifact): F[GlobalSnapshotInfo]

  def createSnapshot(
    lastSnapshot: Signed[SnapshotArtifact],
    trigger: ConsensusTrigger,
    stateChannelSnapshots: SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
    acceptedBlocks: SortedSet[DAGBlockAsActiveTip],
    lastTxRefs: Map[Address, TransactionReference],
    lastBalances: Map[Address, Balance],
    rewards: SortedSet[RewardTransaction],
    tips: SnapshotTips
  ): F[SnapshotArtifact]
}
