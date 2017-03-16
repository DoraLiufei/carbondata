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

package org.apache.spark.sql

import org.apache.spark.{Partition, TaskContext}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.errors.attachTree
import org.apache.spark.sql.catalyst.expressions.{AttributeSet, _}
import org.apache.spark.sql.catalyst.expressions.codegen.{CodegenContext, ExprCode, ExpressionCanonicalizer}
import org.apache.spark.sql.catalyst.plans.physical.Partitioning
import org.apache.spark.sql.execution.{CodegenSupport, SparkPlan, UnaryExecNode}
import org.apache.spark.sql.hive.{CarbonMetastoreTypes, CarbonRelation}
import org.apache.spark.sql.optimizer.CarbonDecoderRelation
import org.apache.spark.sql.types._

import org.apache.carbondata.core.cache.{Cache, CacheProvider, CacheType}
import org.apache.carbondata.core.cache.dictionary.{Dictionary, DictionaryColumnUniqueIdentifier}
import org.apache.carbondata.core.metadata.{AbsoluteTableIdentifier, ColumnIdentifier}
import org.apache.carbondata.core.metadata.datatype.DataType
import org.apache.carbondata.core.metadata.encoder.Encoding
import org.apache.carbondata.core.metadata.schema.table.column.CarbonDimension
import org.apache.carbondata.core.util.DataTypeUtil
import org.apache.carbondata.spark.CarbonAliasDecoderRelation

/**
 * It decodes the data.
 *
 */
