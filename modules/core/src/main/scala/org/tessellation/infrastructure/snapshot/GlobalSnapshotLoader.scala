package org.tessellation.infrastructure.snapshot

import cats.data.OptionT
import cats.effect.kernel.Async
import cats.syntax.either._
import cats.syntax.functor._

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.{GlobalSnapshot, IncrementalGlobalSnapshot}
import org.tessellation.sdk.infrastructure.snapshot.storage.SnapshotLocalFileSystemStorage
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import fs2.io.file.Path

trait GlobalSnapshotLoader[F[_]] {

  def readGlobalSnapshot(hash: Hash): F[Either[Signed[GlobalSnapshot], Signed[IncrementalGlobalSnapshot]]]
}

object GlobalSnapshotLoader {

  def make[F[_]: Async: KryoSerializer](
    incrementalGlobalSnapshotLocalFileSystemStorage: SnapshotLocalFileSystemStorage[F, IncrementalGlobalSnapshot],
    path: Path
  ): F[GlobalSnapshotLoader[F]] =
    SnapshotLocalFileSystemStorage.make[F, GlobalSnapshot](path).map(make(incrementalGlobalSnapshotLocalFileSystemStorage, _))

  def make[F[_]: Async](
    incrementalGlobalSnapshotLocalFileSystemStorage: SnapshotLocalFileSystemStorage[F, IncrementalGlobalSnapshot],
    globalSnapshotLocalFileSystemStorage: SnapshotLocalFileSystemStorage[F, GlobalSnapshot]
  ): GlobalSnapshotLoader[F] = new GlobalSnapshotLoader[F] {

    def readGlobalSnapshot(hash: Hash): F[Either[Signed[GlobalSnapshot], Signed[IncrementalGlobalSnapshot]]] = {

      def getIncremental = OptionT(incrementalGlobalSnapshotLocalFileSystemStorage.read(hash))
      def getFull = OptionT(globalSnapshotLocalFileSystemStorage.read(hash))

      def getSnapshot = getIncremental
        .map(_.asRight[Signed[GlobalSnapshot]])
        .orElse(getFull.map(_.asLeft[Signed[IncrementalGlobalSnapshot]]))

      getSnapshot.getOrRaise(new Throwable("Cannot find neither GlobalSnapshot nor IncrementalGlobalSnapshot."))

    }

  }

}
