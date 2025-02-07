package org.tessellation.node.shared.infrastructure.consensus

import org.tessellation.node.shared.infrastructure.consensus.declaration._

import derevo.cats.{eqv, show}
import derevo.derive
import derevo.scalacheck.arbitrary

@derive(arbitrary, eqv, show)
case class PeerDeclarations(
  facility: Option[Facility],
  proposal: Option[Proposal],
  signature: Option[MajoritySignature]
)

object PeerDeclarations {
  val empty: PeerDeclarations = PeerDeclarations(Option.empty, Option.empty, Option.empty)
}
