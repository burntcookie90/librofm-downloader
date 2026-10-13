package com.vishnurajeevan.libroabs.connector

import java.io.IOException

/** A request to the tracker failed. Other books may still go through. */
open class TrackerException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The tracker won't take requests at all right now (a bad token, or its rate limit), so there is no point going on. */
class TrackerUnavailableException(message: String, cause: Throwable? = null) : TrackerException(message, cause)
