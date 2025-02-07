package org.tessellation.node.shared.domain.cluster.programs

import cats.Applicative
import cats.effect.Async
import cats.effect.std.Queue
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.order._
import cats.syntax.show._
import cats.syntax.traverse._

import org.tessellation.effects.GenUUID
import org.tessellation.env.AppEnvironment
import org.tessellation.env.AppEnvironment.Dev
import org.tessellation.ext.crypto._
import org.tessellation.node.shared.domain.cluster.services.{Cluster, Session}
import org.tessellation.node.shared.domain.cluster.storage.{ClusterStorage, SessionStorage}
import org.tessellation.node.shared.domain.healthcheck.LocalHealthcheck
import org.tessellation.node.shared.domain.node.NodeStorage
import org.tessellation.node.shared.domain.seedlist.SeedlistEntry
import org.tessellation.node.shared.http.p2p.clients.SignClient
import org.tessellation.schema.ID.Id
import org.tessellation.schema.cluster._
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.Peer.toP2PContext
import org.tessellation.schema.peer._
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed
import org.tessellation.security.{Hasher, SecurityProvider}

import com.comcast.ip4s.{Host, IpLiteralSyntax}
import fs2.{Pipe, Stream}
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Joining {

  def make[F[_]: Async: GenUUID: SecurityProvider: Hasher](
    environment: AppEnvironment,
    nodeStorage: NodeStorage[F],
    clusterStorage: ClusterStorage[F],
    signClient: SignClient[F],
    cluster: Cluster[F],
    session: Session[F],
    sessionStorage: SessionStorage[F],
    localHealthcheck: LocalHealthcheck[F],
    seedlist: Option[Set[SeedlistEntry]],
    selfId: PeerId,
    stateAfterJoining: NodeState,
    peerDiscovery: PeerDiscovery[F],
    versionHash: Hash
  ): F[Joining[F]] =
    Queue
      .unbounded[F, P2PContext]
      .flatMap(
        make(
          _,
          environment,
          nodeStorage,
          clusterStorage,
          signClient,
          cluster,
          session,
          sessionStorage,
          localHealthcheck,
          seedlist,
          selfId,
          stateAfterJoining,
          peerDiscovery,
          versionHash
        )
      )

  def make[F[_]: Async: GenUUID: SecurityProvider: Hasher](
    joiningQueue: Queue[F, P2PContext],
    environment: AppEnvironment,
    nodeStorage: NodeStorage[F],
    clusterStorage: ClusterStorage[F],
    signClient: SignClient[F],
    cluster: Cluster[F],
    session: Session[F],
    sessionStorage: SessionStorage[F],
    localHealthcheck: LocalHealthcheck[F],
    seedlist: Option[Set[SeedlistEntry]],
    selfId: PeerId,
    stateAfterJoining: NodeState,
    peerDiscovery: PeerDiscovery[F],
    versionHash: Hash
  ): F[Joining[F]] = {

    val logger = Slf4jLogger.getLogger[F]

    val joining = new Joining(
      environment,
      nodeStorage,
      clusterStorage,
      signClient,
      cluster,
      session,
      sessionStorage,
      localHealthcheck,
      seedlist,
      selfId,
      stateAfterJoining,
      versionHash,
      joiningQueue
    ) {}

    def join: Pipe[F, P2PContext, Unit] =
      in =>
        in.evalMap { peer =>
          {
            joining.twoWayHandshake(peer, none) >>
              clusterStorage
                .getPeer(peer.id)
                .flatMap {
                  _.fold(Set.empty[P2PContext].pure[F]) { p =>
                    peerDiscovery.discoverFrom(p).map(_.map(toP2PContext)).handleErrorWith { err =>
                      logger.error(err)(s"Peer discovery from peer ${peer.show} failed").as(Set.empty)
                    }
                  }
                }
                .flatMap(_.toList.traverse(joiningQueue.offer(_).void))
                .void
          }.handleErrorWith { err =>
            logger.error(err)(s"Joining to peer ${peer.show} failed")
          }
        }

    val process = Stream.fromQueueUnterminated(joiningQueue).through(join).compile.drain

    Async[F].start(process).as(joining)
  }
}

