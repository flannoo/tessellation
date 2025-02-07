package org.tessellation.dag.l0.infrastructure.snapshot

import cats.effect.std.Supervisor
import cats.effect.{IO, Resource}
import cats.syntax.option._

import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.ext.crypto._
import org.tessellation.json.JsonHashSerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.infrastructure.snapshot.storage.{
  SnapshotInfoLocalFileSystemStorage,
  SnapshotLocalFileSystemStorage,
  SnapshotStorage
}
import org.tessellation.node.shared.nodeSharedKryoRegistrar
import org.tessellation.schema._
import org.tessellation.schema.epoch.EpochProgress
import org.tessellation.security.signature.Signed
import org.tessellation.security.{Hasher, KeyPairGenerator, SecurityProvider}

import better.files._
import eu.timepit.refined.auto._
import fs2.io.file.Path
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object SnapshotStorageSuite extends MutableIOSuite with Checkers {

  type Res = (Supervisor[IO], KryoSerializer[IO], Hasher[IO], SecurityProvider[IO])

  def sharedResource: Resource[IO, Res] = for {
    supervisor <- Supervisor[IO]
    implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](nodeSharedKryoRegistrar)
    sp <- SecurityProvider.forAsync[IO]
    implicit0(j: JsonHashSerializer[IO]) <- JsonHashSerializer.forSync[IO].asResource
    h = Hasher.forSync[IO]
  } yield (supervisor, ks, h, sp)

  def mkStorage(tmpDir: File)(implicit K: KryoSerializer[IO], H: Hasher[IO], S: Supervisor[IO]) =
    SnapshotLocalFileSystemStorage.make[IO, GlobalIncrementalSnapshot](Path(tmpDir.pathAsString)).flatMap { snapshotFileStorage =>
      SnapshotInfoLocalFileSystemStorage.make[IO, GlobalSnapshotStateProof, GlobalSnapshotInfo](Path(tmpDir.pathAsString)).flatMap {
        snapshotInfoFileStorage =>
          SnapshotStorage.make[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo](
            snapshotFileStorage,
            snapshotInfoFileStorage,
            inMemoryCapacity = 5L,
            SnapshotOrdinal.MinValue
          )
      }
    }

  def mkSnapshots(
    implicit H: Hasher[IO],
    S: SecurityProvider[IO]
  ): IO[(Signed[GlobalSnapshot], Signed[GlobalIncrementalSnapshot])] =
    KeyPairGenerator.makeKeyPair[IO].flatMap { keyPair =>
      Signed.forAsyncKryo[IO, GlobalSnapshot](GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue), keyPair).flatMap { genesis =>
        GlobalIncrementalSnapshot.fromGlobalSnapshot(genesis).flatMap { snapshot =>
          Signed.forAsyncKryo[IO, GlobalIncrementalSnapshot](snapshot, keyPair).map((genesis, _))
        }
      }
    }

  test("head - returns none for empty storage") { res =>
    implicit val (s, kryo, h, _) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        storage.head.map {
          expect.eql(none, _)
        }
      }
    }
  }

  test("head - returns latest snapshot if not empty") { res =>
    implicit val (s, kryo, h, sp) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case (genesis, snapshot) =>
            storage.prepend(snapshot, genesis.info) >>
              storage.headSnapshot.map {
                expect.eql(snapshot.some, _)
              }
        }
      }
    }
  }

  test("prepend - should return true if next snapshot creates a chain") { res =>
    implicit val (s, kryo, h, sp) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case (genesis, snapshot) =>
            storage.prepend(snapshot, genesis.info).map(expect.eql(true, _))
        }
      }
    }
  }

  test("prepend - should allow to start from any arbitrary snapshot") { res =>
    implicit val (s, kryo, h, sp) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case (genesis, snapshot) =>
            storage.prepend(snapshot, genesis.info).map(expect.same(true, _))
        }
      }
    }
  }

  test("get - should return snapshot by ordinal") { res =>
    implicit val (s, kryo, h, sp) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case (genesis, snapshot) =>
            storage.prepend(snapshot, genesis.info) >>
              storage.get(snapshot.ordinal).map(expect.eql(snapshot.some, _))
        }
      }
    }
  }

  test("get - should return snapshot by hash") { res =>
    implicit val (s, kryo, h, sp) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case (genesis, snapshot) =>
            storage.prepend(snapshot, genesis.info) >>
              snapshot.value.hash.flatMap { hash =>
                storage.get(hash).map(expect.eql(snapshot.some, _))
              }
        }
      }
    }
  }

  test("getLatestBalancesStream - subscriber should get latest balances") { res =>
    implicit val (s, kryo, h, sp) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case (genesis, snapshot) =>
            storage.prepend(snapshot, genesis.info) >>
              storage.getLatestBalancesStream.take(1).compile.toList.map {
                expect.same(_, List(Map.empty[address.Address, balance.Balance]))
              }
        }
      }
    }
  }

  test("getLatestBalancesStream - second subscriber should get latest balances") { res =>
    implicit val (s, kryo, h, sp) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case (genesis, snapshot) =>
            storage.prepend(snapshot, genesis.info) >>
              storage.getLatestBalancesStream.take(1).compile.toList >>
              storage.getLatestBalancesStream.take(1).compile.toList.map {
                expect.same(_, List(Map.empty[address.Address, balance.Balance]))
              }
        }
      }
    }
  }
}
