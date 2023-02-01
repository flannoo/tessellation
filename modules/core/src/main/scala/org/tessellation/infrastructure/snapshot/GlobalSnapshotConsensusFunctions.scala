package org.tessellation.infrastructure.snapshot

import cats.Eq
import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.bifunctor._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.functorFilter._
import cats.syntax.option._
import cats.syntax.order._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.util.control.NoStackTrace

import org.tessellation.dag.block.processing._
import org.tessellation.dag.domain.block.DAGBlockAsActiveTip
import org.tessellation.dag.snapshot.epoch.EpochProgress
import org.tessellation.domain.rewards.Rewards
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.schema._
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.{Amount, Balance}
import org.tessellation.schema.height.Height
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.transaction.RewardTransaction
import org.tessellation.sdk.config.AppEnvironment
import org.tessellation.sdk.config.AppEnvironment.Mainnet
import org.tessellation.sdk.domain.consensus.ConsensusFunctions
import org.tessellation.sdk.domain.consensus.ConsensusFunctions.InvalidArtifact
import org.tessellation.sdk.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed
import org.tessellation.statechannel.{StateChannelOutput, StateChannelSnapshotBinary}
import org.tessellation.syntax.sortedCollection._

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong

trait GlobalSnapshotConsensusFunctions[F[_], SnapshotArtifact]
    extends ConsensusFunctions[F, GlobalSnapshotEvent, GlobalSnapshotKey, SnapshotArtifact] {}

object GlobalSnapshotConsensusFunctions {

