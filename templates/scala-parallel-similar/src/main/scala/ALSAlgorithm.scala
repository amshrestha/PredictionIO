package org.template.similar

import io.prediction.controller.PAlgorithm
import io.prediction.controller.Params
import io.prediction.controller.IPersistentModel
import io.prediction.controller.IPersistentModelLoader
import io.prediction.data.storage.BiMap

import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD
import org.apache.spark.mllib.recommendation.ALS
import org.apache.spark.mllib.recommendation.{Rating => MLlibRating}
import org.apache.spark.mllib.linalg.distributed.MatrixEntry
import org.apache.spark.mllib.linalg.distributed.CoordinateMatrix

import grizzled.slf4j.Logger

import scala.collection.mutable.PriorityQueue

case class ALSAlgorithmParams(
  val rank: Int,
  val numIterations: Int
) extends Params

class ALSModel(
  val productFeatures: RDD[(Int, Array[Double])],
  val itemStringIntMap: BiMap[String, Int],
  val items: Map[Int, Item]
) extends IPersistentModel[ALSAlgorithmParams] with Serializable {

  @transient lazy val itemIntStringMap = itemStringIntMap.inverse

  def save(id: String, params: ALSAlgorithmParams,
    sc: SparkContext): Boolean = {

    productFeatures.saveAsObjectFile(s"/tmp/${id}/productFeatures")
    sc.parallelize(Seq(itemStringIntMap))
      .saveAsObjectFile(s"/tmp/${id}/itemStringIntMap")
    sc.parallelize(Seq(items))
      .saveAsObjectFile(s"/tmp/${id}/items")
    true
  }

  override def toString = {
    s" productFeatures: [${productFeatures.count()}]" +
    s"(${productFeatures.take(2).toList}...)" +
    s" itemStringIntMap: [${itemStringIntMap.size}]" +
    s"(${itemStringIntMap.take(2).toString}...)]" +
    s" items: [${items.size}]" +
    s"(${items.take(2).toString}...)]"
  }
}

object ALSModel
  extends IPersistentModelLoader[ALSAlgorithmParams, ALSModel] {
  def apply(id: String, params: ALSAlgorithmParams,
    sc: Option[SparkContext]) = {
    new ALSModel(
      productFeatures = sc.get.objectFile(s"/tmp/${id}/productFeatures"),
      itemStringIntMap = sc.get
        .objectFile[BiMap[String, Int]](s"/tmp/${id}/itemStringIntMap").first,
      items = sc.get
        .objectFile[Map[Int, Item]](s"/tmp/${id}/items").first)
  }
}

/**
  * Use ALS to build item x feature matrix
  */
