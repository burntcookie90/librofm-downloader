package com.vishnurajeevan.libroabs.server.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoginThrottleTest {
  private var now = 0L
  private val throttle = LoginThrottle(maxFailures = 3, windowMillis = 1_000, clock = { now })

  @Test
  fun locksOutAfterRepeatedFailuresEvenForTheRightPassword() {
    repeat(3) { assertFalse(throttle.attempt("a") { false }) }
    assertTrue(throttle.isLocked("a"))
    assertFalse(throttle.attempt("a") { true })
    // other clients are unaffected
    assertTrue(throttle.attempt("b") { true })
  }

  @Test
  fun lockoutExpiresWithTheWindow() {
    repeat(3) { throttle.attempt("a") { false } }
    now += 1_001
    assertFalse(throttle.isLocked("a"))
    assertTrue(throttle.attempt("a") { true })
  }

  @Test
  fun successClearsEarlierFailures() {
    repeat(2) { throttle.attempt("a") { false } }
    assertTrue(throttle.attempt("a") { true })
    repeat(2) { throttle.attempt("a") { false } }
    assertFalse(throttle.isLocked("a"))
  }
}
