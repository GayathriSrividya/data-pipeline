package org.sunbird.job.contentautocreator.functions

import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.functions.ProcessFunction
import org.slf4j.LoggerFactory
import org.sunbird.job.contentautocreator.domain.Event
import org.sunbird.job.contentautocreator.helpers.ContentAutoCreator
import org.sunbird.job.contentautocreator.task.ContentAutoCreatorConfig
import org.sunbird.job.domain.`object`.DefinitionCache
import org.sunbird.job.util._
import org.sunbird.job.{BaseProcessFunction, Metrics}

import java.util

class ContentAutoCreatorFunction(config: ContentAutoCreatorConfig, httpUtil: HttpUtil,
                                 @transient var neo4JUtil: Neo4JUtil = null,
                                 @transient var cloudStorageUtil: CloudStorageUtil = null)
                                (implicit mapTypeInfo: TypeInformation[util.Map[String, AnyRef]], stringTypeInfo: TypeInformation[String])
  extends BaseProcessFunction[Event, String](config) with ContentAutoCreator {

  private[this] lazy val logger = LoggerFactory.getLogger(classOf[ContentAutoCreatorFunction])
  lazy val defCache: DefinitionCache = new DefinitionCache()

  override def metricsList(): List[String] = {
    List(config.totalEventsCount, config.successEventCount, config.failedEventCount, config.skippedEventCount)
  }

  override def open(parameters: Configuration): Unit = {
    super.open(parameters)
    neo4JUtil = new Neo4JUtil(config.graphRoutePath, config.graphName)
    cloudStorageUtil = new CloudStorageUtil(config)
  }

  override def close(): Unit = {
    super.close()
  }

  override def processElement(event: Event,
                              context: ProcessFunction[Event, String]#Context,
                              metrics: Metrics): Unit = {
    metrics.incCounter(config.totalEventsCount)
    if (event.isValid(config)) {
      logger.info("ContentAutoCreatorFunction::processElement:: Processing event for auto creator content upload/approval operation having identifier : " + event.objectId)
      logger.info("ContentAutoCreatorFunction::processElement:: event edata : " + event.eData)
      if(event.validateStage(config)) {
        if(event.validateMetadata(config)) {
          process(config, event, httpUtil, neo4JUtil, cloudStorageUtil)
          logger.info("ContentAutoCreatorFunction::processElement:: Content auto creator upload/approval operation completed for : " + event.objectId)
          metrics.incCounter(config.successEventCount)
        } else{
          logger.info("ContentAutoCreatorFunction::processElement:: Event Ignored. Event Metadata Validation Failed for :" + event.identifier + " | Metadata : " + event.metadata + " Required fields are : " + config.mandatoryContentMetadata)
          metrics.incCounter(config.skippedEventCount)
        }
      }
      else{
        logger.info("ContentAutoCreatorFunction::processElement:: Event Ignored. Content Stage Validation Failed for :" + event.identifier + " | Stage : " + event.stage + " Allowed Stages are : " + config.allowedContentStages)
        metrics.incCounter(config.skippedEventCount)
      }
    } else {
      logger.info("ContentAutoCreatorFunction::processElement:: Event is not qualified for auto creator content upload/approval having identifier : " + event.objectId + " | objectType : " + event.objectType + " | source : " + event.repository)
      metrics.incCounter(config.skippedEventCount)
    }
  }
}
