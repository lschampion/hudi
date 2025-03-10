/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi

import org.apache.hadoop.fs.Path
import org.apache.hudi.DataSourceReadOptions._
import org.apache.hudi.DataSourceWriteOptions.{BOOTSTRAP_OPERATION_OPT_VAL, OPERATION, STREAMING_CHECKPOINT_IDENTIFIER}
import org.apache.hudi.cdc.CDCRelation
import org.apache.hudi.common.fs.FSUtils
import org.apache.hudi.common.model.HoodieTableType.{COPY_ON_WRITE, MERGE_ON_READ}
import org.apache.hudi.common.model.{HoodieRecord, WriteConcurrencyMode}
import org.apache.hudi.common.table.timeline.HoodieInstant
import org.apache.hudi.common.table.{HoodieTableMetaClient, TableSchemaResolver}
import org.apache.hudi.common.util.ConfigUtils
import org.apache.hudi.common.util.ValidationUtils.checkState
import org.apache.hudi.config.HoodieBootstrapConfig.DATA_QUERIES_ONLY
import org.apache.hudi.config.HoodieWriteConfig.WRITE_CONCURRENCY_MODE
import org.apache.hudi.exception.HoodieException
import org.apache.hudi.util.PathUtils
import org.apache.spark.sql.execution.streaming.{Sink, Source}
import org.apache.spark.sql.hudi.HoodieSqlCommonUtils.isUsingHiveCatalog
import org.apache.spark.sql.hudi.streaming.{HoodieEarliestOffsetRangeLimit, HoodieLatestOffsetRangeLimit, HoodieSpecifiedOffsetRangeLimit, HoodieStreamSource}
import org.apache.spark.sql.sources._
import org.apache.spark.sql.streaming.OutputMode
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{DataFrame, SQLContext, SaveMode, SparkSession}
import org.slf4j.LoggerFactory

import scala.collection.JavaConversions.mapAsJavaMap
import scala.collection.JavaConverters._

/**
  * Hoodie Spark Datasource, for reading and writing hoodie tables
  *
  */
