package org.http4s.servlet

import javax.servlet.http.HttpServlet

import org.http4s.server.{AsyncTimeoutSupport, ServerBuilder}

trait ServletContainer
  extends ServerBuilder
  with AsyncTimeoutSupport
{
  type Self <: ServletContainer

  def mountServlet(servlet: HttpServlet, urlMapping: String, name: Option[String] = None): Self
}

