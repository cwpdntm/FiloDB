package filodb.coordinator

import akka.remote.testkit.{MultiNodeConfig, MultiNodeSpec}
import akka.testkit.ImplicitSender
import com.typesafe.scalalogging.StrictLogging
import org.scalatest.{BeforeAndAfterAll, FunSpecLike, Matchers}
import org.scalatest.concurrent.ScalaFutures

abstract class ClusterSpec(config: MultiNodeConfig) extends MultiNodeSpec(config)
  with FunSpecLike with Matchers with BeforeAndAfterAll
  with StrictLogging
  with ImplicitSender with ScalaFutures {

  val cluster = FilodbCluster(system)

}