class DefaultSource extends RelationProvider
  with SchemaRelationProvider
  with CreatableRelationProvider
  with DataSourceRegister
  with StreamSinkProvider
  with StreamSourceProvider
  with SparkAdapterSupport
  with Serializable {

  SparkSession.getActiveSession.foreach { spark =>
    val sparkVersion = spark.version
    if (sparkVersion.startsWith("0.") || sparkVersion.startsWith("1.") || sparkVersion.startsWith("2.")) {
      // Enable "passPartitionByAsOptions" to support "write.partitionBy(...)"
      spark.conf.set("spark.sql.legacy.sources.write.passPartitionByAsOptions", "true")
    }
    // Revisit EMRFS incompatibilities, for now disable
    spark.sparkContext.hadoopConfiguration.set("fs.s3.metadata.cache.expiration.seconds", "0")
  }

  private val log = LoggerFactory.getLogger(classOf[DefaultSource])

  override def createRelation(sqlContext: SQLContext,
                              parameters: Map[String, String]): BaseRelation = {
    createRelation(sqlContext, parameters, null)
  }

  override def createRelation(sqlContext: SQLContext,
                              optParams: Map[String, String],
                              schema: StructType): BaseRelation = {
    val path = optParams.get("path")
    val readPathsStr = optParams.get(DataSourceReadOptions.READ_PATHS.key)

    if (path.isEmpty && readPathsStr.isEmpty) {
      throw new HoodieException(s"'path' or '$READ_PATHS' or both must be specified.")
    }

    val readPaths = readPathsStr.map(p => p.split(",").toSeq).getOrElse(Seq())
    val allPaths = path.map(p => Seq(p)).getOrElse(Seq()) ++ readPaths

    val fs = FSUtils.getFs(allPaths.head, sqlContext.sparkContext.hadoopConfiguration)

    val globPaths = if (path.exists(_.contains("*")) || readPaths.nonEmpty) {
      PathUtils.checkAndGlobPathIfNecessary(allPaths, fs)
    } else {
      Seq.empty
    }

    // Add default options for unspecified read options keys.
    val parameters = (if (globPaths.nonEmpty) {
      Map(
        "glob.paths" -> globPaths.mkString(",")
      )
    } else {
      Map()
    }) ++ DataSourceOptionsHelper.parametersWithReadDefaults(optParams +
      (DATA_QUERIES_ONLY.key() -> sqlContext.getConf(DATA_QUERIES_ONLY.key(), optParams.getOrElse(DATA_QUERIES_ONLY.key(), DATA_QUERIES_ONLY.defaultValue()))))

    // Get the table base path
    val tablePath = if (globPaths.nonEmpty) {
      DataSourceUtils.getTablePath(fs, globPaths.toArray)
    } else {
      DataSourceUtils.getTablePath(fs, Array(new Path(path.get)))
    }
    log.info("Obtained hudi table path: " + tablePath)

    val metaClient = HoodieTableMetaClient.builder().setMetaserverConfig(parameters.asJava)
      .setConf(fs.getConf).setBasePath(tablePath).build()

    DefaultSource.createRelation(sqlContext, metaClient, schema, globPaths, parameters)
  }

  def getValidCommits(metaClient: HoodieTableMetaClient): String = {
    metaClient
      .getCommitsAndCompactionTimeline.filterCompletedInstants.getInstantsAsStream.toArray().map(_.asInstanceOf[HoodieInstant].getFileName).mkString(",")
  }

  /**
    * This DataSource API is used for writing the DataFrame at the destination. For now, we are returning a dummy
    * relation here because Spark does not really make use of the relation returned, and just returns an empty
    * dataset at [[org.apache.spark.sql.execution.datasources.SaveIntoDataSourceCommand.run()]]. This saves us the cost
    * of creating and returning a parquet relation here.
    *
    * TODO: Revisit to return a concrete relation here when we support CREATE TABLE AS for Hudi with DataSource API.
    *       That is the only case where Spark seems to actually need a relation to be returned here
    *       [[org.apache.spark.sql.execution.datasources.DataSource.writeAndRead()]]
    *
    * @param sqlContext Spark SQL Context
    * @param mode Mode for saving the DataFrame at the destination
    * @param optParams Parameters passed as part of the DataFrame write operation
    * @param df Spark DataFrame to be written
    * @return Spark Relation
    */
  override def createRelation(sqlContext: SQLContext,
                              mode: SaveMode,
                              optParams: Map[String, String],
                              df: DataFrame): BaseRelation = {
    val dfWithoutMetaCols = df.drop(HoodieRecord.HOODIE_META_COLUMNS.asScala:_*)

    if (optParams.get(OPERATION.key).contains(BOOTSTRAP_OPERATION_OPT_VAL)) {
      HoodieSparkSqlWriter.bootstrap(sqlContext, mode, optParams, dfWithoutMetaCols)
      HoodieSparkSqlWriter.cleanup()
    } else {
      val (success, _, _, _, _, _) = HoodieSparkSqlWriter.write(sqlContext, mode, optParams, dfWithoutMetaCols)
      HoodieSparkSqlWriter.cleanup()
      if (!success) {
        throw new HoodieException("Write to Hudi failed")
      }
    }

    new HoodieEmptyRelation(sqlContext, dfWithoutMetaCols.schema)
  }

  override def createSink(sqlContext: SQLContext,
                          optParams: Map[String, String],
                          partitionColumns: Seq[String],
                          outputMode: OutputMode): Sink = {
    validateMultiWriterConfigs(optParams)
    new HoodieStreamingSink(
      sqlContext,
      optParams,
      partitionColumns,
      outputMode)
  }

  def validateMultiWriterConfigs(options: Map[String, String]) : Unit = {
    if (ConfigUtils.resolveEnum(classOf[WriteConcurrencyMode], options.getOrDefault(WRITE_CONCURRENCY_MODE.key(),
      WRITE_CONCURRENCY_MODE.defaultValue())) == WriteConcurrencyMode.OPTIMISTIC_CONCURRENCY_CONTROL) {
      // ensure some valid value is set for identifier
      checkState(options.contains(STREAMING_CHECKPOINT_IDENTIFIER.key()), "For multi-writer scenarios, please set "
        + STREAMING_CHECKPOINT_IDENTIFIER.key() + ". Each writer should set different values for this identifier")
    }
  }

  override def shortName(): String = "hudi_v1"

  override def sourceSchema(sqlContext: SQLContext,
                            schema: Option[StructType],
                            providerName: String,
                            parameters: Map[String, String]): (String, StructType) = {
    val path = parameters.get("path")
    if (path.isEmpty || path.get == null) {
      throw new HoodieException(s"'path'  must be specified.")
    }
    val metaClient = HoodieTableMetaClient.builder().setConf(
      sqlContext.sparkSession.sessionState.newHadoopConf()).setBasePath(path.get).build()

    val sqlSchema = DefaultSource.resolveSchema(metaClient, parameters, schema)
    (shortName(), sqlSchema)
  }

  override def createSource(sqlContext: SQLContext,
                            metadataPath: String,
                            schema: Option[StructType],
                            providerName: String,
                            parameters: Map[String, String]): Source = {
    val offsetRangeLimit = parameters.getOrElse(START_OFFSET.key(), START_OFFSET.defaultValue()) match {
      case offset if offset.equalsIgnoreCase("earliest") =>
        HoodieEarliestOffsetRangeLimit
      case offset if offset.equalsIgnoreCase("latest") =>
        HoodieLatestOffsetRangeLimit
      case instantTime =>
        HoodieSpecifiedOffsetRangeLimit(instantTime)
    }

    new HoodieStreamSource(sqlContext, metadataPath, schema, parameters, offsetRangeLimit)
  }
}

