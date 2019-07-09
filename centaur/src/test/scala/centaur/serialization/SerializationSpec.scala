package centaur.serialization

import org.scalatest.{FlatSpec, Matchers}

import scala.collection.mutable

class SerializationSpec extends FlatSpec with Matchers {
  behavior of "SucessfullTests"
  it should "correctly serialize and deserialize data" in {
    val testsReports = TestsReports(mutable.HashMap("cool_test_1" -> "43-snapshot", "cool_test_2" -> "42-snapshot",
      "cool_test_3" -> "43-fq33qw4"))
    TestsReportsSerializer.write("centaur/src/test/resources/testSerialize.txt", testsReports)

    val result = TestsReportsSerializer.read("centaur/src/test/resources/testSerialize.txt")

    assert(testsReports == result)
  }
}
