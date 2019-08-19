package wdl.transforms.base.wdlom2wom

import cats.instances.either._
import cats.instances.vector._
import cats.syntax.either._
import cats.syntax.traverse._
import cats.syntax.validated._
import common.Checked
import common.transforms.CheckedAtoB
import common.validation.Checked._
import common.validation.ErrorOr.{ErrorOr, _}
import common.validation.Validation._
import cromwell.languages.LanguageFactory
import cromwell.languages.util.ImportResolver._
import wdl.model.draft3.elements._
import wdl.transforms.base.wdlom2wom.StructEvaluation.StructEvaluationInputs
import wdl.transforms.base.wdlom2wom.TaskDefinitionElementToWomTaskDefinition.TaskDefinitionElementToWomInputs
import wdl.transforms.base.wdlom2wom.WorkflowDefinitionElementToWomWorkflowDefinition.WorkflowDefinitionConvertInputs
import wom.SourceFileLocation  // add it for your fix
//import wom.SourceFileLocation
import wom.callable.{Callable, CallableTaskDefinition, WorkflowDefinition}
import wom.core.WorkflowOptionsJson
import wom.executable.WomBundle
import wom.transforms.WomBundleMaker
import wom.transforms.WomBundleMaker.ops._
import wom.types.WomType

object FileElementToWomBundle {

  implicit val fileElementToWomBundle: WomBundleMaker[FileElementToWomBundleInputs] = new WomBundleMaker[FileElementToWomBundleInputs] {

    override def toWomBundle(a: FileElementToWomBundleInputs): Checked[WomBundle] = {

      def toWorkflowInner(imports: Vector[WomBundle], tasks: Vector[TaskDefinitionElement], structs: Map[String, WomType]): ErrorOr[WomBundle] = {

        val allStructs = structs ++ imports.flatMap(_.typeAliases)


        //
        def localTaskValidator(x: FileElementToWomBundleInputs): ErrorOr[WomBundle] = {
          val localTasksValidation: ErrorOr[Map[String, Callable]] = {
            tasks.traverse { taskDefinition =>
              x.taskConverter
                .run(TaskDefinitionElementToWomInputs(taskDefinition, structs))
                .map(t => t.name -> t).toValidated
            }.map(_.toMap)
          }
          localTasksValidation flatMap { localTaskMapping =>

            val workflowsValidation: ErrorOr[Vector[WorkflowDefinition]] = {
              x.fileElement.workflows.toVector.traverse { workflowDefinition =>

                val convertInputs = WorkflowDefinitionConvertInputs(workflowDefinition,
                  allStructs,
                  localTaskMapping ++ imports.flatMap(_.allCallables),
                  x.convertNestedScatterToSubworkflow)
                x.workflowConverter.run(convertInputs).toValidated
              }
            }
            workflowsValidation map { workflows =>
              val primary: Option[Callable] =
                if (workflows.size == 1) {
                  workflows.headOption
                } else if (workflows.isEmpty && tasks.size == 1) {
                  localTaskMapping.headOption map { case (_, callable) => callable }
                } else None

              val bundledCallableMap = (localTaskMapping.values.toSet ++ workflows).map(c => c.name -> c).toMap

              WomBundle(primary, bundledCallableMap, allStructs)
            }
          }
        }

        if (a.fileElement.workflows.isEmpty) {
          val mockingGraphElements: Set[WorkflowGraphElement] = Set(CallElement(a.fileElement.tasks.head.name, // todo looks like it should be empty initially it was a.fileElement.tasks.head.name
            None,
            Vector.empty,
            None,
            Some(SourceFileLocation(4))
            )
          )

          val mockingWorkflows: Seq[WorkflowDefinitionElement] = Seq(WorkflowDefinitionElement("", // todo looks like it should be empty initially it was "mockingWorkflows"
            None,
            mockingGraphElements,
            None,
            None,
            None,
            Some(SourceFileLocation(3))
          )
          )
          val mockingFileElement = a.fileElement.copy(workflows = mockingWorkflows)
          val aa: FileElementToWomBundleInputs = a.copy(fileElement = mockingFileElement)
          localTaskValidator(aa)
        }
        else localTaskValidator(a)
        //
      }

      val taskDefValidation: ErrorOr[Vector[TaskDefinitionElement]] = a.fileElement.tasks.toVector.validNel // this lines seems to be similar in task and workflow
      val importsValidation: ErrorOr[Vector[WomBundle]] = a.fileElement.imports.toVector.traverse { importWomBundle(_, a.workflowOptionsJson, a.importResolvers, a.languageFactories) }
      // at this point importsValidation == Valid(Vector())

      def structsValidationCopy(x: ErrorOr[Map[String, WomType]]) = x
      var structsValidationCpy: ErrorOr[Map[String, WomType]] = null
      var importsCpy: Vector[WomBundle] = null

      val res = (importsValidation flatMap { imports =>
        val structsValidation: ErrorOr[Map[String, WomType]] = StructEvaluation.convert(StructEvaluationInputs(a.fileElement.structs, imports.flatMap(_.typeAliases).toMap))
        structsValidationCpy = structsValidationCopy(structsValidation)
        importsCpy = imports
        (taskDefValidation, structsValidation) flatMapN { (tasks, structs) =>
          toWorkflowInner(imports, tasks, structs) }
      }).toEither
      println(res.getClass + structsValidationCpy.getClass.toString)

       val myRes = (taskDefValidation, structsValidationCpy) flatMapN {
         (tasks, structs) =>
           toWorkflowInner(importsCpy, tasks, structs)
       }
      println(myRes.getClass)
      res // difference in primaryCallable and allCallables (the workflow case contains yet another tuple with graph and more)
    }
  }