class ALSAlgorithm(val ap: ALSAlgorithmParams)
  extends PAlgorithm[PreparedData, ALSModel, Query, PredictedResult] {

  @transient lazy val logger = Logger[this.type]

  def train(data: PreparedData): ALSModel = {
    // create User and item's String ID to integer index BiMap
    val userStringIntMap = BiMap.stringInt(data.users.keys)
    val itemStringIntMap = BiMap.stringInt(data.items.keys)

    // collect Item as Map and convert ID to Int index
    val items: Map[Int, Item] = data.items.map { case (id, item) =>
      (itemStringIntMap(id), item)
    }.collectAsMap.toMap

    val itemCount = items.size
    val sc = data.viewEvents.context
    val mllibRatings = data.viewEvents
      .map { r =>
        // Convert user and item String IDs to Int index for MLlib
        val uindex = userStringIntMap.getOrElse(r.user, -1)
        val iindex = itemStringIntMap.getOrElse(r.item, -1)

        if (uindex == -1)
          logger.info(s"Couldn't convert nonexistent user ID ${r.user}"
            + " to Int index.")

        if (iindex == -1)
          logger.info(s"Couldn't convert nonexistent item ID ${r.item}"
            + " to Int index.")

        ((uindex, iindex), 1)
      }.filter { case ((u, i), v) =>
        // keep events with valid user and item index
        (u != -1) && (i != -1)
      }.reduceByKey(_ + _) // aggregate all view events of same user-item pair
      .map { case ((u, i), v) =>
        // MLlibRating requires integer index for user and item
        MLlibRating(u, i, v)
      }

    val m = ALS.trainImplicit(mllibRatings, ap.rank, ap.numIterations)

    new ALSModel(
      productFeatures = m.productFeatures,
      itemStringIntMap = itemStringIntMap,
      items = items
    )
  }

  def predict(model: ALSModel, query: Query): PredictedResult = {

    // convert items to Int index
    val queryList: Set[Int] = query.items.map(model.itemStringIntMap.get(_))
      .flatten.toSet

    val queryFeatures: Vector[Array[Double]] = queryList.toVector.par
      .map { item =>
        val qf: Array[Double] = model.productFeatures.lookup(item).head
        qf
      }.seq

    val whiteList: Option[Set[Int]] = query.whiteList.map( set =>
      set.map(model.itemStringIntMap.get(_)).flatten
    )
    val blackList: Option[Set[Int]] = query.blackList.map ( set =>
      set.map(model.itemStringIntMap.get(_)).flatten
    )

    val ord = Ordering.by[(Int, Double), Double](_._2).reverse

    val indexScores: Array[(Int, Double)] = if (queryFeatures.isEmpty) {
      logger.info(s"No valid items in ${query.items}.")
      Array[(Int, Double)]()
    } else {
      model.productFeatures
        .mapValues { f =>
          queryFeatures.map{ qf =>
            cosine(qf, f)
          }.reduce(_ + _)
        }
        .collect()
    }

    val filteredScore = indexScores.view.filter { case (i, v) =>
      isCandidateItem(
        i = i,
        items = model.items,
        categories = query.categories,
        queryList = queryList,
        whiteList = whiteList,
        blackList = blackList
      )
    }

    val topScores = getTopN(filteredScore, query.num)(ord).toArray

    val itemScores = topScores.map { case (i, s) =>
      new ItemScore(
        item = model.itemIntStringMap(i),
        score = s
      )
    }

    new PredictedResult(itemScores)
  }

  private
  def getTopN[T](s: Seq[T], n: Int)(implicit ord: Ordering[T]): Seq[T] = {

    val q = PriorityQueue()

    for (x <- s) {
      if (q.size < n)
        q.enqueue(x)
      else {
        // q is full
        if (ord.compare(x, q.head) < 0) {
          q.dequeue()
          q.enqueue(x)
        }
      }
    }

    q.dequeueAll.toSeq.reverse
  }

  private
  def cosine(v1: Array[Double], v2: Array[Double]): Double = {
    val size = v1.size
    var i = 0
    var n1: Double = 0
    var n2: Double = 0
    var d: Double = 0
    while (i < size) {
      n1 += v1(i) * v1(i)
      n2 += v2(i) * v2(i)
      d += v1(i) * v2(i)
      i += 1
    }
    d / (math.sqrt(n1) * math.sqrt(n2))
  }

  private
  def isCandidateItem(
    i: Int,
    items: Map[Int, Item],
    categories: Option[Set[String]],
    queryList: Set[Int],
    whiteList: Option[Set[Int]],
    blackList: Option[Set[Int]]
  ): Boolean = {
    whiteList.map(_.contains(i)).getOrElse(true) &&
    blackList.map(!_.contains(i)).getOrElse(true) &&
    // discard items in query as well
    (!queryList.contains(i)) &&
    // filter categories
    categories.map { cat =>
      items(i).categories.map { itemCat =>
        // keep this item if has ovelap categories with the query
        !(itemCat.toSet.intersect(cat).isEmpty)
      }.getOrElse(false) // discard this item if it has no categories
    }.getOrElse(true)
  }

}
