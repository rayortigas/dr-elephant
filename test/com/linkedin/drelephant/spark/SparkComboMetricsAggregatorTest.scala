/*
 * Copyright 2016 LinkedIn Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.linkedin.drelephant.spark

import java.util.Date

import com.linkedin.drelephant.analysis.ApplicationType
import com.linkedin.drelephant.configurations.aggregator.AggregatorConfigurationData
import com.linkedin.drelephant.spark.data.{SparkComboApplicationData, SparkLogDerivedData, SparkRestDerivedData}
import com.linkedin.drelephant.spark.fetchers.statusapiv1.{ApplicationAttemptInfo, ApplicationInfo, ExecutorSummary}
import org.apache.spark.scheduler.SparkListenerEnvironmentUpdate
import org.scalatest.{FunSpec, Matchers}

class SparkComboMetricsAggregatorTest extends FunSpec with Matchers {
  import SparkComboMetricsAggregatorTest._

  describe("SparkComboMetricsAggregator") {
    val aggregatorConfigurationData = newFakeAggregatorConfigurationData()

    val appId = "application_1"

    val applicationInfo = {
      val applicationAttemptInfo = {
        val now = System.currentTimeMillis
        val duration = 8000000L
        newFakeApplicationAttemptInfo(Some("1"), startTime = new Date(now - duration), endTime = new Date(now))
      }
      new ApplicationInfo(appId, name = "app", Seq(applicationAttemptInfo))
    }

    val restDerivedData = {
      val executorSummaries = Seq(
        newFakeExecutorSummary(id = "1", totalDuration = 1000000L),
        newFakeExecutorSummary(id = "2", totalDuration = 3000000L)
      )
      SparkRestDerivedData(
        applicationInfo,
        jobDatas = Seq.empty,
        stageDatas = Seq.empty,
        executorSummaries
      )
    }

    val logDerivedData = {
      val environmentUpdate = newFakeSparkListenerEnvironmentUpdate(
        Map(
          "spark.serializer" -> "org.apache.spark.serializer.KryoSerializer",
          "spark.storage.memoryFraction" -> "0.3",
          "spark.driver.memory" -> "2G",
          "spark.executor.instances" -> "2",
          "spark.executor.memory" -> "4g",
          "spark.shuffle.memoryFraction" -> "0.5"
        )
      )
      SparkLogDerivedData(environmentUpdate)
    }

    val data = SparkComboApplicationData(appId, restDerivedData, Some(logDerivedData))

    val aggregator = new SparkComboMetricsAggregator(aggregatorConfigurationData)
    aggregator.aggregate(data)

    val result = aggregator.getResult

    it("calculates resources used") {
      val executorMemoryMb = 4096
      val totalExecutorTaskTimeSeconds = 1000 + 3000
      result.getResourceUsed should be(executorMemoryMb * totalExecutorTaskTimeSeconds)
    }

    it("calculates resources wasted") {
      val totalExecutorMemoryMb = 2 * 4096
      val applicationDurationSeconds = 8000

      val executorMemoryMb = 4096
      val totalExecutorTaskTimeSeconds = 1000 + 3000

      result.getResourceWasted should be(
        (totalExecutorMemoryMb * applicationDurationSeconds) - (executorMemoryMb * totalExecutorTaskTimeSeconds)
      )
    }

    it("doesn't calculate total delay") {
      result.getTotalDelay should be(0L)
    }
  }
}

object SparkComboMetricsAggregatorTest {
  def newFakeAggregatorConfigurationData(): AggregatorConfigurationData =
    new AggregatorConfigurationData("org.apache.spark.SparkMetricsAggregator", new ApplicationType("SPARK"), null)

  def newFakeSparkListenerEnvironmentUpdate(appConfigurationProperties: Map[String, String]): SparkListenerEnvironmentUpdate =
    SparkListenerEnvironmentUpdate(Map("Spark Properties" -> appConfigurationProperties.toSeq))

  def newFakeApplicationAttemptInfo(
    attemptId: Option[String],
    startTime: Date,
    endTime: Date
  ): ApplicationAttemptInfo = new ApplicationAttemptInfo(
    attemptId,
    startTime,
    endTime,
    sparkUser = "foo",
    completed = true
  )

  def newFakeExecutorSummary(
    id: String,
    totalDuration: Long
  ): ExecutorSummary = new ExecutorSummary(
    id,
    hostPort = "",
    rddBlocks = 0,
    memoryUsed = 0,
    diskUsed = 0,
    activeTasks = 0,
    failedTasks = 0,
    completedTasks = 0,
    totalTasks = 0,
    totalDuration,
    totalInputBytes = 0,
    totalShuffleRead = 0,
    totalShuffleWrite = 0,
    maxMemory = 0,
    executorLogs = Map.empty
  )
}