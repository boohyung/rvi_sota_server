/**
  * Copyright: Copyright (C) 2016, ATS Advanced Telematic Systems GmbH
  * License: MPL-2.0
  */
package org.genivi.sota.core.campaigns

import org.genivi.sota.common.DeviceRegistry
import org.genivi.sota.core.UpdateService
import org.genivi.sota.core.data.Campaign.CampaignGroup
import org.genivi.sota.core.data.{Campaign, CampaignStatistics, UpdateStatus}
import org.genivi.sota.core.db.Campaigns
import org.genivi.sota.data.Uuid
import slick.driver.MySQLDriver.api.Database

import scala.concurrent.{ExecutionContext, Future}

object CampaignStats {

  private def getCampaignStatsForUpdate(groupId: Uuid, updateId: Uuid, deviceRegistry: DeviceRegistry,
                                        updateService: UpdateService)
                                       (implicit db: Database, ec: ExecutionContext)
  : Future[CampaignStatistics] = {
    for {
      rows <- updateService.fetchUpdateSpecRows(updateId)
      groupSize <- deviceRegistry.fetchDevicesInGroup(groupId)
      successCount = rows.count(r => r.status.equals(UpdateStatus.Finished))
      failureCount = rows.count(r => r.status.equals(UpdateStatus.Failed))
    } yield CampaignStatistics(groupId, updateId, groupSize.size, rows.size, successCount, failureCount)
  }


  def get(id: Campaign.Id, deviceRegistry: DeviceRegistry, updateService: UpdateService)
         (implicit db: Database, ec: ExecutionContext)
  : Future[Seq[CampaignStatistics]] = {
    db.run(Campaigns.fetchGroups(id)).flatMap { groups =>
      Future.sequence(
        groups.collect {
          case CampaignGroup(groupId, Some(updateId)) =>
            getCampaignStatsForUpdate(groupId, updateId, deviceRegistry, updateService)
        })
    }
  }
}
