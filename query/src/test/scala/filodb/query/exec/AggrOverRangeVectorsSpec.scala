package filodb.query.exec

import scala.annotation.tailrec
import scala.util.Random

import com.typesafe.config.ConfigFactory
import monix.execution.Scheduler.Implicits.global
import monix.reactive.Observable
import org.scalatest.{FunSpec, Matchers}
import org.scalatest.concurrent.ScalaFutures

import filodb.core.query.{CustomRangeVectorKey, RangeVector, RangeVectorKey}
import filodb.memory.format.{RowReader, ZeroCopyUTF8String}
import filodb.query.{AggregationOperator, QueryConfig}

class AggrOverRangeVectorsSpec extends FunSpec with Matchers with ScalaFutures {

  val config = ConfigFactory.load("application_test.conf").getConfig("filodb")
  val queryConfig = new QueryConfig(config.getConfig("query"))
  val rand = new Random()
  val error = 0.00000001d

  it ("should work without grouping") {
    val ignoreKey = CustomRangeVectorKey(
      Map(ZeroCopyUTF8String("ignore") -> ZeroCopyUTF8String("ignore")))

    val noKey = CustomRangeVectorKey(Map.empty)
    def noGrouping(rv: RangeVector): RangeVectorKey = noKey

    val samples: Array[RangeVector] = Array.fill(100)(new RangeVector {
      val data = Stream.from(0).map { n=>
        new TransientRow(n.toLong, rand.nextDouble())
      }.take(20)
      override def key: RangeVectorKey = ignoreKey
      override def rows: Iterator[RowReader] = data.iterator
    })

    // Sum
    val resultObs = RangeVectorAggregator.mapReduce(AggregationOperator.Sum,
      Nil, false, Observable.fromIterable(samples), noGrouping)
    val result = resultObs.toListL.runAsync.futureValue
    result.size shouldEqual 1
    result(0).key shouldEqual noKey
    val readyToAggr = samples.toList.map(_.rows.toList).transpose
    compareIter(result(0).rows.map(_.getDouble(1)), readyToAggr.map(_.map(_.getDouble(1)).sum).iterator)

    // Min
    val resultObs2 = RangeVectorAggregator.mapReduce(AggregationOperator.Min,
      Nil, false, Observable.fromIterable(samples), noGrouping)
    val result2 = resultObs2.toListL.runAsync.futureValue
    result2.size shouldEqual 1
    result2(0).key shouldEqual noKey
    val readyToAggr2 = samples.toList.map(_.rows.toList).transpose
    compareIter(result2(0).rows.map(_.getDouble(1)), readyToAggr2.map(_.map(_.getDouble(1)).min).iterator)

    // Count
    val resultObs3a = RangeVectorAggregator.mapReduce(AggregationOperator.Count,
      Nil, false, Observable.fromIterable(samples), noGrouping)
    val resultObs3 = RangeVectorAggregator.mapReduce(AggregationOperator.Count,
      Nil, true, resultObs3a, rv=>rv.key)
    val result3 = resultObs3.toListL.runAsync.futureValue
    result3.size shouldEqual 1
    result3(0).key shouldEqual noKey
    val readyToAggr3 = samples.toList.map(_.rows.toList).transpose
    compareIter(result3(0).rows.map(_.getDouble(1)), readyToAggr3.map(_.map(_.getDouble(1)).size.toDouble).iterator)

    // Avg
    val resultObs4a = RangeVectorAggregator.mapReduce(AggregationOperator.Avg,
      Nil, false, Observable.fromIterable(samples), noGrouping)
    val resultObs4 = RangeVectorAggregator.mapReduce(AggregationOperator.Avg,
      Nil, true, resultObs4a, rv=>rv.key)
    val result4 = resultObs4.toListL.runAsync.futureValue
    result4.size shouldEqual 1
    result4(0).key shouldEqual noKey
    val readyToAggr4 = samples.toList.map(_.rows.toList).transpose
    compareIter(result4(0).rows.map(_.getDouble(1)), readyToAggr4.map { v =>
      v.map(_.getDouble(1)).sum / v.map(_.getDouble(1)).size
    }.iterator)

    // BottomK
    val resultObs5a = RangeVectorAggregator.mapReduce(AggregationOperator.BottomK,
      Seq(3.0), false, Observable.fromIterable(samples), noGrouping)
    val resultObs5 = RangeVectorAggregator.mapReduce(AggregationOperator.BottomK,
      Seq(3.0), true, resultObs5a, rv=>rv.key)
    val result5 = resultObs5.toListL.runAsync.futureValue
    result5.size shouldEqual 1
    result5(0).key shouldEqual noKey
    val readyToAggr5 = samples.toList.map(_.rows.toList).transpose
    compareIter2(result5(0).rows.map(r=> Set(r.getDouble(2), r.getDouble(4), r.getDouble(6))),
      readyToAggr5.map { v =>
      v.map(_.getDouble(1)).sorted.take(3).toSet
    }.iterator)

    // TopK
    val resultObs6a = RangeVectorAggregator.mapReduce(AggregationOperator.TopK,
      Seq(3.0), false, Observable.fromIterable(samples), noGrouping)
    val resultObs6 = RangeVectorAggregator.mapReduce(AggregationOperator.TopK,
      Seq(3.0), true, resultObs6a, rv=>rv.key)
    val result6 = resultObs6.toListL.runAsync.futureValue
    result6.size shouldEqual 1
    result6(0).key shouldEqual noKey
    val readyToAggr6 = samples.toList.map(_.rows.toList).transpose
    compareIter2(result6(0).rows.map(r=> Set(r.getDouble(2), r.getDouble(4), r.getDouble(6))),
      readyToAggr6.map { v =>
        v.map(_.getDouble(1)).sorted(Ordering[Double].reverse).take(3).toSet
      }.iterator)

  }