case class CarbonDictionaryDecoder(
    relations: Seq[CarbonDecoderRelation],
    profile: CarbonProfile,
    aliasMap: CarbonAliasDecoderRelation,
    child: SparkPlan)
  extends UnaryExecNode with CodegenSupport {

  override val output: Seq[Attribute] = {
    child.output.map { a =>
      val attr = aliasMap.getOrElse(a, a)
      val relation = relations.find(p => p.contains(attr))
      if (relation.isDefined && canBeDecoded(attr)) {
        val carbonTable = relation.get.carbonRelation.carbonRelation.metaData.carbonTable
        val carbonDimension = carbonTable
          .getDimensionByName(carbonTable.getFactTableName, attr.name)
        if (carbonDimension != null &&
            carbonDimension.hasEncoding(Encoding.DICTIONARY) &&
            !carbonDimension.hasEncoding(Encoding.DIRECT_DICTIONARY) &&
            !carbonDimension.isComplex()) {
          val newAttr = AttributeReference(a.name,
            convertCarbonToSparkDataType(carbonDimension,
              relation.get.carbonRelation.carbonRelation),
            a.nullable,
            a.metadata)(a.exprId).asInstanceOf[Attribute]
          newAttr
        } else {
          a
        }
      } else {
        a
      }
    }
  }


  override def outputPartitioning: Partitioning = {
    child.outputPartitioning
  }

  def canBeDecoded(attr: Attribute): Boolean = {
    profile match {
      case ip: IncludeProfile if ip.attributes.nonEmpty =>
        ip.attributes
          .exists(a => a.name.equalsIgnoreCase(attr.name) && a.exprId == attr.exprId)
      case ep: ExcludeProfile =>
        !ep.attributes
          .exists(a => a.name.equalsIgnoreCase(attr.name) && a.exprId == attr.exprId)
      case _ => true
    }
  }

  def convertCarbonToSparkDataType(carbonDimension: CarbonDimension,
      relation: CarbonRelation): types.DataType = {
    carbonDimension.getDataType match {
      case DataType.STRING => StringType
      case DataType.SHORT => ShortType
      case DataType.INT => IntegerType
      case DataType.LONG => LongType
      case DataType.DOUBLE => DoubleType
      case DataType.BOOLEAN => BooleanType
      case DataType.DECIMAL =>
        val scale: Int = carbonDimension.getColumnSchema.getScale
        val precision: Int = carbonDimension.getColumnSchema.getPrecision
        if (scale == 0 && precision == 0) {
          DecimalType(18, 2)
        } else {
          DecimalType(precision, scale)
        }
      case DataType.TIMESTAMP => TimestampType
      case DataType.DATE => DateType
      case DataType.STRUCT =>
        CarbonMetastoreTypes
          .toDataType(s"struct<${ relation.getStructChildren(carbonDimension.getColName) }>")
      case DataType.ARRAY =>
        CarbonMetastoreTypes
          .toDataType(s"array<${ relation.getArrayChildren(carbonDimension.getColName) }>")
    }
  }

  val getDictionaryColumnIds: Array[(String, ColumnIdentifier, CarbonDimension)] = {
    child.output.map { attribute =>
      val attr = aliasMap.getOrElse(attribute, attribute)
      val relation = relations.find(p => p.contains(attr))
      if (relation.isDefined && canBeDecoded(attr)) {
        val carbonTable = relation.get.carbonRelation.carbonRelation.metaData.carbonTable
        val carbonDimension =
          carbonTable.getDimensionByName(carbonTable.getFactTableName, attr.name)
        if (carbonDimension != null &&
            carbonDimension.hasEncoding(Encoding.DICTIONARY) &&
            !carbonDimension.hasEncoding(Encoding.DIRECT_DICTIONARY) &&
            !carbonDimension.isComplex) {
          (carbonTable.getFactTableName, carbonDimension.getColumnIdentifier,
            carbonDimension)
        } else {
          (null, null, null)
        }
      } else {
        (null, null, null)
      }
    }.toArray
  }

  override def doExecute(): RDD[InternalRow] = {
    attachTree(this, "execute") {
      val storePath = CarbonEnv.get.carbonMetastore.storePath
      val absoluteTableIdentifiers = relations.map { relation =>
        val carbonTable = relation.carbonRelation.carbonRelation.metaData.carbonTable
        (carbonTable.getFactTableName, carbonTable.getAbsoluteTableIdentifier)
      }.toMap

      if (isRequiredToDecode) {
        val dataTypes = child.output.map { attr => attr.dataType }
        child.execute().mapPartitions { iter =>
          val cacheProvider: CacheProvider = CacheProvider.getInstance
          val forwardDictionaryCache: Cache[DictionaryColumnUniqueIdentifier, Dictionary] =
            cacheProvider.createCache(CacheType.FORWARD_DICTIONARY, storePath)
          val dicts: Seq[Dictionary] = getDictionary(absoluteTableIdentifiers,
            forwardDictionaryCache)
          val dictIndex = dicts.zipWithIndex.filter(x => x._1 != null).map(x => x._2)
          // add a task completion listener to clear dictionary that is a decisive factor for
          // LRU eviction policy
          val dictionaryTaskCleaner = TaskContext.get
          dictionaryTaskCleaner.addTaskCompletionListener(_ =>
            dicts.foreach { dictionary =>
              if (null != dictionary) {
                dictionary.clear()
              }
            }
          )
          new Iterator[InternalRow] {
            val unsafeProjection = UnsafeProjection.create(output.map(_.dataType).toArray)
            var flag = true
            var total = 0L

            override final def hasNext: Boolean = iter.hasNext

            override final def next(): InternalRow = {
              val row: InternalRow = iter.next()
              val data = row.toSeq(dataTypes).toArray
              dictIndex.foreach { index =>
                if (data(index) != null) {
                  data(index) = DataTypeUtil.getDataBasedOnDataType(dicts(index)
                    .getDictionaryValueForKeyInBytes(data(index).asInstanceOf[Int]),
                    getDictionaryColumnIds(index)._3)
                }
              }
              unsafeProjection(new GenericInternalRow(data))
            }
          }
        }
      } else {
        child.execute()
      }
    }
  }

  override def doConsume(ctx: CodegenContext, input: Seq[ExprCode], row: ExprCode): String = {

    val storePath = CarbonEnv.get.carbonMetastore.storePath
    val absoluteTableIdentifiers = relations.map { relation =>
      val carbonTable = relation.carbonRelation.carbonRelation.metaData.carbonTable
      (carbonTable.getFactTableName, carbonTable.getAbsoluteTableIdentifier)
    }.toMap

    if (isRequiredToDecode) {
      val cacheProvider: CacheProvider = CacheProvider.getInstance
      val forwardDictionaryCache: Cache[DictionaryColumnUniqueIdentifier, Dictionary] =
        cacheProvider.createCache(CacheType.FORWARD_DICTIONARY, storePath)
      val dicts: Seq[ForwardDictionaryWrapper] = getDictionaryWrapper(absoluteTableIdentifiers,
        forwardDictionaryCache, storePath)

      val exprs = child.output.map { exp =>
        ExpressionCanonicalizer.execute(BindReferences.bindReference(exp, child.output))
      }
      ctx.currentVars = input
      val resultVars = exprs.zipWithIndex.map { case (expr, index) =>
        if (dicts(index) != null) {
          val ev = expr.genCode(ctx)
          val value = ctx.freshName("value")
          val valueIntern = ctx.freshName("valueIntern")
          val isNull = ctx.freshName("isNull")
          val dictsRef = ctx.addReferenceObj("dictsRef", dicts(index))
          var code =
            s"""
               |${ev.code}
             """.stripMargin
          code +=
            s"""
             |boolean $isNull = false;
             |byte[] $valueIntern = $dictsRef.getDictionaryValueForKeyInBytes(${ ev.value });
             |if (java.util.Arrays.equals(org.apache.carbondata.core.constants
             |.CarbonCommonConstants.MEMBER_DEFAULT_VAL_ARRAY, $valueIntern)) {
             |  $isNull = true;
             |}
             """.stripMargin

            val caseCode = getDictionaryColumnIds(index)._3.getDataType match {
              case DataType.INT =>
                s"""
                   |int $value = Integer.parseInt(new String($valueIntern,
                   |org.apache.carbondata.core.constants.CarbonCommonConstants
                   |.DEFAULT_CHARSET_CLASS));
                 """.stripMargin
              case DataType.SHORT =>
                s"""
                   |short $value =
                   |Short.parseShort(new String($valueIntern,
                   |org.apache.carbondata.core.constants.CarbonCommonConstants
                   |.DEFAULT_CHARSET_CLASS));
                 """.stripMargin
              case DataType.DOUBLE =>
                s"""
                   |double $value =
                   |Double.parseDouble(new String($valueIntern,
                   |org.apache.carbondata.core.constants.CarbonCommonConstants
                   |.DEFAULT_CHARSET_CLASS));
                 """.stripMargin
              case DataType.LONG =>
                s"""
                   |long $value =
                   |Long.parseLong(new String($valueIntern,
                   |org.apache.carbondata.core.constants.CarbonCommonConstants
                   |.DEFAULT_CHARSET_CLASS));
                 """.stripMargin
              case DataType.DECIMAL =>
                s"""
                   |org.apache.spark.sql.types.Decimal $value =
                   |Decimal.apply(new java.math.BigDecimal(
                   |new String($valueIntern, org.apache.carbondata.core.constants
                   |.CarbonCommonConstants.DEFAULT_CHARSET_CLASS)));
                 """.stripMargin
              case _ =>
                s"""
                   | UTF8String $value = UTF8String.fromBytes($valueIntern);
                 """.stripMargin
            }
          code +=
            s"""
               |$caseCode
             """.stripMargin

          ExprCode(code, isNull, value)
        } else {
          expr.genCode(ctx)
        }
      }
      // Evaluation of non-deterministic expressions can't be deferred.
      s"""
         |${consume(ctx, resultVars)}
     """.stripMargin

    } else {

      val exprs = child.output.map(x =>
        ExpressionCanonicalizer.execute(BindReferences.bindReference(x, child.output)))
      ctx.currentVars = input
      val resultVars = exprs.map(_.genCode(ctx))
      // Evaluation of non-deterministic expressions can't be deferred.
      s"""
         |${consume(ctx, resultVars)}
     """.stripMargin

    }
  }


  override def inputRDDs(): Seq[RDD[InternalRow]] = {
    child.asInstanceOf[CodegenSupport].inputRDDs()
  }

  protected override def doProduce(ctx: CodegenContext): String = {
    child.asInstanceOf[CodegenSupport].produce(ctx, this)
  }

  private def isRequiredToDecode = {
    getDictionaryColumnIds.find(p => p._1 != null) match {
      case Some(value) => true
      case _ => false
    }
  }

  private def getDictionary(atiMap: Map[String, AbsoluteTableIdentifier],
      cache: Cache[DictionaryColumnUniqueIdentifier, Dictionary]) = {
    val dicts: Seq[Dictionary] = getDictionaryColumnIds.map { f =>
      if (f._2 != null) {
        try {
          cache.get(new DictionaryColumnUniqueIdentifier(
            atiMap(f._1).getCarbonTableIdentifier,
            f._2, f._3.getDataType))
        } catch {
          case _: Throwable => null
        }
      } else {
        null
      }
    }
    dicts
  }

  private def getDictionaryWrapper(atiMap: Map[String, AbsoluteTableIdentifier],
      cache: Cache[DictionaryColumnUniqueIdentifier, Dictionary], storePath: String) = {
    val dicts: Seq[ForwardDictionaryWrapper] = getDictionaryColumnIds.map {
      case (tableName, columnIdentifier, carbonDimension) =>
        if (columnIdentifier != null) {
          try {
            new ForwardDictionaryWrapper(
              storePath,
              atiMap(tableName),
              columnIdentifier,
              carbonDimension.getDataType,
              cache.get(
                new DictionaryColumnUniqueIdentifier(
                  atiMap(tableName).getCarbonTableIdentifier,
                  columnIdentifier
                )
              )
            )
          } catch {
            case _: Throwable => null
          }
        } else {
          null
        }
    }
    dicts
  }

}


