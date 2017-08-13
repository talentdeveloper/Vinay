package connectors.cortex.controllers

import javax.inject.{ Inject, Singleton }

import scala.collection.JavaConversions.asScalaBuffer
import scala.concurrent.{ ExecutionContext, Future }
import scala.io.Source
import scala.util.control.NonFatal
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import play.api.Logger
import play.api.http.Status
import play.api.libs.json.{ JsBoolean, JsObject }
import play.api.mvc.{ Action, AnyContent, Controller }
import org.elastic4play.{ BadRequestError, Timed }
import org.elastic4play.controllers.{ Authenticated, Fields, FieldsBodyParser, FileInputValue, Renderer }
import org.elastic4play.models.JsonFormat.baseModelEntityWrites
import org.elastic4play.services.{ QueryDSL, QueryDef, Role }
import org.elastic4play.services.AuxSrv
import org.elastic4play.services.JsonFormat.queryReads
import connectors.cortex.services.ReportTemplateSrv
import net.lingala.zip4j.core.ZipFile
import net.lingala.zip4j.model.FileHeader

@Singleton
class ReportTemplateCtrl @Inject() (
    reportTemplateSrv: ReportTemplateSrv,
    auxSrv: AuxSrv,
    authenticated: Authenticated,
    renderer: Renderer,
    fieldsBodyParser: FieldsBodyParser,
    implicit val ec: ExecutionContext,
    implicit val mat: Materializer) extends Controller with Status {

  lazy val logger = Logger(getClass)

  @Timed
  def create: Action[Fields] = authenticated(Role.admin).async(fieldsBodyParser) { implicit request ⇒
    reportTemplateSrv.create(request.body)
      .map(reportTemplate ⇒ renderer.toOutput(CREATED, reportTemplate))
  }

  @Timed
  def get(id: String): Action[AnyContent] = authenticated(Role.read).async { implicit request ⇒
    reportTemplateSrv.get(id)
      .map(reportTemplate ⇒ renderer.toOutput(OK, reportTemplate))
  }

  @Timed
  def getContent(analyzerId: String, reportType: String): Action[AnyContent] = authenticated(Role.read).async { implicit request ⇒
    import org.elastic4play.services.QueryDSL._
    val (reportTemplates, total) = reportTemplateSrv.find(and("analyzerId" ~= analyzerId, "reportType" ~= reportType), Some("0-1"), Nil)
    total.foreach { t ⇒
      if (t > 1) logger.warn(s"Multiple report templates match for analyzer $analyzerId with type $reportType")
    }
    reportTemplates
      .runWith(Sink.headOption)
      .map {
        case Some(reportTemplate) ⇒ Ok(reportTemplate.content()).as("text/html")
        case None                 ⇒ NotFound("")
      }
  }

  @Timed
  def update(id: String): Action[Fields] = authenticated(Role.admin).async(fieldsBodyParser) { implicit request ⇒
    reportTemplateSrv.update(id, request.body)
      .map(reportTemplate ⇒ renderer.toOutput(OK, reportTemplate))
  }

  @Timed
  def delete(id: String): Action[AnyContent] = authenticated(Role.admin).async { implicit request ⇒
    reportTemplateSrv.delete(id)
      .map(_ ⇒ NoContent)
  }

  @Timed
  def find: Action[Fields] = authenticated(Role.read).async(fieldsBodyParser) { implicit request ⇒
    val query = request.body.getValue("query").fold[QueryDef](QueryDSL.any)(_.as[QueryDef])
    val range = request.body.getString("range")
    val sort = request.body.getStrings("sort").getOrElse(Nil)
    val nparent = request.body.getLong("nparent").getOrElse(0L).toInt
    val withStats = request.body.getBoolean("nstats").getOrElse(false)

    val (reportTemplates, total) = reportTemplateSrv.find(query, range, sort)
    val reportTemplatesWithStats = auxSrv(reportTemplates, nparent, withStats, removeUnaudited = false)
    renderer.toOutput(OK, reportTemplatesWithStats, total)
  }

  @Timed
  def importTemplatePackage: Action[Fields] = authenticated(Role.write).async(fieldsBodyParser) { implicit request ⇒
    val zipFile = request.body.get("templates") match {
      case Some(FileInputValue(name, filepath, contentType)) ⇒ new ZipFile(filepath.toFile)
      case _                                                 ⇒ throw BadRequestError("")
    }
    val importedReportTemplates: Seq[Future[(String, JsBoolean)]] = zipFile.getFileHeaders.toSeq.filter(_ != null).collect {
      case fileHeader: FileHeader if !fileHeader.isDirectory ⇒
        val Array(analyzerId, reportTypeHtml, _*) = (fileHeader.getFileName + "/").split("/", 3)
        val inputStream = zipFile.getInputStream(fileHeader)
        val content = Source.fromInputStream(inputStream).mkString
        inputStream.close()

        val reportType = if (reportTypeHtml.endsWith(".html")) reportTypeHtml.dropRight(5) else reportTypeHtml

        val reportTemplateFields = Fields.empty
          .set("reportType", reportType)
          .set("analyzerId", analyzerId)
          .set("content", content)
        reportTemplateSrv.create(reportTemplateFields)
          .recoverWith { // if creation fails, try to update
            case NonFatal(_) ⇒
              val reportTemplateId = analyzerId + "_" + reportType
              reportTemplateSrv.update(reportTemplateId, Fields.empty.set("content", content))
          }
          .map(_.id → JsBoolean(true))
          .recoverWith {
            case NonFatal(e) ⇒
              logger.error(s"The import of the report template $analyzerId ($reportType) has failed", e)
              val reportTemplateId = analyzerId + "_" + reportType
              Future.successful(reportTemplateId → JsBoolean(false))
          }
    }

    Future.sequence(importedReportTemplates).map { result ⇒
      renderer.toOutput(OK, JsObject(result))
    }
  }
}
