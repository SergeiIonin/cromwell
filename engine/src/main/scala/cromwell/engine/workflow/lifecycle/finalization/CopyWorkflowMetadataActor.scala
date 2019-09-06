package cromwell.engine.workflow.lifecycle.finalization

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.event.LoggingReceive
import akka.pattern.ask
import akka.util.Timeout
import cromwell.backend.BackendLifecycleActor.BackendWorkflowLifecycleActorResponse
import cromwell.backend.BackendWorkflowFinalizationActor.{FinalizationFailed, FinalizationResponse, FinalizationSuccess, Finalize}
import cromwell.backend.{AllBackendInitializationData, BackendConfigurationDescriptor, BackendInitializationData, BackendLifecycleActorFactory}
import cromwell.core.Dispatcher.IoDispatcher
import cromwell.core.WorkflowOptions._
import cromwell.core._
import cromwell.core.io.AsyncIoActorClient
import cromwell.core.path.{BetterFileMethods, Path, PathCopier, PathFactory}
import cromwell.engine.EngineWorkflowDescriptor
import cromwell.engine.backend.{BackendConfiguration, CromwellBackends}
import cromwell.filesystems.gcs.batch.GcsBatchCommandBuilder
import cromwell.services.MetadataServicesStore
import cromwell.services.metadata.MetadataQuery
import cromwell.services.metadata.MetadataService.{MetadataLookupResponseWithRequester, MetadataServiceKeyLookupFailed, MetadataServiceResponse}
import cromwell.services.metadata.impl.MetadataDatabaseAccess
import cromwell.webservice.metadata.MetadataBuilderActor
import cromwell.webservice.metadata.MetadataBuilderActor.BuiltMetadataResponse
import spray.json.JsObject
import wom.values.{WomSingleFile, WomValue}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object CopyWorkflowMetadataActor {
  def props(workflowId: WorkflowId, ioActor: ActorRef, serviceRegistryActor: ActorRef, workflowDescriptor: EngineWorkflowDescriptor, workflowOutputs: CallOutputs,
            initializationData: AllBackendInitializationData) = Props(
    new CopyWorkflowMetadataActor(workflowId, ioActor, serviceRegistryActor, workflowDescriptor, workflowOutputs, initializationData)
  ).withDispatcher(IoDispatcher)
}

