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

package uk.gov.hmrc.activemqproxy.models

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.*

class ModelsSpec extends AnyWordSpec with Matchers:

  "QueueIdentifier" should:

    "read every permitted queue from its JSON string" in:
      QueueIdentifier.values.foreach: queue =>
        JsString(queue.toString).validate[QueueIdentifier] shouldBe JsSuccess(queue)

    "expose the physical queue name via queueName" in:
      QueueIdentifier.AGENT_Filing_APRQ.queueName shouldBe "AGENT_Filing_APRQ"

    "resolve a known value via fromString and reject an unknown one" in:
      QueueIdentifier.fromString("SS_Filing_TrackingQ") shouldBe Some(QueueIdentifier.SS_Filing_TrackingQ)
      QueueIdentifier.fromString("NOPE")                shouldBe None

    "reject an unrecognised queueIdentifier with a helpful error" in:
      val result = JsString("NOT_A_QUEUE").validate[QueueIdentifier]
      result                                              shouldBe a[JsError]
      JsError.toJson(result.asInstanceOf[JsError]).toString should include("not a recognised queueIdentifier")

    "reject a non-string queueIdentifier" in:
      JsNumber(123).validate[QueueIdentifier]                                            shouldBe a[JsError]
      JsError.toJson(JsNumber(1).validate[QueueIdentifier].asInstanceOf[JsError]).toString should include("must be a string")

    "write to a JSON string" in:
      Json.toJson(QueueIdentifier.AGENT_Filing_AARQ) shouldBe JsString("AGENT_Filing_AARQ")

  "MessageProperty" should:
    "round-trip through JSON" in:
      val property = MessageProperty("MESSAGE_CLASS", "HMRC-AGENT-APR")
      val json = Json.toJson(property)
      json                           shouldBe Json.obj("key" -> "MESSAGE_CLASS", "value" -> "HMRC-AGENT-APR")
      json.validate[MessageProperty] shouldBe JsSuccess(property)

  "SendMessageRequest" should:

    "read a full request" in:
      val json = Json.obj(
        "queueIdentifier" -> "AGENT_Filing_APRQ",
        "payload"         -> "<xml/>",
        "properties"      -> Json.arr(Json.obj("key" -> "LOB", "value" -> "Agent")),
        "correlationId"   -> "54947df80e9e4471a2f99af509fb5889"
      )
      json.validate[SendMessageRequest] shouldBe JsSuccess(
        SendMessageRequest(
          QueueIdentifier.AGENT_Filing_APRQ,
          "<xml/>",
          Some(List(MessageProperty("LOB", "Agent"))),
          Some("54947df80e9e4471a2f99af509fb5889")
        )
      )

    "read a minimal request and default properties to empty" in:
      val json = Json.obj("queueIdentifier" -> "SS_Filing_TrackingQ", "payload" -> "x")
      val request = json.validate[SendMessageRequest].get
      request.properties        shouldBe None
      request.correlationId     shouldBe None
      request.propertiesOrEmpty shouldBe Nil

    "reject a request missing the payload" in:
      Json.obj("queueIdentifier" -> "SS_Filing_TrackingQ").validate[SendMessageRequest] shouldBe a[JsError]

  "SendMessageResponse" should:
    "write the correlationId" in:
      Json.toJson(SendMessageResponse("abc")) shouldBe Json.obj("correlationId" -> "abc")
