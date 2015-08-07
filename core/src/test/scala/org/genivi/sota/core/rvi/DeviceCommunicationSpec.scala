/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.core.rvi

import akka.http.scaladsl.model.HttpResponse
import com.github.nscala_time.time.Imports._
import org.genivi.sota.core._
import org.genivi.sota.core.db._
import org.scalacheck._
import org.scalacheck.Prop._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.prop.PropertyChecks
import org.scalatest.{BeforeAndAfterAll, Matchers, PropSpec}
import scala.concurrent.{Await, Future}
import slick.driver.MySQLDriver.api._

class DeviceCommunicationSpec extends PropSpec
    with TestDatabase
    with Matchers
    with PropertyChecks
    with BeforeAndAfterEach {

  import org.scalacheck._

  import scala.concurrent.ExecutionContext.Implicits.global

  override def beforeEach {
    resetDatabase
  }

  val vinGen: Gen[Vin] = Gen.listOfN(17, Gen.alphaNumChar).map( xs => Vin(xs.mkString) )

  val packageGen: Gen[Package] = for {
    len <- Gen.choose(5, 10)
    name <- Gen.listOfN(len, Gen.alphaNumChar)
    major <- Gen.choose(0, 10)
    minor <- Gen.choose(0, 10)
  } yield Package(None, name.mkString, s"$major.$minor", None, None)

  type Interval = (DateTime, DateTime)

  import InstallRequest.Status

  def campaign(pkgId: Long, priority: Int, startAfter: DateTime, endBefore: DateTime): InstallCampaign =
    InstallCampaign(None, pkgId, priority, startAfter, endBefore)

  def request(pkgId: Long, campaignId: Long, vin: Vin, status: InstallRequest.Status = Status.NotProcessed): InstallRequest =
    InstallRequest(None, campaignId, pkgId, vin.vin, status, None)

  case class State(pkg1: Package,
                   pkg2: Package,
                   vin1: Vin,
                   vin2: Vin,
                   vin3: Vin,
                   highPriority: Int,
                   lowPriority: Int,
                   currentInterval: Interval,
                   nonCurrentInterval: Interval) {
    override def toString(): String = Seq(
      "pkg1" -> pkg1,
      "pkg2" -> pkg2,
      "vin1" -> vin1,
      "vin2" -> vin2,
      "vin3" -> vin3,
      "highPriority" -> highPriority,
      "lowPriority" -> lowPriority,
      "currentInterval" -> currentInterval,
      "nonCurrentInterval" -> nonCurrentInterval
    ).map { case (n, p) => s"$n: $p\n" }.mkString
  }

  val states: Gen[State] = for {
    pkg1 <- packageGen
    pkg2 <- packageGen
    vin1 <- vinGen
    vin2 <- vinGen
    vin3 <- vinGen
    highPriority <- Gen.choose(50, 200)
    lowPriority <- Gen.choose(1, 49)

    now = DateTime.now

    i <- Gen.choose(5, 200)
    currentInterval = (now - i.days, now + i.days)
    nonCurrentInterval = (now - (i * 2).days, now - i.days)
  } yield State(pkg1, pkg2, vin1, vin2, vin3, highPriority, lowPriority, currentInterval, nonCurrentInterval)

  def persisted(p: Package): DBIO[(Long, Package)] = Packages.create(p).map { p => (p.id.head, p) }
  def persisted(v: Vin): DBIO[String] = Vins.create(v).map { _ => v.vin }
  def persisted(r: InstallRequest): DBIO[Long] = InstallRequests.create(r).map(_.id.head)
  def persisted(c: InstallCampaign): DBIO[Long] = InstallCampaigns.create(c).map(_.id.head)
  def persisted(s: State): DBIO[(Package, Package, Long, Long, Long, Long, Long)] = for {
    (pkgId1, pkg1) <- persisted(s.pkg1)
    (pkgId2, pkg2) <- persisted(s.pkg2)

    _ <- persisted(s.vin1)
    _ <- persisted(s.vin2)
    _ <- persisted(s.vin3)

    highPriorityCurrentCampaignId <- persisted(campaign(pkgId1, s.highPriority, s.currentInterval._1, s.currentInterval._2))
    r1 <- persisted(request(pkgId1, highPriorityCurrentCampaignId, s.vin1))
    r2 <- persisted(request(pkgId1, highPriorityCurrentCampaignId, s.vin2, Status.Notified))

    lowPriorityCurrentCampaignId <- persisted(campaign(pkgId2, s.lowPriority, s.currentInterval._1, s.currentInterval._2))
    r3 <- persisted(request(pkgId2, lowPriorityCurrentCampaignId, s.vin2))
    r4 <- persisted(request(pkgId2, lowPriorityCurrentCampaignId, s.vin1, Status.Notified))

    nonCurrentCampaignId <- persisted(campaign(pkgId1, s.highPriority, s.nonCurrentInterval._1, s.nonCurrentInterval._2))
    r5 <- persisted(request(pkgId1, nonCurrentCampaignId, s.vin3))
  } yield (pkg1, pkg2, r1, r2, r3, r4, r5)


  class RviMock(worksWhen: ((String, Package)) => Boolean = Function.const(true)) extends RviInterface {
    val msgs = scala.collection.mutable.Set[(String, Package)]()
    def notify(s: String, p: Package): Future[HttpResponse] =
      if (worksWhen((s, p))) {
        this.synchronized(msgs += ((s,p)))
        Future.successful(HttpResponse())
      } else Future.failed[HttpResponse](new RviInterface.UnexpectedRviResponse(HttpResponse()))
  }

  property("a DeviceCommunication runs the current campaigns") {
    forAll(states) { state =>
      val rviNode = new RviMock
      val devComm = new DeviceCommunication(db, rviNode, e => println(e) )

      val test = for {
        (pkg1, pkg2, rid1, _, rid3, _, rid5) <- db.run(persisted(state))
        expectedMessages = Seq((state.vin1.vin, pkg1),
                               (state.vin2.vin, pkg2))
        _ <- devComm.runCurrentCampaigns()

        _ = expectedMessages.sortBy(_._1).zip(rviNode.msgs.toSeq.sortBy(_._1)).foreach { case (expected, actual) =>
          expected should equal (actual)
        }

        _ <- db.run(InstallRequests.list(Seq(rid1, rid3, rid5))).map { case Seq(r1, r3, r5) =>
          r1.statusCode should equal(Status.Notified)
          r3.statusCode should equal(Status.Notified)
          r5.statusCode should equal(Status.NotProcessed)
        }
      } yield ()

      test.onFailure { case e => fail(e) }

      {
        import scala.concurrent.duration.DurationInt
        Await.result(test, DurationInt(10).seconds)
      }
    }
  }

  property("a DeviceCommunication logs all failures but still processes but still processes what it can") {
    forAll(states) { state =>
      val rviNode = new RviMock({ case (s, p) => s != state.vin1.vin })
      val errors = scala.collection.mutable.Set[Throwable]()
      val devComm = new DeviceCommunication(db, rviNode, e => { this.synchronized { errors += e } })


      val test = for {
        (pkg1, pkg2, rid1, _, rid3, _, _) <- db.run(persisted(state))
        _ <- devComm.runCurrentCampaigns()

        expectedMessages = Seq((state.vin2.vin, pkg2))
        _ = expectedMessages.length should equal(rviNode.msgs.toSeq.length)
        _ = expectedMessages.sortBy(_._1).zip(rviNode.msgs.toSeq.sortBy(_._1)).foreach { case (expected, actual) =>
          expected should equal (actual)
        }
        _ = errors.toSeq.length should equal (1)

        _ <- db.run(InstallRequests.list(Seq(rid1, rid3))).flatMap { case Seq(r1, r3) =>
          r1.statusCode should equal(Status.NotProcessed)
          r3.statusCode should equal(Status.Notified)
          db.run(InstallRequests.updateNotified(Seq(r1))) // for cleanup
        }
      } yield ()

      test.onFailure { case e => fail(e) }

      {
        import scala.concurrent.duration.DurationInt
        Await.result(test, DurationInt(10).seconds)
      }
    }
  }
}
