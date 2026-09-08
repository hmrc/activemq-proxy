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

package uk.gov.hmrc.activemqproxy.controllers.actions

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.mvc.Results.Ok
import play.api.mvc.{Request, Result}
import play.api.test.FakeRequest
import play.api.test.Helpers.*
import uk.gov.hmrc.auth.core.*
import uk.gov.hmrc.auth.core.authorise.Predicate
import uk.gov.hmrc.auth.core.retrieve.{Retrieval, ~}
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.{ExecutionContext, Future}

class DefaultAuthorisedActionSpec extends AnyWordSpec with Matchers:

  private given ExecutionContext = ExecutionContext.global
  private val cc = stubControllerComponents()

  private val block: Request[?] => Future[Result] = _ => Future.successful(Ok("published"))

  private def mgdAgentEnrolments(activated: Boolean = true, withIdentifier: Boolean = true): Enrolments =
    val identifiers = if withIdentifier then Seq(EnrolmentIdentifier("HMRCMGDAGENTREF", "agent-ref-123")) else Seq.empty
    Enrolments(Set(Enrolment("HMRC-MGD-AGNT", identifiers, if activated then "Activated" else "NotYetActivated")))

  /** Returns whatever `result` is configured, standing in for the auth service call. */
  private class FakeAuthConnector(result: Future[Any]) extends AuthConnector:
    override def authorise[A](predicate: Predicate, retrieval: Retrieval[A])(implicit
      hc: HeaderCarrier,
      ec: ExecutionContext
    ): Future[A] =
      result.map(_.asInstanceOf[A])

  private def invoke(authResult: Future[Any]): Future[Result] =
    new DefaultAuthorisedAction(new FakeAuthConnector(authResult), cc).invokeBlock(FakeRequest(), block)

  private def retrieved(affinity: Option[AffinityGroup], enrolments: Enrolments): Future[Any] =
    Future.successful(new ~(affinity, enrolments))

  "DefaultAuthorisedAction" should:

    "allow an Agent with an active HMRC-MGD-AGNT enrolment" in:
      val result = invoke(retrieved(Some(AffinityGroup.Agent), mgdAgentEnrolments()))
      status(result)          shouldBe OK
      contentAsString(result) shouldBe "published"

    "return 403 for an Agent without the HMRC-MGD-AGNT enrolment" in:
      val result = invoke(retrieved(Some(AffinityGroup.Agent), Enrolments(Set.empty)))
      status(result) shouldBe FORBIDDEN

    "return 403 for an Agent whose HMRC-MGD-AGNT enrolment is not activated" in:
      val result = invoke(retrieved(Some(AffinityGroup.Agent), mgdAgentEnrolments(activated = false)))
      status(result) shouldBe FORBIDDEN

    "return 403 for an Agent whose enrolment has no HMRCMGDAGENTREF identifier" in:
      val result = invoke(retrieved(Some(AffinityGroup.Agent), mgdAgentEnrolments(withIdentifier = false)))
      status(result) shouldBe FORBIDDEN

    "return 403 for a non-Agent affinity group" in:
      val result = invoke(retrieved(Some(AffinityGroup.Organisation), mgdAgentEnrolments()))
      status(result) shouldBe FORBIDDEN

    "return 403 when there is no affinity group" in:
      val result = invoke(retrieved(None, mgdAgentEnrolments()))
      status(result) shouldBe FORBIDDEN

    "return 401 when there is no active session (not logged in)" in:
      val result = invoke(Future.failed(MissingBearerToken()))
      status(result) shouldBe UNAUTHORIZED

    "return 403 for other authorisation failures" in:
      val result = invoke(Future.failed(InsufficientEnrolments()))
      status(result) shouldBe FORBIDDEN