class CarbonDecoderRDD(
    relations: Seq[CarbonDecoderRelation],
    profile: CarbonProfile,
    aliasMap: CarbonAliasDecoderRelation,
    prev: RDD[InternalRow],
    output: Seq[Attribute])
  extends RDD[InternalRow](prev) {

  private val storepath = CarbonEnv.get.carbonMetastore.storePath

  def canBeDecoded(attr: Attribute): Boolean = {
    profile match {
      case ip: IncludeProfile if ip.attributes.nonEmpty =>
        ip.attributes
          .exists(a => a.name.equalsIgnoreCase(attr.name) && a.exprId == attr.exprId)
      case ep: ExcludeProfile =>
        !ep.attributes
          .exists(a => a.name.equalsIgnoreCase(attr.name) && a.exprId == attr.exprId)
      case _ => true
    }
  }

  def convertCarbonToSparkDataType(carbonDimension: CarbonDimension,
      relation: CarbonRelation): types.DataType = {
    carbonDimension.getDataType match {
      case DataType.STRING => StringType
      case DataType.SHORT => ShortType
      case DataType.INT => IntegerType
      case DataType.LONG => LongType
      case DataType.DOUBLE => DoubleType
      case DataType.BOOLEAN => BooleanType
      case DataType.DECIMAL =>
        val scale: Int = carbonDimension.getColumnSchema.getScale
        val precision: Int = carbonDimension.getColumnSchema.getPrecision
        if (scale == 0 && precision == 0) {
          DecimalType(18, 2)
        } else {
          DecimalType(precision, scale)
        }
      case DataType.TIMESTAMP => TimestampType
      case DataType.DATE => DateType
      case DataType.STRUCT =>
        CarbonMetastoreTypes
          .toDataType(s"struct<${ relation.getStructChildren(carbonDimension.getColName) }>")
      case DataType.ARRAY =>
        CarbonMetastoreTypes
          .toDataType(s"array<${ relation.getArrayChildren(carbonDimension.getColName) }>")
    }
  }

  val getDictionaryColumnIds = {
    val dictIds: Array[(String, ColumnIdentifier, CarbonDimension)] = output.map { a =>
      val attr = aliasMap.getOrElse(a, a)
      val relation = relations.find(p => p.contains(attr))
      if (relation.isDefined && canBeDecoded(attr)) {
        val carbonTable = relation.get.carbonRelation.carbonRelation.metaData.carbonTable
        val carbonDimension =
          carbonTable.getDimensionByName(carbonTable.getFactTableName, attr.name)
        if (carbonDimension != null &&
            carbonDimension.hasEncoding(Encoding.DICTIONARY) &&
            !carbonDimension.hasEncoding(Encoding.DIRECT_DICTIONARY) &&
            !carbonDimension.isComplex()) {
          (carbonTable.getFactTableName, carbonDimension.getColumnIdentifier,
            carbonDimension)
        } else {
          (null, null, null)
        }
      } else {
        (null, null, null)
      }

    }.toArray
    dictIds
  }

  override def compute(split: Partition, context: TaskContext): Iterator[InternalRow] = {
    val absoluteTableIdentifiers = relations.map { relation =>
      val carbonTable = relation.carbonRelation.carbonRelation.metaData.carbonTable
      (carbonTable.getFactTableName, carbonTable.getAbsoluteTableIdentifier)
    }.toMap

    val cacheProvider: CacheProvider = CacheProvider.getInstance
    val forwardDictionaryCache: Cache[DictionaryColumnUniqueIdentifier, Dictionary] =
      cacheProvider.createCache(CacheType.FORWARD_DICTIONARY, storepath)
    val dicts: Seq[Dictionary] = getDictionary(absoluteTableIdentifiers,
      forwardDictionaryCache)
    val dictIndex = dicts.zipWithIndex.filter(x => x._1 != null).map(x => x._2)
    // add a task completion listener to clear dictionary that is a decisive factor for
    // LRU eviction policy
    val dictionaryTaskCleaner = TaskContext.get
    dictionaryTaskCleaner.addTaskCompletionListener(_ =>
      dicts.foreach { dictionary =>
        if (null != dictionary) {
          dictionary.clear()
        }
      }
    )
    val iter = firstParent[InternalRow].iterator(split, context)
    new Iterator[InternalRow] {
      var flag = true
      var total = 0L
      val dataTypes = output.map { attr => attr.dataType }

      override final def hasNext: Boolean = iter.hasNext

      override final def next(): InternalRow = {
        val row: InternalRow = iter.next()
        val data = row.toSeq(dataTypes).toArray
        dictIndex.foreach { index =>
          if (data(index) != null) {
            data(index) = DataTypeUtil.getDataBasedOnDataType(dicts(index)
                .getDictionaryValueForKeyInBytes(data(index).asInstanceOf[Int]),
              getDictionaryColumnIds(index)._3)
          }
        }
        new GenericInternalRow(data)
      }
    }
  }

  private def getDictionary(atiMap: Map[String, AbsoluteTableIdentifier],
      cache: Cache[DictionaryColumnUniqueIdentifier, Dictionary]) = {
    val dicts: Seq[Dictionary] = getDictionaryColumnIds.map { f =>
      if (f._2 != null) {
        try {
          cache.get(new DictionaryColumnUniqueIdentifier(
            atiMap(f._1).getCarbonTableIdentifier,
            f._2, f._3.getDataType))
        } catch {
          case _: Throwable => null
        }
      } else {
        null
      }
    }
    dicts
  }

  override protected def getPartitions: Array[Partition] = firstParent[InternalRow].partitions
}

