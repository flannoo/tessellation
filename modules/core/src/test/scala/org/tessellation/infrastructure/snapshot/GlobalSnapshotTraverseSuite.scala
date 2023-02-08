package org.tessellation.dag.snapshot

import java.security.KeyPair

import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.std.Random
import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Resource}
import cats.syntax.applicative._
import cats.syntax.eq._
import cats.syntax.foldable._
import cats.syntax.traverse._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.tessellation.domain.rewards.Rewards
import org.tessellation.domain.snapshot.SnapshotStorage
import org.tessellation.domain.statechannel.StateChannelValidator
import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.infrastructure.snapshot.{
  GlobalSnapshotConsensusFunctions,
  GlobalSnapshotStateChannelEventsProcessor,
  GlobalSnapshotTraverse
}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema._
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.{Amount, Balance}
import org.tessellation.schema.block.DAGBlock
import org.tessellation.schema.epoch.EpochProgress
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.transaction.{DAGTransaction, TransactionReference}
import org.tessellation.sdk.config.AppEnvironment
import org.tessellation.sdk.domain.transaction.{TransactionChainValidator, TransactionValidator}
import org.tessellation.sdk.infrastructure.block.processing.{BlockAcceptanceLogic, BlockAcceptanceManager, BlockValidator}
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.security.hash.{Hash, ProofsHash}
import org.tessellation.security.hex.Hex
import org.tessellation.security.key.ops.PublicKeyOps
import org.tessellation.security.signature.{Signed, SignedValidator}
import org.tessellation.security.{Hashed, KeyPairGenerator, SecurityProvider}
import org.tessellation.shared.sharedKryoRegistrar
import org.tessellation.syntax.sortedCollection._
import org.tessellation.tools.TransactionGenerator._
import org.tessellation.tools.{DAGBlockGenerator, TransactionGenerator}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosInt}
import org.scalacheck.Gen
import weaver._
import weaver.scalacheck.Checkers

object GlobalSnapshotTraverseSuite extends MutableIOSuite with Checkers {
  type GenKeyPairFn = () => KeyPair

  type Res = (KryoSerializer[IO], SecurityProvider[IO], Metrics[IO], Random[IO], GenKeyPairFn)

  override def sharedResource: Resource[IO, Res] = for {
    kryo <- KryoSerializer.forAsync[IO](sharedKryoRegistrar)
    implicit0(sp: SecurityProvider[IO]) <- SecurityProvider.forAsync[IO]
    metrics <- Metrics.forAsync[IO](Seq.empty)
    random <- Random.scalaUtilRandom[IO].asResource
    mkKeyPair = () => KeyPairGenerator.makeKeyPair.unsafeRunSync()
  } yield (kryo, sp, metrics, random, mkKeyPair)

  val balances: Map[Address, Balance] = Map(Address("DAG8Yy2enxizZdWoipKKZg6VXwk7rY2Z54mJqUdC") -> Balance(NonNegLong(10L)))

  def mkSnapshot(lastHash: Hash, reference: IncrementalGlobalSnapshot, keyPair: KeyPair, blocks: SortedSet[BlockAsActiveTip[DAGBlock]])(
    implicit K: KryoSerializer[IO],
    S: SecurityProvider[IO]
  ) =
    for {
      activeTips <- reference.activeTips
      snapshot = IncrementalGlobalSnapshot(
        reference.ordinal.next,
        Height.MinValue,
        SubHeight.MinValue,
        lastHash,
        blocks.toSortedSet,
        SortedMap.empty,
        SortedSet.empty,
        reference.epochProgress,
        NonEmptyList.of(PeerId(Hex("peer1"))),
        reference.tips.copy(remainedActive = activeTips)
      )
      signed <- Signed.forAsyncKryo[IO, IncrementalGlobalSnapshot](snapshot, keyPair)
      hashed <- signed.toHashed
    } yield hashed

  def mkSnapshots(blocks: List[List[BlockAsActiveTip[DAGBlock]]], initBalances: Map[Address, Balance])(
    implicit K: KryoSerializer[IO],
    S: SecurityProvider[IO]
  ): IO[(Hashed[GlobalSnapshot], NonEmptyList[Hashed[IncrementalGlobalSnapshot]])] =
    KeyPairGenerator.makeKeyPair[IO].flatMap { keyPair =>
      Signed
        .forAsyncKryo[IO, GlobalSnapshot](GlobalSnapshot.mkGenesis(initBalances, EpochProgress.MinValue), keyPair)
        .flatMap(_.toHashed)
        .flatMap { genesis =>
          mkSnapshot(genesis.hash, genesis.toIncrementalSnapshot, keyPair, SortedSet.empty).flatMap { snap1 =>
            blocks
              .foldLeftM(NonEmptyList.of(snap1)) {
                case (snapshots, chunkedBlocks) =>
                  mkSnapshot(snapshots.head.hash, snapshots.head.signed.value, keyPair, chunkedBlocks.toSortedSet).map(snapshots.prepend)
              }
              .map(incrementals => (genesis, incrementals.reverse))
          }
        }
    }

