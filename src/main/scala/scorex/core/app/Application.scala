package scorex.core.app

import java.net.InetSocketAddress

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.ExceptionHandler
import akka.stream.ActorMaterializer
import scorex.core.api.http.{ApiErrorHandler, ApiRoute, CompositeHttpService}
import scorex.core.network._
import scorex.core.network.message._
import scorex.core.network.peer.PeerManagerRef
import scorex.core.settings.ScorexSettings
import scorex.core.transaction.Transaction
import scorex.core.utils.NetworkTimeProvider
import scorex.core.{NodeViewHolder, PersistentNodeViewModifier}
import scorex.util.ScorexLogging

import scala.concurrent.ExecutionContext

trait Application extends ScorexLogging {

  import scorex.core.network.NetworkController.ReceivableMessages.ShutdownNetwork

  type TX <: Transaction
  type PMOD <: PersistentNodeViewModifier
  type NVHT <: NodeViewHolder[TX, PMOD]

  val ApplicationNameLimit = 50

  //settings
  implicit val settings: ScorexSettings

  //api
  val apiRoutes: Seq[ApiRoute]

  implicit def exceptionHandler: ExceptionHandler = ApiErrorHandler.exceptionHandler

  protected implicit lazy val actorSystem = ActorSystem(settings.network.agentName)
  implicit val executionContext: ExecutionContext = actorSystem.dispatchers.lookup("scorex.executionContext")

  protected val features: Seq[PeerFeature]
  protected val additionalMessageSpecs: Seq[MessageSpec[_]]

  //p2p
  private val upnpGateway: Option[UPnPGateway] = if (settings.network.upnpEnabled) UPnP.getValidGateway(settings.network) else None
  // TODO use available port on gateway instead settings.network.bindAddress.getPort
  upnpGateway.map(_.addPort(settings.network.bindAddress.getPort))

  private lazy val basicSpecs = {
    val invSpec = new InvSpec(settings.network.maxInvObjects)
    val requestModifierSpec = new RequestModifierSpec(settings.network.maxInvObjects)
    val modifiersSpec = new ModifiersSpec(settings.network.maxPacketSize)
    Seq(
      GetPeersSpec,
      PeersSpec,
      invSpec,
      requestModifierSpec,
      modifiersSpec
    )
  }

  val nodeViewHolderRef: ActorRef
  val nodeViewSynchronizer: ActorRef
  /**
    * API description in openapi format in YAML or JSON
    */
  val swaggerConfig: String

  val timeProvider = new NetworkTimeProvider(settings.ntp)

  //an address to send to peers
  lazy val externalSocketAddress: Option[InetSocketAddress] = {
    settings.network.declaredAddress orElse {
      // TODO use available port on gateway instead settings.bindAddress.getPort
      upnpGateway.map(u => new InetSocketAddress(u.externalAddress, settings.network.bindAddress.getPort))
    }
  }

  val scorexContext = ScorexContext(
    messageSpecs = basicSpecs ++ additionalMessageSpecs,
    features = features,
    upnpGateway = upnpGateway,
    timeProvider = timeProvider,
    externalNodeAddress = externalSocketAddress
  )

  val peerManagerRef = PeerManagerRef(settings, scorexContext)

  val networkControllerRef: ActorRef = NetworkControllerRef(
    "networkController", settings.network, peerManagerRef, scorexContext)

  lazy val combinedRoute = CompositeHttpService(actorSystem, apiRoutes, settings.restApi, swaggerConfig).compositeRoute

  def run(): Unit = {
    require(settings.network.agentName.length <= ApplicationNameLimit)

    log.debug(s"Available processors: ${Runtime.getRuntime.availableProcessors}")
    log.debug(s"Max memory available: ${Runtime.getRuntime.maxMemory}")
    log.debug(s"RPC is allowed at ${settings.restApi.bindAddress.toString}")

    implicit val materializer = ActorMaterializer()
    val bindAddress = settings.restApi.bindAddress

    Http().bindAndHandle(combinedRoute, bindAddress.getAddress.getHostAddress, bindAddress.getPort)

    //on unexpected shutdown
    Runtime.getRuntime.addShutdownHook(new Thread() {
      override def run() {
        log.error("Unexpected shutdown")
        stopAll()
      }
    })
  }

  def stopAll(): Unit = synchronized {
    log.info("Stopping network services")
    upnpGateway.map(_.deletePort(settings.network.bindAddress.getPort))
    networkControllerRef ! ShutdownNetwork

    log.info("Stopping actors (incl. block generator)")
    actorSystem.terminate().onComplete { _ =>

      log.info("Exiting from the app...")
      System.exit(0)
    }
  }
}
