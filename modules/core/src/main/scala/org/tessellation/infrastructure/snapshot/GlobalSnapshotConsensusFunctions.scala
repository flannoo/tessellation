package org.tessellation.infrastructure.snapshot

import cats.Applicative
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.functorFilter._
import cats.syntax.option._
import cats.syntax.order._
import cats.syntax.show._
import cats.syntax.traverse._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.util.control.NoStackTrace

import org.tessellation.domain.rewards.Rewards
import org.tessellation.domain.snapshot._
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema._
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.{Amount, Balance}
import org.tessellation.schema.block.DAGBlock
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.transaction.{DAGTransaction, RewardTransaction}
import org.tessellation.sdk.config.AppEnvironment
import org.tessellation.sdk.config.AppEnvironment.Mainnet
import org.tessellation.sdk.domain.block.processing._
import org.tessellation.sdk.domain.consensus.ConsensusFunctions.InvalidArtifact
import org.tessellation.sdk.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed
import org.tessellation.statechannel.StateChannelOutput

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.auto._
import org.typelevel.log4cats.slf4j.Slf4jLogger

case class InvalidHeight(lastHeight: Height, currentHeight: Height) extends NoStackTrace
case object NoTipsRemaining extends NoStackTrace
case object ArtifactMismatch extends InvalidArtifact
abstract class GlobalSnapshotConsensusFunctions[F[_]: Async: SecurityProvider]
    extends SnapshotConsensusFunctions[
      F,
      DAGTransaction,
      DAGBlock,
      GlobalSnapshotEvent,
      GlobalSnapshotArtifact,
      GlobalSnapshotContext,
      ConsensusTrigger
    ] {}

object GlobalSnapshotConsensusFunctions {

