package org.tessellation.currency.l0

import cats.effect.{IO, Resource}
import cats.syntax.all._

import org.tessellation.BuildInfo
import org.tessellation.currency.dataApplication.{BaseDataApplicationL0Service, L0NodeContext}
import org.tessellation.currency.l0.cli.method
import org.tessellation.currency.l0.cli.method._
import org.tessellation.currency.l0.http.p2p.P2PClient
import org.tessellation.currency.l0.modules._
import org.tessellation.currency.l0.node.L0NodeContext
import org.tessellation.currency.schema.currency._
import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.ext.kryo._
import org.tessellation.node.shared.app.{NodeShared, TessellationIOApp, getMajorityPeerIds}
import org.tessellation.node.shared.domain.rewards.Rewards
import org.tessellation.node.shared.infrastructure.gossip.{GossipDaemon, RumorHandlers}
import org.tessellation.node.shared.resources.MkHttpServer
import org.tessellation.node.shared.resources.MkHttpServer.ServerName
import org.tessellation.node.shared.snapshot.currency.CurrencySnapshotEvent
import org.tessellation.node.shared.{NodeSharedOrSharedRegistrationIdRange, nodeSharedKryoRegistrar}
import org.tessellation.schema.cluster.ClusterId
import org.tessellation.schema.node.NodeState
import org.tessellation.security.SecurityProvider

import com.monovore.decline.Opts

abstract class CurrencyL0App(
  name: String,
  header: String,
  clusterId: ClusterId,
  version: String
) extends TessellationIOApp[Run](
      name,
      header,
      clusterId,
      version = version
    ) {

  val opts: Opts[Run] = method.opts

  type KryoRegistrationIdRange = NodeSharedOrSharedRegistrationIdRange

  val kryoRegistrar: Map[Class[_], KryoRegistrationId[KryoRegistrationIdRange]] =
    nodeSharedKryoRegistrar

  def dataApplication: Option[Resource[IO, BaseDataApplicationL0Service[IO]]] = None

  def rewards(
    implicit sp: SecurityProvider[IO]
  ): Option[Rewards[IO, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotEvent]] = None

  def run(method: Run, nodeShared: NodeShared[IO]): Resource[IO, Unit] = {
    import nodeShared._

    val cfg = method.appConfig

    for {
      dataApplicationService <- dataApplication.sequence

      queues <- Queues.make[IO](sharedQueues).asResource
      storages <- Storages.make[IO](sharedStorages, cfg.snapshot, method.globalL0Peer, dataApplicationService).asResource
      p2pClient = P2PClient.make[IO](sharedP2PClient, sharedResources.client, sharedServices.session)
      validators = Validators.make[IO](seedlist)
      implicit0(nodeContext: L0NodeContext[IO]) = L0NodeContext.make[IO](storages.snapshot)
      maybeMajorityPeerIds <- getMajorityPeerIds[IO](
        nodeShared.prioritySeedlist,
        method.nodeSharedConfig.priorityPeerIds,
        cfg.environment
      ).asResource
      services <- Services
        .make[IO](
          p2pClient,
          sharedServices,
          storages,
          sharedResources.client,
          sharedServices.session,
          nodeShared.seedlist,
          nodeShared.nodeId,
          keyPair,
          cfg,
          dataApplicationService,
          rewards,
          validators.signedValidator,
          sharedServices.globalSnapshotContextFns,
          maybeMajorityPeerIds
        )
        .asResource
      programs = Programs.make[IO](
        keyPair,
        nodeShared.nodeId,
        cfg.globalL0Peer,
        sharedPrograms,
        storages,
        services,
        p2pClient,
        services.snapshotContextFunctions,
        dataApplicationService.zip(storages.calculatedStateStorage)
      )
      healthChecks <- HealthChecks
        .make[IO](
          storages,
          services,
          programs,
          p2pClient,
          sharedResources.client,
          sharedServices.session,
          cfg.healthCheck,
          nodeShared.nodeId
        )
        .asResource
      rumorHandler = RumorHandlers
        .make[IO](storages.cluster, healthChecks.ping, services.localHealthcheck, sharedStorages.forkInfo)
        .handlers <+>
        services.consensus.handler
      _ <- Daemons
        .start(storages, services, programs, queues, healthChecks, services.dataApplication, cfg)
        .asResource

      api = HttpApi
        .make[IO](
          storages,
          queues,
          services,
          programs,
          healthChecks,
          keyPair.getPrivate,
          cfg.environment,
          nodeShared.nodeId,
          BuildInfo.version,
          cfg.http,
          services.dataApplication
        )
      _ <- MkHttpServer[IO].newEmber(ServerName("public"), cfg.http.publicHttp, api.publicApp)
      _ <- MkHttpServer[IO].newEmber(ServerName("p2p"), cfg.http.p2pHttp, api.p2pApp)
      _ <- MkHttpServer[IO].newEmber(ServerName("cli"), cfg.http.cliHttp, api.cliApp)

      gossipDaemon = GossipDaemon.make[IO](
        storages.rumor,
        queues.rumor,
        storages.cluster,
        p2pClient.gossip,
        rumorHandler,
        validators.rumorValidator,
        services.localHealthcheck,
        nodeId,
        generation,
        cfg.gossip.daemon,
        services.collateral
      )

      program <- (method match {
        case m: CreateGenesis =>
          programs.genesis.create(dataApplicationService)(
            m.genesisBalancesPath,
            keyPair
          ) >> nodeShared.stopSignal.set(true)

        case other =>
          for {
            innerProgram <- other match {
              case rv: RunValidator =>
                storages.identifier.setInitial(rv.identifier) >>
                  gossipDaemon.startAsRegularValidator >>
                  programs.globalL0PeerDiscovery.discoverFrom(cfg.globalL0Peer) >>
                  storages.node.tryModifyState(NodeState.Initial, NodeState.ReadyToJoin)

              case rr: RunRollback =>
                storages.identifier.setInitial(rr.identifier) >>
                  storages.node.tryModifyState(
                    NodeState.Initial,
                    NodeState.RollbackInProgress,
                    NodeState.RollbackDone
                  )(programs.rollback.rollback) >>
                  gossipDaemon.startAsInitialValidator >>
                  services.cluster.createSession >>
                  services.session.createSession >>
                  programs.globalL0PeerDiscovery.discoverFrom(cfg.globalL0Peer) >>
                  storages.node.setNodeState(NodeState.Ready)

              case m: RunGenesis =>
                storages.node.tryModifyState(
                  NodeState.Initial,
                  NodeState.LoadingGenesis,
                  NodeState.GenesisReady
                )(programs.genesis.accept(dataApplicationService)(m.genesisPath)) >>
                  gossipDaemon.startAsInitialValidator >>
                  services.cluster.createSession >>
                  services.session.createSession >>
                  programs.globalL0PeerDiscovery.discoverFrom(cfg.globalL0Peer) >>
                  storages.node.setNodeState(NodeState.Ready)

              case _ => IO.unit
            }
            _ <- StateChannel
              .run[IO](services, storages, programs)
              .compile
              .drain
          } yield innerProgram
      }).asResource

    } yield program
  }
}
