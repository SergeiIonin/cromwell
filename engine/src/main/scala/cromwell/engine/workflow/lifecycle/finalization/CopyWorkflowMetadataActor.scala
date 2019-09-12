package cromwell.engine.workflow.lifecycle.finalization

import akka.actor.{ActorRef, LoggingFSM, PoisonPill, Props}
import cromwell.backend.AllBackendInitializationData
import cromwell.backend.BackendWorkflowFinalizationActor.{FinalizationFailed, FinalizationSuccess, Finalize}
import cromwell.core.Dispatcher.{IoDispatcher, ServiceDispatcher}
import cromwell.core.WorkflowOptions._
import cromwell.core._
import cromwell.core.io.AsyncIoActorClient
import cromwell.core.path.{BetterFileMethods, PathFactory}
import cromwell.engine.EngineWorkflowDescriptor
import cromwell.filesystems.gcs.batch.GcsBatchCommandBuilder
import cromwell.services.MetadataServicesStore
import cromwell.services.metadata.MetadataQuery
import cromwell.services.metadata.MetadataService.{MetadataLookupResponseWithRequester, MetadataServiceKeyLookupFailed, SwitchToWaitMetadata}
import cromwell.services.metadata.impl.{MetadataDatabaseAccess, ReadDatabaseMetadataWorkerActor}
import spray.json.JsObject
import cromwell.services.metadata.impl.builder.MetadataBuilderActor
import cromwell.services.metadata.impl.builder.MetadataBuilderActor.{BuiltMetadataResponse, ReadyToBuildResponse}

import scala.concurrent.Future
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.{Failure, Success}

object CopyWorkflowMetadataActor {
  def props(workflowId: WorkflowId, ioActor: ActorRef, serviceRegistryActor: ActorRef, workflowDescriptor: EngineWorkflowDescriptor, workflowOutputs: CallOutputs,
            initializationData: AllBackendInitializationData) = Props(
    new CopyWorkflowMetadataActor(workflowId, ioActor, serviceRegistryActor, workflowDescriptor, workflowOutputs, initializationData)
  ).withDispatcher(IoDispatcher)
}

sealed trait CopyWorkflowMetadataActorState

case object Initial extends CopyWorkflowMetadataActorState
case object WaitingState extends CopyWorkflowMetadataActorState
case object GetMetadata extends CopyWorkflowMetadataActorState

case class CopyWorkflowMetadataActorData(
                                          metaDataPath: String,
                                          builderActor: Option[ActorRef],
                                          respActor: Option[ActorRef]
                                        )

class CopyWorkflowMetadataActor(workflowId: WorkflowId, override val ioActor: ActorRef, serviceRegistryActor: ActorRef, val workflowDescriptor: EngineWorkflowDescriptor, workflowOutputs: CallOutputs,
                                initializationData: AllBackendInitializationData)
  extends LoggingFSM[CopyWorkflowMetadataActorState, Option[CopyWorkflowMetadataActorData]] with MetadataServicesStore with MetadataDatabaseAccess
    with PathFactory with AsyncIoActorClient {
  implicit val ec = context.dispatcher
  override lazy val ioCommandBuilder = GcsBatchCommandBuilder
  override val pathBuilders = workflowDescriptor.pathBuilders

  private val metadataReadTimeout: Duration = Duration(30, "seconds")

  def readMetadataWorkerActorProps(): Props = ReadDatabaseMetadataWorkerActor.props(metadataReadTimeout).withDispatcher(ServiceDispatcher)

  startWith(Initial, None)

  when(Initial) {
    case Event(Finalize, _) =>
      val respondTo: ActorRef = sender
      val selv = context.self
      workflowDescriptor.getWorkflowOption(FinalWorkflowMetadataDir) match {
        case Some(metadataPath) =>
          val mba = context.actorOf(MetadataBuilderActor.props(readMetadataWorkerActorProps))
          mba ! SwitchToWaitMetadata(selv)
          goto(WaitingState) using Option(CopyWorkflowMetadataActorData(metadataPath, Option(mba), Option(respondTo)))
        case None =>
          respondTo ! FinalizationSuccess
          stay()
      }
  }

  when(WaitingState) {
    case Event(ReadyToBuildResponse, data) => {
      val query = MetadataQuery(workflowId, None, None, None, None, false)
      val timeout = FiniteDuration(30, "seconds")
      val mba = data.get.builderActor.get
      val selv = context.self
      queryMetadataEvents(query, timeout) onComplete {
        case Success(s) => mba ! MetadataLookupResponseWithRequester(query, s, selv)
        case Failure(f) => mba ! MetadataServiceKeyLookupFailed(query, f)
      }
      goto(GetMetadata) using data
    }
  }

  when(GetMetadata) {
    case Event(builtMetadataResponse: BuiltMetadataResponse, data) => {
      val mba = data.get.builderActor.get
      mba ! PoisonPill
      val respondTo = data.get.respActor.get
      val metadataContent = getJsBundle(builtMetadataResponse).toString
      writeMetadataToPath(data.get.metaDataPath, metadataContent) onComplete {
        case Success(_) =>
          respondTo ! FinalizationSuccess
        case Failure(f) =>
          respondTo ! FinalizationFailed(f)
      }
    }
      stay()
  }

  private def getJsBundle(builtMetadataResponse: BuiltMetadataResponse): JsObject = builtMetadataResponse match {
    case BuiltMetadataResponse(_, jsObject) => jsObject
    case _ => JsObject.empty
  }

  private def writeMetadataToPath(workflowMetadataFilePath: String, metadataContent: String): Future[Unit] = {
    val workflowMetadataPath = buildPath(workflowMetadataFilePath)
    val destFileName = workflowId.id + "_metadata.json"
    val fullWorkflowMetadataPath = buildPath(workflowMetadataPath + "/" + destFileName)
    asyncIo.writeAsync(fullWorkflowMetadataPath, metadataContent, BetterFileMethods.OpenOptions.default)
  }

  override def postStop() = {
    context.stop(self)
    super.postStop()
  }

}