  def convert(a: FileElementToWomBundleInputs): Checked[WomBundle] = a.toWomBundle

  private def importWomBundle(importElement: ImportElement,
                              optionsJson: WorkflowOptionsJson,
                              importResolvers: List[ImportResolver],
                              languageFactories: List[LanguageFactory]): ErrorOr[WomBundle] = {
    val compoundImportResolver: CheckedAtoB[ImportResolutionRequest, ResolvedImportBundle] = CheckedAtoB.firstSuccess(importResolvers.map(_.resolver), s"resolve import '${importElement.importUrl}'")

    val languageFactoryKleislis: List[CheckedAtoB[ResolvedImportBundle, WomBundle]] = languageFactories map { factory =>
      CheckedAtoB.fromCheck { resolutionBundle: ResolvedImportBundle =>
        factory.getWomBundle(resolutionBundle.source, optionsJson, resolutionBundle.newResolvers, languageFactories)
      }
    }
    val compoundLanguageFactory: CheckedAtoB[ResolvedImportBundle, WomBundle] = CheckedAtoB.firstSuccess(languageFactoryKleislis, s"convert imported '${importElement.importUrl}' to WOM")

    val overallConversion = compoundImportResolver andThen compoundLanguageFactory

    overallConversion
      .run(ImportResolutionRequest(importElement.importUrl, importResolvers))
      .map { applyNamespace(_, importElement) }
      .flatMap { respectImportRenames(_, importElement.structRenames) }
      .contextualizeErrors(s"import '${importElement.importUrl}'")
      .toValidated
  }

  private def applyNamespace(womBundle: WomBundle, importElement: ImportElement): WomBundle = {
    val namespace = importElement.namespace match {
      case Some(n) => n
      case None => importElement.importUrl.split('/').last.stripSuffix(".wdl")
    }

    def applyNamespace(tuple: (String, Callable)): (String, Callable) = s"$namespace.${tuple._1}" -> tuple._2

    womBundle.copy(allCallables = womBundle.allCallables.map(applyNamespace))
  }

  private def respectImportRenames(womBundle: WomBundle, importAliases: Map[String, String]): Checked[WomBundle] = {
    val importedStructs = womBundle.typeAliases
    val unexpectedAliases = importAliases.keySet.diff(womBundle.typeAliases.keySet)
    if (unexpectedAliases.isEmpty) {
      val newStructs = importedStructs map {
        case (key, value) if importAliases.contains(key) => importAliases(key) -> value
        case (otherKey, otherValue) => otherKey -> otherValue
      }
      womBundle.copy(typeAliases = newStructs).validNelCheck
    } else {
      s"Cannot import and rename: [${unexpectedAliases.mkString(", ")}] because the set of imported structs was: [${importedStructs.keySet.mkString(", ")}]".invalidNelCheck
    }
  }
}

final case class FileElementToWomBundleInputs(fileElement: FileElement,
                                              workflowOptionsJson: WorkflowOptionsJson,
                                              convertNestedScatterToSubworkflow : Boolean,
                                              importResolvers: List[ImportResolver],
                                              languageFactories: List[LanguageFactory],
                                              workflowConverter: CheckedAtoB[WorkflowDefinitionConvertInputs, WorkflowDefinition],
                                              taskConverter: CheckedAtoB[TaskDefinitionElementToWomInputs, CallableTaskDefinition]
                                             )