object DefaultSource {

  private val log = LoggerFactory.getLogger(classOf[DefaultSource])

  def createRelation(sqlContext: SQLContext,
                     metaClient: HoodieTableMetaClient,
                     schema: StructType,
                     globPaths: Seq[Path],
                     parameters: Map[String, String]): BaseRelation = {
    val tableType = metaClient.getTableType
    val isBootstrappedTable = metaClient.getTableConfig.getBootstrapBasePath.isPresent
    val queryType = parameters(QUERY_TYPE.key)
    val isCdcQuery = queryType == QUERY_TYPE_INCREMENTAL_OPT_VAL &&
      parameters.get(INCREMENTAL_FORMAT.key).contains(INCREMENTAL_FORMAT_CDC_VAL)

    log.info(s"Is bootstrapped table => $isBootstrappedTable, tableType is: $tableType, queryType is: $queryType")

    // NOTE: In cases when Hive Metastore is used as catalog and the table is partitioned, schema in the HMS might contain
    //       Hive-specific partitioning columns created specifically for HMS to handle partitioning appropriately. In that
    //       case  we opt in to not be providing catalog's schema, and instead force Hudi relations to fetch the schema
    //       from the table itself
    val userSchema = if (isUsingHiveCatalog(sqlContext.sparkSession)) {
      None
    } else {
      Option(schema)
    }

    if (metaClient.getCommitsTimeline.filterCompletedInstants.countInstants() == 0) {
      new EmptyRelation(sqlContext, resolveSchema(metaClient, parameters, Some(schema)))
    } else if (isCdcQuery) {
      CDCRelation.getCDCRelation(sqlContext, metaClient, parameters)
    } else {
      (tableType, queryType, isBootstrappedTable) match {
        case (COPY_ON_WRITE, QUERY_TYPE_SNAPSHOT_OPT_VAL, false) |
             (COPY_ON_WRITE, QUERY_TYPE_READ_OPTIMIZED_OPT_VAL, false) |
             (MERGE_ON_READ, QUERY_TYPE_READ_OPTIMIZED_OPT_VAL, false) =>
          resolveBaseFileOnlyRelation(sqlContext, globPaths, userSchema, metaClient, parameters)

        case (COPY_ON_WRITE, QUERY_TYPE_INCREMENTAL_OPT_VAL, _) =>
          new IncrementalRelation(sqlContext, parameters, userSchema, metaClient)

        case (MERGE_ON_READ, QUERY_TYPE_SNAPSHOT_OPT_VAL, false) =>
          new MergeOnReadSnapshotRelation(sqlContext, parameters, metaClient, globPaths, userSchema)

        case (MERGE_ON_READ, QUERY_TYPE_INCREMENTAL_OPT_VAL, _) =>
          new MergeOnReadIncrementalRelation(sqlContext, parameters, metaClient, userSchema)

        case (_, _, true) =>
          resolveHoodieBootstrapRelation(sqlContext, globPaths, userSchema, metaClient, parameters)

        case (_, _, _) =>
          throw new HoodieException(s"Invalid query type : $queryType for tableType: $tableType," +
            s"isBootstrappedTable: $isBootstrappedTable ")
      }
    }
  }

