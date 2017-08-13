package controllers

import javax.inject.{ Inject, Singleton }

import akka.stream.Materializer
import org.elastic4play.controllers.{ Authenticated, Fields, FieldsBodyParser, Renderer }
import org.elastic4play.models.JsonFormat.baseModelEntityWrites
import org.elastic4play.services.JsonFormat.{ aggReads, queryReads }
import org.elastic4play.services._
import org.elastic4play.{ BadRequestError, Timed }
import play.api.Logger
import play.api.http.Status
import play.api.libs.json.{ JsArray, JsObject, Json }
import play.api.mvc.{ Action, AnyContent, Controller }
import services.{ AlertSrv, CaseSrv }
import services.JsonFormat.caseSimilarityWrites

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

@Singleton
class AlertCtrl @Inject() (
    alertSrv: AlertSrv,
    caseSrv: CaseSrv,
    auxSrv: AuxSrv,
    authenticated: Authenticated,
    renderer: Renderer,
    fieldsBodyParser: FieldsBodyParser,
    implicit val ec: ExecutionContext,
    implicit val mat: Materializer) extends Controller with Status {

  val log = Logger(getClass)

  @Timed
  def create(): Action[Fields] = authenticated(Role.write).async(fieldsBodyParser) { implicit request ⇒
    alertSrv.create(request.body)
      .map(alert ⇒ renderer.toOutput(CREATED, alert))
  }

  @Timed
  def mergeWithCase(alertId: String, caseId: String): Action[Fields] = authenticated(Role.write).async(fieldsBodyParser) { implicit request ⇒
    for {
      alert ← alertSrv.get(alertId)
      caze ← caseSrv.get(caseId)
      updatedCaze ← alertSrv.mergeWithCase(alert, caze)
    } yield renderer.toOutput(CREATED, updatedCaze)
  }

  @Timed
  def get(id: String): Action[AnyContent] = authenticated(Role.read).async { implicit request ⇒
    val withStats = request
      .queryString
      .get("nstats")
      .flatMap(_.headOption)
      .exists(v ⇒ Try(v.toBoolean).getOrElse(v == "1"))

    val withSimilarity = request
      .queryString
      .get("similarity")
      .flatMap(_.headOption)
      .exists(v ⇒ Try(v.toBoolean).getOrElse(v == "1"))

    for {
      alert ← alertSrv.get(id)
      alertsWithStats ← auxSrv.apply(alert, 0, withStats, removeUnaudited = false)
      similarCases ← if (withSimilarity)
        alertSrv.similarCases(alert)
          .map(sc ⇒ Json.obj("similarCases" → Json.toJson(sc)))
      else Future.successful(JsObject(Nil))
    } yield {
      renderer.toOutput(OK, alertsWithStats ++ similarCases)
    }
  }

  @Timed
  def update(id: String): Action[Fields] = authenticated(Role.write).async(fieldsBodyParser) { implicit request ⇒
    alertSrv.update(id, request.body)
      .map { alert ⇒ renderer.toOutput(OK, alert) }
  }

  @Timed
  def bulkUpdate(): Action[Fields] = authenticated(Role.write).async(fieldsBodyParser) { implicit request ⇒
    request.body.getStrings("ids").fold(Future.successful(Ok(JsArray()))) { ids ⇒
      alertSrv.bulkUpdate(ids, request.body.unset("ids")).map(multiResult ⇒ renderer.toMultiOutput(OK, multiResult))
    }
  }

  @Timed
  def delete(id: String): Action[AnyContent] = authenticated(Role.write).async { implicit request ⇒
    alertSrv.delete(id)
      .map(_ ⇒ NoContent)
  }

  @Timed
  def find(): Action[Fields] = authenticated(Role.read).async(fieldsBodyParser) { implicit request ⇒
    val query = request.body.getValue("query").fold[QueryDef](QueryDSL.any)(_.as[QueryDef])
    val range = request.body.getString("range")
    val sort = request.body.getStrings("sort").getOrElse(Nil)
    val nparent = request.body.getLong("nparent").getOrElse(0L).toInt
    val withStats = request.body.getBoolean("nstats").getOrElse(false)

    val (alerts, total) = alertSrv.find(query, range, sort)
    val alertsWithStats = auxSrv.apply(alerts, nparent, withStats, removeUnaudited = false)
    renderer.toOutput(OK, alertsWithStats, total)
  }

  @Timed
  def stats(): Action[Fields] = authenticated(Role.read).async(fieldsBodyParser) { implicit request ⇒
    val query = request.body.getValue("query")
      .fold[QueryDef](QueryDSL.any)(_.as[QueryDef])
    val aggs = request.body.getValue("stats")
      .getOrElse(throw BadRequestError("Parameter \"stats\" is missing")).as[Seq[Agg]]
    alertSrv.stats(query, aggs).map(s ⇒ Ok(s))
  }

  @Timed
  def markAsRead(id: String): Action[AnyContent] = authenticated(Role.write).async { implicit request ⇒
    for {
      alert ← alertSrv.get(id)
      updatedAlert ← alertSrv.markAsRead(alert)
    } yield renderer.toOutput(OK, updatedAlert)
  }

  @Timed
  def markAsUnread(id: String): Action[AnyContent] = authenticated(Role.write).async { implicit request ⇒
    for {
      alert ← alertSrv.get(id)
      updatedAlert ← alertSrv.markAsUnread(alert)
    } yield renderer.toOutput(OK, updatedAlert)
  }

  @Timed
  def createCase(id: String): Action[Fields] = authenticated(Role.write).async(fieldsBodyParser) { implicit request ⇒
    for {
      alert ← alertSrv.get(id)
      customCaseTemplate = request.body.getString("caseTemplate")
      caze ← alertSrv.createCase(alert, customCaseTemplate)
    } yield renderer.toOutput(CREATED, caze)
  }

  @Timed
  def followAlert(id: String): Action[AnyContent] = authenticated(Role.write).async { implicit request ⇒
    alertSrv.setFollowAlert(id, follow = true)
      .map { alert ⇒ renderer.toOutput(OK, alert) }
  }

  @Timed
  def unfollowAlert(id: String): Action[AnyContent] = authenticated(Role.write).async { implicit request ⇒
    alertSrv.setFollowAlert(id, follow = false)
      .map { alert ⇒ renderer.toOutput(OK, alert) }
  }

  @Timed
  def fixStatus(): Action[AnyContent] = authenticated(Role.admin).async { implicit request ⇒
    alertSrv.fixStatus()
      .map(_ ⇒ NoContent)
  }
}