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

package uk.gov.hmrc.activemqproxy

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.activemqproxy.services.{ActiveMqService, QueueService}

class ModuleSpec extends AnyWordSpec with Matchers:

  "Module" should:
    "bind QueueService to the ActiveMQ implementation" in:
      val app = new GuiceApplicationBuilder().build()
      try app.injector.instanceOf[QueueService] shouldBe a[ActiveMqService]
      finally app.stop()
