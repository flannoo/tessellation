package org.tessellation.infrastructure.snapshot

import cats.Applicative
import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.order._
import cats.syntax.traverse._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.util.control.NoStackTrace

import org.tessellation.dag.domain.block.DAGBlockAsActiveTip
import org.tessellation.dag.snapshot.{GlobalSnapshotInfo, _}
import org.tessellation.domain.snapshot.GlobalSnapshotStorage
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema._
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.transaction.{RewardTransaction, TransactionReference}
import org.tessellation.sdk.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed
import org.tessellation.statechannel.StateChannelSnapshotBinary

import eu.timepit.refined.auto._
import org.typelevel.log4cats.slf4j.Slf4jLogger

object GlobalSnapshotService {

  def make[F[_]: Async: Metrics: KryoSerializer: SecurityProvider](
    globalSnapshotStorage: GlobalSnapshotStorage[F]
  ) = new SnapshotService[F, GlobalSnapshotArtifact] {

    private val logger = Slf4jLogger.getLoggerFromClass(GlobalSnapshotService.getClass)

    override def storeSnapshot(signedSnapshot: Signed[GlobalSnapshotArtifact]) =
      globalSnapshotStorage
        .prepend(signedSnapshot)
        .ifM(
          metrics.globalSnapshot(signedSnapshot),
          logger.error("Cannot save GlobalSnapshot into the storage")
        )

    override def getBalances(snapshot: GlobalSnapshotArtifact, address: Address): F[Option[Balance]] =
      snapshot.info.balances.get(address).pure[F]

    override def getGlobalSnapshotInfo(snapshot: GlobalSnapshotArtifact): F[GlobalSnapshotInfo] = snapshot.info.pure[F]

    override def createSnapshot(
      lastSnapshot: Signed[GlobalSnapshotArtifact],
      trigger: ConsensusTrigger,
      stateChannelSnapshots: SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
      acceptedBlocks: SortedSet[DAGBlockAsActiveTip],
      lastTxRefs: Map[Address, TransactionReference],
      lastBalances: Map[Address, Balance],
      rewards: SortedSet[RewardTransaction],
      tips: SnapshotTips
    ): F[GlobalSnapshotArtifact] = for {
      lastArtifactHash <- lastSnapshot.value.hashF
      currentOrdinal = lastSnapshot.ordinal.next
      currentEpochProgress = trigger match {
        case EventTrigger => lastSnapshot.epochProgress
        case TimeTrigger  => lastSnapshot.epochProgress.next
      }
      sCSnapshotHashes <- stateChannelSnapshots.toList.traverse { case (address, nel) => nel.head.hashF.map(address -> _) }
        .map(_.toMap)
      updatedLastStateChannelSnapshotHashes = lastSnapshot.info.lastStateChannelSnapshotHashes ++ sCSnapshotHashes
      (height, subHeight) <- getHeightAndSubHeight(
        lastSnapshot.height,
        lastSnapshot.subHeight,
        tips.deprecated,
        tips.remainedActive,
        acceptedBlocks
      )
      updatedLastTxRefs = lastSnapshot.info.lastTxRefs ++ lastTxRefs
      updatedLastBalances = lastSnapshot.info.balances ++ lastBalances
      globalSnapshot = GlobalSnapshot(
        currentOrdinal,
        height,
        subHeight,
        lastArtifactHash,
        acceptedBlocks,
        stateChannelSnapshots,
        rewards,
        currentEpochProgress,
        GlobalSnapshot.nextFacilitators,
        GlobalSnapshotInfo(
          updatedLastStateChannelSnapshotHashes,
          updatedLastTxRefs,
          updatedLastBalances
        ),
        tips
      )

    } yield globalSnapshot

    private def getHeightAndSubHeight(
      lastHeight: Height,
      lastSubHeight: SubHeight,
      deprecated: Set[DeprecatedTip],
      remainedActive: Set[ActiveTip],
      accepted: Set[DAGBlockAsActiveTip]
    ): F[(Height, SubHeight)] = {
      val tipHeights = (deprecated.map(_.block.height) ++ remainedActive.map(_.block.height) ++ accepted
        .map(_.block.height)).toList

      for {
        height <- tipHeights.minimumOption.liftTo[F](NoTipsRemaining)

        _ <-
          if (height < lastHeight)
            InvalidHeight(lastHeight, height).raiseError
          else
            Applicative[F].unit

        subHeight = if (height === lastHeight) lastSubHeight.next else SubHeight.MinValue
      } yield (height, subHeight)
    }

    object metrics {

      def globalSnapshot(signedGS: Signed[GlobalSnapshotArtifact]): F[Unit] = {
        val activeTipsCount = signedGS.tips.remainedActive.size + signedGS.blocks.size
        val deprecatedTipsCount = signedGS.tips.deprecated.size
        val transactionCount = signedGS.blocks.map(_.block.transactions.size).sum
        val scSnapshotCount = signedGS.stateChannelSnapshots.view.values.map(_.size).sum

        Metrics[F].updateGauge("dag_global_snapshot_ordinal", signedGS.ordinal.value) >>
          Metrics[F].updateGauge("dag_global_snapshot_height", signedGS.height.value) >>
          Metrics[F].updateGauge("dag_global_snapshot_signature_count", signedGS.proofs.size) >>
          Metrics[F]
            .updateGauge("dag_global_snapshot_tips_count", deprecatedTipsCount, Seq(("tip_type", "deprecated"))) >>
          Metrics[F].updateGauge("dag_global_snapshot_tips_count", activeTipsCount, Seq(("tip_type", "active"))) >>
          Metrics[F].incrementCounterBy("dag_global_snapshot_blocks_total", signedGS.blocks.size) >>
          Metrics[F].incrementCounterBy("dag_global_snapshot_transactions_total", transactionCount) >>
          Metrics[F].incrementCounterBy("dag_global_snapshot_state_channel_snapshots_total", scSnapshotCount)
      }
    }
  }

  case object NoTipsRemaining extends NoStackTrace
  case class InvalidHeight(lastHeight: Height, currentHeight: Height) extends NoStackTrace

}
