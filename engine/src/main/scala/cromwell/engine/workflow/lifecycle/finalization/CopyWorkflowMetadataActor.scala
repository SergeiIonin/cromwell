package cromwell.engine.workflow.lifecycle.finalization

import akka.actor.{ActorRef, LoggingFSM, Props}
import cromwell.backend.AllBackendInitializationData
import cromwell.backend.BackendWorkflowFinalizationActor.{FinalizationFailed, FinalizationSuccess, Finalize}
import cromwell.core.Dispatcher.IoDispatcher
import cromwell.core.WorkflowOptions._
import cromwell.core._
import cromwell.core.io.AsyncIoActorClient
import cromwell.core.path.{BetterFileMethods, PathFactory}
import cromwell.engine.EngineWorkflowDescriptor
import cromwell.filesystems.gcs.batch.GcsBatchCommandBuilder
import cromwell.services.MetadataServicesStore
import cromwell.services.metadata.MetadataQuery
import cromwell.services.metadata.MetadataService.{MetadataLookupResponseWithRequester, MetadataServiceKeyLookupFailed, SwitchToWaitMetadata}
import cromwell.services.metadata.impl.MetadataDatabaseAccess
import cromwell.webservice.metadata.MetadataBuilderActor
import cromwell.webservice.metadata.MetadataBuilderActor.{BuiltMetadataResponse, ReadyToBuildResponse}
import spray.json.JsObject

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
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
  override lazy val ioCommandBuilder = GcsBatchCommandBuilder
  implicit val ec = context.dispatcher
  override val pathBuilders = workflowDescriptor.pathBuilders
  //val respondTo: ActorRef = sender

  startWith(Initial, None)

  onTransition {
    case Initial -> GetMetadata  =>
      log.info("Transition from Initial to GetMetadata")
  }

  when(Initial) {
    case Event(Finalize, _) =>
      val respondTo: ActorRef = sender
      val selv = context.self // todo should it be here?
      log.info(s"In the CWMA, state ${stateName}, respondTo is $respondTo")
      workflowDescriptor.getWorkflowOption(FinalWorkflowMetadataDir) match {
        case Some(metadataPath) =>
          val mba = context.actorOf(MetadataBuilderActor.props(serviceRegistryActor))
          mba ! SwitchToWaitMetadata(selv)
          goto(WaitingState) using Option(CopyWorkflowMetadataActorData(metadataPath, Option(mba), Option(respondTo)))
        case None =>
          respondTo ! FinalizationSuccess
          context.stop(self)
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
      log.info(s"In the CWMA, state $stateName")
      val mba = data.get.builderActor.get // todo is it important?
      context.stop(mba)
      val respondTo = data.get.respActor.get
      val metadataContent = getJsBundle(builtMetadataResponse).toString
      writeMetadataToPath(data.get.metaDataPath, metadataContent) onComplete {
        case Success(_) =>
          log.info(s"the FinalizationSuccess is sending to ${respondTo}")
          respondTo ! FinalizationSuccess //respondTo ! s
        case Failure(f) =>
          log.info(s"the FinalizationFailed is sending to ${respondTo}")
          respondTo ! FinalizationFailed(f)
        /*case Success(_) => data.get.respActor.get ! FinalizationSuccess //respondTo ! s
        case Failure(f) => data.get.respActor.get ! FinalizationFailed(f)*/
      }
    }
      //context.stop(self)  // todo figure out how context.stop(self) is affecting! check for system.stop(mba)
      stay()
  }

  private def getJsBundle(builtMetadataResponse: BuiltMetadataResponse): JsObject = {
    val jsObject: JsObject = (BuiltMetadataResponse unapply builtMetadataResponse).getOrElse(JsObject.empty)
    log.info(s"CWMetadataActor, this is the jsObject $jsObject")
    jsObject
  }

  private def writeMetadataToPath(workflowMetadataFilePath: String, metadataContent: String): Future[Unit] = {
    val workflowMetadataPath = buildPath(workflowMetadataFilePath)
    val destFileName = workflowId.id + "_metadata.json"
    val fullWorkflowMetadataPath = buildPath(workflowMetadataPath + "/" + destFileName)
    log.info(s"In CWMA, this is the workflowMetadataPath $workflowMetadataPath")
    //log.info(s"In CWMA, here's the metadataContent $metadataContent")
    asyncIo.writeAsync(fullWorkflowMetadataPath, metadataContent, BetterFileMethods.OpenOptions.default)
  }

}