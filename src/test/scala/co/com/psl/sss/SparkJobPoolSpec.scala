package co.com.psl.sss

import org.apache.spark.scheduler.{SparkListener, SparkListenerJobEnd, SparkListenerJobStart}
import org.apache.spark.{JobExecutionStatus, SparkConf, SparkContext}
import org.apache.spark.sql.SparkSession
import org.scalatest.FlatSpec

class SparkJobPoolSpec extends FlatSpec {

  /**
    *
    */
  trait FixtureSparkJobPool {

    val sparkConfig: SparkConf = new SparkConf()
    sparkConfig.set("spark.master", "local")
    val sparkSession: SparkSession = SparkSession
      .builder()
      .config(sparkConfig)
      .getOrCreate()
    val fixtureSparkServerContext = SparkServerContext(sparkConfig,sparkSession, sparkSession.sparkContext)
    val sparkJobPool = new SparkJobPoolImpl(fixtureSparkServerContext)
  }

  /**
    *
    */
  trait FixtureSampleSparkJobs {
      // It is expected to run a Job with four seconds running time
    val longRunningSparkJob = new SparkJob {
      override def name(): String = "Long Running Spark Job"

      override def main(sparkSession: SparkSession): Unit = {
        val rdd1 = sparkSession.sparkContext.parallelize(Array.ofDim[Int](4))
        val sum1 = rdd1.map(_ => {
          Thread.sleep(500); 1
        }).reduce(_ + _)
        val rdd2 = sparkSession.sparkContext.parallelize(Array.ofDim[Int](4))
        val sum2 = rdd2.map(_ => {
          Thread.sleep(500); 1
        }).reduce(_ + _)
        println(sum1+sum2)
      }
    }
      // It is expected to run a fast Job
    val fastRunningSparkJob = new SparkJob {
      override def name(): String = "Fast Running Spark Job"

      override def main(sparkSession: SparkSession): Unit = {
        val rdd1 = sparkSession.sparkContext.parallelize(Array(1, 2, 3, 4))
        val sum1 = rdd1.reduce(_ + _)
        val rdd2 = sparkSession.sparkContext.parallelize(Array(1, 2, 3, 4, 5))
        val sum2 = rdd2.reduce(_ + _)
        val rdd3 = sparkSession.sparkContext.parallelize(Array(1, 2, 3, 4, 5, 6))
        val sum3 = rdd3.reduce(_ + _)
        println(sum1+sum2+sum3)
      }
    }
  }

  "The SparkJobPool.start method" should "start a SparkJob and returns its related ID" in
    new FixtureSparkJobPool with FixtureSampleSparkJobs {

    val id = sparkJobPool.start(fastRunningSparkJob, true)
    fixtureSparkServerContext.sparkSession.stop()

    assertResult(true) {
      Set(sparkJobPool.getAllJobsIDs()).contains(Array(id))
    }
  }

  "The SparkJobPool.start method" should "start a Native Spark Job" in
    new FixtureSparkJobPool with FixtureSampleSparkJobs {

    var someJobStarted = false

    fixtureSparkServerContext.sparkContext.addSparkListener(new SparkListener {
      override def onJobStart(jobStart: SparkListenerJobStart): Unit = {
        someJobStarted = true
      }
    })
    sparkJobPool.start(fastRunningSparkJob, true)
    fixtureSparkServerContext.sparkSession.stop()
    assert(someJobStarted)
  }

  "The SparkJobPool.stop method" should "stop a SparkJob" in
    new FixtureSparkJobPool with FixtureSampleSparkJobs {

    var jobFailed : Option[Boolean] = None

    fixtureSparkServerContext.sparkContext.addSparkListener(new SparkListener {
      override def onJobEnd(jobEnd: SparkListenerJobEnd): Unit = {
        val info = fixtureSparkServerContext.sparkContext.statusTracker.getJobInfo(jobEnd.jobId)
        jobFailed = jobFailed match {
          case Some(x) => Some(x&&info.get.status() == JobExecutionStatus.FAILED)
          case None => Some(info.get.status() == JobExecutionStatus.FAILED)
        }
      }
    })
      // I am trying to find a better way of doing this test!
      // At least it marks the task as FAILED without doubts
    val id = sparkJobPool.start(longRunningSparkJob)
    Thread.sleep(1000)
    sparkJobPool.stop(id)
    Thread.sleep(1000)

    fixtureSparkServerContext.sparkSession.stop()
    assert(jobFailed == Some(true))
  }

  "The SparkJobPool.getAllJobsIDs method" should " returns a sequence of length x if x job(s) have been started" in
    new FixtureSparkJobPool with FixtureSampleSparkJobs {

    for (numStartedJobs <- 0 to 10) {
      assert(sparkJobPool.getAllJobsIDs().length == numStartedJobs)
      sparkJobPool.start(fastRunningSparkJob, true)
    }
    fixtureSparkServerContext.sparkSession.stop()
  }

  "The SparkJobPool.getNativeSparkJobs method" should " returns the same set of ids generated by the underlying spark server" in
    new FixtureSparkJobPool with FixtureSampleSparkJobs {

    var nativeSparkIds : Set[Int] = Set()

    fixtureSparkServerContext.sparkContext.addSparkListener(new SparkListener {
      override def onJobStart(jobStart: SparkListenerJobStart): Unit = {
        nativeSparkIds += jobStart.jobId
      }
    })
    val id = sparkJobPool.start(fastRunningSparkJob, true)
    val jobPoolIds = sparkJobPool.getNativeSparkJobs(id)

    assert(jobPoolIds.length == nativeSparkIds.size)

    for (j <- jobPoolIds) {
      assert(nativeSparkIds.contains(j.jobId()))
    }
    fixtureSparkServerContext.sparkSession.stop()
  }
}
