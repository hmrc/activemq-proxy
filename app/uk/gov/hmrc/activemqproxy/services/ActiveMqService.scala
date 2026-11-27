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

import jakarta.jms.{Connection, DeliveryMode, Session}
import org.apache.activemq.ActiveMQConnectionFactory
import play.api.Logging
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.activemqproxy.config.AppConfig
import uk.gov.hmrc.activemqproxy.models.{MessageProperty, QueueIdentifier}

import java.util.concurrent.atomic.AtomicReference
import javax.inject.{Inject, Singleton}
import scala.concurrent.Future
import scala.util.control.NonFatal

@Singleton
class ActiveMqService @Inject() (
  appConfig: AppConfig,
  lifecycle: ApplicationLifecycle
)(using ec: MqExecutionContext)
    extends QueueService
    with Logging {

  // Reconnect/failover behaviour is expressed entirely in the broker URL
  // (e.g. failover:(tcp://...,tcp://...)?maxReconnectAttempts=3&...), like the mongo uri.
  private val connectionFactory: ActiveMQConnectionFactory =
    new ActiveMQConnectionFactory(appConfig.brokerUrl)

  private val connectionRef = new AtomicReference[Connection]()

  private def connection: Connection = {
    connectionRef.get() match
      case null =>
        synchronized {
          Option(connectionRef.get()).getOrElse {
            val conn = connectionFactory.createConnection()
            conn.start()
            connectionRef.set(conn)
            logger.info(s"Opened ActiveMQ connection to ${appConfig.brokerUrl}")
            conn
          }
        }
      case existing => existing
  }

  lifecycle.addStopHook { () =>
    Future.successful {
      Option(connectionRef.getAndSet(null)).foreach { conn =>
        try conn.close()
        catch case NonFatal(e) => logger.warn("Error closing ActiveMQ connection", e)
      }
    }
  }

  override def send(
    queueIdentifier: QueueIdentifier,
    payload: String,
    properties: List[MessageProperty],
    correlationId: String
  ): Future[Unit] =
    Future {
      val session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
      try
        val producer = session.createProducer(session.createQueue(queueIdentifier.queueName))
        try
          producer.setDeliveryMode(DeliveryMode.PERSISTENT)
          val message = session.createTextMessage(payload)
          message.setJMSCorrelationID(correlationId)
          properties.foreach(property => message.setStringProperty(property.key, property.value))
          producer.send(message)
          logger.info(s"Published message to ${queueIdentifier.queueName} [correlationId=$correlationId]")
        finally producer.close()
      finally session.close()
    }
}
