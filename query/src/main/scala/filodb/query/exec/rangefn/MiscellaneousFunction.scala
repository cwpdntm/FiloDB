package filodb.query.exec.rangefn

import java.util.regex.{Pattern, PatternSyntaxException}

import monix.reactive.Observable

import filodb.core.query.{CustomRangeVectorKey, IteratorBackedRangeVector, RangeVector, RangeVectorKey}
import filodb.memory.format.ZeroCopyUTF8String

trait MiscellaneousFunction {
  def execute(source: Observable[RangeVector]): Observable[RangeVector]
}

final case class LabelReplaceFunction(funcParams: Seq[String]) extends MiscellaneousFunction {
  val labelIdentifier: String = "[a-zA-Z_][a-zA-Z0-9_:\\-\\.]*"

  require(funcParams.size == 4,
    "Cannot use LabelReplace without function parameters: " +
      "instant-vector, dst_label string, replacement string, src_label string, regex string")

  val dstLabel: String = funcParams(0)
  val replacementString: String = funcParams(1)
  val srcLabel: String = funcParams(2)
  val regexString: String = funcParams(3)

  require(dstLabel.matches(labelIdentifier), "Invalid destination label name")

  try {
    Pattern.compile(regexString)
  }
  catch {
    case ex: PatternSyntaxException => {
      throw new IllegalArgumentException("Invalid Regular Expression for label_replace", ex)
    }
  }

  override def execute(source: Observable[RangeVector]): Observable[RangeVector] = {
    source.map { rv =>
      val newLabel = labelReplaceImpl(rv.key, funcParams)
      IteratorBackedRangeVector(newLabel, rv.rows)
    }
  }

  private def labelReplaceImpl(rangeVectorKey: RangeVectorKey, funcParams: Seq[Any]): RangeVectorKey = {

    val value: ZeroCopyUTF8String = if (rangeVectorKey.labelValues.contains(ZeroCopyUTF8String(srcLabel))) {
      rangeVectorKey.labelValues.get(ZeroCopyUTF8String(srcLabel)).get
    }
    else {
      // Assign dummy value as label_replace should overwrite destination label if the source label is empty but matched
      ZeroCopyUTF8String.empty
    }

    // Pattern is not deserialized correctly if it is a data member
    val pattern = Pattern.compile(regexString)
    val matcher = pattern.matcher(value.toString)
    if (matcher.matches()) {
      var labelReplaceValue = replacementString
      for (index <- 1 to matcher.groupCount()) {
        labelReplaceValue = labelReplaceValue.replace(s"$$$index", matcher.group(index))
      }
      // Remove groups which are not present
      labelReplaceValue = labelReplaceValue.replaceAll("\\$[A-Za-z0-9]+", "")

      if (labelReplaceValue.length > 0) {
        return CustomRangeVectorKey(rangeVectorKey.labelValues.
          updated(ZeroCopyUTF8String(dstLabel), ZeroCopyUTF8String(labelReplaceValue)), rangeVectorKey.sourceShards)
      }
      else {
        // Drop label if new value is empty
        return CustomRangeVectorKey(rangeVectorKey.labelValues -
          ZeroCopyUTF8String(dstLabel), rangeVectorKey.sourceShards)
      }
    }

    return rangeVectorKey;
  }
}

final case class LabelJoinFunction(funcParams: Seq[String]) extends MiscellaneousFunction {
  val labelIdentifier: String = "[a-zA-Z_][a-zA-Z0-9_:\\-\\.]*"

  require(funcParams.size >= 2,
    "expected at least 3 argument(s) in call to label_join")

  val dstLabel: String = funcParams(0)
  val separator: String = funcParams(1)

  require(dstLabel.asInstanceOf[String].matches(labelIdentifier), "Invalid destination label name in label_join()")
  var srcLabel =
    funcParams.drop(2).map { x =>
      require(x.matches(labelIdentifier),
        "Invalid source label name in label_join()")
      x
    }

  override def execute(source: Observable[RangeVector]): Observable[RangeVector] = {
    source.map { rv =>
      val newLabel = labelJoinImpl(rv.key)
      IteratorBackedRangeVector(newLabel, rv.rows)
    }
  }

  private def labelJoinImpl(rangeVectorKey: RangeVectorKey): RangeVectorKey = {

    val srcLabelValues = srcLabel.map(x=> rangeVectorKey.labelValues.get(ZeroCopyUTF8String(x)).
      map(_.toString).getOrElse(""))

    val labelJoinValue = srcLabelValues.mkString(separator)

    if (labelJoinValue.length > 0) {
      return CustomRangeVectorKey(rangeVectorKey.labelValues.
        updated(ZeroCopyUTF8String(dstLabel), ZeroCopyUTF8String(labelJoinValue)), rangeVectorKey.sourceShards)
    }
    else {
      // Drop label if new value is empty
      return CustomRangeVectorKey(rangeVectorKey.labelValues -
        ZeroCopyUTF8String(dstLabel), rangeVectorKey.sourceShards)
    }
  }
}
