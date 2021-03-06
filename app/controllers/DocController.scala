package controllers

import javax.inject._

import models.JsonFormats._
import models._
import play.api.data.Form
import play.api.data.Forms.{tuple, _}
import play.api.libs.json.{JsObject, Json}
import play.api.mvc._
import play.modules.reactivemongo.ReactiveMongoApi
import reactivemongo.api.QueryOpts
import reactivemongo.bson.BSONObjectID
import reactivemongo.play.json._
import reactivemongo.play.json.collection.JSONCollection
import services.{CounterService, EventService}
import utils.{BitmapUtil, DateTimeUtil, HashUtil, RequestHelper}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DocController @Inject()(cc: ControllerComponents, val reactiveMongoApi: ReactiveMongoApi, counter: CounterService, eventService: EventService) (implicit ec: ExecutionContext, parser: BodyParsers.Default) extends AbstractController(cc) {
  def docColFuture = reactiveMongoApi.database.map(_.collection[JSONCollection]("common-doc"))
  def categoryColFuture = reactiveMongoApi.database.map(_.collection[JSONCollection]("common-category"))
  def userColFuture = reactiveMongoApi.database.map(_.collection[JSONCollection]("common-user"))
  def msgColFuture = reactiveMongoApi.database.map(_.collection[JSONCollection]("common-message"))
  def getColFuture(name: String) = reactiveMongoApi.database.map(_.collection[JSONCollection](name))

  def index(path: String, page: Int) = Action.async { implicit request: Request[AnyContent] =>
    val cPage = if(page < 1){1}else{page}
    for {
      userCol <- userColFuture
      docCol <- docColFuture
      docs <- docCol.find(Json.obj("categoryPath" -> Json.obj("$regex" -> s"^${path}"))).sort(Json.obj("index" -> 1)).options(QueryOpts(skipN = (cPage-1) * 15, batchSizeN = 15)).cursor[Doc]().collect[List](15)
      topViewDocs <- docCol.find(Json.obj()).sort(Json.obj("viewStat.count" -> -1)).cursor[Doc]().collect[List](10)
      topReplyDocs <- docCol.find(Json.obj()).sort(Json.obj("replyStat.count" -> -1)).cursor[Doc]().collect[List](10)
      topReplyUsers <- userCol.find(Json.obj()).sort(Json.obj("stat.replyCount" -> -1)).cursor[User]().collect[List](12)
      categoryCol <- categoryColFuture
      categoryList <- categoryCol.find(Json.obj("parentPath" -> "/", "disabled" -> false)).sort(Json.obj("index" -> 1)).cursor[Category]().collect[List]()
      total <- docCol.count(None)
    } yield {
      if (total > 0 && cPage > math.ceil(total/15.0).toInt) {
        Redirect(routes.DocController.index(path, math.ceil(total/15.0).toInt))
      } else {
        Ok(views.html.doc.index(categoryList, path, docs, topReplyUsers, topViewDocs, topReplyDocs, cPage, total))
      }
    }
  }

  def add = checkAdmin.async { implicit request: Request[AnyContent] =>
    for {
      categoryCol <- categoryColFuture
      categoryList <- categoryCol.find(Json.obj("parentPath" -> "/")).cursor[Category]().collect[List]()
    } yield {
      Ok(views.html.doc.add(None, categoryList))
    }
  }

  def edit(_id: String) = checkAdmin.async { implicit request: Request[AnyContent] =>
    for {
      docCol <- docColFuture
      doc <- docCol.find(Json.obj("_id" -> _id)).one[Doc]
      categoryCol <- categoryColFuture
      categoryList <- categoryCol.find(Json.obj("parentPath" -> "/")).cursor[Category]().collect[List]()
    } yield {
      doc match {
        case Some(a) => Ok(views.html.doc.add(Some(a), categoryList))
        case None => Redirect(routes.Application.notFound)
      }
    }
  }

  def doAdd = checkAdmin.async { implicit request: Request[AnyContent] =>
    Form(tuple("_id" -> optional(text), "title" -> nonEmptyText, "content" -> nonEmptyText, "keywords" -> nonEmptyText, "categoryPath" -> nonEmptyText)).bindFromRequest().fold(
      errForm => Future.successful(Ok(views.html.message("系统提示", "您的输入有误！"))),
      tuple => {
        val (_idOpt, title, content, keywords, categoryPath) = tuple
        for {
          docCol <- docColFuture
          categoryCol <- categoryColFuture
          category <- categoryCol.find(Json.obj("path" -> categoryPath)).one[Category]
          index <- counter.getNextSequence("doc-index")
          _ <-  _idOpt match {
                  case Some(_id) =>
                    eventService.updateResource(RequestHelper.getAuthor, _id, "doc", title)
                    docCol.update(Json.obj("_id" -> _id), Json.obj("$set" -> Json.obj(
                      "title" -> title,
                      "content" -> content,
                      "keywords" -> keywords,
                      "categoryPath" -> categoryPath,
                      "categoryName" -> category.map(_.name).getOrElse[String]("-"),
                      "author.name" -> request.session("name"),
                      "author.headImg" -> request.session("headImg"),
                      "timeStat.updateTime" -> DateTimeUtil.now()
                    )))
                  case None =>
                    val _id = RequestHelper.generateId
                    eventService.createResource(RequestHelper.getAuthor, _id, "doc", title)
                    docCol.insert(Doc(_id, title, content, keywords, "quill", RequestHelper.getAuthor, categoryPath, category.map(_.name).getOrElse("-"), List.empty[String], List.empty[Reply], ViewStat(0, ""), VoteStat(0, ""), ReplyStat(0, 0, ""),  CollectStat(0, ""), DocTimeStat(DateTimeUtil.now, DateTimeUtil.now), index))
                    userColFuture.map(_.update(Json.obj("_id" -> RequestHelper.getUid), Json.obj("$inc" -> Json.obj("stat.resCount" -> 1, "stat.docCount" -> 1))))
                }
        } yield {
          Redirect(routes.DocController.index(categoryPath, 1))
        }
      }
    )
  }

  def view(_id: String) = Action.async { implicit request: Request[AnyContent] =>
    for {
      docCol <- docColFuture
      doc <- docCol.find(Json.obj("_id" -> _id)).one[Doc]
    } yield {
      doc match {
        case Some(a) =>
          request.session.get("uid") match {
            case Some(uid) =>
              val uid = request.session("uid").toInt
              val viewBitmap = BitmapUtil.fromBase64String(a.viewStat.bitmap)
              if (!viewBitmap.contains(uid)) {
                viewBitmap.add(uid)
                docCol.update(Json.obj("_id" -> _id), Json.obj("$set" -> Json.obj("viewStat" -> ViewStat(a.viewStat.count + 1, BitmapUtil.toBase64String(viewBitmap)))))
                Ok(views.html.doc.detail(a.copy(viewStat = a.viewStat.copy(count = a.viewStat.count + 1))))
              } else {
                Ok(views.html.doc.detail(a))
              }
            case None =>
              Ok(views.html.doc.detail(a))
          }
        case None => Redirect(routes.Application.notFound)
      }
    }
  }

}
