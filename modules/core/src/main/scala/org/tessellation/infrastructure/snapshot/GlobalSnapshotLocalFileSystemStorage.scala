package org.tessellation.infrastructure.snapshot

import cats.Applicative
import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.contravariantSemigroupal._
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.{IncrementalGlobalSnapshot, SnapshotOrdinal}
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed
import org.tessellation.storage.LocalFileSystemStorage

import eu.timepit.refined.auto._
import fs2.io.file.Path
import io.estatico.newtype.ops._

final class GlobalSnapshotLocalFileSystemStorage[F[_]: Async: KryoSerializer] private (path: Path)
    extends LocalFileSystemStorage[F, Signed[IncrementalGlobalSnapshot]](path) {

  def write(snapshot: Signed[IncrementalGlobalSnapshot]): F[Unit] = {
    val ordinalName = toOrdinalName(snapshot.value)

    toHashName(snapshot.value).flatMap { hashName =>
      (exists(ordinalName), exists(hashName)).mapN {
        case (ordinalExists, hashExists) =>
          if (ordinalExists || hashExists) {
            (new Throwable("Snapshot already exists under ordinal or hash filename")).raiseError[F, Unit]
          } else {
            write(hashName, snapshot) >> link(hashName, ordinalName)
          }
      }.flatten
    }

  }

  def read(ordinal: SnapshotOrdinal): F[Option[Signed[IncrementalGlobalSnapshot]]] =
    read(toOrdinalName(ordinal))

  def read(hash: Hash): F[Option[Signed[IncrementalGlobalSnapshot]]] =
    read(hash.coerce[String])

  private def toOrdinalName(snapshot: IncrementalGlobalSnapshot): String = toOrdinalName(snapshot.ordinal)
  private def toOrdinalName(ordinal: SnapshotOrdinal): String = ordinal.value.value.toString

  private def toHashName(snapshot: IncrementalGlobalSnapshot): F[String] = snapshot.hashF.map(_.coerce[String])

}

object GlobalSnapshotLocalFileSystemStorage {

  def make[F[_]: Async: KryoSerializer](path: Path): F[GlobalSnapshotLocalFileSystemStorage[F]] =
    Applicative[F].pure(new GlobalSnapshotLocalFileSystemStorage[F](path)).flatTap { storage =>
      storage.createDirectoryIfNotExists().rethrowT
    }
}