  def make[F[_]: Async: KryoSerializer: SecurityProvider: Metrics](
    globalSnapshotStorage: SnapshotStorage[F, GlobalSnapshotArtifact, GlobalSnapshotContext],
    blockAcceptanceManager: BlockAcceptanceManager[F, DAGTransaction, DAGBlock],
    stateChannelEventsProcessor: GlobalSnapshotStateChannelEventsProcessor[F],
    collateral: Amount,
    rewards: Rewards[F],
    environment: AppEnvironment
  ): GlobalSnapshotConsensusFunctions[F] = new GlobalSnapshotConsensusFunctions[F] {

    private val logger = Slf4jLogger.getLoggerFromClass(GlobalSnapshotConsensusFunctions.getClass)

    def getRequiredCollateral: Amount = collateral

    def consumeSignedMajorityArtifact(signedArtifact: Signed[GlobalSnapshotArtifact]): F[Unit] =
      globalSnapshotStorage
        .prepend(signedArtifact)
        .ifM(
          metrics.globalSnapshot(signedArtifact),
          logger.error("Cannot save GlobalSnapshot into the storage")
        )

    override def validateArtifact(
      lastSignedArtifact: Signed[IncrementalGlobalSnapshot],
      lastContext: GlobalSnapshotInfo,
      trigger: ConsensusTrigger
    )(
      artifact: IncrementalGlobalSnapshot
    ): F[Either[InvalidArtifact, IncrementalGlobalSnapshot]] = {
      val dagEvents = artifact.blocks.unsorted.map(_.block.asRight[StateChannelOutput])
      val scEvents = artifact.stateChannelSnapshots.toList.flatMap {
        case (address, stateChannelBinaries) => stateChannelBinaries.map(StateChannelOutput(address, _).asLeft[DAGEvent]).toList
      }
      val events = dagEvents ++ scEvents

      def recreatedArtifact: F[IncrementalGlobalSnapshot] =
        createProposalArtifact(lastSignedArtifact.ordinal, lastSignedArtifact, lastContext, trigger, events)
          .map(_._1)

      recreatedArtifact
        .map(_ === artifact)
        .ifF(
          artifact.asRight[InvalidArtifact],
          ArtifactMismatch.asLeft[IncrementalGlobalSnapshot]
        )
    }

    def createGlobalSnapshotInfo(
      lastSnapshotContext: GlobalSnapshotContext,
      lastSnapshot: IncrementalGlobalSnapshot,
      snapshot: IncrementalGlobalSnapshot
    ): F[GlobalSnapshotInfo] = for {
      lastActiveTips <- lastSnapshot.activeTips
      lastDeprecatedTips = lastSnapshot.tips.deprecated

      blocksForAcceptance = snapshot.blocks.toList.map(_.block)
      acceptanceResult <- acceptBlocks(blocksForAcceptance, lastSnapshotContext, lastActiveTips, lastDeprecatedTips)
      _ <- CannotApplyBlocksError(acceptanceResult.notAccepted.map(_._2)).raiseError[F, Unit].whenA(acceptanceResult.notAccepted.nonEmpty)

      scEvents = snapshot.stateChannelSnapshots.toList.flatMap {
        case (address, stateChannelBinaries) => stateChannelBinaries.map(StateChannelOutput(address, _)).toList
      }
      (scSnapshots, returnedSCEvents) <- stateChannelEventsProcessor.process(lastSnapshotContext, scEvents)
      sCSnapshotHashes <- scSnapshots.toList.traverse { case (address, nel) => nel.head.hashF.map(address -> _) }
        .map(_.toMap)
      updatedLastStateChannelSnapshotHashes = lastSnapshotContext.lastStateChannelSnapshotHashes ++ sCSnapshotHashes
      _ <- CannotApplyStateChannelsError(returnedSCEvents).raiseError[F, Unit].whenA(returnedSCEvents.nonEmpty)

      transactionsRefs = lastSnapshotContext.lastTxRefs ++ acceptanceResult.contextUpdate.lastTxRefs

      (updatedBalancesByRewards, acceptedRewardTxs) = updateBalancesWithRewards(
        lastSnapshotContext,
        acceptanceResult.contextUpdate.balances,
        snapshot.rewards
      )
      diffRewards = acceptedRewardTxs -- lastSnapshot.rewards
      _ <- CannotApplyRewardsError(diffRewards).raiseError[F, Unit].whenA(diffRewards.nonEmpty)

    } yield GlobalSnapshotInfo(updatedLastStateChannelSnapshotHashes, transactionsRefs, updatedBalancesByRewards)

    def createProposalArtifact(
      lastKey: GlobalSnapshotKey,
      lastArtifact: Signed[GlobalSnapshotArtifact],
      snapshotContext: GlobalSnapshotContext,
      trigger: ConsensusTrigger,
      events: Set[GlobalSnapshotEvent]
    ): F[(GlobalSnapshotArtifact, Set[GlobalSnapshotEvent])] = {
      val (scEvents: List[StateChannelEvent], dagEvents: List[DAGEvent]) = events.filter { event =>
        if (environment == Mainnet) event.isRight else true
      }.toList.partitionMap(identity)

      val blocksForAcceptance = dagEvents
        .filter(_.height > lastArtifact.height)

      for {
        lastArtifactHash <- lastArtifact.value.hashF
        currentOrdinal = lastArtifact.ordinal.next
        currentEpochProgress = trigger match {
          case EventTrigger => lastArtifact.epochProgress
          case TimeTrigger  => lastArtifact.epochProgress.next
        }
        (scSnapshots, returnedSCEvents) <- stateChannelEventsProcessor.process(snapshotContext, scEvents)

        lastActiveTips <- lastArtifact.activeTips
        lastDeprecatedTips = lastArtifact.tips.deprecated

        acceptanceResult <- acceptBlocks(blocksForAcceptance, snapshotContext, lastActiveTips, lastDeprecatedTips)

        (deprecated, remainedActive, accepted) = getUpdatedTips(
          lastActiveTips,
          lastDeprecatedTips,
          acceptanceResult,
          currentOrdinal
        )

        (height, subHeight) <- getHeightAndSubHeight(lastArtifact, deprecated, remainedActive, accepted)

        facilitators = lastArtifact.proofs.map(_.id)
        transactions = lastArtifact.value.blocks.flatMap(_.block.transactions.toSortedSet).map(_.value)

        rewardTxsForAcceptance <- rewards.feeDistribution(lastArtifact.ordinal, transactions, facilitators).flatMap { feeRewardTxs =>
          trigger match {
            case EventTrigger => feeRewardTxs.pure[F]
            case TimeTrigger  => rewards.mintedDistribution(lastArtifact.epochProgress, facilitators).map(_ ++ feeRewardTxs)
          }
        }

        (_, acceptedRewardTxs) = updateBalancesWithRewards(snapshotContext, acceptanceResult.contextUpdate.balances, rewardTxsForAcceptance)

        returnedDAGEvents = getReturnedDAGEvents(acceptanceResult)

        globalSnapshot = IncrementalGlobalSnapshot(
          currentOrdinal,
          height,
          subHeight,
          lastArtifactHash,
          accepted,
          scSnapshots,
          acceptedRewardTxs,
          currentEpochProgress,
          GlobalSnapshot.nextFacilitators,
          SnapshotTips(
            deprecated = deprecated,
            remainedActive = remainedActive
          )
        )
        returnedEvents = returnedSCEvents.map(_.asLeft[DAGEvent]).union(returnedDAGEvents)
      } yield (globalSnapshot, returnedEvents)
    }

    private def acceptBlocks(
      blocksForAcceptance: List[Signed[DAGBlock]],
      lastSnapshotContext: GlobalSnapshotContext,
      lastActiveTips: SortedSet[ActiveTip],
      lastDeprecatedTips: SortedSet[DeprecatedTip]
    ) = for {
      tipUsages <- getTipsUsages(lastActiveTips, lastDeprecatedTips).pure[F]
      context = BlockAcceptanceContext.fromStaticData(
        lastSnapshotContext.balances,
        lastSnapshotContext.lastTxRefs,
        tipUsages,
        collateral
      )
      acceptanceResult <- blockAcceptanceManager.acceptBlocksIteratively(blocksForAcceptance, context)
    } yield acceptanceResult

    private def updateBalancesWithRewards(
      lastSnapshotInfo: GlobalSnapshotContext,
      acceptedBalances: Map[Address, Balance],
      rewardsForAcceptance: SortedSet[RewardTransaction]
    ) = {
      val balances = lastSnapshotInfo.balances ++ acceptedBalances
      acceptRewardTxs(balances, rewardsForAcceptance)
    }

    private def acceptRewardTxs(
      balances: SortedMap[Address, Balance],
      txs: SortedSet[RewardTransaction]
    ): (SortedMap[Address, Balance], SortedSet[RewardTransaction]) =
      txs.foldLeft((balances, SortedSet.empty[RewardTransaction])) { (acc, tx) =>
        val (updatedBalances, acceptedTxs) = acc

        updatedBalances
          .getOrElse(tx.destination, Balance.empty)
          .plus(tx.amount)
          .map(balance => (updatedBalances.updated(tx.destination, balance), acceptedTxs + tx))
          .getOrElse(acc)
      }

    private def getHeightAndSubHeight(
      lastGS: IncrementalGlobalSnapshot,
      deprecated: Set[DeprecatedTip],
      remainedActive: Set[ActiveTip],
      accepted: Set[BlockAsActiveTip[DAGBlock]]
    ): F[(Height, SubHeight)] = {
      val tipHeights = (deprecated.map(_.block.height) ++ remainedActive.map(_.block.height) ++ accepted
        .map(_.block.height)).toList

      for {
        height <- tipHeights.minimumOption.liftTo[F](NoTipsRemaining)

        _ <-
          if (height < lastGS.height)
            InvalidHeight(lastGS.height, height).raiseError
          else
            Applicative[F].unit

        subHeight = if (height === lastGS.height) lastGS.subHeight.next else SubHeight.MinValue
      } yield (height, subHeight)
    }

    private def getReturnedDAGEvents(
      acceptanceResult: BlockAcceptanceResult[DAGBlock]
    ): Set[GlobalSnapshotEvent] =
      acceptanceResult.notAccepted.mapFilter {
        case (signedBlock, _: BlockAwaitReason) => signedBlock.asRight[StateChannelEvent].some
        case _                                  => none
      }.toSet

    object metrics {

      def globalSnapshot(signedGS: Signed[IncrementalGlobalSnapshot]): F[Unit] = {
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

  @derive(eqv, show)
  case class CannotApplyBlocksError(reasons: List[BlockNotAcceptedReason]) extends NoStackTrace {

    override def getMessage: String =
      s"Cannot build global snapshot ${reasons.show}"
  }

  @derive(eqv)
  case class CannotApplyStateChannelsError(returnedStateChannels: Set[StateChannelEvent]) extends NoStackTrace {

    override def getMessage: String =
      s"Cannot build global snapshot becuse of returned StateChannels for addresses: ${returnedStateChannels.map(_.address).show}"
  }

  @derive(eqv, show)
  case class CannotApplyRewardsError(notAcceptedRewards: SortedSet[RewardTransaction]) extends NoStackTrace {

    override def getMessage: String =
      s"Cannot build global snapshot becuase of not accepted rewards: ${notAcceptedRewards.show}"
  }
}
