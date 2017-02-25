package org.openpolicyagent.linkerd

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle._
import com.twitter.finagle.http.{HeaderMap, Request, Response}
import com.twitter.io.Buf
import com.twitter.io.Buf.Utf8
import com.twitter.util.Future
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.linkerd.protocol.HttpIdentifierConfig
import io.buoyant.router.RoutingFactory
import io.buoyant.router.RoutingFactory.{Identifier, RequestIdentification, UnidentifiedRequest}
import io.buoyant.router.http.MethodAndHostIdentifier
import org.json4s.DefaultFormats
import org.json4s.jackson.{Serialization, parseJson}

case class AuthzIdentifier(
  prefix: Path,
  baseDtab: () => Dtab = () => Dtab.base
) extends RoutingFactory.Identifier[Request] {

  import Helpers._

  def apply(req: Request): Future[RequestIdentification[Request]] = {

    // TODO(tsandall): support other built-in identifiers through config/reflection
    val identifier = MethodAndHostIdentifier.mk(prefix, baseDtab)

    // Execute normal identifier.
    identifier(req).flatMap { id =>

      // TODO(tsandall): turn into configuration
      val opaNetloc = "localhost:8181"
      val opaDocument = "/v1/data/linkerd_experiment/allow"

      // Prepare OPA query
      val client = Http.newService(opaNetloc)
      val request = http.Request(http.Method.Post, opaDocument)

      request.host = opaNetloc  // Finagle was not setting host automatically.
      request.setContentTypeJson()

      val pair = mapHeadersToSourceIPHostPair(req.headerMap) match {
        case Some((s, h)) => (Some(s), Some(h))
        case None => (None, None)
      }

      request.content = mapRequestToBuf(OPARequest(Input(req.method.toString(), req.path, req.headerMap, pair._1.map(Identity), pair._2)))

      // Execute OPA query
      client(request).map { response =>
        if (mapResponseToBoolean(response)) {
          id
        } else {
          new UnidentifiedRequest[Request]("request rejected by administrative policy")
        }
      }
    }
  }
}

class AuthzIdentifierConfig extends HttpIdentifierConfig{

  @JsonIgnore
  override def newIdentifier(prefix: Path, baseDtab: () => Dtab): Identifier[Request] = {
    new AuthzIdentifier(prefix, baseDtab)
  }
}


class AuthzIdentifierInitializer extends IdentifierInitializer {

  override def configId: String = "org.openpolicyagent.linkerd.authzIdentifier"

  override def configClass: Class[_] = return classOf[AuthzIdentifierConfig]
}


// OPA request contains an input document.
case class OPARequest(input: Input)

case class Identity(source_ip: String)

// In this case, policy can be written over incoming HTTP requests.
case class Input(
                  method: String,
                  path: String,
                  headers: HeaderMap,
                  identity: Option[Identity],
                  host: Option[String]
                )

// In this case, policy produces a boolean value indicating if request should be allowed.
case class OPAResponse(result: Option[Boolean])

private[linkerd] object Helpers {

  implicit val f = DefaultFormats

  def mapHeadersToSourceIPHostPair(headers: HeaderMap): Option[(String, String)] = {
    headers.get("Forwarded").flatMap({ s =>
      val parts = s.split(';')
      val items = parts.flatMap({ t =>
        val pair = t.split('=').toList match {
          case k :: v :: Nil => Some((k, v))
          case _ => None
        }
        pair match {
          case Some((k, v)) => List((k,v))
          case None => List.empty[(String, String)]
        }
      }).toMap
      (items.get("for"), items.get("host")) match {
        case (Some(sourceIP), Some(host)) => Some((sourceIP, host))
        case _ => None
      }
    })
  }

  def mapRequestToBuf(request: OPARequest): Buf = {
    Utf8(Serialization.write(request))
  }

  def mapResponseToBoolean(response: Response): Boolean = {
    if (response.getStatusCode() == http.Status.Ok.code) {
      val parsed = parseJson(response.getReader())
      parsed.extract[OPAResponse].result.getOrElse(true)
    } else {
      false
    }
  }

}