class CopyWorkflowMetadataActor(workflowId: WorkflowId, override val ioActor: ActorRef, serviceRegistryActor: ActorRef, val workflowDescriptor: EngineWorkflowDescriptor, workflowOutputs: CallOutputs,
                               initializationData: AllBackendInitializationData)
  extends Actor with ActorLogging with PathFactory with AsyncIoActorClient with MetadataServicesStore with MetadataDatabaseAccess {
  override lazy val ioCommandBuilder = GcsBatchCommandBuilder
  implicit val ec = context.dispatcher
  override val pathBuilders = workflowDescriptor.pathBuilders

  override def receive = LoggingReceive {
    case Finalize => performActionThenRespond(afterAll()(context.dispatcher), FinalizationFailed)(context.dispatcher) //sendJsBundleRequest
    //case builtMetadataResponse: BuiltMetadataResponse => getJsBundle(builtMetadataResponse)
    case builtMetadataResponse: BuiltMetadataResponse => saveMetadataOutputs(builtMetadataResponse)
  }


  private def performActionThenRespond(operation: => Future[BackendWorkflowLifecycleActorResponse],
                                       onFailure: (Throwable) => BackendWorkflowLifecycleActorResponse)
                                      (implicit ec: ExecutionContext) = {
    val respondTo: ActorRef = sender
    operation onComplete {
      case Success(r) => respondTo ! r
      case Failure(t) => respondTo ! onFailure(t)
    }
  }

 /* private def performActionThenRespond() = {
    workflowDescriptor.getWorkflowOption(FinalWorkflowMetadataDir) match {
      case Some(metadata) => sendJsBundleRequest()
        saveMetadataOutputs(metadata) map { _ => FinalizationSuccess }
      case None => Future.successful(FinalizationSuccess)
    }
*/
    /*val respondTo: ActorRef = sender
    operation onComplete {
      case Success(r) => respondTo ! r
      case Failure(t) => respondTo ! onFailure(t)
    }
  }*/

  /*private def sendJsBundleRequest(): Unit = {

    val mba = context.actorOf(MetadataBuilderActor.props(serviceRegistryActor))
    val query = MetadataQuery(workflowId, None, None, None, None, false)
    val timeout = FiniteDuration(30, "seconds")

    queryMetadataEvents(query, timeout) onComplete {
      case Success(m) => mba ! MetadataLookupResponseWithRequester(query, m, self)
      case Failure(t) => mba ! MetadataServiceKeyLookupFailed(query, t)
    }
  }*/

  private def getJsBundle(builtMetadataResponse: BuiltMetadataResponse): JsObject = {
    val jsObject: JsObject = (BuiltMetadataResponse unapply builtMetadataResponse).getOrElse(JsObject.empty)
    log.info(s"CWMetadataActor, this is the jsObject $jsObject")
    jsObject
  }

  private def saveMetadataOutputs(builtMetadataResponse: BuiltMetadataResponse) = {
    val metadataContent = getJsBundle(builtMetadataResponse).toString
    val operation = workflowDescriptor.getWorkflowOption(FinalWorkflowMetadataDir) match {
      case Some(outputs) => writeMetadataToPath(outputs, metadataContent) map { _ => FinalizationSuccess }
      case None => Future.successful(FinalizationSuccess)
    }
    val respondTo: ActorRef = sender
    operation onComplete {
      case Success(r) => respondTo ! r
      case Failure(t) => respondTo ! t
    }

  }

  private def writeMetadataToPath(workflowMetadataPath: Path, metadataContent: String): Future[Unit] = {
    //val workflowMetadataPath = buildPath(workflowMetadataFilePath)
    val destFileName = workflowId.id + "_metadata.json"
    val fullWorkflowMetadataPath = buildPath(workflowMetadataPath + "/" + destFileName)
    log.info(s"In CWMA, this is the workflowMetadataPath $workflowMetadataPath")
    log.info(s"In CWMA, here's the metadataContent $metadataContent")
    asyncIo.writeAsync(fullWorkflowMetadataPath, metadataContent, BetterFileMethods.OpenOptions.default)
  }

  private def saveMetadataOutputs(workflowMetadataFilePath: String) = {
    val workflowMetadataPath = buildPath(workflowMetadataFilePath)
    val outputFilePaths = getOutputFilePaths(workflowMetadataPath)

    // Check if there are duplicated destination paths and throw an exception if that is the case.
    // This creates a map of destinations and source paths which point to them in cases where there are multiple
    // source paths that point to the same destination.
    val duplicatedDestPaths: Map[Path, List[Path]] = outputFilePaths.groupBy{ case (_, destPath) => destPath}.collect {
      case (destPath, list) if list.size > 1 => destPath -> list.map {case (source, _) => source}
    }
    if (duplicatedDestPaths.nonEmpty) {
      val formattedCollidingCopyOptions = duplicatedDestPaths.toList
        .sortBy{case(dest, _) => dest.pathAsString} // Sort by destination path
        // Make a '/my/src -> /my/dest' copy tape string for each source and destination. Use flat map to get a single list
        // srcList is also sorted to get a deterministic output order. This is necessary for making sure the tests
        // for the error always succeed.
        .flatMap{ case (dest, srcList) => srcList.sortBy(_.pathAsString).map(_.pathAsString + s" -> $dest")}
      throw new IllegalStateException(
        "Cannot copy output files to given final_workflow_outputs_dir" +
          s" as multiple files will be copied to the same path: \n${formattedCollidingCopyOptions.mkString("\n")}")}

    val mba = context.actorOf(MetadataBuilderActor.props(serviceRegistryActor))
    val query = MetadataQuery(workflowId, None, None, None, None, false)
    val timeout = FiniteDuration(30, "seconds")
    implicit val actorTimeout = Timeout(timeout)

    queryMetadataEvents(query, timeout) onComplete {
      case Success(m) => {
        mba.ask(MetadataLookupResponseWithRequester(query, m, self)).mapTo[MetadataServiceResponse] collect {
          case responce: BuiltMetadataResponse => saveMetadataOutputs(responce)
          case _ => throw new Exception
        }
      }
      case Failure(t) => mba ! MetadataServiceKeyLookupFailed(query, t)
    }

  }

  // todo looks like unused
  private def findFiles(values: Seq[WomValue]): Seq[WomSingleFile] = {
    values flatMap {
      _.collectAsSeq {
        case file: WomSingleFile => file
      }
    }
  }

  // todo looks like unused
  private def getOutputFilePaths(workflowOutputsPath: Path): List[(Path, Path)] = {

    val useRelativeOutputPaths: Boolean = workflowDescriptor.getWorkflowOption(UseRelativeOutputPaths).contains("true")
    val rootAndFiles = for {
      // NOTE: Without .toSeq, outputs in arrays only yield the last output
      backend <- workflowDescriptor.backendAssignments.values.toSeq
      config <- BackendConfiguration.backendConfigurationDescriptor(backend).toOption.toSeq
      rootPath <- getBackendRootPath(backend, config).toSeq
      outputFiles = findFiles(workflowOutputs.outputs.values.toSeq).map(_.value)
    } yield (rootPath, outputFiles)

    // This regex will make sure the path is relative to the execution folder.
    // the call-.* part is there to prevent arbitrary folders called execution to get caught.
    // Truncate regex is declared here. If it were declared in the if statement the regex would have to be
    // compiled for every single file.
    lazy val truncateRegex = ".*/call-.*/execution/".r
    val outputFileDestinations = rootAndFiles flatMap {
      case (workflowRoot, outputs) =>
        outputs map { output =>
          val outputPath = PathFactory.buildPath(output, pathBuilders)
          outputPath -> {
            if (useRelativeOutputPaths) {
              val pathRelativeToExecDir = truncateRegex.replaceFirstIn(outputPath.pathAsString, "")
              workflowOutputsPath.resolve(pathRelativeToExecDir)
            }
            else PathCopier.getDestinationFilePath(workflowRoot, outputPath, workflowOutputsPath)
          }
        }
    }
    outputFileDestinations.distinct.toList
  }

  private def getBackendRootPath(backend: String, config: BackendConfigurationDescriptor): Option[Path] = {
    getBackendFactory(backend) map getRootPath(config, initializationData.get(backend))
  }

  private def getBackendFactory(backend: String): Option[BackendLifecycleActorFactory] = {
    CromwellBackends.backendLifecycleFactoryActorByName(backend).toOption
  }

  private def getRootPath(config: BackendConfigurationDescriptor, initializationData: Option[BackendInitializationData])
                         (backendFactory: BackendLifecycleActorFactory): Path = {
    backendFactory.getExecutionRootPath(workflowDescriptor.backendDescriptor, config.backendConfig, initializationData)
  }

  /**
   * Happens after everything else runs
   */
  final def afterAll()(implicit ec: ExecutionContext): Future[FinalizationResponse] = {
    workflowDescriptor.getWorkflowOption(FinalWorkflowMetadataDir) match {
      case Some(metadata) => saveMetadataOutputs(metadata) map { _ => FinalizationSuccess }
      case None => Future.successful(FinalizationSuccess)
    }
  }
}
