package org.tessellation.dag.l1.domain.block

import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.std.Random
import cats.effect.{IO, Resource}
import cats.syntax.either._
import cats.syntax.option._
import cats.syntax.traverse._

import org.tessellation.block.generators._
import org.tessellation.dag.l1.Main
import org.tessellation.dag.l1.domain.address.storage.AddressStorage
import org.tessellation.dag.l1.domain.block.BlockStorage
import org.tessellation.dag.l1.domain.block.BlockStorage._
import org.tessellation.dag.l1.domain.transaction.TransactionStorage
import org.tessellation.dag.l1.domain.transaction.TransactionStorage.{LastTransactionReferenceState, Majority}
import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.ext.collection.MapRefUtils._
import org.tessellation.json.JsonHashSerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.domain.block.processing._
import org.tessellation.node.shared.nodeSharedKryoRegistrar
import org.tessellation.schema._
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.{Amount, Balance}
import org.tessellation.schema.transaction._
import org.tessellation.security.hash.ProofsHash
import org.tessellation.security.signature.Signed
import org.tessellation.security.{Hashed, Hasher}

import eu.timepit.refined.auto._
import io.chrisdavenport.mapref.MapRef
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object BlockServiceSuite extends MutableIOSuite with Checkers {

  type Res = Hasher[IO]

  def sharedResource: Resource[IO, Res] = for {
    implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](Main.kryoRegistrar ++ nodeSharedKryoRegistrar)
    implicit0(j: JsonHashSerializer[IO]) <- JsonHashSerializer.forSync[IO].asResource
    h = Hasher.forSync[IO]
  } yield h

  def mkBlockService(
    blocksR: MapRef[IO, ProofsHash, Option[StoredBlock]],
    lastAccTxR: MapRef[IO, Address, Option[LastTransactionReferenceState]],
    notAcceptanceReason: Option[BlockNotAcceptedReason] = None
  )(implicit H: Hasher[IO]) = {

    val blockAcceptanceManager = new BlockAcceptanceManager[IO]() {

      override def acceptBlocksIteratively(
        blocks: List[Signed[Block]],
        context: BlockAcceptanceContext[IO]
      ): IO[BlockAcceptanceResult] =
        ???

      override def acceptBlock(
        block: Signed[Block],
        context: BlockAcceptanceContext[IO]
      ): IO[Either[BlockNotAcceptedReason, (BlockAcceptanceContextUpdate, UsageCount)]] = IO.pure(notAcceptanceReason).map {
        case None         => (BlockAcceptanceContextUpdate.empty, initUsageCount).asRight[BlockNotAcceptedReason]
        case Some(reason) => reason.asLeft[(BlockAcceptanceContextUpdate, UsageCount)]
      }
    }

    val addressStorage = new AddressStorage[IO] {

      override def getState: IO[Map[Address, Balance]] = ???

      override def getBalance(address: Address): IO[balance.Balance] = ???

      override def updateBalances(addressBalances: Map[Address, Balance]): IO[Unit] = IO.unit

      override def clean: IO[Unit] = ???

    }

    Random.scalaUtilRandom.flatMap { implicit r =>
      MapRef.ofConcurrentHashMap[IO, Address, NonEmptySet[Hashed[Transaction]]]().map { waitingTxsR =>
        val blockStorage = new BlockStorage[IO](blocksR)
        val transactionStorage = new TransactionStorage[IO](lastAccTxR, waitingTxsR, TransactionReference.empty)
        BlockService
          .make[IO](blockAcceptanceManager, addressStorage, blockStorage, transactionStorage, Amount.empty)
      }

    }
  }

  test("valid block should be accepted") { implicit res =>
    forall(signedBlockGen) { block =>
      for {
        hashedBlock <- block.toHashed[IO]
        blocksR <- MapRef.ofConcurrentHashMap[IO, ProofsHash, StoredBlock]()
        _ <- blocksR(hashedBlock.proofsHash).set(Some(WaitingBlock(hashedBlock.signed)))
        _ <- addParents(blocksR, block)
        lastAccTxR <- setUpLastTxnR(Some(block))

        blockService <- mkBlockService(blocksR, lastAccTxR)

        _ <- blockService.accept(block)
        blocksRes <- blocksR.toMap
      } yield
        expect.same(
          block.parent.toList
            .map(parent => parent.hash -> MajorityBlock(parent, 2L, Active))
            .toMap + (hashedBlock.proofsHash -> AcceptedBlock(hashedBlock)),
          blocksRes
        )
    }
  }

  test("valid block should be accepted and not related block should stay postponed ") { implicit res =>
    forall((block: Signed[Block], notRelatedBlock: Signed[Block]) =>
      for {
        hashedBlock <- block.toHashed[IO]
        hashedNotRelatedBlock <- notRelatedBlock.toHashed[IO]
        blocksR <- MapRef.ofConcurrentHashMap[IO, ProofsHash, StoredBlock]()
        _ <- blocksR(hashedBlock.proofsHash).set(Some(WaitingBlock(hashedBlock.signed)))
        _ <- blocksR(hashedNotRelatedBlock.proofsHash).set(Some(PostponedBlock(hashedNotRelatedBlock.signed)))
        _ <- addParents(blocksR, block)
        lastAccTxR <- setUpLastTxnR(Some(block))

        blockService <- mkBlockService(blocksR, lastAccTxR)

        _ <- blockService.accept(block)
        blocksRes <- blocksR.toMap
      } yield
        expect.same(
          Map(
            hashedBlock.proofsHash -> AcceptedBlock(hashedBlock),
            hashedNotRelatedBlock.proofsHash -> PostponedBlock(hashedNotRelatedBlock.signed)
          ) ++
            block.parent.toList
              .map(parent => parent.hash -> MajorityBlock(parent, 2L, Active)),
          blocksRes
        )
    )
  }

  test("valid block should be accepted and related block should go back to waiting") { implicit res =>
    forall((block: Signed[Block], notRelatedBlock: Signed[Block]) =>
      for {
        hashedBlock <- block.toHashed[IO]
        hashedRelatedBlock <- notRelatedBlock
          .copy(value = notRelatedBlock.value.copy(parent = NonEmptyList.of(hashedBlock.ownReference)))
          .toHashed[IO]
        blocksR <- MapRef.ofConcurrentHashMap[IO, ProofsHash, StoredBlock]()
        _ <- blocksR(hashedBlock.proofsHash).set(Some(WaitingBlock(hashedBlock.signed)))
        _ <- blocksR(hashedRelatedBlock.proofsHash).set(Some(PostponedBlock(hashedRelatedBlock.signed)))
        _ <- addParents(blocksR, block)
        lastAccTxR <- setUpLastTxnR(Some(block))

        blockService <- mkBlockService(blocksR, lastAccTxR)

        _ <- blockService.accept(block)
        blocksRes <- blocksR.toMap
      } yield
        expect.same(
          Map(
            hashedBlock.proofsHash -> AcceptedBlock(hashedBlock),
            hashedRelatedBlock.proofsHash -> WaitingBlock(hashedRelatedBlock.signed)
          ) ++
            block.parent.toList
              .map(parent => parent.hash -> MajorityBlock(parent, 2L, Active)),
          blocksRes
        )
    )
  }

  test("invalid block should be postponed for acceptance") { implicit res =>
    forall(signedBlockGen) { block =>
      for {
        hashedBlock <- block.toHashed[IO]
        blocksR <- MapRef.ofConcurrentHashMap[IO, ProofsHash, StoredBlock]()
        _ <- blocksR(hashedBlock.proofsHash).set(Some(WaitingBlock(hashedBlock.signed)))
        _ <- addParents(blocksR, block)
        lastAccTxR <- setUpLastTxnR(Some(block))

        blockService <- mkBlockService(blocksR, lastAccTxR, Some(ParentNotFound(block.parent.head)))

        error <- blockService.accept(block).attempt
        blocksRes <- blocksR.toMap
      } yield
        expect.same(
          (
            block.parent.toList
              .map(parent => parent.hash -> MajorityBlock(parent, 1L, Active))
              .toMap + (hashedBlock.proofsHash -> PostponedBlock(hashedBlock.signed)),
            Left(
              BlockService
                .BlockAcceptanceError(BlockReference(hashedBlock.height, hashedBlock.proofsHash), ParentNotFound(block.parent.head))
            )
          ),
          (blocksRes, error)
        )
    }
  }

  test("invalid block should be postponed for acceptance and not related should stay postponed") { implicit res =>
    forall((block: Signed[Block], notRelatedBlock: Signed[Block]) =>
      for {
        hashedBlock <- block.toHashed[IO]
        hashedNotRelatedBlock <- notRelatedBlock.toHashed[IO]
        blocksR <- MapRef.ofConcurrentHashMap[IO, ProofsHash, StoredBlock]()
        _ <- blocksR(hashedBlock.proofsHash).set(Some(WaitingBlock(hashedBlock.signed)))
        _ <- blocksR(hashedNotRelatedBlock.proofsHash).set(Some(PostponedBlock(hashedNotRelatedBlock.signed)))
        _ <- addParents(blocksR, block)
        lastAccTxR <- setUpLastTxnR(Some(block))

        blockService <- mkBlockService(blocksR, lastAccTxR, Some(ParentNotFound(block.parent.head)))

        error <- blockService.accept(block).attempt
        blocksRes <- blocksR.toMap
      } yield
        expect.same(
          (
            Map(
              hashedBlock.proofsHash -> PostponedBlock(hashedBlock.signed),
              hashedNotRelatedBlock.proofsHash -> PostponedBlock(hashedNotRelatedBlock.signed)
            ) ++
              block.parent.toList
                .map(parent => parent.hash -> MajorityBlock(parent, 1L, Active))
                .toMap,
            Left(
              BlockService
                .BlockAcceptanceError(BlockReference(hashedBlock.height, hashedBlock.proofsHash), ParentNotFound(block.parent.head))
            )
          ),
          (blocksRes, error)
        )
    )
  }

  private def addParents(blocksR: MapRef[IO, ProofsHash, Option[StoredBlock]], block: Signed[Block]) =
    block.parent.toList.traverse(parent => blocksR(parent.hash).set(Some(MajorityBlock(parent, 1L, Active))))

  private def setUpLastTxnR(maybeBlock: Option[Signed[Block]]) = for {
    lastAccTxR <- MapRef.ofConcurrentHashMap[IO, Address, TransactionStorage.LastTransactionReferenceState]()
    _ <- maybeBlock.traverse(block =>
      block.transactions.toNonEmptyList.toList.traverse(txn => lastAccTxR(txn.source).set(Majority(txn.parent).some))
    )
  } yield lastAccTxR

}
