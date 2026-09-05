import sbt.Keys.libraryDependencies
import sbt.*

object AppDependencies {

  private val bootstrapVersion = "10.8.0"
  private val activeMqVersion = "6.1.5"

  // ActiveMQ pulls a newer jackson-databind than Pekko/Play's jackson-module-scala supports.
  // We don't use ActiveMQ's jackson features, so drop them and let Play's jackson win.
  private val jacksonExclusion = ExclusionRule(organization = "com.fasterxml.jackson.core")

  val compile = Seq(
    "uk.gov.hmrc"        %% "bootstrap-backend-play-30" % bootstrapVersion,
    "org.apache.activemq" % "activemq-client"           % activeMqVersion excludeAll jacksonExclusion
  )

  val test = Seq(
    "uk.gov.hmrc"        %% "bootstrap-test-play-30" % bootstrapVersion % Test,
    "org.apache.activemq" % "activemq-broker"        % activeMqVersion  % Test excludeAll jacksonExclusion
  )

  val it = Seq.empty
}
