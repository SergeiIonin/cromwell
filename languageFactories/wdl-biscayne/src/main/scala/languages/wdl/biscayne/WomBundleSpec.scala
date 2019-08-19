package languages.wdl.biscayne

import java.io.File

import cromwell.core.path.DefaultPath
import cromwell.languages.util.ImportResolver.{DirectoryResolver, ImportResolver}
import wom.core.{WorkflowOptionsJson, WorkflowSource}

import scala.collection.mutable.ArrayBuffer

//import wom.core.WorkflowSource

import scala.io.Source

object WomBundleSpec extends App {

  def getWorkflowSource(sourceWdl: File): WorkflowSource = {
    val fileBuf: ArrayBuffer[String] = ArrayBuffer.empty
    for (line <- Source.fromFile(sourceWdl).getLines) {
      fileBuf += line
    }
    val wfsource = fileBuf.map(_.concat("\n")).mkString
    wfsource
  }

  def getWorkflowOptionsJson: WorkflowOptionsJson = "{\n\n}"

  def getImportResolvers: List[ImportResolver] = {
    DirectoryResolver.apply("/home/serj/Scala/cromwellEpam/cromwell", true, None, false)
    //List(DirectoryResolver.apply("/home/serj/Scala/cromwellEpam/cromwell", true, None, false))
  }
  getWorkflowSource(new File("/home/serj/Scala/cromwellEpam/cromwell/myTask.wdl"))

 // println(getWorkflowSource(new File("/home/serj/Scala/cromwellEpam/cromwell/myTask.wdl")))

 /* def createWomBundle(): Unit = {

  }*/

}
