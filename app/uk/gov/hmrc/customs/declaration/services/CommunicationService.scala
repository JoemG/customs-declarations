/*
 * Copyright 2018 HM Revenue & Customs
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

package uk.gov.hmrc.customs.declaration.services

import java.net.URLEncoder
import java.util.UUID
import javax.inject.{Inject, Singleton}

import org.joda.time.DateTime
import play.api.Configuration
import uk.gov.hmrc.customs.declaration.connectors.{ApiSubscriptionFieldsConnector, MdgWcoDeclarationConnector}
import uk.gov.hmrc.customs.declaration.logging.DeclarationsLogger
import uk.gov.hmrc.customs.declaration.model._
import uk.gov.hmrc.customs.declaration.xml.MdgPayloadDecorator
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.xml.NodeSeq

@Singleton
class CommunicationService @Inject()(logger: DeclarationsLogger,
                                     connector: MdgWcoDeclarationConnector,
                                     apiSubFieldsConnector: ApiSubscriptionFieldsConnector,
                                     wrapper: MdgPayloadDecorator,
                                     uuidService: UuidService,
                                     dateTimeProvider: DateTimeService,
                                     configuration: Configuration) {

  private val apiContextEncoded = URLEncoder.encode("customs/declarations", "UTF-8")

  lazy val futureMaybeClientIdFromConfiguration: Future[Option[String]] = {
    Future.successful(configuration.getString("override.clientID"))
  }

  def prepareAndSend(inboundXml: NodeSeq, requestedApiVersion: RequestedVersion)(implicit hc: HeaderCarrier): Future[Ids] = {
    //TODO MC generate earlier please
    val conversationId = uuidService.uuid()
    val correlationId = uuidService.uuid()
    val dateTime = dateTimeProvider.nowUtc()

    logger.info(s"Generated conversationId=$conversationId correlationId=$correlationId, dateTime=$dateTime, requestedApiVersion=$requestedApiVersion")

    for {
      fieldsId <- futureClientId(requestedApiVersion.versionNumber)
      xmlToSend = preparePayload(inboundXml, conversationId, fieldsId, dateTime)
      conversationId <- connector.send(xmlToSend, dateTime, correlationId, requestedApiVersion.configPrefix).map(_ => ConversationId(conversationId.toString))
    } yield Ids(conversationId, fieldsId)
  }

  private def futureClientId(requestedApiVersionNumber: => String)(implicit hc: HeaderCarrier): Future[FieldsId] = {
    lazy val futureMaybeFromHeaders = Future.successful(findHeaderValue("api-subscription-fields-id"))
    val foConfigOrHeader = orElse(futureMaybeClientIdFromConfiguration, futureMaybeFromHeaders)

    orElse(foConfigOrHeader, futureApiSubFieldsId(requestedApiVersionNumber)) flatMap {
      case Some(fieldsId) => Future.successful(FieldsId(fieldsId))
      case _ =>
        val msg = "No value found for clientId."
        Future.failed(new IllegalStateException(msg))
    }
  }

  private def orElse(fo1: Future[Option[String]], fo2: => Future[Option[String]]): Future[Option[String]] = {
    fo1.flatMap[Option[String]]{
      case None => fo2
      case some => Future.successful(some)
    }
  }

  private def preparePayload(xml: NodeSeq, conversationId: UUID, fieldsId: FieldsId, dateTime: DateTime)(implicit hc: HeaderCarrier): NodeSeq = {
    wrapper.wrap(xml, conversationId.toString, fieldsId.value, dateTime)
  }

  private def futureApiSubFieldsId(requestedApiVersionNumber: String)(implicit hc: HeaderCarrier): Future[Option[String]] = {
    val maybeXClientId: Option[String] = findHeaderValue("X-Client-ID")

    maybeXClientId.fold[Future[Option[String]]](Future.successful(None)) { xClientId =>
      val apiSubscriptionKey = ApiSubscriptionKey(xClientId, apiContextEncoded, requestedApiVersionNumber)
      apiSubFieldsConnector.getSubscriptionFields(apiSubscriptionKey) map (response => {
        Some(response.fieldsId.toString)
      })
    }
  }

  private def findHeaderValue(headerName: String)(implicit hc: HeaderCarrier): Option[String] = {
    hc.headers.collectFirst{
      case (`headerName`, headerValue) => headerValue
    }
  }
}
