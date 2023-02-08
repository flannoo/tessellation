package org.tessellation.infrastructure.snapshot

import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.functor._
import cats.{Applicative, Monad, Traverse}

import org.tessellation.schema.{GlobalSnapshot, GlobalSnapshotInfo, IncrementalGlobalSnapshot}
import org.tessellation.security.hash.Hash

import higherkindness.droste._
import higherkindness.droste.data._
import higherkindness.droste.util.DefaultTraverse
sealed trait StackF[A]

case class More[A](a: A, step: IncrementalGlobalSnapshot) extends StackF[A]
case class Done[A](result: GlobalSnapshot) extends StackF[A]

object StackF {

  implicit val traverse: Traverse[StackF] = new DefaultTraverse[StackF] {
    override def traverse[G[_]: Applicative, A, B](fa: StackF[A])(f: A => G[B]): G[StackF[B]] =
      fa match {
        case More(a, step) => f(a).map(More(_, step))
        case Done(r)       => (Done(r): StackF[B]).pure[G]
      }
  }
}

trait GlobalSnapshotTraverse[F[_]] {
  def computeState(latest: IncrementalGlobalSnapshot): F[GlobalSnapshotInfo]
}

object GlobalSnapshotTraverse {

  def loadGlobalSnapshotCoalgebra[F[_]: Monad](
    loadGlobalSnapshotFn: Hash => F[Either[GlobalSnapshot, IncrementalGlobalSnapshot]]
  ): CoalgebraM[F, StackF, Either[GlobalSnapshot, IncrementalGlobalSnapshot]] = CoalgebraM {
    case Left(globalSnapshot) => Applicative[F].pure(Done(globalSnapshot))
    case Right(incrementalGlobalSnapshot) =>
      def prevHash = incrementalGlobalSnapshot.lastSnapshotHash

      loadGlobalSnapshotFn(prevHash).map(More(_, incrementalGlobalSnapshot))
  }

  def computeStateAlgebra[F[_]: Monad](
    applyGlobalSnapshotFn: (
      GlobalSnapshotInfo,
      IncrementalGlobalSnapshot,
      IncrementalGlobalSnapshot
    ) => F[GlobalSnapshotInfo]
  ): GAlgebraM[F, StackF, Attr[StackF, GlobalSnapshotInfo], GlobalSnapshotInfo] = GAlgebraM {
    case Done(globalSnapshot) => globalSnapshot.info.pure[F]
    case More(info :< Done(globalSnapshot), incrementalSnapshot) =>
      applyGlobalSnapshotFn(info, globalSnapshot.toIncrementalSnapshot, incrementalSnapshot)
    case More(info :< More(_ :< _, previousIncrementalSnapshot), incrementalSnapshot) =>
      applyGlobalSnapshotFn(info, previousIncrementalSnapshot, incrementalSnapshot)
  }

  def make[F[_]: Monad](
    loadGlobalSnapshotFn: Hash => F[Either[GlobalSnapshot, IncrementalGlobalSnapshot]],
    snapshotInfoFunctions: GlobalSnapshotConsensusFunctions[F]
  ): GlobalSnapshotTraverse[F] =
    new GlobalSnapshotTraverse[F] {

      def computeState(latest: IncrementalGlobalSnapshot): F[GlobalSnapshotInfo] =
        scheme
          .ghyloM(
            computeStateAlgebra(snapshotInfoFunctions.createGlobalSnapshotInfo).gather(Gather.histo),
            loadGlobalSnapshotCoalgebra(loadGlobalSnapshotFn).scatter(Scatter.ana)
          )
          .apply(latest.asRight[GlobalSnapshot])
    }

}
