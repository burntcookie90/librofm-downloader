package com.vishnurajeevan.libroabs.models.graph

import dev.zacsweers.metro.Qualifier

@Qualifier
@Target(
  AnnotationTarget.PROPERTY_GETTER,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.VALUE_PARAMETER,
  AnnotationTarget.TYPE
)
annotation class Named(val value: String)