  def gst(
    globalSnapshot: Hashed[GlobalSnapshot],
    incrementalSnapshots: List[Hashed[IncrementalGlobalSnapshot]]
  )(implicit K: KryoSerializer[IO], S: SecurityProvider[IO], M: Metrics[IO]) = {
    def loadGlobalSnapshot(hash: Hash): IO[Either[GlobalSnapshot, IncrementalGlobalSnapshot]] =
      hash match {
        case h if h === globalSnapshot.hash => Left(globalSnapshot.signed.value).pure[IO]
        case _ => Right(incrementalSnapshots.map(snapshot => (snapshot.hash, snapshot)).toMap.get(hash).get.signed.value).pure[IO]
      }

    val globalSnapshotStorage = new SnapshotStorage[IO, IncrementalGlobalSnapshot, GlobalSnapshotInfo] {

      override def prepend(snapshot: Signed[IncrementalGlobalSnapshot]): IO[Boolean] = ???

      override def prepend(snapshot: Signed[IncrementalGlobalSnapshot], initialState: GlobalSnapshotInfo): IO[Boolean] = ???

      override def head: IO[Option[(Signed[IncrementalGlobalSnapshot], GlobalSnapshotInfo)]] = ???

      override def headSnapshot: IO[Option[Signed[IncrementalGlobalSnapshot]]] = ???

      override def get(ordinal: SnapshotOrdinal): IO[Option[Signed[IncrementalGlobalSnapshot]]] = ???

      override def get(hash: Hash): IO[Option[Signed[IncrementalGlobalSnapshot]]] = ???

    }

    val rewards = new Rewards[IO] {

      override def mintedDistribution(
        epochProgress: EpochProgress,
        facilitators: NonEmptySet[ID.Id]
      ): IO[SortedSet[transaction.RewardTransaction]] = ???

      override def feeDistribution(
        snapshotOrdinal: SnapshotOrdinal,
        transactions: SortedSet[DAGTransaction],
        facilitators: NonEmptySet[ID.Id]
      ): IO[SortedSet[transaction.RewardTransaction]] = ???

      override def getAmountByEpoch(epochProgress: EpochProgress, rewardsPerEpoch: SortedMap[EpochProgress, Amount]): Amount = ???

    }
    val signedValidator = SignedValidator.make[IO]
    val blockValidator =
      BlockValidator.make[IO, DAGTransaction, DAGBlock](
        signedValidator,
        TransactionChainValidator.make[IO, DAGTransaction],
        TransactionValidator.make[IO, DAGTransaction](signedValidator)
      )
    val blockAcceptanceManager = BlockAcceptanceManager.make(BlockAcceptanceLogic.make[IO, DAGTransaction, DAGBlock], blockValidator)
    val stateChannelValidator = StateChannelValidator.make[IO](signedValidator)
    val stateChannelProcessor = GlobalSnapshotStateChannelEventsProcessor.make[IO](stateChannelValidator)
    val globalSnapshotConsensusFunctions =
      GlobalSnapshotConsensusFunctions
        .make[IO](globalSnapshotStorage, blockAcceptanceManager, stateChannelProcessor, Amount.empty, rewards, AppEnvironment.Dev)
    GlobalSnapshotTraverse.make(loadGlobalSnapshot, globalSnapshotConsensusFunctions)
  }

  test("can compute state for given incremental global snapshot") { res =>
    implicit val (kryo, sp, metrics, _, _) = res

    mkSnapshots(List.empty, balances).flatMap { snapshots =>
      gst(snapshots._1, snapshots._2.toList).computeState(snapshots._2.head)
    }.map(state => expect.eql(state, GlobalSnapshotInfo(SortedMap.empty, SortedMap.empty, SortedMap.from(balances))))
  }

  test("computed state contains last refs and preserve total amount of balances when no fees or rewards ") { res =>
    implicit val (kryo, sp, metrics, random, keyPairFn) = res

    forall(dagBlockChainChunkedGen(keyPairFn)) {
      case (addresses, lastTx, _, dags) =>
        for {
          changedDAGS <- dags.traverse(_.traverse(_.block.toHashed.map(hashed => hashed.ownReference -> hashed.signed.parent)))
          (global, incrementals) <- mkSnapshots(dags, addresses.map(address => address -> Balance(NonNegLong(1000L))).toMap)
          traverser = gst(global, incrementals.toList)
          info <- traverser.computeState(incrementals.last)
          totalBalance = info.balances.values.map(Balance.toAmount(_)).reduce(_.plus(_).toOption.get)
          lastTxRefs <- lastTx.traverse(TransactionReference.of(_))
        } yield expect.eql((lastTxRefs, totalBalance), (info.lastTxRefs, Amount(NonNegLong.unsafeFrom(addresses.size * 1000L))))

    }
  }