  def make[F[_]: Async: SecurityProvider, SnapshotArtifact: Eq](
    blockAcceptanceManager: BlockAcceptanceManager[F],
    stateChannelEventsProcessor: GlobalSnapshotStateChannelEventsProcessor[F],
    collateral: Amount,
    rewards: Rewards[F],
    environment: AppEnvironment,
    snapshotService: SnapshotService[F, SnapshotArtifact],
    getHeight: SnapshotArtifact => Height,
    getTips: SnapshotArtifact => SnapshotTips,
    getActiveTips: SnapshotArtifact => F[SortedSet[ActiveTip]],
    getOrdinal: SnapshotArtifact => SnapshotOrdinal,
    getBlocks: SnapshotArtifact => SortedSet[DAGBlockAsActiveTip],
    getStateChannels: SnapshotArtifact => SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
    getEpochProgress: SnapshotArtifact => EpochProgress
  ): GlobalSnapshotConsensusFunctions[F, SnapshotArtifact] = new GlobalSnapshotConsensusFunctions[F, SnapshotArtifact] {

    def consumeSignedMajorityArtifact(signedArtifact: Signed[SnapshotArtifact]): F[Unit] =
      snapshotService.storeSnapshot(signedArtifact)

    def triggerPredicate(
      event: GlobalSnapshotEvent
    ): Boolean = true // placeholder for triggering based on fee

    def facilitatorFilter(lastSignedArtifact: Signed[SnapshotArtifact], peerId: PeerId): F[Boolean] =
      peerId.toAddress[F].flatMap { address =>
        snapshotService.getBalances(lastSignedArtifact, address).map(_.getOrElse(Balance.empty).satisfiesCollateral(collateral))
      }

    def validateArtifact(lastSignedArtifact: Signed[SnapshotArtifact], trigger: ConsensusTrigger)(
      artifact: SnapshotArtifact
    ): F[Either[InvalidArtifact, SnapshotArtifact]] = {
      val dagEvents = getBlocks(artifact).unsorted.map(_.block.asRight[StateChannelOutput])
      val scEvents = getStateChannels(artifact).toList.flatMap {
        case (address, stateChannelBinaries) => stateChannelBinaries.map(StateChannelOutput(address, _).asLeft[DAGEvent]).toList
      }
      val events = dagEvents ++ scEvents

      def recreatedArtifact: F[SnapshotArtifact] =
        createProposalArtifact(getOrdinal(lastSignedArtifact), lastSignedArtifact, trigger, events)
          .map(_._1)

      recreatedArtifact
        .map(_ === artifact)
        .ifF(
          artifact.asRight[InvalidArtifact],
          ArtifactMismatch.asLeft[SnapshotArtifact]
        )
    }

    def createProposalArtifact(
      lastKey: GlobalSnapshotKey,
      lastArtifact: Signed[SnapshotArtifact],
      trigger: ConsensusTrigger,
      events: Set[GlobalSnapshotEvent]
    ): F[(SnapshotArtifact, Set[GlobalSnapshotEvent])] = {
      val (scEvents: List[StateChannelEvent], dagEvents: List[DAGEvent]) = events.filter { event =>
        if (environment == Mainnet) event.isRight else true
      }.toList.partitionMap(identity)

      val blocksForAcceptance = dagEvents
        .filter(_.height > getHeight(lastArtifact))
        .toList

      for {
        lastGlobalSnapshotInfo <- snapshotService.getGlobalSnapshotInfo(lastArtifact)
        currentOrdinal = getOrdinal(lastArtifact).next
        (scSnapshots, returnedSCEvents) <- stateChannelEventsProcessor.process(lastGlobalSnapshotInfo, scEvents)

        lastActiveTips <- getActiveTips(lastArtifact)
        lastDeprecatedTips = getTips(lastArtifact).deprecated

        tipUsages = getTipsUsages(lastActiveTips, lastDeprecatedTips)
        context = BlockAcceptanceContext.fromStaticData(
          lastGlobalSnapshotInfo.balances,
          lastGlobalSnapshotInfo.lastTxRefs,
          tipUsages,
          collateral
        )
        acceptanceResult <- blockAcceptanceManager.acceptBlocksIteratively(blocksForAcceptance, context)

        (deprecated, remainedActive, accepted) = getUpdatedTips(
          lastActiveTips,
          lastDeprecatedTips,
          acceptanceResult,
          currentOrdinal
        )
        tips = SnapshotTips(deprecated, remainedActive)

        balances = lastGlobalSnapshotInfo.balances ++ acceptanceResult.contextUpdate.balances
        positiveBalances = balances.filter { case (_, balance) => balance =!= Balance.empty }

        facilitators = lastArtifact.proofs.map(_.id)
        transactions = getBlocks(lastArtifact.value).flatMap(_.block.transactions.toSortedSet).map(_.value)

        rewardTxsForAcceptance <- rewards.feeDistribution(getOrdinal(lastArtifact), transactions, facilitators).flatMap { feeRewardTxs =>
          trigger match {
            case EventTrigger => feeRewardTxs.pure[F]
            case TimeTrigger  => rewards.mintedDistribution(getEpochProgress(lastArtifact), facilitators).map(_ ++ feeRewardTxs)
          }
        }

        (updatedBalancesByRewards, acceptedRewardTxs) = acceptRewardTxs(positiveBalances, rewardTxsForAcceptance)

        returnedDAGEvents = getReturnedDAGEvents(acceptanceResult)

        newArtifact <- snapshotService.createSnapshot(
          lastArtifact,
          trigger,
          scSnapshots,
          accepted,
          acceptanceResult.contextUpdate.lastTxRefs,
          updatedBalancesByRewards,
          acceptedRewardTxs,
          tips
        )
        returnedEvents = returnedSCEvents.union(returnedDAGEvents)
      } yield (newArtifact, returnedEvents)
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

    private def getTipsUsages(
      lastActive: Set[ActiveTip],
      lastDeprecated: Set[DeprecatedTip]
    ): Map[BlockReference, NonNegLong] = {
      val activeTipsUsages = lastActive.map(at => (at.block, at.usageCount)).toMap
      val deprecatedTipsUsages = lastDeprecated.map(dt => (dt.block, deprecationThreshold)).toMap

      activeTipsUsages ++ deprecatedTipsUsages
    }

    private def getUpdatedTips(
      lastActive: SortedSet[ActiveTip],
      lastDeprecated: SortedSet[DeprecatedTip],
      acceptanceResult: BlockAcceptanceResult,
      currentOrdinal: SnapshotOrdinal
    ): (SortedSet[DeprecatedTip], SortedSet[ActiveTip], SortedSet[DAGBlockAsActiveTip]) = {
      val usagesUpdate = acceptanceResult.contextUpdate.parentUsages
      val accepted =
        acceptanceResult.accepted.map { case (block, usages) => DAGBlockAsActiveTip(block, usages) }.toSortedSet
      val (remainedActive, newlyDeprecated) = lastActive.partitionMap { at =>
        val maybeUpdatedUsage = usagesUpdate.get(at.block)
        Either.cond(
          maybeUpdatedUsage.exists(_ >= deprecationThreshold),
          DeprecatedTip(at.block, currentOrdinal),
          maybeUpdatedUsage.map(uc => at.copy(usageCount = uc)).getOrElse(at)
        )
      }.bimap(_.toSortedSet, _.toSortedSet)
      val lowestActiveIntroducedAt = remainedActive.toList.map(_.introducedAt).minimumOption.getOrElse(currentOrdinal)
      val remainedDeprecated = lastDeprecated.filter(_.deprecatedAt > lowestActiveIntroducedAt)

      (remainedDeprecated | newlyDeprecated, remainedActive, accepted)
    }

    private def getReturnedDAGEvents(
      acceptanceResult: BlockAcceptanceResult
    ): Set[GlobalSnapshotEvent] =
      acceptanceResult.notAccepted.mapFilter {
        case (signedBlock, _: BlockAwaitReason) => signedBlock.asRight[StateChannelEvent].some
        case _                                  => none
      }.toSet

    case object ArtifactMismatch extends InvalidArtifact
    case class InvalidHeight(lastHeight: Height, currentHeight: Height) extends NoStackTrace

  }
}