/**
 * It is a wrapper around Dictionary, it is a work around to keep the dictionary serializable in
 * case of codegen
 * @param storePath
 * @param absoluteTableIdentifier
 * @param columnIdentifier
 * @param dataType
 * @param dictionary
 */
class ForwardDictionaryWrapper(val storePath: String,
    val absoluteTableIdentifier: AbsoluteTableIdentifier,
    columnIdentifier: ColumnIdentifier,
    dataType: DataType, @transient var dictionary: Dictionary) extends Serializable {


  def getDictionaryValueForKeyInBytes (surrogateKey: Int): Array[Byte] = {
    if (dictionary == null) {
      createDictionary
    }
    dictionary.getDictionaryValueForKeyInBytes(surrogateKey)
  }

  private def createDictionary = {
    val cacheProvider: CacheProvider = CacheProvider.getInstance
    val forwardDictionaryCache: Cache[DictionaryColumnUniqueIdentifier, Dictionary] =
      cacheProvider.createCache(CacheType.FORWARD_DICTIONARY, storePath)
    dictionary = forwardDictionaryCache.get(new DictionaryColumnUniqueIdentifier(
      absoluteTableIdentifier.getCarbonTableIdentifier,
      columnIdentifier, dataType))
  }

  def clear(): Unit = {
    if (dictionary == null) {
      createDictionary
    }
    dictionary.clear()
  }
}
