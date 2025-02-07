package org.tessellation.node.shared.infrastructure.snapshot

import cats.data.{Validated, ValidatedNec}
import cats.effect.kernel.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.tessellation.currency.dataApplication.dataApplication.DataApplicationBlock
import org.tessellation.currency.dataApplication.{BaseDataApplicationService, DataCalculatedState}
import org.tessellation.currency.schema.currency._
import org.tessellation.ext.cats.syntax.validated.validatedSyntax
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.domain.rewards.Rewards
import org.tessellation.node.shared.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import org.tessellation.node.shared.snapshot.currency.{CurrencySnapshotArtifact, CurrencySnapshotEvent}
import org.tessellation.schema._
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.signature.SignedValidator.SignedValidationError
import org.tessellation.security.signature.{Signed, SignedValidator}

import derevo.cats.{eqv, show}
import derevo.derive
import monocle.syntax.all._

trait CurrencySnapshotValidator[F[_]] {

  type CurrencySnapshotValidationErrorOr[A] = ValidatedNec[CurrencySnapshotValidationError, A]

  def validateSignedSnapshot(
    lastArtifact: Signed[CurrencySnapshotArtifact],
    lastContext: CurrencySnapshotContext,
    artifact: Signed[CurrencySnapshotArtifact]
  ): F[CurrencySnapshotValidationErrorOr[(Signed[CurrencyIncrementalSnapshot], CurrencySnapshotContext)]]

  def validateSnapshot(
    lastArtifact: Signed[CurrencySnapshotArtifact],
    lastContext: CurrencySnapshotContext,
    artifact: CurrencySnapshotArtifact,
    facilitators: Set[PeerId]
  ): F[CurrencySnapshotValidationErrorOr[(CurrencyIncrementalSnapshot, CurrencySnapshotContext)]]
}

object CurrencySnapshotValidator {

  def make[F[_]: Async: KryoSerializer](
    currencySnapshotCreator: CurrencySnapshotCreator[F],
    signedValidator: SignedValidator[F],
    maybeRewards: Option[Rewards[F, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotEvent]],
    maybeDataApplication: Option[BaseDataApplicationService[F]]
  ): CurrencySnapshotValidator[F] = new CurrencySnapshotValidator[F] {

    def validateSignedSnapshot(
      lastArtifact: Signed[CurrencySnapshotArtifact],
      lastContext: CurrencySnapshotContext,
      artifact: Signed[CurrencySnapshotArtifact]
    ): F[CurrencySnapshotValidationErrorOr[(Signed[CurrencyIncrementalSnapshot], CurrencySnapshotContext)]] =
      validateSigned(artifact).flatMap { signedV =>
        val facilitators = artifact.proofs.map(_.id).map(PeerId.fromId).toSortedSet

        validateSnapshot(lastArtifact, lastContext, artifact, facilitators).map { snapshotV =>
          signedV.product(snapshotV.map { case (_, info) => info })
        }
      }

    def validateSnapshot(
      lastArtifact: Signed[CurrencySnapshotArtifact],
      lastContext: CurrencySnapshotContext,
      artifact: CurrencySnapshotArtifact,
      facilitators: Set[PeerId]
    ): F[CurrencySnapshotValidationErrorOr[(CurrencyIncrementalSnapshot, CurrencySnapshotContext)]] = for {
      contentV <- validateRecreateContent(lastArtifact, lastContext, artifact, facilitators)
      blocksV <- contentV.map(validateNotAcceptedEvents).pure[F]
    } yield
      (contentV, blocksV).mapN {
        case (creationResult, _) => (creationResult.artifact, creationResult.context)
      }

    def validateSigned(
      signedSnapshot: Signed[CurrencyIncrementalSnapshot]
    ): F[CurrencySnapshotValidationErrorOr[Signed[CurrencyIncrementalSnapshot]]] =
      signedValidator.validateSignatures(signedSnapshot).map(_.errorMap(InvalidSigned))

    def validateRecreateContent(
      lastArtifact: Signed[CurrencySnapshotArtifact],
      lastContext: CurrencySnapshotContext,
      expected: CurrencySnapshotArtifact,
      facilitators: Set[PeerId]
    ): F[CurrencySnapshotValidationErrorOr[CurrencySnapshotCreationResult[CurrencySnapshotEvent]]] = {
      def dataApplicationBlocks = maybeDataApplication.flatTraverse { service =>
        expected.dataApplication.map(_.blocks).traverse {
          _.traverse(b => service.deserializeBlock(b))
        }
      }.map(_.map(_.flatMap(_.toOption)))
        .map(_.getOrElse(List.empty))

      def mkEvents: F[Set[CurrencySnapshotEvent]] = dataApplicationBlocks
        .map(_.map(_.asRight[Signed[Block]]))
        .map(_.toSet ++ expected.blocks.unsorted.map(_.block.asLeft[Signed[DataApplicationBlock]]))

      // Rewrite if implementation not provided
      val rewards = maybeRewards.orElse(Some {
        new Rewards[F, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotEvent] {
          def distribute(
            lastArtifact: Signed[CurrencySnapshotArtifact],
            lastBalances: SortedMap[address.Address, balance.Balance],
            acceptedTransactions: SortedSet[Signed[transaction.Transaction]],
            trigger: ConsensusTrigger,
            events: Set[CurrencySnapshotEvent],
            maybeCalculatedState: Option[DataCalculatedState] = None
          ): F[SortedSet[transaction.RewardTransaction]] = expected.rewards.pure[F]
        }
      })

      val recreateFn = (trigger: ConsensusTrigger) =>
        mkEvents.flatMap { events =>
          currencySnapshotCreator
            .createProposalArtifact(lastArtifact.ordinal, lastArtifact, lastContext, trigger, events, rewards, facilitators)
            // Rewrite if implementation not provided
            .map { creationResult =>
              maybeDataApplication match {
                case Some(_) => creationResult
                case None =>
                  creationResult.focus(_.artifact.dataApplication).replace(expected.dataApplication)
              }
            }
            .map { creationResult =>
              if (creationResult.artifact =!= expected)
                SnapshotDifferentThanExpected(expected, creationResult.artifact).invalidNec
              else
                creationResult.validNec
            }
        }

      recreateFn(TimeTrigger).flatMap { tV =>
        recreateFn(EventTrigger).map(_.orElse(tV))
      }
    }

    def validateNotAcceptedEvents(
      creationResult: CurrencySnapshotCreationResult[CurrencySnapshotEvent]
    ): CurrencySnapshotValidationErrorOr[Unit] = {
      def getBlocks(s: Set[CurrencySnapshotEvent]): Set[Signed[Block]] = s.collect { case Left(block) => block }

      val awaitingBlocks = getBlocks(creationResult.awaitingEvents)
      val rejectedBlocks = getBlocks(creationResult.rejectedEvents)

      Validated.condNec(
        awaitingBlocks.nonEmpty && rejectedBlocks.nonEmpty,
        (),
        SomeBlocksWereNotAccepted(awaitingBlocks, rejectedBlocks)
      )
    }
  }

}

@derive(eqv, show)
sealed trait CurrencySnapshotValidationError

case class SnapshotDifferentThanExpected(expected: CurrencyIncrementalSnapshot, actual: CurrencyIncrementalSnapshot)
    extends CurrencySnapshotValidationError

case class SomeBlocksWereNotAccepted(awaitingBlocks: Set[Signed[Block]], rejectedBlocks: Set[Signed[Block]])
    extends CurrencySnapshotValidationError

case class InvalidSigned(error: SignedValidationError) extends CurrencySnapshotValidationError