  it ("should ignore NaN while aggregating") {
    val ignoreKey = CustomRangeVectorKey(
      Map(ZeroCopyUTF8String("ignore") -> ZeroCopyUTF8String("ignore")))

    val noKey = CustomRangeVectorKey(Map.empty)
    def noGrouping(rv: RangeVector): RangeVectorKey = noKey

    val samples: Array[RangeVector] = Array(
      new RangeVector {
        override def key: RangeVectorKey = ignoreKey
        override def rows: Iterator[RowReader] = Seq(new TransientRow(1L, Double.NaN),
                                                   new TransientRow(2L, 5.6d)).iterator
      },
      new RangeVector {
        override def key: RangeVectorKey = ignoreKey
        override def rows: Iterator[RowReader] = Seq(new TransientRow(1L, 4.6d),
          new TransientRow(2L, 4.4d)).iterator
      },
      new RangeVector {
        override def key: RangeVectorKey = ignoreKey
        override def rows: Iterator[RowReader] = Seq(new TransientRow(1L, 2.1d),
          new TransientRow(2L, 5.4d)).iterator
      }
    )

    // Sum
    val resultObs = RangeVectorAggregator.mapReduce(AggregationOperator.Sum,
      Nil, false, Observable.fromIterable(samples), noGrouping)
    val result = resultObs.toListL.runAsync.futureValue
    result.size shouldEqual 1
    result(0).key shouldEqual noKey
    compareIter(result(0).rows.map(_.getDouble(1)), Seq(6.7d, 15.4d).iterator)

    // Min
    val resultObs2 = RangeVectorAggregator.mapReduce(AggregationOperator.Min,
      Nil, false, Observable.fromIterable(samples), noGrouping)
    val result2 = resultObs2.toListL.runAsync.futureValue
    result2.size shouldEqual 1
    result2(0).key shouldEqual noKey
    compareIter(result2(0).rows.map(_.getDouble(1)), Seq(2.1d, 4.4d).iterator)

    // Count
    val resultObs3a = RangeVectorAggregator.mapReduce(AggregationOperator.Count,
      Nil, false, Observable.fromIterable(samples), noGrouping)
    val resultObs3 = RangeVectorAggregator.mapReduce(AggregationOperator.Count,
      Nil, true, resultObs3a, rv=>rv.key)
    val result3 = resultObs3.toListL.runAsync.futureValue
    result3.size shouldEqual 1
    result3(0).key shouldEqual noKey
    compareIter(result3(0).rows.map(_.getDouble(1)), Seq(2d, 3d).iterator)

    // Avg
    val resultObs4a = RangeVectorAggregator.mapReduce(AggregationOperator.Avg,
      Nil, false, Observable.fromIterable(samples), noGrouping)
    val resultObs4 = RangeVectorAggregator.mapReduce(AggregationOperator.Avg,
      Nil, true, resultObs4a, rv=>rv.key)
    val result4 = resultObs4.toListL.runAsync.futureValue
    result4.size shouldEqual 1
    result4(0).key shouldEqual noKey
    compareIter(result4(0).rows.map(_.getDouble(1)), Seq(3.35d, 5.133333333333333d).iterator)

    // BottomK
    val resultObs5a = RangeVectorAggregator.mapReduce(AggregationOperator.BottomK,
      Seq(2.0), false, Observable.fromIterable(samples), noGrouping)
    val resultObs5 = RangeVectorAggregator.mapReduce(AggregationOperator.BottomK,
      Seq(2.0), true, resultObs5a, rv=>rv.key)
    val result5 = resultObs5.toListL.runAsync.futureValue
    result5.size shouldEqual 1
    result5(0).key shouldEqual noKey
    compareIter2(result5(0).rows.map(r=> Set(r.getDouble(2), r.getDouble(4))),
      Seq(Set(2.1d, 4.6d), Set(4.4, 5.4d)).iterator)

    // TopK
    val resultObs6a = RangeVectorAggregator.mapReduce(AggregationOperator.TopK,
      Seq(2.0), false, Observable.fromIterable(samples), noGrouping)
    val resultObs6 = RangeVectorAggregator.mapReduce(AggregationOperator.TopK,
      Seq(2.0), true, resultObs6a, rv=>rv.key)
    val result6 = resultObs6.toListL.runAsync.futureValue
    result6.size shouldEqual 1
    result6(0).key shouldEqual noKey
    compareIter2(result6(0).rows.map(r=> Set(r.getDouble(2), r.getDouble(4))),
      Seq(Set(4.6d, 2.1d), Set(5.6, 5.4d)).iterator)


  }

  @tailrec
  final private def compareIter(it1: Iterator[Double], it2: Iterator[Double]) : Unit = {
    (it1.hasNext, it2.hasNext) match{
      case (true, true) =>
        val v1 = it1.next()
        val v2 = it2.next()
        if (v1.isNaN) v2.isNaN shouldEqual true
        else Math.abs(v1-v2) should be < error
        compareIter(it1, it2)
      case (false, false) => Unit
      case _ => fail("Unequal lengths")
    }
  }

  @tailrec
  final private def compareIter2(it1: Iterator[Set[Double]], it2: Iterator[Set[Double]]) : Unit = {
    (it1.hasNext, it2.hasNext) match{
      case (true, true) =>
        val v1 = it1.next()
        val v2 = it2.next()
        v1 shouldEqual v2
        compareIter2(it1, it2)
      case (false, false) => Unit
      case _ => fail("Unequal lengths")
    }
  }
}