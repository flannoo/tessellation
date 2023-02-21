package org.tessellation.currency.domain.cell

import cats.effect.Async
import cats.effect.std.Queue
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.currency.domain.cell.AlgebraCommand.{EnqueueL1BlockData, NoAction}
import org.tessellation.currency.domain.cell.CoalgebraCommand.ProcessL1Block
import org.tessellation.currency.domain.cell.L0Cell.{Algebra, Coalgebra}
import org.tessellation.currency.domain.cell.L0CellInput.HandleL1Block
import org.tessellation.currency.schema.currency.CurrencyBlock
import org.tessellation.kernel.Cell.NullTerminal
import org.tessellation.kernel._
import org.tessellation.security.signature.Signed

import higherkindness.droste.{AlgebraM, CoalgebraM, scheme}

sealed trait L0CellInput

object L0CellInput {
  case class HandleL1Block(data: Signed[CurrencyBlock]) extends L0CellInput
}

class L0Cell[F[_]: Async](
  data: L0CellInput,
  l1OutputQueue: Queue[F, Signed[CurrencyBlock]]
) extends Cell[F, StackF, L0CellInput, Either[CellError, Ω], CoalgebraCommand](
      data,
      scheme.hyloM(
        AlgebraM[F, StackF, Either[CellError, Ω]] {
          case More(a) => a.pure[F]
          case Done(Right(cmd: AlgebraCommand)) =>
            cmd match {
              case EnqueueL1BlockData(data) =>
                Algebra.enqueueL1BlockData(l1OutputQueue)(data)
              case NoAction =>
                NullTerminal.asRight[CellError].widen[Ω].pure[F]
            }
          case Done(other) => other.pure[F]
        },
        CoalgebraM[F, StackF, CoalgebraCommand] {
          case ProcessL1Block(data) => Coalgebra.processL1Block(data)
        }
      ),
      {
        case HandleL1Block(data) => ProcessL1Block(data)
      }
    )

object L0Cell {

  type Mk[F[_]] = L0CellInput => L0Cell[F]

  def mkL0Cell[F[_]: Async](
    l1OutputQueue: Queue[F, Signed[CurrencyBlock]]
  ): Mk[F] =
    data => new L0Cell(data, l1OutputQueue)

  type AlgebraR[F[_]] = F[Either[CellError, Ω]]
  type CoalgebraR[F[_]] = F[StackF[CoalgebraCommand]]

  object Algebra {

    def enqueueL1BlockData[F[_]: Async](queue: Queue[F, Signed[CurrencyBlock]])(data: Signed[CurrencyBlock]): AlgebraR[F] =
      queue.offer(data) >>
        NullTerminal.asRight[CellError].widen[Ω].pure[F]
  }

  object Coalgebra {

    def processL1Block[F[_]: Async](data: Signed[CurrencyBlock]): CoalgebraR[F] = {
      def res: StackF[CoalgebraCommand] = Done(AlgebraCommand.EnqueueL1BlockData(data).asRight[CellError])

      res.pure[F]
    }
  }
}