  private def resolveHoodieBootstrapRelation(sqlContext: SQLContext,
                                             globPaths: Seq[Path],
                                             userSchema: Option[StructType],
                                             metaClient: HoodieTableMetaClient,
                                             parameters: Map[String, String]): BaseRelation = {
    val enableFileIndex = HoodieSparkConfUtils.getConfigValue(parameters, sqlContext.sparkSession.sessionState.conf,
      ENABLE_HOODIE_FILE_INDEX.key, ENABLE_HOODIE_FILE_INDEX.defaultValue.toString).toBoolean
    val isSchemaEvolutionEnabledOnRead = HoodieSparkConfUtils.getConfigValue(parameters,
      sqlContext.sparkSession.sessionState.conf, DataSourceReadOptions.SCHEMA_EVOLUTION_ENABLED.key,
      DataSourceReadOptions.SCHEMA_EVOLUTION_ENABLED.defaultValue.toString).toBoolean
    if (!enableFileIndex || isSchemaEvolutionEnabledOnRead
      || globPaths.nonEmpty || !parameters.getOrElse(DATA_QUERIES_ONLY.key, DATA_QUERIES_ONLY.defaultValue).toBoolean) {
      HoodieBootstrapRelation(sqlContext, userSchema, globPaths, metaClient, parameters + (DATA_QUERIES_ONLY.key() -> "false"))
    } else {
      HoodieBootstrapRelation(sqlContext, userSchema, globPaths, metaClient, parameters).toHadoopFsRelation
    }
  }

  private def resolveBaseFileOnlyRelation(sqlContext: SQLContext,
                                          globPaths: Seq[Path],
                                          userSchema: Option[StructType],
                                          metaClient: HoodieTableMetaClient,
                                          optParams: Map[String, String]): BaseRelation = {
    val baseRelation = new BaseFileOnlyRelation(sqlContext, metaClient, optParams, userSchema, globPaths)

    // NOTE: We fallback to [[HadoopFsRelation]] in all of the cases except ones requiring usage of
    //       [[BaseFileOnlyRelation]] to function correctly. This is necessary to maintain performance parity w/
    //       vanilla Spark, since some of the Spark optimizations are predicated on the using of [[HadoopFsRelation]].
    //
    //       You can check out HUDI-3896 for more details
    if (baseRelation.hasSchemaOnRead) {
      baseRelation
    } else {
      baseRelation.toHadoopFsRelation
    }
  }

  private def resolveSchema(metaClient: HoodieTableMetaClient,
                            parameters: Map[String, String],
                            schema: Option[StructType]): StructType = {
    val isCdcQuery = CDCRelation.isCDCEnabled(metaClient) &&
      parameters.get(QUERY_TYPE.key).contains(QUERY_TYPE_INCREMENTAL_OPT_VAL) &&
      parameters.get(INCREMENTAL_FORMAT.key).contains(INCREMENTAL_FORMAT_CDC_VAL)
    if (isCdcQuery) {
      CDCRelation.FULL_CDC_SPARK_SCHEMA
    } else {
      val schemaResolver = new TableSchemaResolver(metaClient)
      try {
        val avroSchema = schemaResolver.getTableAvroSchema
        AvroConversionUtils.convertAvroSchemaToStructType(avroSchema)
      } catch {
        case _: Exception =>
          require(schema.isDefined, "Fail to resolve source schema")
          schema.get
      }
    }
  }
}
