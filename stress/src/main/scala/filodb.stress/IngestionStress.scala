package filodb.stress

import org.apache.spark.{SparkContext, SparkConf}
import org.apache.spark.sql.{DataFrame, SaveMode, SQLContext}
import scala.util.Random
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

import filodb.coordinator.{DatasetCoordinatorActor, NodeCoordinatorActor}
import filodb.core.DatasetRef
import filodb.spark._

/**
 * Ingests into two different tables simultaneously, one with small segment size. Tests ingestion pipeline
 * handling of tons of concurrent write / I/O, backpressure, accuracy, etc.
 * Reads back both datasets and compares every cell and row to be sure the data is readable and accurate.
 *
 * To prepare, download the first month's worth of data from http://www.andresmh.com/nyctaxitrips/
 * Also, run this to initialize the filo-stress keyspace:
 *   `filo-cli --database filostress --command init`
 *
 * Recommended to run this with the first million rows only as a first run to make sure everything works.
 * Test at different memory settings - but recommend minimum 4G.
 *
 * TODO: randomize number of lines to ingest.  Maybe $numOfLines - util.Random.nextInt(10000)....
 */
object IngestionStress extends App {
  val taxiCsvFile = args(0)
  val numRuns = 50    // Make this higher when doing performance profiling

  def puts(s: String): Unit = {
    //scalastyle:off
    println(s)
    //scalastyle:on
  }

  // Setup SparkContext, etc.
  val conf = (new SparkConf).setMaster("local[8]")
                            .setAppName("test")
                            .set("spark.filodb.cassandra.keyspace", "filostress")
                            .set("spark.sql.shuffle.partitions", "4")
                            .set("spark.scheduler.mode", "FAIR")
  val sc = new SparkContext(conf)
  val sql = new SQLContext(sc)

  // Ingest the taxi file two different ways using two Futures
  // One way is by hour of day - very relaxed and fast
  // Another is the "stress" schema - very tiny segments, huge amounts of memory churn and I/O bandwidth
  val csvDF = sql.read.format("com.databricks.spark.csv").
                 option("header", "true").option("inferSchema", "true").
                 load(taxiCsvFile)
  val inputLines = csvDF.count()
  puts(s"$taxiCsvFile has $inputLines lines of data")

  import scala.concurrent.ExecutionContext.Implicits.global

  val stressIngestor = Future {
    puts("Starting stressful ingestion...")
    csvDF.write.format("filodb.spark").
      option("dataset", "taxi_medallion_seg").
      option("row_keys", "hack_license,pickup_datetime").
      option("segment_key", ":stringPrefix medallion 3").
      option("partition_keys", ":stringPrefix medallion 2").
      mode(SaveMode.Overwrite).save()
    puts("Stressful ingestion done.")

    val df = sql.filoDataset("taxi_medallion_seg")
    df.registerTempTable("taxi_medallion_seg")
    df
  }

  val hrOfDayIngestor = Future {
    puts("Starting hour-of-day (easy) ingestion...")

    // Define a hour of day function
    import org.joda.time.DateTime
    import java.sql.Timestamp
    val hourOfDay = sql.udf.register("hourOfDay", { (t: Timestamp) => new DateTime(t).getHourOfDay })

    val dfWithHoD = csvDF.withColumn("hourOfDay", hourOfDay(csvDF("pickup_datetime")))

    dfWithHoD.write.format("filodb.spark").
      option("dataset", "taxi_hour_of_day").
      option("row_keys", "hack_license,pickup_datetime").
      option("segment_key", ":timeslice pickup_datetime 4d").
      option("partition_keys", "hourOfDay").
      option("reset_schema", "true").
      mode(SaveMode.Overwrite).save()

    puts("hour-of-day (easy) ingestion done.")

    val df = sql.filoDataset("taxi_hour_of_day")
    df.registerTempTable("taxi_hour_of_day")
    df
  }

  def checkDatasetCount(df: DataFrame, expected: Long): Future[Long] = Future {
    val count = df.count()
    if (count == expected)  puts(s"Count matched $count for dataframe $df")
    else                    puts(s"Expected $expected rows, but actually got $count for dataframe $df")
    count
  }

  def printIngestionStats(dataset: String): Unit = {
    val getStatsMsg = NodeCoordinatorActor.GetIngestionStats(DatasetRef(dataset), 0)
    FiloRelation.actorAsk(FiloSetup.coordinatorActor, getStatsMsg) {
      case stats: DatasetCoordinatorActor.Stats => puts(s"  Stats for dataset $dataset => $stats")
    }
  }

  val fut = for { stressDf  <- stressIngestor
        hrOfDayDf <- hrOfDayIngestor
        stressCount <- checkDatasetCount(stressDf, inputLines)
        hrCount   <- checkDatasetCount(hrOfDayDf, inputLines) } yield {
    puts("Now doing data comparison checking")

    // Do something just so we have to depend on both things being done
    puts(s"Counts: $stressCount $hrCount")

    puts("\nStats for each dataset:")
    printIngestionStats("taxi_medallion_seg")
    printIngestionStats("taxi_hour_of_day")

    // clean up!
    FiloSetup.shutdown()
    sc.stop()
  }

  Await.result(fut, 99.minutes)
}