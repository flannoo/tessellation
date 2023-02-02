package org.tessellation.infrastructure.dag

import cats.effect.Async
import cats.syntax.functor._

import org.tessellation.domain.dag.DAGService
import org.tessellation.domain.snapshot.SnapshotStorage
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.{GlobalSnapshotInfo, IncrementalGlobalSnapshot, SnapshotOrdinal}

import io.estatico.newtype.ops._

object DAGService {

  def make[F[_]: Async](globalSnapshotStorage: SnapshotStorage[F, IncrementalGlobalSnapshot, GlobalSnapshotInfo]): DAGService[F] =
    new DAGService[F] {

      def getBalance(address: Address): F[Option[(Balance, SnapshotOrdinal)]] =
        globalSnapshotStorage.head.map(_.map {
          case (snapshot, state) =>
            val balance = state.balances.getOrElse(address, Balance.empty)
            val ordinal = snapshot.value.ordinal

            (balance, ordinal)
        })

      def getBalance(ordinal: SnapshotOrdinal, address: Address): F[Option[(Balance, SnapshotOrdinal)]] =
        Async[F].pure(None) // TODO: incremental snapshots - consider computing state

      def getTotalSupply: F[Option[(BigInt, SnapshotOrdinal)]] =
        globalSnapshotStorage.head.map(_.map {
          case (snapshot, state) =>
            val empty = BigInt(Balance.empty.coerce.value)
            val supply = state.balances.values
              .foldLeft(empty) { (acc, b) =>
                acc + BigInt(b.coerce.value)
              }
            val ordinal = snapshot.value.ordinal

            (supply, ordinal)

        })

      def getTotalSupply(ordinal: SnapshotOrdinal): F[Option[(BigInt, SnapshotOrdinal)]] =
        Async[F].pure(None) // TODO: incremental snapshots - consider computing state

      def getWalletCount: F[Option[(Int, SnapshotOrdinal)]] =
        globalSnapshotStorage.head.map(_.map {
          case (snapshot, state) =>
            val balance = state.balances.size
            val ordinal = snapshot.value.ordinal

            (balance, ordinal)
        })

      def getWalletCount(ordinal: SnapshotOrdinal): F[Option[(Int, SnapshotOrdinal)]] =
        Async[F].pure(None) // TODO: incremental snapshots - consider computing state

    }
}
