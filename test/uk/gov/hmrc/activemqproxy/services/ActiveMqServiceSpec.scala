/*
 * Copyright 2026 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.activemqproxy.services

import com.typesafe.config.ConfigFactory
import jakarta.jms.{Connection, DeliveryMode, Session, TextMessage}
import org.apache.activemq.ActiveMQConnectionFactory
import org.apache.pekko.actor.ActorSystem
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.Configuration
import play.api.inject.DefaultApplicationLifecycle
import uk.gov.hmrc.activemqproxy.config.AppConfig
import uk.gov.hmrc.activemqproxy.models.{MessageProperty, QueueIdentifier}

class ActiveMqServiceSpec extends AnyWordSpec with Matchers with ScalaFutures with IntegrationPatience with BeforeAndAfterAll:

  private val brokerUrl = "vm://amqproxy-test?broker.persistent=false&broker.useJmx=false&create=true"
  private val validCorrelationId = "0123456789abcdef0123456789abcdef"

  private val dispatcherConfig = ConfigFactory.parseString(
    """mq-dispatcher {
      |  type = Dispatcher
      |  executor = "thread-pool-executor"
      |  thread-pool-executor { fixed-pool-size = 4 }
      |  throughput = 1
      |}""".stripMargin
  )

  private given system: ActorSystem =
    ActorSystem("ActiveMqServiceSpec", dispatcherConfig.withFallback(ConfigFactory.defaultReference()))
  private given MqExecutionContext = new MqExecutionContext(system)

  private def appConfig(url: String): AppConfig =
    new AppConfig(Configuration("appName" -> "activemq-proxy", "jms.brokerUrl" -> url))

  private val lifecycle = new DefaultApplicationLifecycle()
  private val service = new ActiveMqService(appConfig(brokerUrl), lifecycle)

  private lazy val consumerConnection: Connection =
    val connection = new ActiveMQConnectionFactory(brokerUrl).createConnection()
    connection.start()
    connection

  private def receiveFrom(queue: String): TextMessage =
    val session = consumerConnection.createSession(false, Session.AUTO_ACKNOWLEDGE)
    val consumer = session.createConsumer(session.createQueue(queue))
    try consumer.receive(5000).asInstanceOf[TextMessage]
    finally session.close()

  override def afterAll(): Unit =
    consumerConnection.close()
    lifecycle.stop().futureValue // exercises the connection-close stop hook
    system.terminate()
    super.afterAll()

  "send" should:

    "publish a persistent text message carrying the payload, correlationId and properties" in:
      service
        .send(
          QueueIdentifier.AGENT_Filing_APRQ,
          "<?xml version=\"1.0\"?><agentPinRequest/>",
          List(MessageProperty("MESSAGE_CLASS", "HMRC-AGENT-APR"), MessageProperty("LOB", "Agent")),
          validCorrelationId
        )
        .futureValue

      val received = receiveFrom("AGENT_Filing_APRQ")
      received.getText                            shouldBe "<?xml version=\"1.0\"?><agentPinRequest/>"
      received.getJMSCorrelationID                shouldBe validCorrelationId
      received.getStringProperty("MESSAGE_CLASS") shouldBe "HMRC-AGENT-APR"
      received.getStringProperty("LOB")           shouldBe "Agent"
      received.getJMSDeliveryMode                 shouldBe DeliveryMode.PERSISTENT

    "publish to the queue named by the identifier with no properties" in:
      service.send(QueueIdentifier.SS_Filing_TrackingQ, "<tracking/>", Nil, validCorrelationId).futureValue

      receiveFrom("SS_Filing_TrackingQ").getText shouldBe "<tracking/>"

    "fail the returned Future when the broker cannot be reached" in:
      val brokenService =
        new ActiveMqService(appConfig("tcp://localhost:1"), new DefaultApplicationLifecycle())

      brokenService.send(QueueIdentifier.AGENT_Filing_AARQ, "x", Nil, validCorrelationId).failed.futureValue
