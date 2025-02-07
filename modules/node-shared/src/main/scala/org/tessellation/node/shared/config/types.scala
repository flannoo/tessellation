package org.tessellation.node.shared.config

import cats.data.NonEmptySet

import scala.concurrent.duration.FiniteDuration

import org.tessellation.env.AppEnvironment
import org.tessellation.schema.balance.Amount
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.PeerId

import com.comcast.ip4s.{Host, Port}
import eu.timepit.refined.types.numeric.{NonNegLong, PosInt, PosLong}
import fs2.io.file.Path

object types {

  case class SharedConfig(
    environment: AppEnvironment,
    gossipConfig: GossipConfig,
    httpConfig: HttpConfig,
    leavingDelay: FiniteDuration,
    stateAfterJoining: NodeState,
    collateral: CollateralConfig,
    trustStorage: TrustStorageConfig,
    priorityPeerIds: Option[NonEmptySet[PeerId]],
    snapshotSizeConfig: SnapshotSizeConfig,
    forkInfoStorage: ForkInfoStorageConfig
  )

  case class SnapshotSizeConfig(
    singleSignatureSizeInBytes: PosLong,
    maxStateChannelSnapshotBinarySizeInBytes: PosLong
  )

  case class RumorStorageConfig(
    peerRumorsCapacity: PosLong,
    activeCommonRumorsCapacity: NonNegLong,
    seenCommonRumorsCapacity: NonNegLong
  )

  case class GossipDaemonConfig(
    peerRound: GossipRoundConfig,
    commonRound: GossipRoundConfig
  )

  case class GossipRoundConfig(
    fanout: PosInt,
    interval: FiniteDuration,
    maxConcurrentRounds: PosInt
  )

  case class GossipConfig(
    storage: RumorStorageConfig,
    daemon: GossipDaemonConfig
  )

  case class ConsensusConfig(
    timeTriggerInterval: FiniteDuration,
    declarationTimeout: FiniteDuration,
    declarationRangeLimit: NonNegLong,
    lockDuration: FiniteDuration
  )

  case class SnapshotConfig(
    consensus: ConsensusConfig,
    snapshotPath: Path,
    incrementalTmpSnapshotPath: Path,
    incrementalPersistedSnapshotPath: Path,
    inMemoryCapacity: NonNegLong,
    snapshotInfoPath: Path
  )

  case class HttpClientConfig(
    timeout: FiniteDuration,
    idleTimeInPool: FiniteDuration
  )

  case class HttpServerConfig(
    host: Host,
    port: Port,
    shutdownTimeout: FiniteDuration
  )

  case class HttpConfig(
    externalIp: Host,
    client: HttpClientConfig,
    publicHttp: HttpServerConfig,
    p2pHttp: HttpServerConfig,
    cliHttp: HttpServerConfig
  )

  case class HealthCheckConfig(
    ping: PingHealthCheckConfig,
    removeUnresponsiveParallelPeersAfter: FiniteDuration,
    requestProposalsAfter: FiniteDuration
  )

  case class PingHealthCheckConfig(
    enabled: Boolean,
    concurrentChecks: PosInt,
    defaultCheckTimeout: FiniteDuration,
    defaultCheckAttempts: PosInt,
    ensureCheckInterval: FiniteDuration
  )

  case class CollateralConfig(
    amount: Amount
  )

  case class TrustStorageConfig(
    ordinalTrustUpdateInterval: NonNegLong,
    ordinalTrustUpdateDelay: NonNegLong,
    seedlistInputBias: Double,
    seedlistOutputBias: Double
  )

  case class PeerDiscoveryDelay(
    checkPeersAttemptDelay: FiniteDuration,
    checkPeersMaxDelay: FiniteDuration,
    additionalDiscoveryDelay: FiniteDuration,
    minPeers: PosInt
  )

  case class ForkInfoStorageConfig(
    maxSize: PosInt
  )

}
