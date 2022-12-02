package io.sentry.spring.jakarta.tracing

import io.sentry.IHub
import io.sentry.SentryOptions
import io.sentry.SentryTracer
import io.sentry.SpanId
import io.sentry.SpanStatus
import io.sentry.TraceContext
import io.sentry.TransactionContext
import io.sentry.TransactionOptions
import io.sentry.protocol.SentryId
import io.sentry.protocol.TransactionNameSource
import org.assertj.core.api.Assertions.assertThat
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.check
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.servlet.HandlerMapping
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class SentryTracingFilterTest {
    private class Fixture {
        val hub = mock<IHub>()
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()
        val chain = mock<FilterChain>()
        val transactionNameProvider = mock<TransactionNameProvider>()

        init {
            whenever(hub.options).thenReturn(
                SentryOptions().apply {
                    dsn = "https://key@sentry.io/proj"
                }
            )
        }

        fun getSut(isEnabled: Boolean = true, status: Int = 200, sentryTraceHeader: String? = null): SentryTracingFilter {
            request.requestURI = "/product/12"
            request.method = "POST"
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/product/{id}")
            whenever(transactionNameProvider.provideTransactionName(request)).thenReturn("POST /product/{id}")
            whenever(transactionNameProvider.provideTransactionSource()).thenReturn(TransactionNameSource.CUSTOM)
            if (sentryTraceHeader != null) {
                request.addHeader("sentry-trace", sentryTraceHeader)
                whenever(hub.startTransaction(any(), check<TransactionOptions> { it.isBindToScope })).thenAnswer { SentryTracer(it.arguments[0] as TransactionContext, hub) }
            }
            response.status = status
            whenever(hub.startTransaction(any(), check<TransactionOptions> { assertTrue(it.isBindToScope) })).thenAnswer { SentryTracer(it.arguments[0] as TransactionContext, hub) }
            whenever(hub.isEnabled).thenReturn(isEnabled)
            return SentryTracingFilter(hub, transactionNameProvider)
        }
    }

    private val fixture = Fixture()

    @Test
    fun `creates transaction around the request`() {
        val filter = fixture.getSut()

        filter.doFilter(fixture.request, fixture.response, fixture.chain)

        verify(fixture.hub).startTransaction(
            check<TransactionContext> {
                assertEquals("POST /product/12", it.name)
                assertEquals(TransactionNameSource.URL, it.transactionNameSource)
                assertEquals("http.server", it.operation)
            },
            check<TransactionOptions> {
                assertNotNull(it.customSamplingContext?.get("request"))
                assertTrue(it.customSamplingContext?.get("request") is HttpServletRequest)
                assertTrue(it.isBindToScope)
            }
        )
        verify(fixture.chain).doFilter(fixture.request, fixture.response)
        verify(fixture.hub).captureTransaction(
            check {
                assertThat(it.transaction).isEqualTo("POST /product/{id}")
                assertThat(it.contexts.trace!!.status).isEqualTo(SpanStatus.OK)
                assertThat(it.contexts.trace!!.operation).isEqualTo("http.server")
            },
            anyOrNull<TraceContext>(),
            anyOrNull()
        )
    }

    @Test
    fun `sets correct span status based on the response status`() {
        val filter = fixture.getSut(status = 500)

        filter.doFilter(fixture.request, fixture.response, fixture.chain)

        verify(fixture.hub).captureTransaction(
            check {
                assertThat(it.contexts.trace!!.status).isEqualTo(SpanStatus.INTERNAL_ERROR)
            },
            anyOrNull<TraceContext>(),
            anyOrNull()
        )
    }

    @Test
    fun `does not set span status for response status that dont match predefined span statuses`() {
        val filter = fixture.getSut(status = 302)

        filter.doFilter(fixture.request, fixture.response, fixture.chain)

        verify(fixture.hub).captureTransaction(
            check {
                assertThat(it.contexts.trace!!.status).isNull()
            },
            anyOrNull<TraceContext>(),
            anyOrNull()
        )
    }

    @Test
    fun `when sentry trace is not present, transaction does not have parentSpanId set`() {
        val filter = fixture.getSut()

        filter.doFilter(fixture.request, fixture.response, fixture.chain)

        verify(fixture.hub).captureTransaction(
            check {
                assertThat(it.contexts.trace!!.parentSpanId).isNull()
            },
            anyOrNull<TraceContext>(),
            anyOrNull()
        )
    }

    @Test
    fun `when sentry trace is present, transaction has parentSpanId set`() {
        val parentSpanId = SpanId()
        val filter = fixture.getSut(sentryTraceHeader = "${SentryId()}-$parentSpanId-1")

        filter.doFilter(fixture.request, fixture.response, fixture.chain)

        verify(fixture.hub).captureTransaction(
            check {
                assertThat(it.contexts.trace!!.parentSpanId).isEqualTo(parentSpanId)
            },
            anyOrNull<TraceContext>(),
            anyOrNull()
        )
    }

    @Test
    fun `when hub is disabled, components are not invoked`() {
        val filter = fixture.getSut(isEnabled = false)

        filter.doFilter(fixture.request, fixture.response, fixture.chain)

        verify(fixture.chain).doFilter(fixture.request, fixture.response)

        verify(fixture.hub).isEnabled
        verifyNoMoreInteractions(fixture.hub)
        verify(fixture.transactionNameProvider, never()).provideTransactionName(any())
    }

    @Test
    fun `sets status to internal server error when chain throws exception`() {
        val filter = fixture.getSut()
        whenever(fixture.chain.doFilter(any(), any())).thenThrow(RuntimeException("error"))

        try {
            filter.doFilter(fixture.request, fixture.response, fixture.chain)
            fail("filter is expected to rethrow exception")
        } catch (_: Exception) {
        }
        verify(fixture.hub).captureTransaction(
            check {
                assertThat(it.status).isEqualTo(SpanStatus.INTERNAL_ERROR)
            },
            anyOrNull<TraceContext>(),
            anyOrNull()
        )
    }
}