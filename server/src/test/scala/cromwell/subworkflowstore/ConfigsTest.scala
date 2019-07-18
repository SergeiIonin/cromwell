package cromwell.subworkflowstore

import com.typesafe.config.ConfigFactory
import cromwell.{CommandLineArguments, CommandLineParser}

object ConfigsTest extends App {

  sealed trait Command
  case object Run extends Command
  case object Server extends Command
  case object Submit extends Command

  def buildParser(): scopt.OptionParser[CommandLineArguments] = new CommandLineParser()

  def runCromwell(args: CommandLineArguments): Unit = {
    //CromwellEntryPoint.runSingle(args)
    val config = ConfigFactory.load()
println(config == config)
    // val awsConfig = config.getConfig("aws")

  }

  val parser = buildParser()

  val parsedArgs = parser.parse(args, CommandLineArguments())
  parsedArgs match {
    case Some(pa) => runCromwell(pa)
    case None => parser.showUsage()
  }

}
