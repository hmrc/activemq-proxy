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

package uk.gov.hmrc.activemqproxy.controllers

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.http.Status
import play.api.libs.json.{JsValue, Json}
import play.api.test.Helpers.*
import play.api.test.{FakeRequest, Helpers}
import uk.gov.hmrc.activemqproxy.models.{MessageProperty, QueueIdentifier}
import uk.gov.hmrc.activemqproxy.services.QueueService

import scala.concurrent.{ExecutionContext, Future}

class QueueControllerSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll:

  private given ExecutionContext = ExecutionContext.global
  private given system: ActorSystem = ActorSystem("QueueControllerSpec")
  private given Materializer = Materializer(system)

  override def afterAll(): Unit =
    system.terminate()
    super.afterAll()

  /** Records the last call and returns a configurable result. */
  private class FakeQueueService(result: Future[Unit] = Future.unit) extends QueueService:
    var calls: List[(QueueIdentifier, String, List[MessageProperty], String)] = Nil
    override def send(
      queueIdentifier: QueueIdentifier,
      payload: String,
      properties: List[MessageProperty],
      correlationId: String
    ): Future[Unit] =
      calls = calls :+ (queueIdentifier, payload, properties, correlationId)
      result

  private def controllerWith(service: QueueService): QueueController =
    new QueueController(Helpers.stubControllerComponents(), service)

  private def postJson(body: JsValue) =
    FakeRequest(POST, "/queue/send").withHeaders(CONTENT_TYPE -> JSON).withBody(body)

  private val fullRequest: JsValue = Json.obj(
    "queueIdentifier" -> "AGENT_Filing_APRQ",
    "payload"         -> "<?xml version=\"1.0\"?><root/>",
    "properties" -> Json.arr(
      Json.obj("key" -> "MESSAGE_CLASS", "value" -> "HMRC-AGENT-APR"),
      Json.obj("key" -> "LOB", "value"           -> "Agent")
    ),
    "correlationId" -> "54947df80e9e4471a2f99af509fb5889"
  )

  "POST /queue/send" should:

    "publish the message and echo the supplied correlationId" in:
      val service = FakeQueueService()
      val result = call(controllerWith(service).send, postJson(fullRequest))

      status(result)                                       shouldBe Status.OK
      (contentAsJson(result) \ "correlationId").as[String] shouldBe "54947df80e9e4471a2f99af509fb5889"

      service.calls should have size 1
      service.calls.head shouldBe (
        QueueIdentifier.AGENT_Filing_APRQ,
        "<?xml version=\"1.0\"?><root/>",
        List(MessageProperty("MESSAGE_CLASS", "HMRC-AGENT-APR"), MessageProperty("LOB", "Agent")),
        "54947df80e9e4471a2f99af509fb5889"
      )

    "generate a 32-character correlationId when none is supplied" in:
      val service = FakeQueueService()
      val body = fullRequest.as[play.api.libs.json.JsObject] - "correlationId"
      val result = call(controllerWith(service).send, postJson(body))

      status(result) shouldBe Status.OK
      val generated = (contentAsJson(result) \ "correlationId").as[String]
      generated.length      shouldBe 32
      generated               should fullyMatch regex "[0-9a-f]{32}"
      service.calls.head._4 shouldBe generated

    "default properties to empty when omitted" in:
      val service = FakeQueueService()
      val body = fullRequest.as[play.api.libs.json.JsObject] - "properties"
      val result = call(controllerWith(service).send, postJson(body))

      status(result)        shouldBe Status.OK
      service.calls.head._3 shouldBe Nil

    "reject an unknown queueIdentifier with 400 and not publish" in:
      val service = FakeQueueService()
      val body = fullRequest.as[play.api.libs.json.JsObject] ++ Json.obj("queueIdentifier" -> "NOT_A_QUEUE")
      val result = call(controllerWith(service).send, postJson(body))

      status(result) shouldBe Status.BAD_REQUEST
      service.calls  shouldBe empty

    "reject a request with a missing payload with 400" in:
      val service = FakeQueueService()
      val body = fullRequest.as[play.api.libs.json.JsObject] - "payload"
      val result = call(controllerWith(service).send, postJson(body))

      status(result) shouldBe Status.BAD_REQUEST
      service.calls  shouldBe empty

    "reject a correlationId that is not exactly 32 characters with 400" in:
      val service = FakeQueueService()
      val body = fullRequest.as[play.api.libs.json.JsObject] ++ Json.obj("correlationId" -> "too-short")
      val result = call(controllerWith(service).send, postJson(body))

      status(result)                               shouldBe Status.BAD_REQUEST
      (contentAsJson(result) \ "message").as[String] should include("32 characters")
      service.calls                                shouldBe empty

    "return 500 when publishing to the queue fails" in:
      val service = FakeQueueService(Future.failed(new RuntimeException("broker down")))
      val result = call(controllerWith(service).send, postJson(fullRequest))

      status(result) shouldBe Status.INTERNAL_SERVER_ERROR
