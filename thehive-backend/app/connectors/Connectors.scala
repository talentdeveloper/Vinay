package connectors

import javax.inject.Inject

import com.google.inject.AbstractModule
import net.codingwell.scalaguice.{ ScalaModule, ScalaMultibinder }
import play.api.libs.json.{ JsObject, Json }
import play.api.mvc.{ Action, Handler, RequestHeader, Results }
import play.api.routing.sird.UrlContext
import play.api.routing.{ Router, SimpleRouter }

import scala.collection.immutable

trait Connector {
  val name: String
  val router: Router
  val status: JsObject = Json.obj("enabled" → true)
}

class ConnectorRouter @Inject() (connectors: immutable.Set[Connector]) extends SimpleRouter {
  def get(connectorName: String): Option[Connector] = connectors.find(_.name == connectorName)

  def routes: PartialFunction[RequestHeader, Handler] = {
    case request @ p"/$connector/$path<.*>" ⇒
      get(connector)
        .flatMap(_.router.withPrefix(s"/$connector/").handlerFor(request))
        .getOrElse(Action { _ ⇒ Results.NotFound(s"connector $connector not found") })
  }
}

abstract class ConnectorModule extends AbstractModule with ScalaModule {
  def registerController[C <: Connector](implicit evidence: Manifest[C]): Unit = {
    val connectorBindings = ScalaMultibinder.newSetBinder[Connector](binder)
    connectorBindings.addBinding.to[C]
    ()
  }
}