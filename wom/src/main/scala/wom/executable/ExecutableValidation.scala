package wom.executable

import common.validation.IOChecked
import common.validation.IOChecked._
import wom.callable.ExecutableCallable
import wom.executable.Executable.{DelayedCoercionFunction, InputParsingFunction, ResolvedExecutableInputs}
import wom.expression.IoFunctionSet
import wom.graph.Graph

private [executable] object ExecutableValidation {

  private [executable] def validateExecutable(entryPoint: ExecutableCallable,
                                              inputParsingFunction: InputParsingFunction,
                                              parseGraphInputs: (Graph, Map[String, DelayedCoercionFunction], IoFunctionSet) => IOChecked[ResolvedExecutableInputs],
                                              inputFile: Option[String],
                                              ioFunctions: IoFunctionSet): IOChecked[Executable] = {
 /*   val parsedInputs = inputFile.map(inputParsingFunction).map(_.toIOChecked).getOrElse(IOChecked.pure(Map.empty[String, DelayedCoercionFunction]))
    println(parsedInputs.getClass)
    val validatedInputs = parseGraphInputs(entryPoint.graph, parsedInputs, ioFunctions)
    println(validatedInputs.getClass)*/
    inputFile.map(inputParsingFunction).map(_.toIOChecked).getOrElse(IOChecked.pure(Map.empty[String, DelayedCoercionFunction]))
      .flatMap(parsedInputs =>
        parseGraphInputs(entryPoint.graph, parsedInputs, ioFunctions)
          .map(validatedInputs => {
            val inputs = validatedInputs
            println(inputs)
            Executable(entryPoint, validatedInputs)
          })
      )
  }
}
