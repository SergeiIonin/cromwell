package centaur.serialization

import java.io.{FileInputStream, FileOutputStream}

import com.esotericsoftware.kryo.io.{Input, Output}
import com.twitter.chill.ScalaKryoInstantiator

import scala.collection.mutable

/**
  * This class contains information about last version in which corresponding integration test was successfully run
  * @param testCasesRuns  key is a test name; value is a version of Cromwell, in which this test passed last time
  */
case class TestsReports(testCasesRuns: scala.collection.mutable.Map[String, String]) {
  def addSuccessfulTest(testName: String, version: String): Option[String] =
    testCasesRuns.put(testName, version)

  def testsToSkip(version: String): Set[String] =
    testCasesRuns.filter(_._2 == version).keys.toSet
}

/**
  * Serializer for {@link centaur.serialization.TestsReports} class. Based on Kryo, uses twitter's chill for Scala collections serialization
  * @see <a href="https://github.com/twitter/chill">chill</a>
  */
object TestsReportsSerializer {
  private val instantiator = new ScalaKryoInstantiator
  instantiator.setRegistrationRequired(false)
  private val kryo = instantiator.newKryo()

  def read(fileName: String): TestsReports = {
    val input = new Input(new FileInputStream(fileName))
    val obj = kryo.readObject(input, classOf[mutable.HashMap[String, String]])
    input.close()
    obj match {
      case null => TestsReports(mutable.Map.empty[String, String])
      case map => TestsReports(map)
    }
  }

  def write(fileName: String, successfullTests: TestsReports): Unit = {
    val output = new Output(new FileOutputStream(fileName))
    kryo.writeObject(output, successfullTests.testCasesRuns)
    output.close()
  }
}