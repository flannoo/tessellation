package org.tessellation.sdk.infrastructure.consensus

import cats.Show
import cats.syntax.eq._
import cats.syntax.option._
import cats.syntax.show._

import scala.concurrent.duration.FiniteDuration

import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.infrastructure.consensus.declaration.kind._
import org.tessellation.sdk.infrastructure.consensus.trigger.ConsensusTrigger
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(eqv)
case class ConsensusState[Key, Artifact, Context](
  key: Key,
  lastKey: Key,
  context: Context,
  facilitators: List[PeerId],
  status: ConsensusStatus[Artifact, Context],
  createdAt: FiniteDuration,
  removedFacilitators: Set[PeerId] = Set.empty,
  withdrawnFacilitators: Set[PeerId] = Set.empty,
  lockStatus: LockStatus = Open,
  spreadAckKinds: Set[PeerDeclarationKind] = Set.empty
)

object ConsensusState {
  implicit def showInstance[K: Show, A, C]: Show[ConsensusState[K, A, C]] = { cs =>
    s"""ConsensusState{
       |key=${cs.key.show},
       |lockStatus=${cs.lockStatus.show},
       |facilitatorCount=${cs.facilitators.size.show},
       |removedFacilitators=${cs.removedFacilitators.show},
       |withdrawnFacilitators=${cs.withdrawnFacilitators.show},
       |spreadAckKinds=${cs.spreadAckKinds.show},
       |status=${cs.status.show}
       |}""".stripMargin.replace(",\n", ", ")
  }

  implicit class ConsensusStateOps[K, A, C](value: ConsensusState[K, A, C]) {
    private val kindRelation: (Option[PeerDeclarationKind], Set[PeerDeclarationKind]) = value.status match {
      case _: CollectingFacilities[A, C] => (Facility.some, Set.empty)
      case _: CollectingProposals[A, C]  => (Proposal.some, Set(Facility))
      case _: CollectingSignatures[A, C] => (MajoritySignature.some, Set(Facility, Proposal))
      case _: Finished[A, C]             => (none, Set(Facility, Proposal, MajoritySignature))
    }

    def collectedKinds: Set[PeerDeclarationKind] = kindRelation._2
    def maybeCollectingKind: Option[PeerDeclarationKind] = kindRelation._1
    def locked: Boolean = value.lockStatus === Closed
    def notLocked: Boolean = !locked

  }
}

@derive(eqv)
sealed trait ConsensusStatus[Artifact, Context]

final case class CollectingFacilities[A, C](
  maybeTrigger: Option[ConsensusTrigger],
  lastSignedArtifact: Signed[A],
  context: C,
  facilitatorsHash: Hash
) extends ConsensusStatus[A, C]
final case class CollectingProposals[A, C](
  majorityTrigger: ConsensusTrigger,
  proposalInfo: ProposalInfo[A],
  lastSignedArtifact: Signed[A],
  lastContext: C,
  candidates: Set[PeerId],
  facilitatorsHash: Hash
) extends ConsensusStatus[A, C]
final case class CollectingSignatures[A, C](
  majorityArtifactHash: Hash,
  majorityTrigger: ConsensusTrigger,
  candidates: Set[PeerId],
  facilitatorsHash: Hash
) extends ConsensusStatus[A, C]

@derive(encoder, decoder)
final case class Finished[A, C](
  signedMajorityArtifact: Signed[A],
  context: C,
  majorityTrigger: ConsensusTrigger,
  candidates: Set[PeerId],
  facilitatorsHash: Hash
) extends ConsensusStatus[A, C]

object ConsensusStatus {
  implicit def showInstance[A, C]: Show[ConsensusStatus[A, C]] = {
    case CollectingFacilities(maybeTrigger, _, _, facilitatorsHash) =>
      s"CollectingFacilities{maybeTrigger=${maybeTrigger.show}, facilitatorsHash=${facilitatorsHash.show}}"
    case CollectingProposals(majorityTrigger, maybeProposalInfo, _, _, candidates, facilitatorsHash) =>
      s"CollectingProposals{majorityTrigger=${majorityTrigger.show}, maybeProposalInfo=${maybeProposalInfo.show}, candidates=${candidates.show}, facilitatorsHash=${facilitatorsHash.show}}"
    case CollectingSignatures(majorityArtifactHash, majorityTrigger, candidates, facilitatorsHash) =>
      s"CollectingSignatures{majorityArtifactHash=${majorityArtifactHash.show}, ${majorityTrigger.show}, candidates=${candidates.show}, facilitatorsHash=${facilitatorsHash.show}}"
    case Finished(_, _, majorityTrigger, candidates, facilitatorsHash) =>
      s"Finished{majorityTrigger=${majorityTrigger.show}, candidates=${candidates.show}, facilitatorsHash=${facilitatorsHash.show}}"
  }
}

@derive(eqv)
case class ProposalInfo[A](proposalArtifact: A, proposalArtifactHash: Hash)
object ProposalInfo {
  implicit def showInstance[A]: Show[ProposalInfo[A]] = pi => s"ProposalInfo{proposalArtifactHash=${pi.proposalArtifactHash.show}}"
}

@derive(eqv, show)
sealed trait LockStatus

case object Open extends LockStatus
case object Closed extends LockStatus
case object Reopened extends LockStatus
