package io.opencensus.scala.akka.http

import akka.http.scaladsl.model.{HttpHeader, HttpRequest, StatusCodes}
import akka.http.scaladsl.server.{Directives, Route, StandardRoute}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import io.opencensus.scala.Tracing
import io.opencensus.scala.akka.http.AkkaMockPropagation._
import io.opencensus.scala.http.ServiceData
import io.opencensus.scala.http.propagation.Propagation
import io.opencensus.scala.http.testSuite.MockTracing
import io.opencensus.trace.{AttributeValue, Status}
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

trait ServiceRequirementsSpec
    extends AnyFlatSpec
    with Matchers
    with ScalatestRouteTest
    with OptionValues {

  type RouteResult = () => StandardRoute

  def tracedService(
      routeFromTracingDirectiveAndResult: TracingDirective => RouteResult => Route,
      serviceData: Option[ServiceData]
  ): Unit = {

    val path = "/my/fancy/path"

    it should "start a span with the path of the request" in {
      val (directive, mockTracing) = directiveWithMock()

      Get(path) ~> routeFromTracingDirectiveAndResult(directive)(() =>
        Directives.complete("")
      ) ~> check {
        mockTracing.startedSpans.map(_.name) should contain(path)
      }
    }

    it should "start a span without parent context when no span context was propagated" in {
      val (directive, mockTracing) = directiveWithMock()

      Get(requestPathWithoutParent) ~> routeFromTracingDirectiveAndResult(
        directive
      )(() => Directives.complete("")) ~> check {
        val parentSpanContext =
          mockTracing.startedSpans.headOption.value.parentContext
        parentSpanContext shouldBe empty
      }
    }

    it should "start a span with the propagated context as parent when a span context was propagated" in {
      val (directive, mockTracing) = directiveWithMock()

      Get(path) ~> routeFromTracingDirectiveAndResult(directive)(() =>
        Directives.complete("")
      ) ~> check {
        val parentSpanContext =
          mockTracing.startedSpans.headOption.value.parentContext.value

        parentSpanContext.getTraceId.toLowerBase16 shouldBe fakeTraceId
        parentSpanContext.getSpanId.toLowerBase16 shouldBe fakeSpanId
      }

    }

    it should "end a span with status OK when the route is successfull" in {
      val (directive, mockTracing) = directiveWithMock()

      Get(path) ~> routeFromTracingDirectiveAndResult(directive)(() =>
        Directives.complete("")
      ) ~> check {
        responseEntity.discardBytes() // drain entity so the span gets closed
        mockTracing.endedSpans.map(_._2.get) should contain(Status.OK)
      }
    }

    it should "end a span with status UNKNOWN when the route completes with an errornous status code" in {
      val (directive, mockTracing) = directiveWithMock()

      Get(path) ~> routeFromTracingDirectiveAndResult(directive)(() =>
        Directives.complete(StatusCodes.InternalServerError)
      ) ~> check {
        responseEntity.discardBytes() // drain entity so the span gets closed
        mockTracing.endedSpans.map(_._2.get) should contain(Status.UNKNOWN)
      }
    }

    it should "end a span with status INTERNAL when the route fails" in {
      val (directive, mockTracing) = directiveWithMock()

      Get(path) ~> routeFromTracingDirectiveAndResult(directive)(() =>
        throw new Exception("test exception")
      ) ~> check {
        mockTracing.endedSpans.map(_._2.get) should contain(Status.INTERNAL)
      }
    }

    it should "set the http attributes" in {
      import AttributeValue._
      val (directive, mockTracing) = directiveWithMock()

      Get(path) ~> routeFromTracingDirectiveAndResult(directive)(() =>
        Directives.complete("")
      ) ~> check {
        val startedSpan = mockTracing.startedSpans.headOption.value
        val attributes  = startedSpan.attributes

        attributes.get("http.host").value shouldBe stringAttributeValue(
          "example.com"
        )
        attributes.get("http.path").value shouldBe stringAttributeValue(
          "/my/fancy/path"
        )
        attributes.get("http.method").value shouldBe stringAttributeValue("GET")
        attributes.get("http.status_code").value shouldBe longAttributeValue(
          200L
        )
      }
    }

    it should "set the service attributes when given" in {
      import AttributeValue._
      val (directive, mockTracing) = directiveWithMock()

      Get(path) ~> routeFromTracingDirectiveAndResult(directive)(() =>
        Directives.complete("")
      ) ~> check {
        val startedSpan = mockTracing.startedSpans.headOption.value
        val attributes  = startedSpan.attributes

        serviceData match {
          case Some(ServiceData(Some(name), Some(version))) =>
            attributes.get("service.name").value shouldBe stringAttributeValue(
              name
            )
            attributes
              .get("service.version")
              .value shouldBe stringAttributeValue(
              version
            )
          case _ => succeed
        }
      }
    }
  }

  def directiveWithMock() = {
    val mockTracing = new MockTracing
    val directive = new TracingDirective {
      override protected def tracing: Tracing = mockTracing
      override protected def propagation: Propagation[HttpHeader, HttpRequest] =
        AkkaMockPropagation
    }

    (directive, mockTracing)
  }
}
