/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.core.db

import slick.driver.MySQLDriver.api._
import org.genivi.sota.core.Package

object Packages {

  // scalastyle:off
  class PackageTable(tag: Tag) extends Table[Package](tag, "Package") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def name = column[String]("name")
    def version = column[String]("version")
    def description = column[String]("description")
    def vendor = column[String]("vendor")

    def * = (id.?, name, version, description.?, vendor.?) <>
      ((Package.apply _).tupled, Package.unapply)
  }
  // scalastyle:on

  val packages = TableQuery[PackageTable]

  def list: DBIO[Seq[Package]] = packages.result

  def create(pkg: Package): DBIO[Package] =
    (packages
      returning packages.map(_.id)
      into ((pkg, id) => pkg.copy(id = Some(id)))) += pkg

  def createPackages(reqs: Seq[Package]): DBIO[Seq[Package]] = {
    DBIO.sequence( reqs.map( create ) )
  }
}
