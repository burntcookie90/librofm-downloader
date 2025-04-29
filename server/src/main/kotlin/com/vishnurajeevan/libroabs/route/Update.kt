package com.vishnurajeevan.libroabs.route

import io.ktor.resources.Resource

@Resource("/update")
class Update(val overwrite: Boolean? = false)