sealed abstract class Joining[F[_]: Async: GenUUID: SecurityProvider: Hasher] private (
  environment: AppEnvironment,
  nodeStorage: NodeStorage[F],
  clusterStorage: ClusterStorage[F],
  signClient: SignClient[F],
  cluster: Cluster[F],
  session: Session[F],
  sessionStorage: SessionStorage[F],
  localHealthcheck: LocalHealthcheck[F],
  seedlist: Option[Set[SeedlistEntry]],
  selfId: PeerId,
  stateAfterJoining: NodeState,
  versionHash: Hash,
  joiningQueue: Queue[F, P2PContext]
) {

  private val logger = Slf4jLogger.getLogger[F]

  def join(toPeer: PeerToJoin): F[Unit] =
    for {
      _ <- validateJoinConditions()
      _ <- session.createSession

      _ <- joiningQueue.offer(toPeer)
    } yield ()

  def rejoin(withPeer: PeerToJoin): F[Unit] =
    twoWayHandshake(withPeer, None, skipJoinRequest = true).void

  private def validateJoinConditions(): F[Unit] =
    for {
      nodeState <- nodeStorage.getNodeState
      canJoinCluster <- nodeStorage.canJoinCluster
      _ <- Applicative[F].unlessA(canJoinCluster)(NodeStateDoesNotAllowForJoining(nodeState).raiseError[F, Unit])
    } yield ()

  def joinRequest(hasCollateral: PeerId => F[Boolean])(joinRequest: JoinRequest, remoteAddress: Host): F[Unit] = {
    for {
      _ <- nodeStorage.getNodeState.map(NodeState.inCluster).flatMap(NodeNotInCluster.raiseError[F, Unit].unlessA)
      _ <- sessionStorage.getToken.flatMap(_.fold(SessionDoesNotExist.raiseError[F, Unit])(_ => Applicative[F].unit))

      registrationRequest = joinRequest.registrationRequest

      _ <- hasCollateral(registrationRequest.id).flatMap(CollateralNotSatisfied.raiseError[F, Unit].unlessA)

      withPeer = PeerToJoin(
        registrationRequest.id,
        registrationRequest.ip,
        registrationRequest.p2pPort
      )
      _ <- twoWayHandshake(withPeer, remoteAddress.some, skipJoinRequest = true)
    } yield ()
  }.onError(err => logger.error(err)(s"Error during join attempt by ${joinRequest.registrationRequest.id.show}"))

  private def twoWayHandshake(
    withPeer: PeerToJoin,
    remoteAddress: Option[Host],
    skipJoinRequest: Boolean = false
  ): F[Peer] =
    for {
      _ <- validateSeedlist(withPeer)

      registrationRequest <- signClient.getRegistrationRequest.run(withPeer)

      _ <- validateHandshake(registrationRequest, remoteAddress)

      signRequest <- GenUUID[F].make.map(SignRequest.apply)
      signedSignRequest <- signClient.sign(signRequest).run(withPeer)

      _ <- verifySignRequest(signRequest, signedSignRequest, PeerId._Id.get(withPeer.id))
        .ifM(Applicative[F].unit, HandshakeSignatureNotValid.raiseError[F, Unit])

      _ <-
        if (skipJoinRequest) {
          Applicative[F].unit
        } else {
          clusterStorage
            .setToken(registrationRequest.clusterSession)
            .flatMap(_ => cluster.getRegistrationRequest)
            .map(JoinRequest.apply)
            .flatMap(signClient.joinRequest(_).run(withPeer))
            .ifM(
              Applicative[F].unit,
              new Throwable(s"Unexpected error occured when joining with peer=${withPeer.id}.").raiseError[F, Unit]
            )
        }

      peer = Peer(
        registrationRequest.id,
        registrationRequest.ip,
        registrationRequest.publicPort,
        registrationRequest.p2pPort,
        registrationRequest.session,
        registrationRequest.state,
        Responsive
      )

      _ <- clusterStorage
        .addPeer(peer)
        .ifM(
          localHealthcheck.cancel(registrationRequest.id),
          PeerAlreadyJoinedWithDifferentRegistrationData(registrationRequest.id).raiseError[F, Unit]
        )
      _ <- nodeStorage.tryModifyStateGetResult(NodeState.SessionStarted, stateAfterJoining)
    } yield peer

  private def validateSeedlist(peer: PeerToJoin): F[Unit] =
    PeerNotInSeedlist(peer.id)
      .raiseError[F, Unit]
      .unlessA(seedlist.map(_.map(_.peerId)).forall(_.contains(peer.id)))

  private def validateHandshake(registrationRequest: RegistrationRequest, remoteAddress: Option[Host]): F[Unit] =
    for {

      _ <- VersionMismatch.raiseError[F, Unit].whenA(registrationRequest.version =!= versionHash)
      _ <- EnvMismatch.raiseError[F, Unit].whenA(registrationRequest.environment =!= environment)

      ip = registrationRequest.ip
      existingPeer <- clusterStorage.getPeer(registrationRequest.id)

      _ <- existingPeer match {
        case Some(peer) if peer.session <= registrationRequest.session => Applicative[F].unit
        case None                                                      => Applicative[F].unit
        case _ =>
          PeerAlreadyJoinedWithNewerSession(registrationRequest.id, ip, registrationRequest.p2pPort, registrationRequest.session)
            .raiseError[F, Unit]
      }

      ownClusterId = clusterStorage.getClusterId

      _ <- Applicative[F].unlessA(registrationRequest.clusterId == ownClusterId)(ClusterIdDoesNotMatch.raiseError[F, Unit])

      ownClusterSession <- clusterStorage.getToken

      _ <- ownClusterSession match {
        case Some(session) if session === registrationRequest.clusterSession => Applicative[F].unit
        case None                                                            => Applicative[F].unit
        case _                                                               => ClusterSessionDoesNotMatch.raiseError[F, Unit]
      }

      _ <-
        Applicative[F].unlessA(environment == Dev || ip.toString != host"127.0.0.1".toString && ip.toString != host"localhost".toString) {
          LocalHostNotPermitted.raiseError[F, Unit]
        }

      _ <- remoteAddress.fold(Applicative[F].unit)(ra =>
        Applicative[F].unlessA(ip.compare(ra) == 0)(InvalidRemoteAddress.raiseError[F, Unit])
      )

      _ <- Applicative[F].unlessA(registrationRequest.id != selfId)(IdDuplicationFound.raiseError[F, Unit])

      seedlistHash <- seedlist.map(_.map(_.peerId)).hash
      _ <- Applicative[F].unlessA(registrationRequest.seedlist === seedlistHash)(SeedlistDoesNotMatch.raiseError[F, Unit])

    } yield ()

  private def verifySignRequest(signRequest: SignRequest, signed: Signed[SignRequest], id: Id): F[Boolean] =
    for {
      isSignedRequestConsistent <- (signRequest == signed.value).pure[F]
      isSignerCorrect = signed.proofs.forall(_.id == id)
      hasValidSignature <- signed.hasValidSignature
    } yield isSignedRequestConsistent && isSignerCorrect && hasValidSignature
}