  test("computed state contains last refs and include fees in total amount of balances when no rewards") { res =>
    implicit val (kryo, sp, metrics, random, keyPairFn) = res

    forall(dagBlockChainChunkedGen(keyPairFn, 1L)) {
      case (addresses, lastTx, txnsSize, dags) =>
        for {
          (global, incrementals) <- mkSnapshots(dags, addresses.map(address => address -> Balance(NonNegLong(1000L))).toMap)
          traverser = gst(global, incrementals.toList)
          info <- traverser.computeState(incrementals.last)
          totalBalance = info.balances.values.map(Balance.toAmount(_)).reduce(_.plus(_).toOption.get)
          lastTxRefs <- lastTx.traverse(TransactionReference.of(_))
        } yield
          expect.eql((lastTxRefs, totalBalance), (info.lastTxRefs, Amount(NonNegLong.unsafeFrom(addresses.size * 1000L - txnsSize * 1L))))

    }
  }

  private def transactionsChainGen(
    keyPairFn: => KeyPair,
    feeValue: NonNegLong
  )(implicit r: Random[IO], ks: KryoSerializer[IO], sc: SecurityProvider[IO]) = for {
    numberOfKeys <- Gen.choose(2, 5)
    keyPairs <- Gen.listOfN(numberOfKeys, Gen.delay(keyPairFn))
    addressParams = keyPairs.map(keyPair => AddressParams(keyPair))
    sizeOfChain <- Gen.choose(0, 50)
    sizeOfChunk <- Gen.choose(1, sizeOfChain + 1)
    txnStream <- Gen.const(
      TransactionGenerator
        .infiniteTransactionStream[IO](PosInt.unsafeFrom(sizeOfChunk), feeValue, NonEmptyList.fromListUnsafe(addressParams))
    )
    txns = txnStream.take(sizeOfChain.toLong).compile.toList.unsafeRunSync()
  } yield (keyPairs, txns.groupBy(_.source).view.mapValues(_.last).toMap.toSortedMap, txns)

  private def initialReferences() =
    NonEmptyList.fromListUnsafe(
      List
        .range(0, 4)
        .map { i =>
          BlockReference(Height.MinValue, ProofsHash(s"%064d".format(i)))
        }
    )

  private def dagBlockChainGen(
    keyPairFn: () => KeyPair,
    feeValue: NonNegLong = 0L
  )(implicit r: Random[IO], ks: KryoSerializer[IO], sc: SecurityProvider[IO]) = for {
    (keyPairs, lastTxns, txns) <- transactionsChainGen(keyPairFn(), feeValue)
    windows <- Gen.listOf(Gen.choose(0, txns.size)).map(l => (0 :: l ::: txns.size :: Nil).distinct.sorted).map(list => list.zip(list.tail))
    transactionsChain = windows
      .foldLeft[List[List[Signed[DAGTransaction]]]](Nil) { case (acc, (start, end)) => txns.slice(start, end) :: acc }
      .map(txns => NonEmptySet.fromSetUnsafe(SortedSet.from(txns)))
      .reverse
    dagChain = DAGBlockGenerator.dagBlockChain(
      transactionsChain,
      initialReferences(),
      NonEmptyList.of(keyPairFn(), keyPairFn(), keyPairFn())
    )
  } yield (keyPairs.map(_.getPublic.toAddress), lastTxns, txns.size, dagChain.compile.toList.unsafeRunSync())

  private def dagBlockChainChunkedGen(
    keyPairFn: () => KeyPair,
    feeValue: NonNegLong = 0L
  )(implicit r: Random[IO], ks: KryoSerializer[IO], sc: SecurityProvider[IO]) = for {
    (addresses, lastTxns, txnsSize, dags) <- dagBlockChainGen(keyPairFn, feeValue)
    // windows <- Gen.listOf(Gen.choose(0, dags.size)).map(l => (0 :: l ::: dags.size :: Nil).distinct.sorted).map(list => list.zip(list.tail))
    windows <- Gen.const((0 to dags.size).toList).map(l => (0 :: l ::: dags.size :: Nil).distinct.sorted).map(list => list.zip(list.tail))
    dagChunkedChain = windows
      .foldLeft[List[List[BlockAsActiveTip[DAGBlock]]]](Nil) { case (acc, (start, end)) => dags.slice(start, end) :: acc }
      .reverse
  } yield (addresses, lastTxns, txnsSize, dagChunkedChain)

}
