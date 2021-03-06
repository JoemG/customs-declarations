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

package acceptance

import com.github.tomakehurst.wiremock.client.WireMock.{postRequestedFor, urlEqualTo, verify}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Matchers, OptionValues}
import play.api.mvc._
import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.customs.declaration.model.ApiSubscriptionKey
import util.TestData._
import util.externalservices.{ApiSubscriptionFieldsService, AuthService, MdgWcoDecService}
import util.{RequestHeaders, CustomsDeclarationsExternalServicesConfig}

import scala.concurrent.Future

class CustomsDeclarationsSpec extends AcceptanceTestSpec
  with Matchers
  with OptionValues
  with BeforeAndAfterAll
  with BeforeAndAfterEach
  with MdgWcoDecService
  with ApiSubscriptionFieldsService
  with AuthService {

  private val endpoint = "/"

  private val apiSubscriptionKeyForXClientIdV1 =
    ApiSubscriptionKey(clientId = xClientId, context = "customs%2Fdeclarations", version = "1.0")

  private val apiSubscriptionKeyForXClientIdV2 = apiSubscriptionKeyForXClientIdV1.copy(version = "2.0")

  private val UnauthorisedError =
    """<?xml version="1.0" encoding="UTF-8"?>
      |<errorResponse>
      |  <code>UNAUTHORIZED</code>
      |  <message>Unauthorised request</message>
      |</errorResponse>
    """.stripMargin

  override protected def beforeAll() {
    startMockServer()
  }

  override protected def beforeEach() {
    resetMockServer()
  }

  override protected def afterAll() {
    stopMockServer()
  }

  feature("Submissions with v1.0 accept header") {
    scenario("An authorised CSP successfully submits a customs declaration") {
      Given("A CSP wants to submit a valid customs declaration")
      startMdgWcoDecService()
      val request: FakeRequest[AnyContentAsXml] = ValidRequestWithV1AcceptHeader.fromCsp.postTo(endpoint)

      And("the CSP is authorised with its privileged application")
      authServiceAuthorizesCSP()

      When("a POST request with data is sent to the API")
      val result: Future[Result] = route(app = app, request).value

      Then("a response with a 204 (NO_CONTENT) status is received")
      status(result) shouldBe NO_CONTENT

      And("the response body is empty")
      contentAsString(result) shouldBe 'empty

      And("the request was authorised with AuthService")
      verifyAuthServiceCalledForCsp()

      And("v1 config was used")
      verify(1, postRequestedFor(urlEqualTo(CustomsDeclarationsExternalServicesConfig.MdgWcoDecV1ServiceContext)))

    }
  }

  feature("Declaration API authorises submissions from CSPs and Software Houses with v2.0 accept header") {
    scenario("An authorised CSP successfully submits a customs declaration") {
      Given("A CSP wants to submit a valid customs declaration")
      startMdgWcoDecService()
      val request: FakeRequest[AnyContentAsXml] = ValidRequest.fromCsp.postTo(endpoint)

      And("the CSP is authorised with its privileged application")
      authServiceAuthorizesCSP()

      When("a POST request with data is sent to the API")
      val result: Future[Result] = route(app = app, request).value

      Then("a response with a 204 (NO_CONTENT) status is received")
      status(result) shouldBe NO_CONTENT

      And("the response body is empty")
      contentAsString(result) shouldBe 'empty

      And("the request was authorised with AuthService")
      verifyAuthServiceCalledForCsp()

      And("v2 config was used")
      verify(1, postRequestedFor(urlEqualTo(CustomsDeclarationsExternalServicesConfig.MdgWcoDecV2ServiceContext)))
    }

    scenario("An unauthorised CSP is not allowed to submit a customs declaration") {
      Given("A CSP wants to submit a valid customs declaration")
      val request: FakeRequest[AnyContentAsXml] = ValidRequest.fromCsp.postTo(endpoint)

      And("the CSP is unauthorised with its privileged application")
      authServiceUnauthorisesScopeForCSP()
      authServiceUnauthorisesCustomsEnrolmentForNonCSP(cspBearerToken)

      When("a POST request with data is sent to the API")
      val result: Future[Result] = route(app = app, request).value

      Then("a response with a 401 (UNAUTHORIZED) status is received")
      status(result) shouldBe UNAUTHORIZED

      And("the response body is empty")
      string2xml(contentAsString(result)) shouldBe string2xml(UnauthorisedError)

      And("the request was authorised with AuthService")
      verifyAuthServiceCalledForCsp()
    }

    scenario("A non-CSP successfully submits a declaration on behalf of somebody with Customs enrolment") {
      Given("A Software House wants to submit a valid customs declaration")
      startMdgWcoDecService()
      val request: FakeRequest[AnyContentAsXml] = ValidRequest.fromNonCsp.postTo(endpoint)

      And("declarant is enrolled with Customs having an EORI number")
      authServiceUnauthorisesScopeForCSP(nonCspBearerToken)
      authServiceAuthorizesNonCspWithEori()

      When("a POST request with data is sent to the API")
      val result: Future[Result] = route(app = app, request).value

      Then("a response with a 204 (NO_CONTENT) status is received")
      status(result) shouldBe NO_CONTENT

      And("the response body is empty")
      contentAsString(result) shouldBe 'empty

      And("the request was authorised with AuthService")
      verifyAuthServiceCalledForNonCsp()
    }

    scenario("A non-CSP is not authorised to submit a declaration on behalf of somebody without Customs enrolment") {
      Given("A Software House wants to submit a valid customs declaration")
      startMdgWcoDecService()
      val request: FakeRequest[AnyContentAsXml] = ValidRequest.fromNonCsp.postTo(endpoint)

      And("declarant is not enrolled with Customs")
      authServiceUnauthorisesScopeForCSP(nonCspBearerToken)
      authServiceUnauthorisesCustomsEnrolmentForNonCSP()

      When("a POST request with data is sent to the API")
      val result: Future[Result] = route(app = app, request).value

      Then("a response with a 401 (UNAUTHORIZED) status is received")
      status(result) shouldBe UNAUTHORIZED

      And("the response body is empty")
      string2xml(contentAsString(result)) shouldBe string2xml(UnauthorisedError)

      And("the request was authorised with AuthService")
      verifyAuthServiceCalledForNonCsp()
    }
  }

  feature("When fields id header is absent, declaration API uses X-Client-ID header to retrieve fields id from api-subscription-fields service") {

    scenario("An authorised CSP successfully submits a customs declaration having X-Client-ID request header to v1 api") {
      Given("A CSP wants to submit a valid customs declaration and API Gateway provides X-Client-ID header only")
      startMdgWcoDecService()
      startApiSubscriptionFieldsService(apiSubscriptionKeyForXClientIdV1)
      val request: FakeRequest[AnyContentAsXml] =
        ValidRequestWithXClientIdHeader.withHeaders(RequestHeaders.ACCEPT_HMRC_XML_V1_HEADER).fromCsp.postTo(endpoint)

      And("the CSP is authorised with its privileged application")
      authServiceAuthorizesCSP()

      When("a POST request with data is sent to the API")
      val result: Future[Result] = route(app = app, request).value

      Then("a response with a 204 (NO_CONTENT) status is received")
      status(result) shouldBe NO_CONTENT

      And("the response body is empty")
      contentAsString(result) shouldBe 'empty

      And("the request was authorised with AuthService")
      verifyAuthServiceCalledForCsp()

      And("the api-subscription-fields service was called with value of X-Client-ID header and api version 1.0")
      verifyGetSubscriptionFieldsCalled(apiSubscriptionKeyForXClientIdV1)
      verifyGetSubscriptionFieldsNotCalled(apiSubscriptionKeyForXClientIdV2)
    }

    scenario("An authorised CSP successfully submits a customs declaration having X-Client-ID request header to v2 api") {
      Given("A CSP wants to submit a valid customs declaration and API Gateway provides X-Client-ID header only")
      startMdgWcoDecService()
      startApiSubscriptionFieldsService(apiSubscriptionKeyForXClientIdV2)
      val request: FakeRequest[AnyContentAsXml] = ValidRequestWithXClientIdHeader.fromCsp.postTo(endpoint)

      And("the CSP is authorised with its privileged application")
      authServiceAuthorizesCSP()

      When("a POST request with data is sent to the API")
      val result: Future[Result] = route(app = app, request).value

      Then("a response with a 204 (NO_CONTENT) status is received")
      status(result) shouldBe NO_CONTENT

      And("the response body is empty")
      contentAsString(result) shouldBe 'empty

      And("the request was authorised with AuthService")
      verifyAuthServiceCalledForCsp()

      And("the api-subscription-fields service was called with value of X-Client-ID header and api version 2.0")
      verifyGetSubscriptionFieldsNotCalled(apiSubscriptionKeyForXClientIdV1)
      verifyGetSubscriptionFieldsCalled(apiSubsKey = apiSubscriptionKeyForXClientIdV2)
    }

    scenario("A non-CSP successfully submits a declaration on behalf of somebody with Customs enrolment having X-Client-ID request header to v1 api") {
      Given("A Software House wants to submit a valid customs declaration and API Gateway provides X-Client-ID header only")
      startMdgWcoDecService()
      startApiSubscriptionFieldsService(apiSubscriptionKeyForXClientIdV1)
      val request: FakeRequest[AnyContentAsXml] =
        ValidRequestWithXClientIdHeader.withHeaders(RequestHeaders.ACCEPT_HMRC_XML_V1_HEADER).fromNonCsp.postTo(endpoint)

      And("declarant is enrolled with Customs having an EORI number")
      authServiceUnauthorisesScopeForCSP(nonCspBearerToken)
      authServiceAuthorizesNonCspWithEori()

      When("a POST request with data is sent to the API")
      val result: Future[Result] = route(app = app, request).value

      Then("a response with a 204 (NO_CONTENT) status is received")
      status(result) shouldBe NO_CONTENT

      And("the response body is empty")
      contentAsString(result) shouldBe 'empty

      And("the request was authorised with AuthService")
      verifyAuthServiceCalledForNonCsp()

      And("the api-subscription-fields service was called with value of X-Client-ID header and api version 1.0")
      verifyGetSubscriptionFieldsCalled(apiSubscriptionKeyForXClientIdV1)
      verifyGetSubscriptionFieldsNotCalled(apiSubsKey = apiSubscriptionKeyForXClientIdV2)
    }

    scenario("A non-CSP successfully submits a declaration on behalf of somebody with Customs enrolment having X-Client-ID request header to v2 api") {
      Given("A Software House wants to submit a valid customs declaration and API Gateway provides X-Client-ID header only")
      startMdgWcoDecService()
      startApiSubscriptionFieldsService(apiSubscriptionKeyForXClientIdV2)
      val request: FakeRequest[AnyContentAsXml] = ValidRequestWithXClientIdHeader.fromNonCsp.postTo(endpoint)

      And("declarant is enrolled with Customs having an EORI number")
      authServiceUnauthorisesScopeForCSP(nonCspBearerToken)
      authServiceAuthorizesNonCspWithEori()

      When("a POST request with data is sent to the API")
      val result: Future[Result] = route(app = app, request).value

      Then("a response with a 204 (NO_CONTENT) status is received")
      status(result) shouldBe NO_CONTENT

      And("the response body is empty")
      contentAsString(result) shouldBe 'empty

      And("the request was authorised with AuthService")
      verifyAuthServiceCalledForNonCsp()

      And("the api-subscription-fields service was called with value of X-Client-ID header and api version 2.0")
      verifyGetSubscriptionFieldsNotCalled(apiSubscriptionKeyForXClientIdV1)
      verifyGetSubscriptionFieldsCalled(apiSubsKey = apiSubscriptionKeyForXClientIdV2)
    }
  }

}
