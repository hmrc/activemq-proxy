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

import com.google.inject.ImplementedBy
import play.api.Logging
import play.api.http.Status.{FORBIDDEN, UNAUTHORIZED}
import play.api.libs.json.{JsObject, Json}
import play.api.mvc.*
import play.api.mvc.Results.{Forbidden, Unauthorized}
import uk.gov.hmrc.auth.core.*
import uk.gov.hmrc.auth.core.retrieve.v2.Retrievals
import uk.gov.hmrc.auth.core.retrieve.~
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.http.HeaderCarrierConverter

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@ImplementedBy(classOf[DefaultAuthorisedAction])
trait AuthorisedAction extends ActionBuilder[Request, AnyContent]

@Singleton
class DefaultAuthorisedAction @Inject() (
  override val authConnector: AuthConnector,
  cc: ControllerComponents
)(using val executionContext: ExecutionContext)
    extends AuthorisedAction
    with AuthorisedFunctions
    with Logging:

  private val AgentEnrolmentKey = "HMRC-MGD-AGNT"
  private val AgentIdentifierKey = "HMRCMGDAGENTREF"

  override val parser: BodyParser[AnyContent] = cc.parsers.defaultBodyParser

  override def invokeBlock[A](request: Request[A], block: Request[A] => Future[Result]): Future[Result] =
    given HeaderCarrier = HeaderCarrierConverter.fromRequest(request)

    authorised()
      .retrieve(Retrievals.affinityGroup and Retrievals.allEnrolments) {
        case Some(AffinityGroup.Agent) ~ enrolments =>
          if hasAgentEnrolment(enrolments) then block(request)
          else {
            logger.warn(s"Access denied: Agent is missing an active $AgentEnrolmentKey enrolment")
            Future.successful(Forbidden(errorJson(FORBIDDEN, s"Agent is not enrolled for $AgentEnrolmentKey")))
          }
        case affinityGroup ~ _ =>
          logger.warn(s"Access denied: affinity group '${affinityGroup.getOrElse("none")}' is not Agent")
          Future.successful(Forbidden(errorJson(FORBIDDEN, "User must be logged in with an Agent affinity group")))
      }
      .recover {
        case _: NoActiveSession =>
          logger.warn("Access denied: no active session - user is not logged in")
          Unauthorized(errorJson(UNAUTHORIZED, "User is not logged in"))
        case e: AuthorisationException =>
          logger.warn(s"Access denied: ${e.reason}")
          Forbidden(errorJson(FORBIDDEN, "User is not authorised"))
      }

  private def hasAgentEnrolment(enrolments: Enrolments): Boolean =
    enrolments
      .getEnrolment(AgentEnrolmentKey)
      .filter(_.isActivated)
      .flatMap(_.getIdentifier(AgentIdentifierKey))
      .isDefined

  private def errorJson(statusCode: Int, message: String): JsObject =
    Json.obj("statusCode" -> statusCode, "message" -> message)
