package org.genivi.sota.core.resolver

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model.{HttpEntity, HttpResponse, StatusCodes, Uri}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import cats.data.Xor
import eu.timepit.refined.api.Refined
import io.circe.Json
import io.circe.jawn._
import org.genivi.sota.data.{PackageId, Vehicle}
import io.circe.generic.auto._
import org.genivi.sota.marshalling.CirceMarshallingSupport._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfterAll, Matchers, PropSpec}

import scala.concurrent.{ExecutionContext, Future}



/**
 * Spec tests for the external resolver client
 */
class ExternalResolverClientSpec extends PropSpec with Matchers with BeforeAndAfterAll {

  implicit val system = ActorSystem("test")
  implicit val materializer = ActorMaterializer()
  implicit val excecutionCtx = ExecutionContext.Implicits.global
  val client = new DefaultExternalResolverClient( Uri.Empty, Uri.Empty, Uri.Empty, Uri.Empty )

  property("handles failed put requests") {
    val error = new Throwable("ups")
    ScalaFutures.whenReady( client.handlePutResponse( Future.failed( error ) ).failed ) { e =>
      e shouldBe ExternalResolverRequestFailed( error )
    }
  }

  property("handles unexpected status codes") {
    ScalaFutures.whenReady( client.handlePutResponse( Future.successful( HttpResponse(StatusCodes.BadRequest) ) ).failed ) { e =>
      e shouldBe a [ExternalResolverRequestFailed]
    }
  }

  val s: String = s"""[["V1NBEAGLEB0ARD000",[{"version":"23.5.2","name":"rust"}]]]"""

  val m: Map[Vehicle.Vin, Set[PackageId]] =
    Map(Refined.unsafeApply("V1NBEAGLEB0ARD000") -> Set(PackageId(Refined.unsafeApply("rust"), Refined.unsafeApply("23.5.2"))))

  property("parse the external resolver's response") {
    import org.genivi.sota.marshalling.CirceInstances.refinedMapDecoder
    decode[Map[Vehicle.Vin, Set[PackageId]]](s)(refinedMapDecoder) shouldBe Xor.Right(m)
  }

  val resp: HttpResponse = HttpResponse(entity = HttpEntity(`application/json`, s))

  property("parse from a HttpResponse as Json") {

    ScalaFutures.whenReady(Unmarshal(resp.entity).to[Json]) { json =>
      json.noSpaces shouldBe s
    }

  }

  property("parse from a HttpResponse as a Map") {
    ScalaFutures.whenReady(Unmarshal(resp.entity).to[Map[Vehicle.Vin, Set[PackageId]]]) { m2 =>
      m2 shouldBe m
    }

  }

  override def afterAll(): Unit = {
    system.terminate()
  }

}
